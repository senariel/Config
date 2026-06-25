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
TeamCity Versioned Settings — UnrealEngine5 (engine builds)

이 파일은 DevPub / UnrealEngine5 프로젝트의 단일 소스 (One-way 모드).
변경 절차:
  1) 이 파일 수정 → push
  2) TeamCity가 ~30초 내 자동 적용
  3) UI는 read-only로 유지 (긴급 변경 절차는 CLAUDE.md 참조)
*/

version = "2025.11"

project {

    vcsRoot(EngineVcs)

    // 빌드 시 클린 범위 — 일반 Run 시 기본값(Incremental) 즉시 사용,
    // 다른 모드는 Run Custom Build 다이얼로그에서 선택
    params {
        select(
            "CleanMode", "Incremental",
            label = "빌드 클린 모드",
            description = "빌드 전 정리 범위. Run Custom Build에서 변경 가능.",
            display = ParameterDisplay.NORMAL,
            // TeamCity DSL: Pair<displayLabel, value>
            options = listOf(
                "빠른 빌드 (클린 없음)"                       to "Incremental",
                "소스 정리 (고아 파일 제거)"                  to "CleanSource",
                "전체 재빌드 (Binaries/Intermediate 초기화)"  to "FullRebuild"
            )
        )
    }

    buildType(SyncFork)
    buildType(FetchSource)
    buildType(BuildEditor)
    buildTypesOrder = arrayListOf(SyncFork, FetchSource, BuildEditor)
}

// ==========================================================================
// VCS Root: Engine source repository
// ==========================================================================
object EngineVcs : GitVcsRoot({
    name = "UnrealEngine release"
    url = "https://github.com/senariel/UnrealEngine"
    branch = "refs/heads/release"
    branchSpec = "refs/heads/*"
    authMethod = token {
        userName = "oauth2"
        // EngineVcs 토큰 (DevPubApp으로 발급, 부트스트랩 시 VCS Auth Tokens에서 Copy ID로 획득)
        tokenId = "tc_token_id:CID_3ab2f5c96314802c7074714f2b03c3a5:-1:62ad1ec8-56b9-4b2a-adce-68a33ee027a2"
    }
})

