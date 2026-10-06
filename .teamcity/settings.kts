import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.powerShell
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.failureConditions.BuildFailureOnText
import jetbrains.buildServer.configs.kotlin.failureConditions.failOnText
import jetbrains.buildServer.configs.kotlin.triggers.VcsTrigger
import jetbrains.buildServer.configs.kotlin.triggers.schedule
import jetbrains.buildServer.configs.kotlin.triggers.vcs
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2025.11"

project {

    vcsRoot(EngineVcs)

    buildType(FetchSource)
    buildType(SyncFork)
    buildType(BuildEditor)

    params {
        select("CleanMode", "Incremental", label = "빌드 클린 모드", description = "빌드 전 정리 범위. Run Custom Build에서 변경 가능.",
                options = listOf("빠른 빌드 (클린 없음)" to "Incremental", "소스 정리 (고아 파일 제거)" to "CleanSource", "전체 재빌드 (Binaries/Intermediate 초기화)" to "FullRebuild"))
    }
    buildTypesOrder = arrayListOf(SyncFork, FetchSource, BuildEditor)

    features {
        // 빌드 기록 정리: 10일 보관 (UI에서 추가했던 것을 patch에서 병합)
        feature {
            type = "cleanUp"
            id = "PROJECT_CLEANUP_RULE"
            param("keepRule.1.dataToKeep", "everything")
            param("keepRule.1.type", "days")
            param("keepRule.1.days", "10")
        }
    }
}

