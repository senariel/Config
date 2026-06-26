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
}

object BuildEditor : BuildType({
    name = "Build Editor"

    params {
        param("env.UE5_DIST_PATH", """D:\Shared\UE5""")
        // 동시 실행 액션 수 상한 (OOM 완화). 빈값/0 = 엔진 기본. 스텝이 에이전트 BuildConfiguration.xml에 머지.
        param("MaxParallelActions", "10")
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

                    # MaxParallelActions를 머신 BuildConfiguration.xml에 안전 머지 (Horde 등 기존 설정 보존, 엔진 소스/repo 무관).
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
                        if (${'$'}mpa -and ${'$'}mpa.Trim() -and ${'$'}mpa.Trim() -ne '0') {
                            if (-not ${'$'}bc) { ${'$'}bc = ${'$'}doc.CreateElement('BuildConfiguration', ${'$'}nsUri); [void]${'$'}doc.DocumentElement.AppendChild(${'$'}bc) }
                            if (-not ${'$'}node) { ${'$'}node = ${'$'}doc.CreateElement('MaxParallelActions', ${'$'}nsUri); [void]${'$'}bc.AppendChild(${'$'}node) }
                            ${'$'}node.InnerText = ${'$'}mpa.Trim()
                            ${'$'}doc.Save(${'$'}bcFile)
                            Write-Host (">> MaxParallelActions=" + ${'$'}mpa.Trim() + " merged into BuildConfiguration.xml (Horde preserved)")
                        } elseif (${'$'}node) {
                            [void]${'$'}node.ParentNode.RemoveChild(${'$'}node)
                            ${'$'}doc.Save(${'$'}bcFile)
                            Write-Host ">> MaxParallelActions cleared - engine default parallelism"
                        } else {
                            Write-Host ">> MaxParallelActions not set - engine default parallelism"
                        }
                    } else {
                        Write-Host ">> BuildConfiguration.xml not found on agent - skipping MaxParallelActions"
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
                    ${'$'}uatArgsStr = 'BuildGraph -script="Engine/Build/InstalledEngineBuild.xml" -target="Make Installed Build Win64" -set:WithDDC=false -set:HostPlatformOnly=true -set:GameConfigurations=Development'
                    if ('%CleanMode%' -eq 'FullRebuild') {
                        ${'$'}uatArgsStr += ' -clean'
                    }
                    
                    Write-Host ">> RunUAT BuildGraph 시작 (watchdog: 60분 무활동 시 종료)"
                    Write-Host ">> args: ${'$'}uatArgsStr"
                    ${'$'}uatProc = Start-Process -FilePath ".\Engine\Build\BatchFiles\RunUAT.bat" -ArgumentList ${'$'}uatArgsStr -RedirectStandardOutput ${'$'}uatLog -PassThru -NoNewWindow
                    
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
                    
                    if (${'$'}uatProc.ExitCode -ne 0) {
                        Write-Host "##teamcity[buildProblem description='RunUAT failed with exit code ${'$'}(${'$'}uatProc.ExitCode)']"
                        exit ${'$'}uatProc.ExitCode
                    }
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
                    
                    if (${'$'}proc.ExitCode -ge 8) { Write-Error "Robocopy failed with code ${'$'}(${'$'}proc.ExitCode)" }
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
        }
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
            scriptContent = """.\Engine\Binaries\DotNET\GitDependencies\win-x64\GitDependencies.exe --force"""
        }
    }

    features {
        perfmon {
        }
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Windows 11")
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