// ==========================================================================
// Sync Fork: Epic 본가(EpicGames/UnrealEngine) → 포크(senariel/UnrealEngine) 최신화
//   - 포크 자체는 자동 갱신되지 않으므로, 스케줄로 upstream을 당겨 release에 push
//   - push가 발생하면 Build Editor의 VCS trigger가 전체 체인(FetchSource→BuildEditor) 실행
//   - TeamCity 체크아웃 안 함(checkout 없음). 스텝이 SyncWorkDir에 전용 클론을 직접 관리
//     (빌드용 UE5 체크아웃과 분리 → 빌드 진행 중 충돌 없음)
//   - 현재는 커스텀 커밋이 없어 항상 fast-forward. 미래에 포크에 독자 커밋이 생기면
//     3-way 머지로 처리하고, 충돌 시 abort+실패시켜 사람이 해결
// ==========================================================================
object SyncFork : BuildType({
    name = "Sync Fork"
    description = "EpicGames/UnrealEngine release를 포크(senariel/UnrealEngine)로 동기화. push 시 Build Editor 트리거가 체인 실행."

    params {
        param("SyncBranch", "release")
        // 빌드 체크아웃과 분리된 전용 클론 위치 (에이전트에 영속). 첫 실행만 무거움.
        param("SyncWorkDir", """%teamcity.agent.work.dir%\..\ue5-fork-sync""")
        // GitHub PAT: senariel/UnrealEngine push + EpicGames/UnrealEngine fetch 권한 필요.
        // one-way 모드라 값은 UI 비상절차로 주입 (CLAUDE.md "비밀값 주입" 참조).
        password(
            "env.GIT_PUSH_TOKEN", "",
            label = "GitHub Push/Fetch PAT",
            description = "senariel/UnrealEngine push + EpicGames/UnrealEngine fetch 권한 토큰",
            display = ParameterDisplay.HIDDEN
        )
    }

    // VCS Root 미부착 — 엔진 트리를 TeamCity가 체크아웃하지 않음 (스텝이 직접 git 관리)

    steps {
        powerShell {
            name = "Sync fork from upstream"
            id = "Sync_fork_from_upstream"
            scriptMode = script {
                content = """
                    ${'$'}ErrorActionPreference = 'Stop'
                    ${'$'}branch  = '%SyncBranch%'
                    ${'$'}workDir = '%SyncWorkDir%'
                    ${'$'}token   = ${'$'}env:GIT_PUSH_TOKEN
                    if ([string]::IsNullOrEmpty(${'$'}token)) {
                        throw 'GIT_PUSH_TOKEN 미설정 - Sync Fork 파라미터(env.GIT_PUSH_TOKEN) 확인'
                    }

                    ${'$'}origin   = "https://x-access-token:${'$'}token@github.com/senariel/UnrealEngine.git"
                    ${'$'}upstream = "https://x-access-token:${'$'}token@github.com/EpicGames/UnrealEngine.git"

                    Write-Host ">> workDir=${'$'}workDir branch=${'$'}branch"

                    if (-not (Test-Path (Join-Path ${'$'}workDir '.git'))) {
                        Write-Host ">> 최초 클론 (release 단일 브랜치, 수십 GB - 처음만 오래 걸림)"
                        git clone --branch ${'$'}branch --single-branch ${'$'}origin ${'$'}workDir
                        if (${'$'}LASTEXITCODE -ne 0) { throw 'clone 실패' }
                    }

                    git -C ${'$'}workDir remote set-url origin ${'$'}origin
                    git -C ${'$'}workDir remote remove upstream 2>${'$'}null
                    git -C ${'$'}workDir remote add upstream ${'$'}upstream

                    Write-Host ">> origin fetch + 정렬"
                    git -C ${'$'}workDir fetch origin ${'$'}branch
                    if (${'$'}LASTEXITCODE -ne 0) { throw 'origin fetch 실패' }
                    git -C ${'$'}workDir checkout -B ${'$'}branch "origin/${'$'}branch"
                    if (${'$'}LASTEXITCODE -ne 0) { throw 'checkout 실패' }
                    git -C ${'$'}workDir reset --hard "origin/${'$'}branch"

                    Write-Host ">> upstream fetch"
                    git -C ${'$'}workDir fetch upstream ${'$'}branch
                    if (${'$'}LASTEXITCODE -ne 0) { throw 'upstream fetch 실패' }

                    # 이미 최신? (upstream/branch가 HEAD의 조상이면 받을 게 없음)
                    git -C ${'$'}workDir merge-base --is-ancestor "upstream/${'$'}branch" HEAD
                    if (${'$'}LASTEXITCODE -eq 0) {
                        Write-Host '>> 이미 최신 - 변경 없음'
                        exit 0
                    }

                    # 1) fast-forward 시도 (커스텀 커밋 없을 때 = 현재 상태)
                    git -C ${'$'}workDir merge --ff-only "upstream/${'$'}branch"
                    if (${'$'}LASTEXITCODE -eq 0) {
                        Write-Host '>> fast-forward 동기화'
                    } else {
                        # 2) 분기됨 -> 3-way 머지 (미래에 포크 독자 커밋 생길 때)
                        Write-Host '>> 분기 감지 - 머지 시도'
                        git -C ${'$'}workDir merge --no-edit "upstream/${'$'}branch"
                        if (${'$'}LASTEXITCODE -ne 0) {
                            git -C ${'$'}workDir merge --abort
                            throw "upstream 머지 충돌 - 수동 해결 필요 (origin/${'$'}branch 로컬 머지 후 push)"
                        }
                    }

                    git -C ${'$'}workDir push origin "HEAD:refs/heads/${'$'}branch"
                    if (${'$'}LASTEXITCODE -ne 0) { throw 'push 실패' }
                    Write-Host '>> 동기화 완료 - push 됨 (Build Editor VCS 트리거가 체인 실행)'
                """.trimIndent()
            }
        }
    }

    triggers {
        // 매일 03:00(서버 시간) 포크 최신화. 변경 없으면 push 안 함 → 체인도 안 돎.
        schedule {
            schedulingPolicy = daily {
                hour = 3
                minute = 0
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

// ==========================================================================
// Fetch Source: 소스 동기화 + CleanMode에 따른 정리
//   - 트리거 없음 (Build Editor가 트리거되면 snapshot dep로 자동 선행)
// ==========================================================================
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

    // Triggers 없음 — Build Editor의 VCS trigger가 snapshot dep로 이쪽도 깨움

    features {
        perfmon {
        }
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Windows 11")
    }
})

// ==========================================================================
// Build Editor: 엔진 Installed Build 생성 + 배포
//   - VCS commit이 들어오면 이 빌드가 트리거됨 → snapshot dep로 FetchSource 선행
//   - Distribute 스텝은 robocopy /MIR로 destination을 source와 정확히 일치
// ==========================================================================
object BuildEditor : BuildType({
    name = "Build Editor"

    params {
        param("env.UE5_DIST_PATH", """D:\Shared\UE5""")
    }

    vcs {
        // VCS Root 부착 — VCS trigger가 동작하려면 필요
        // 단, checkoutMode = MANUAL 이라 Build Editor는 자체 체크아웃 안 함
        // FetchSource가 받아둔 트리를 그대로 사용 (checkoutDir 공유)
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

                    Write-Host ">> RunUAT BuildGraph 시작 (watchdog: 30분 무출력 시 종료)"
                    Write-Host ">> args: ${'$'}uatArgsStr"
                    ${'$'}uatProc = Start-Process -FilePath ".\Engine\Build\BatchFiles\RunUAT.bat" -ArgumentList ${'$'}uatArgsStr -RedirectStandardOutput ${'$'}uatLog -PassThru -NoNewWindow

                    # Watchdog: stdout과 UAT 자체 로그 파일 둘 다 감시.
                    # UAT는 큰 파일 복사 등 일부 단계에서 stdout 침묵하지만 자체 로그
                    # (Engine/Programs/AutomationTool/Saved/Logs/Log.txt)에는 계속 기록함.
                    # 둘 중 하나라도 변하면 alive로 간주, 둘 다 N분 침묵하면 진짜 hang.
                    ${'$'}noOutputTimeoutMin = 30
                    ${'$'}uatInternalLogPath = ".\Engine\Programs\AutomationTool\Saved\Logs\Log.txt"

                    ${'$'}lastStdoutSize = 0
                    ${'$'}lastStdoutChange = Get-Date
                    ${'$'}lastDisplaySize = 0

                    ${'$'}lastInternalSize = -1   # -1 = 아직 파일을 본 적 없음
                    ${'$'}lastInternalChange = Get-Date

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
                        if (${'$'}stdoutSize -ne ${'$'}lastStdoutSize) {
                            ${'$'}lastStdoutChange = Get-Date
                            ${'$'}lastStdoutSize = ${'$'}stdoutSize
                        }

                        # --- UAT 자체 로그 파일 ---
                        if (Test-Path ${'$'}uatInternalLogPath) {
                            ${'$'}internalSize = (Get-Item ${'$'}uatInternalLogPath).Length
                            if (${'$'}lastInternalSize -lt 0 -or ${'$'}internalSize -ne ${'$'}lastInternalSize) {
                                ${'$'}lastInternalChange = Get-Date
                                ${'$'}lastInternalSize = ${'$'}internalSize
                            }
                        }
                        # 파일이 아직 없으면 lastInternalChange 그대로 유지
                        # (script 시작 시각이라 internalSilentMin이 빠르게 커짐 → Min에서 stdout이 dominant)

                        # --- 활동 판단: stdout과 internal 중 더 최근 활동 사용 ---
                        ${'$'}stdoutSilentMin = ((Get-Date) - ${'$'}lastStdoutChange).TotalMinutes
                        ${'$'}internalSilentMin = ((Get-Date) - ${'$'}lastInternalChange).TotalMinutes
                        ${'$'}silentMin = [Math]::Min(${'$'}stdoutSilentMin, ${'$'}internalSilentMin)

                        # stdout 침묵이 5분 이상이면 watchdog 상태 한 번 출력 (heartbeat 역할 + 디버깅)
                        if (${'$'}stdoutSilentMin -gt 5) {
                            Write-Host ("[WATCHDOG] stdout silent {0:N1}m, UAT log silent {1:N1}m, alive (Min={2:N1}m < {3}m)" -f ${'$'}stdoutSilentMin, ${'$'}internalSilentMin, ${'$'}silentMin, ${'$'}noOutputTimeoutMin)
                        }

                        # 무출력 timeout 검사 (둘 다 침묵해야 발동)
                        if (${'$'}silentMin -ge ${'$'}noOutputTimeoutMin) {
                            Write-Host ("##teamcity[buildProblem description='[WATCHDOG] BuildGraph stdout AND UAT log 둘 다 {0}분간 무출력 - hang 감지로 강제 종료']" -f [int]${'$'}silentMin)
                            try { ${'$'}uatProc.Kill() } catch { Write-Host "[WATCHDOG] Kill failed: ${'$'}_" }
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
        // Engine repo commit → Build Editor 큐잉 → snapshot dep로 FetchSource 자동 선행 → 이 빌드 실행
        vcs {
            quietPeriodMode = VcsTrigger.QuietPeriodMode.USE_DEFAULT
        }
    }

    features {
        perfmon {
        }
    }

    failureConditions {
        // 모듈 로드 실패 패턴 (빌드 자체는 success여도 런타임 로딩 이슈 자동 감지)
        failOnText {
            conditionType = BuildFailureOnText.ConditionType.CONTAINS
            pattern = "could not be loaded"
            failureMessage = "모듈 로딩 실패 패턴 감지"
            reverse = false
            stopBuildOnFailure = false
        }
        // .modules 매니페스트와 DLL의 BuildId 불일치
        failOnText {
            conditionType = BuildFailureOnText.ConditionType.CONTAINS
            pattern = "BuildId mismatch"
            failureMessage = "BuildId mismatch 감지 (modules 매니페스트 ↔ DLL)"
            reverse = false
            stopBuildOnFailure = false
        }
    }

    dependencies {
        snapshot(FetchSource) {
            runOnSameAgent = true
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }
})