object BuildEditor : BuildType({
    name = "Build Editor"

    params {
        param("env.UE5_DIST_PATH", """D:\Shared\UE5""")
        // ArchiveBuild zip 보관 폴더. 배포 폴더(UE5_DIST_PATH) 밖이어야 함 — 안에 두면 다음 빌드의 robocopy /MIR이 지움.
        param("env.UE5_ARCHIVE_PATH", """D:\Shared\UE5_Archives""")
        // 보관할 zip 개수 (최근 N개만 유지, 0 = 무제한)
        param("ArchiveKeepCount", "3")
        // 동시 실행 액션 수 상한 (OOM 완화). 빈값/0 = 엔진 기본. 스텝이 에이전트 BuildConfiguration.xml에 머지.
        param("MaxParallelActions", "10")
        // Android 타깃 플랫폼 포함 여부 (Win64는 항상 포함). 에이전트에 Machine 범위 ANDROID_HOME/NDKROOT 필요.
        checkbox("WithAndroid", "true", label = "Android 포함",
            description = "Win64에 더해 Android 타깃(arm64+x64)도 빌드. 에이전트에 Android SDK/NDK 필요.",
            display = ParameterDisplay.NORMAL, checked = "true", unchecked = "false")
        checkbox("ArchiveBuild", "false", label = "빌드 아카이빙 (Zip)", description = "빌드 완료 후 Installed Engine을 zip 아카이브로 압축하여 배포 경로에 보관합니다.",
            checked = "true", unchecked = "false")
    }

    vcs {
        root(EngineVcs)

        checkoutMode = CheckoutMode.MANUAL
        checkoutDir = "UE5"
    }

    steps {
        powerShell {
            name = "Build UE5 Installed Engine"
            id = "jetbrains_powershell"
            scriptMode = script {
                content = """
                    ${'$'}ErrorActionPreference = 'Stop'
                    
                    # UTF-8 codepage (cmd의 chcp 65001 대응)
                    chcp 65001 | Out-Null
                    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                    
                    # MaxParallelActions, MaxLinkActions, Horde MaxIdle, UBA Timeout을 머신 BuildConfiguration.xml에 안전 머지.
                    # XML 파싱으로 해당 노드만 갱신/제거 → 다른 설정 안 건드림.
                    ${'$'}mpa = '%MaxParallelActions%'
                    ${'$'}bcFile = Join-Path ${'$'}env:ProgramData 'Unreal Engine\UnrealBuildTool\BuildConfiguration.xml'
                    ${'$'}nsUri = 'https://www.unrealengine.com/BuildConfiguration'
                    if (Test-Path ${'$'}bcFile) {
                        [xml]${'$'}doc = Get-Content -Raw ${'$'}bcFile
                        ${'$'}nsm = New-Object System.Xml.XmlNamespaceManager(${'$'}doc.NameTable)
                        ${'$'}nsm.AddNamespace('u', ${'$'}nsUri)
                        ${'$'}bc = ${'$'}doc.SelectSingleNode('/u:Configuration/u:BuildConfiguration', ${'$'}nsm)
                        ${'$'}node = if (${'$'}bc) { ${'$'}bc.SelectSingleNode('u:MaxParallelActions', ${'$'}nsm) } else { ${'$'}null }
                        ${'$'}linkNode = if (${'$'}bc) { ${'$'}bc.SelectSingleNode('u:MaxLinkActions', ${'$'}nsm) } else { ${'$'}null }
                        ${'$'}deprecNode = if (${'$'}bc) { ${'$'}bc.SelectSingleNode('u:bAllowUBALocalExecutor', ${'$'}nsm) } else { ${'$'}null }
                        if (${'$'}deprecNode) { [void]${'$'}deprecNode.ParentNode.RemoveChild(${'$'}deprecNode) }
                    
                        if (${'$'}mpa -and ${'$'}mpa.Trim() -and ${'$'}mpa.Trim() -ne '0') {
                            if (-not ${'$'}bc) { ${'$'}bc = ${'$'}doc.CreateElement('BuildConfiguration', ${'$'}nsUri); [void]${'$'}doc.DocumentElement.AppendChild(${'$'}bc) }
                            if (-not ${'$'}node) { ${'$'}node = ${'$'}doc.CreateElement('MaxParallelActions', ${'$'}nsUri); [void]${'$'}bc.AppendChild(${'$'}node) }
                            ${'$'}node.InnerText = ${'$'}mpa.Trim()
                            
                            # OOM 방지를 위해 링크 작업 개수를 1개로 제한
                            if (-not ${'$'}linkNode) { ${'$'}linkNode = ${'$'}doc.CreateElement('MaxLinkActions', ${'$'}nsUri); [void]${'$'}bc.AppendChild(${'$'}linkNode) }
                            ${'$'}linkNode.InnerText = '1'
                        } elseif (${'$'}node) {
                            [void]${'$'}node.ParentNode.RemoveChild(${'$'}node)
                            if (${'$'}linkNode) { [void]${'$'}linkNode.ParentNode.RemoveChild(${'$'}linkNode) }
                        }
                    
                        # Horde 타임아웃 완화 (원격 워커 LLM 작업 및 대기 시 끊김 방지: MaxIdle 60초)
                        ${'$'}horde = ${'$'}doc.SelectSingleNode('/u:Configuration/u:Horde', ${'$'}nsm)
                        if (${'$'}horde) {
                            ${'$'}idleNode = ${'$'}horde.SelectSingleNode('u:MaxIdle', ${'$'}nsm)
                            if (-not ${'$'}idleNode) { ${'$'}idleNode = ${'$'}doc.CreateElement('MaxIdle', ${'$'}nsUri); [void]${'$'}horde.AppendChild(${'$'}idleNode) }
                            ${'$'}idleNode.InnerText = '60'
                        }
                    
                        # 잘못된 노드가 있으면 정리 (UBT가 모르는 이름이라 경고만 내고 무시되는 항목들 — 과거 수동 편집 잔재)
                        foreach (${'$'}badName in @('UBAAccelerator', 'LocalExecutorSettings')) {
                            ${'$'}badNode = ${'$'}doc.SelectSingleNode("/u:Configuration/u:${'$'}badName", ${'$'}nsm)
                            if (${'$'}badNode) {
                                [void]${'$'}badNode.ParentNode.RemoveChild(${'$'}badNode)
                                Write-Host ">> BuildConfiguration.xml: invalid node removed: ${'$'}badName"
                            }
                        }
                    
                        ${'$'}doc.Save(${'$'}bcFile)
                        Write-Host ">> BuildConfiguration.xml 갱신 완료: MaxParallelActions=${'$'}mpa, MaxLinkActions=1, Horde.MaxIdle=60"
                    } else {
                        Write-Host ">> BuildConfiguration.xml not found on agent - skipping config merge"
                    }

                    # Android SDK 사전 점검 (WithAndroid=true). 미설치면 즉시 실패 → 1h+ 컴파일 뒤 실패 방지.
                    # 에이전트는 서비스(LocalSystem)라 User 범위 env를 못 봄 → Machine 범위 ANDROID_HOME/NDKROOT 필요.
                    if ('%WithAndroid%' -eq 'true') {
                        ${'$'}sdk = ${'$'}env:ANDROID_HOME
                        ${'$'}ndk = ${'$'}env:NDKROOT
                        ${'$'}missing = @()
                        if (-not ${'$'}sdk -or -not (Test-Path ${'$'}sdk)) { ${'$'}missing += 'ANDROID_HOME' }
                        if (-not ${'$'}ndk -or -not (Test-Path ${'$'}ndk)) { ${'$'}missing += 'NDKROOT' }
                        if (${'$'}missing.Count -gt 0) {
                            Write-Host ("##teamcity[buildProblem description='Android SDK not ready on agent: missing " + (${'$'}missing -join ', ') + ". Run SetupAndroid.bat, promote env vars to Machine scope, restart agent. Or set WithAndroid=false.']")
                            exit 1
                        }
                        Write-Host (">> Android SDK OK: ANDROID_HOME=" + ${'$'}sdk + " NDKROOT=" + ${'$'}ndk)
                    }
                    
                    if ('%CleanMode%' -eq 'FullRebuild') {
                        Write-Host ">> CleanMode = FullRebuild → UAT -clean 적용 (아래 args에 추가됨)"
                    }
                    
                    # Sub-step 1a: Generate project files (보통 1-2분, watchdog 불필요)
                    & ".\GenerateProjectFiles.bat"
                    if (${'$'}LASTEXITCODE -ne 0) {
                        Write-Host "##teamcity[buildProblem description='GenerateProjectFiles failed']"
                        exit ${'$'}LASTEXITCODE
                    }
                    
                    # Sub-step 1b: RunUAT BuildGraph — watchdog으로 감싸서 무출력 timeout 적용
                    # TeamCity는 "no output for N min" failure condition을 지원하지 않으므로,
                    # 빌드 머신 성능을 고려해 전체 timeout 대신 무출력 hang만 감지하기 위함.
                    # 정상 빌드는 BuildGraph가 매 초 다수의 컴파일/링크 라인을 emit하므로
                    # 30분 무출력은 거의 확실히 hang 상태로 판단.
                    #
                    # 인자 처리: Start-Process -ArgumentList에 배열을 주면 공백 포함 인자 처리가
                    # cmd.exe로 갈 때 따옴표가 깨짐. -target="Make Installed Build Win64"가
                    # -target=Make / Installed / Build / Win64 4개로 쪼개져서 UAT가 fail함.
                    # 해결: ArgumentList에 따옴표 박힌 단일 문자열로 전달 → cmd가 그대로 파싱.
                    ${'$'}uatLog = [System.IO.Path]::GetTempFileName()
                    # HostPlatformOnly=true는 플랫폼별 '기본값'만 끔 → Mac/Linux/iOS는 꺼진 채, 명시한 WithAndroid만 켜짐 (Win64는 호스트라 유지).
                    ${'$'}uatArgsStr = 'BuildGraph -script="Engine/Build/InstalledEngineBuild.xml" -target="Make Installed Build Win64" -set:WithDDC=false -set:HostPlatformOnly=true -set:GameConfigurations=Development -set:WithAndroid=%WithAndroid%'
                    if ('%CleanMode%' -eq 'FullRebuild') {
                        ${'$'}uatArgsStr += ' -clean'
                    }
                    
                    Write-Host ">> RunUAT BuildGraph 시작 (watchdog: 60분 무활동 시 종료)"
                    Write-Host ">> args: ${'$'}uatArgsStr"
                    ${'$'}uatProc = Start-Process -FilePath ".\Engine\Build\BatchFiles\RunUAT.bat" -ArgumentList ${'$'}uatArgsStr -RedirectStandardOutput ${'$'}uatLog -PassThru -NoNewWindow
                    # Start-Process -PassThru는 핸들을 미리 캐싱 안 하면 종료 후 .ExitCode가 null → 한 번 읽어 캐싱
                    ${'$'}null = ${'$'}uatProc.Handle
                    
                    # Watchdog: '무출력'이 아니라 '무활동(파일 변경 없음)'으로 hang 판정.
                    # UBA 원격 분산/쿠킹/패키징은 stdout이 수십 분 조용해도 정상(빌드 #31 오탐).
                    # stdout 임시파일 + UAT/UBA 로그(Saved/Logs) + UBT 로그의 '최신 수정시각'이
                    # 하나라도 갱신되면 alive. 전부 N분 정지해야 진짜 hang으로 보고 트리 종료.
                    ${'$'}noOutputTimeoutMin = 60
                    ${'$'}activityFiles = @(".\Engine\Programs\UnrealBuildTool\Log.txt", ".\Engine\Programs\UnrealBuildTool\Trace.uba")
                    ${'$'}activityDirs  = @(".\Engine\Programs\AutomationTool\Saved\Logs")
                    ${'$'}lastDisplaySize = 0
                    ${'$'}lastActivity = Get-Date
                    ${'$'}lastNewestTicks = 0
                    ${'$'}lastIoTotal = 0
                    ${'$'}killedByWatchdog = ${'$'}false
                    
                    while (!${'$'}uatProc.HasExited) {
                        Start-Sleep -Seconds 30
                    
                        # --- stdout (Start-Process로 redirect한 임시 파일) ---
                        ${'$'}stdoutSize = if (Test-Path ${'$'}uatLog) { (Get-Item ${'$'}uatLog).Length } else { 0 }
                        if (${'$'}stdoutSize -gt ${'$'}lastDisplaySize) {
                            ${'$'}fs = [System.IO.File]::Open(${'$'}uatLog, 'Open', 'Read', 'ReadWrite')
                            ${'$'}fs.Position = ${'$'}lastDisplaySize
                            ${'$'}sr = New-Object System.IO.StreamReader(${'$'}fs)
                            ${'$'}newContent = ${'$'}sr.ReadToEnd()
                            ${'$'}sr.Close(); ${'$'}fs.Close()
                            if (${'$'}newContent.Trim()) { Write-Host ${'$'}newContent }
                            ${'$'}lastDisplaySize = ${'$'}stdoutSize
                        }
                        # --- 활동 신호: stdout 파일 + 활동 파일/디렉터리 중 가장 최근 수정시각 ---
                        ${'$'}newest = 0
                        foreach (${'$'}p in (@(${'$'}uatLog) + ${'$'}activityFiles)) {
                            if (Test-Path ${'$'}p) { ${'$'}t = (Get-Item ${'$'}p).LastWriteTimeUtc.Ticks; if (${'$'}t -gt ${'$'}newest) { ${'$'}newest = ${'$'}t } }
                        }
                        foreach (${'$'}d in ${'$'}activityDirs) {
                            if (Test-Path ${'$'}d) {
                                ${'$'}f = Get-ChildItem -Path ${'$'}d -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
                                if (${'$'}f) { ${'$'}t = ${'$'}f.LastWriteTimeUtc.Ticks; if (${'$'}t -gt ${'$'}newest) { ${'$'}newest = ${'$'}t } }
                            }
                        }
                        if (${'$'}newest -gt ${'$'}lastNewestTicks) { ${'$'}lastNewestTicks = ${'$'}newest; ${'$'}lastActivity = Get-Date }
                    
                        # --- 추가 신호: 빌드 프로세스 트리의 누적 I/O 바이트 ---
                        # 파일 mtime이 못 잡는 단계(예: Make Installed Build의 LocalBuilds 대용량 복사) 커버.
                        # RunUAT 자손 트리의 ReadTransferCount+WriteTransferCount 합이 늘면 alive.
                        ${'$'}ioTotal = 0
                        try {
                            ${'$'}allProc = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue
                            ${'$'}tree = @{}; ${'$'}tree[[int]${'$'}uatProc.Id] = ${'$'}true
                            for (${'$'}pass = 0; ${'$'}pass -lt 8; ${'$'}pass++) {
                                foreach (${'$'}pr in ${'$'}allProc) { if (${'$'}tree[[int]${'$'}pr.ParentProcessId] -and -not ${'$'}tree[[int]${'$'}pr.ProcessId]) { ${'$'}tree[[int]${'$'}pr.ProcessId] = ${'$'}true } }
                            }
                            foreach (${'$'}pr in ${'$'}allProc) { if (${'$'}tree[[int]${'$'}pr.ProcessId]) { ${'$'}ioTotal += [int64]${'$'}pr.ReadTransferCount + [int64]${'$'}pr.WriteTransferCount } }
                        } catch {}
                        if (${'$'}ioTotal -gt ${'$'}lastIoTotal) { ${'$'}lastIoTotal = ${'$'}ioTotal; ${'$'}lastActivity = Get-Date }
                    
                        ${'$'}silentMin = ((Get-Date) - ${'$'}lastActivity).TotalMinutes
                        if (${'$'}silentMin -gt 10) {
                            Write-Host ("[WATCHDOG] no activity {0:N1}m (stdout/logs unchanged, threshold {1}m)" -f ${'$'}silentMin, ${'$'}noOutputTimeoutMin)
                        }
                    
                        # 모든 활동 신호가 N분 정지 → 진짜 hang으로 판정
                        if (${'$'}silentMin -ge ${'$'}noOutputTimeoutMin) {
                            Write-Host ("##teamcity[buildProblem description='WATCHDOG: no build activity for {0} min - hang detected, killing process tree']" -f [int]${'$'}silentMin)
                            # 프로세스 트리 전체 종료 (.Kill()은 부모만 죽여 UBT/UBA 좀비가 뮤텍스 점유 → 다음 빌드 ConflictingInstance)
                            try { taskkill /T /F /PID ${'$'}uatProc.Id 2>${'$'}null | Out-Null } catch { Write-Host "[WATCHDOG] tree kill failed" }
                            # 트리에서 분리됐을 수 있는 UBT/UBA 잔류 정리 (watchdog 발동 시 이 빌드가 유일 → 안전)
                            Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.Name -match 'UbaAgent|UbaServer' -or ${'$'}_.CommandLine -match 'UnrealBuildTool|AutomationTool' } | ForEach-Object { try { Stop-Process -Id ${'$'}_.ProcessId -Force -ErrorAction SilentlyContinue } catch {} }
                            ${'$'}killedByWatchdog = ${'$'}true
                            break
                        }
                    }
                    
                    # 잔여 출력 flush
                    Get-Content ${'$'}uatLog -Raw -ErrorAction SilentlyContinue | ForEach-Object { if (${'$'}_) { Write-Host ${'$'}_ } }
                    Remove-Item ${'$'}uatLog -ErrorAction SilentlyContinue
                    
                    if (${'$'}killedByWatchdog) { exit 124 }  # 124 = GNU timeout convention
                    
                    ${'$'}uatProc.WaitForExit()
                    ${'$'}exitCode = ${'$'}uatProc.ExitCode
                    if (${'$'}null -eq ${'$'}exitCode) { Write-Host ">> WARN: ExitCode가 null - 0으로 간주"; ${'$'}exitCode = 0 }
                    if (${'$'}exitCode -ne 0) {
                        Write-Host "##teamcity[buildProblem description='RunUAT failed with exit code ${'$'}exitCode']"
                        exit ${'$'}exitCode
                    }
                    
                    # 중간 빌드 캐시 및 임시 파일 정리 (디스크 절약)
                    Write-Host ">> [Cache Cleanup] 빌드 완료 후 중간 컴파일 캐시 및 임시 로그 정리"
                    Remove-Item ".\Engine\Intermediate\Build" -Recurse -Force -ErrorAction SilentlyContinue
                    Remove-Item ".\Engine\Programs\AutomationTool\Saved\Logs" -Recurse -Force -ErrorAction SilentlyContinue
                """.trimIndent()
            }
        }
        powerShell {
            name = "Distribute to Shared Folder"
            id = "Distribute_to_Shared_Folder"
            scriptMode = script {
                content = """
                    chcp 65001
                    
                    ${'$'}source = "%teamcity.build.checkoutDir%\LocalBuilds\Engine\Windows"
                    ${'$'}destination = "${'$'}env:UE5_DIST_PATH"
                    
                    if (!(Test-Path ${'$'}destination)) { New-Item -ItemType Directory -Force -Path ${'$'}destination }
                    
                    # robocopy를 background process로 실행하고 60초마다 heartbeat 출력
                    # robocopy는 큰 파일 복사 중 진행률을 \r-only로 emit하므로 (newline 없음),
                    # heartbeat 없이 실행하면 TeamCity가 무출력으로 오인하여 빌드 강제 종료할 수 있음.
                    # /MIR = /E + /PURGE → destination을 source와 정확히 일치 (옛 빌드 잔해 자동 삭제)
                    # /NP   = 진행률 % 출력 제거 (어차피 \r-only라 로그 가독성 저하만 시킴)
                    # /NDL  = directory 목록 출력 제거 (노이즈 감소)
                    ${'$'}tmpLog = [System.IO.Path]::GetTempFileName()
                    ${'$'}rcArgs = @(${'$'}source, ${'$'}destination, '/MIR', '/Z', '/R:5', '/W:5', '/NP', '/NDL')
                    Write-Host ">> robocopy 시작 (mirror): ${'$'}source -> ${'$'}destination"
                    ${'$'}proc = Start-Process -FilePath 'robocopy.exe' -ArgumentList ${'$'}rcArgs -RedirectStandardOutput ${'$'}tmpLog -PassThru -NoNewWindow
                    ${'$'}null = ${'$'}proc.Handle   # 종료 후 .ExitCode가 null 되는 것 방지 (핸들 캐싱)
                    
                    ${'$'}startTime = Get-Date
                    ${'$'}lastSize = 0
                    while (!${'$'}proc.HasExited) {
                        Start-Sleep -Seconds 60
                    
                        # 새로 쓰여진 로그 라인을 stdout으로 흘려보냄 (file 단위 완료 줄 등)
                        if (Test-Path ${'$'}tmpLog) {
                            ${'$'}currentSize = (Get-Item ${'$'}tmpLog).Length
                            if (${'$'}currentSize -gt ${'$'}lastSize) {
                                ${'$'}fs = [System.IO.File]::Open(${'$'}tmpLog, 'Open', 'Read', 'ReadWrite')
                                ${'$'}fs.Position = ${'$'}lastSize
                                ${'$'}sr = New-Object System.IO.StreamReader(${'$'}fs)
                                ${'$'}newContent = ${'$'}sr.ReadToEnd()
                                ${'$'}sr.Close(); ${'$'}fs.Close()
                                if (${'$'}newContent.Trim()) { Write-Host ${'$'}newContent }
                                ${'$'}lastSize = ${'$'}currentSize
                            }
                        }
                    
                        ${'$'}elapsed = ((Get-Date) - ${'$'}startTime).TotalMinutes
                        Write-Host ('[heartbeat] robocopy running for {0:N1} min' -f ${'$'}elapsed)
                    }
                    
                    # 잔여 출력 flush
                    Get-Content ${'$'}tmpLog -Raw -ErrorAction SilentlyContinue | ForEach-Object { if (${'$'}_) { Write-Host ${'$'}_ } }
                    Remove-Item ${'$'}tmpLog -ErrorAction SilentlyContinue
                    
                    ${'$'}proc.WaitForExit()
                    ${'$'}rc = ${'$'}proc.ExitCode
                    if (${'$'}null -eq ${'$'}rc) { Write-Host ">> WARN: robocopy ExitCode가 null - 0으로 간주"; ${'$'}rc = 0 }
                    Write-Host (">> robocopy ExitCode = " + ${'$'}rc + " (0-7=성공, >=8=실패)")
                    if (${'$'}rc -ge 8) { Write-Error "Robocopy failed with code ${'$'}rc" }
                    
                    # 아카이빙 파라미터(ArchiveBuild) 처리
                    # zip은 배포 폴더 밖(UE5_ARCHIVE_PATH)에 저장 — 배포 폴더 안에 두면 다음 빌드의 robocopy /MIR이 지움.
                    ${'$'}archiveBuild = '%ArchiveBuild%'
                    if (${'$'}archiveBuild -eq 'true') {
                        ${'$'}archiveDir = "${'$'}env:UE5_ARCHIVE_PATH"
                        ${'$'}distFull = [System.IO.Path]::GetFullPath(${'$'}destination).TrimEnd('\') + '\'
                        ${'$'}archFull = [System.IO.Path]::GetFullPath(${'$'}archiveDir).TrimEnd('\') + '\'
                        if (${'$'}archFull.StartsWith(${'$'}distFull, [System.StringComparison]::OrdinalIgnoreCase)) {
                            Write-Host "##teamcity[buildProblem description='UE5_ARCHIVE_PATH is inside UE5_DIST_PATH - robocopy /MIR would delete archives. Archive skipped.']"
                        } else {
                            if (!(Test-Path ${'$'}archiveDir)) { New-Item -ItemType Directory -Force -Path ${'$'}archiveDir | Out-Null }
                            ${'$'}zipTarget = Join-Path ${'$'}archiveDir "UE5_InstalledEngine_%build.number%.zip"
                            Write-Host ">> [Archive] Installed Engine 아카이빙 시작: ${'$'}source -> ${'$'}zipTarget"
                            # 7-Zip 우선 (서비스 계정 PATH에 없을 수 있어 기본 설치 경로도 확인). Compress-Archive는 PS 5.1에서 2GB 초과 파일 불가.
                            ${'$'}sevenZip = (Get-Command '7z.exe' -ErrorAction SilentlyContinue).Source
                            if (-not ${'$'}sevenZip -and (Test-Path "${'$'}env:ProgramFiles\7-Zip\7z.exe")) { ${'$'}sevenZip = "${'$'}env:ProgramFiles\7-Zip\7z.exe" }
                            ${'$'}archiveOk = ${'$'}false
                            if (${'$'}sevenZip) {
                                & ${'$'}sevenZip a -tzip -mx=1 "${'$'}zipTarget" "${'$'}source\*"
                                ${'$'}archiveOk = (${'$'}LASTEXITCODE -le 1)   # 7z: 0=OK, 1=경고(일부 파일 잠김 등)
                            } else {
                                try { Compress-Archive -Path "${'$'}source\*" -DestinationPath "${'$'}zipTarget" -Force -ErrorAction Stop; ${'$'}archiveOk = ${'$'}true }
                                catch { Write-Host ">> [Archive] Compress-Archive 실패: ${'$'}_" }
                            }
                            if (${'$'}archiveOk) {
                                Write-Host ">> [Archive] 아카이빙 완료: ${'$'}zipTarget"
                                # 보관 개수 제한: 새 zip이 성공했을 때만 오래된 것 삭제 (실패 시 기존 zip 보존)
                                ${'$'}keep = [int]'%ArchiveKeepCount%'
                                if (${'$'}keep -gt 0) {
                                    Get-ChildItem ${'$'}archiveDir -Filter 'UE5_InstalledEngine_*.zip' | Sort-Object LastWriteTime -Descending | Select-Object -Skip ${'$'}keep | ForEach-Object {
                                        Write-Host (">> [Archive] 오래된 아카이브 삭제: " + ${'$'}_.Name)
                                        Remove-Item ${'$'}_.FullName -Force
                                    }
                                }
                            } else {
                                Write-Host "##teamcity[buildProblem description='Archive (zip) failed - previous archives kept.']"
                            }
                        }
                    }
                """.trimIndent()
            }
        }
    }

    triggers {
        vcs {
            quietPeriodMode = VcsTrigger.QuietPeriodMode.USE_DEFAULT
        }
    }

    failureConditions {
        failOnText {
            conditionType = BuildFailureOnText.ConditionType.CONTAINS
            pattern = "could not be loaded"
            failureMessage = "모듈 로딩 실패 패턴 감지"
            reverse = false
            stopBuildOnFailure = false
        }
        failOnText {
            conditionType = BuildFailureOnText.ConditionType.CONTAINS
            pattern = "BuildId mismatch"
            failureMessage = "BuildId mismatch 감지 (modules 매니페스트 ↔ DLL)"
            reverse = false
            stopBuildOnFailure = false
        }
    }

    features {
        perfmon {
            param("teamcity.perfmon.feature.enabled", "true")
        }
    }

    requirements {
        // 엔진 빌드는 Agent_Win64 고정 (UE5 체크아웃·LocalBuilds·Android SDK가 있는 머신). MAGI_Main 등으로 배정 방지.
        equals("teamcity.agent.name", "Agent_Win64")
    }

    dependencies {
        snapshot(FetchSource) {
            runOnSameAgent = true
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }
})

object FetchSource : BuildType({
    name = "Fetch Source"

    vcs {
        root(EngineVcs)

        checkoutDir = "UE5"
    }

    steps {
        powerShell {
            name = "Clean by CleanMode"
            id = "Clean_by_CleanMode"
            scriptMode = script {
                content = """
                    ${'$'}ErrorActionPreference = 'Stop'
                    ${'$'}cleanMode = '%CleanMode%'
                    
                    Write-Host "================================================"
                    Write-Host "  Clean Mode: ${'$'}cleanMode"
                    Write-Host "================================================"
                    
                    switch (${'$'}cleanMode) {
                        'Incremental' {
                            Write-Host ">> 클린 스킵 - 인크리멘탈 유지 (UBA 캐시 활용)"
                        }
                        'CleanSource' {
                            Write-Host ">> 소스 트리 고아 파일 제거"
                            git clean -fd -- Engine/Source Engine/Plugins Engine/Shaders
                            if (${'$'}LASTEXITCODE -ne 0) { throw "git clean failed: ${'$'}LASTEXITCODE" }
                        }
                        'FullRebuild' {
                            Write-Host ">> 전체 초기화 (Binaries/Intermediate 포함)"
                            git clean -fdx -- Engine
                            if (${'$'}LASTEXITCODE -ne 0) { throw "git clean failed: ${'$'}LASTEXITCODE" }
                        }
                        default {
                            throw "Unknown CleanMode: '${'$'}cleanMode'"
                        }
                    }
                """.trimIndent()
            }
        }
        script {
            name = "Setup"
            id = "Setup"
            scriptContent = """.\Engine\Binaries\DotNET\GitDependencies\win-x64\GitDependencies.exe"""
        }
    }

    features {
        perfmon {
            param("teamcity.perfmon.feature.enabled", "true")
        }
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Windows 11")
        // Build Editor와 같은 머신(UE5 체크아웃 공유). 단독 실행 시에도 다른 에이전트에 체크아웃 안 생기게.
        equals("teamcity.agent.name", "Agent_Win64")
    }
})

object SyncFork : BuildType({
    name = "Sync Fork"
    description = "EpicGames/UnrealEngine release를 포크(senariel/UnrealEngine)로 동기화. push 시 Build Editor 트리거가 체인 실행."

    params {
        password("env.GIT_PUSH_TOKEN", "credentialsJSON:9db31541-e004-4b5a-a9f4-7c10108866f3", label = "GitHub Push/Fetch PAT", description = "senariel/UnrealEngine fork 동기화용 GitHub PAT (repo 스코프, EpicGames org 멤버)", display = ParameterDisplay.HIDDEN)
        param("SyncBranch", "release")
    }

    steps {
        powerShell {
            name = "Sync fork from upstream"
            id = "Sync_fork_from_upstream"
            scriptMode = script {
                content = """
                    ${'$'}ErrorActionPreference = 'Stop'
                    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
                    ${'$'}branch = '%SyncBranch%'
                    ${'$'}token  = ${'$'}env:GIT_PUSH_TOKEN
                    if ([string]::IsNullOrEmpty(${'$'}token)) {
                        throw 'GIT_PUSH_TOKEN 미설정 - Sync Fork 파라미터(env.GIT_PUSH_TOKEN) 확인'
                    }

                    # GitHub 서버사이드 fork 동기화 (clone 불필요).
                    # senariel/UnrealEngine 은 EpicGames/UnrealEngine 의 정식 fork → merge-upstream 으로
                    # upstream release 를 fork release 에 ff/merge. push 가 생기면 Build Editor VCS 트리거가 체인 실행.
                    ${'$'}repo = 'senariel/UnrealEngine'
                    ${'$'}headers = @{
                        Authorization          = "Bearer ${'$'}token"
                        'User-Agent'           = 'teamcity-sync-fork'
                        Accept                 = 'application/vnd.github+json'
                        'X-GitHub-Api-Version' = '2022-11-28'
                    }
                    ${'$'}body = @{ branch = ${'$'}branch } | ConvertTo-Json

                    Write-Host ">> GitHub fork 동기화 요청: ${'$'}repo (branch=${'$'}branch)"
                    try {
                        ${'$'}resp = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/${'$'}repo/merge-upstream" -Headers ${'$'}headers -Body ${'$'}body -ContentType 'application/json'
                        Write-Host (">> merge_type = {0}" -f ${'$'}resp.merge_type)
                        Write-Host (">> base_branch = {0}" -f ${'$'}resp.base_branch)
                        Write-Host (">> message     = {0}" -f ${'$'}resp.message)
                        if (${'$'}resp.merge_type -eq 'none') {
                            Write-Host '>> 이미 최신 - 변경 없음 (체인 트리거 안 됨)'
                        } else {
                            Write-Host '>> 포크 갱신됨 - Build Editor VCS 트리거가 체인 실행'
                        }
                    } catch {
                        ${'$'}code = -1
                        ${'$'}detail = ''
                        if (${'$'}_.Exception.Response) {
                            ${'$'}code = [int]${'$'}_.Exception.Response.StatusCode
                            try {
                                ${'$'}rs = ${'$'}_.Exception.Response.GetResponseStream()
                                ${'$'}detail = (New-Object System.IO.StreamReader(${'$'}rs)).ReadToEnd()
                            } catch {}
                        }
                        if (${'$'}code -eq 409) {
                            throw "fork 동기화 충돌(409) - upstream 과 분기됨(포크 독자 커밋 존재). 수동 머지 필요. 상세: ${'$'}detail"
                        }
                        throw "merge-upstream 실패 (HTTP ${'$'}code): ${'$'}detail"
                    }
                """.trimIndent()
            }
        }
    }

    triggers {
        schedule {
            schedulingPolicy = daily {
                hour = 3
            }
            triggerBuild = always()
            withPendingChangesOnly = false
        }
    }

    features {
        perfmon {
            param("teamcity.perfmon.feature.enabled", "true")
        }
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Windows 11")
    }
})

object EngineVcs : GitVcsRoot({
    name = "UnrealEngine release"
    url = "https://github.com/senariel/UnrealEngine"
    branch = "refs/heads/release"
    branchSpec = "refs/heads/*"
    authMethod = token {
        userName = "oauth2"
        tokenId = "tc_token_id:CID_3ab2f5c96314802c7074714f2b03c3a5:-1:62ad1ec8-56b9-4b2a-adce-68a33ee027a2"
    }
})
