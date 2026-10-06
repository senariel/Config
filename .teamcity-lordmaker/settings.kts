import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.XmlReport
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildFeatures.xmlReport
import jetbrains.buildServer.configs.kotlin.buildSteps.powerShell
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.schedule
import jetbrains.buildServer.configs.kotlin.triggers.vcs
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

/*
DevPub / LordMaker — 게임 CI (Client / Server 하위 프로젝트).
UnrealEngine5(엔진) 설정은 같은 저장소의 .teamcity/ 에 있고, 이 프로젝트는
Versioned Settings의 settingsPath = .teamcity-lordmaker 로 따로 연결돼 있다.
설계: docs/superpowers/specs/2026-10-06-lordmaker-ci-structure-design.md
*/

version = "2025.11"

project {
    description = "LordMaker 게임 CI — Client(UE 컴파일·게이트·패키징) / Server(LMCore·서버 테스트·데이터 드리프트)"

    // VCS 루트는 여기(LordMaker 레벨)에 둔다: refreshable token이 이 프로젝트에 묶여 있어 하위 프로젝트는 공유해 쓴다
    vcsRoot(GameVcs)
    vcsRoot(ServerVcs)

    subProject(Client)
    subProject(Server)
    subProject(Device)

    params {
        param("env.UE5_ENGINE_ROOT", """D:\Shared\UE5""")
        param("env.LM_PYTHON", """C:\Program Files\Python314\python.exe""")
        param("env.VCVARS64", """C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat""")
    }

    features {
        // 빌드 기록 정리: 10일 보관 (UnrealEngine5와 동일)
        feature {
            type = "cleanUp"
            id = "PROJECT_CLEANUP_RULE"
            param("keepRule.1.dataToKeep", "everything")
            param("keepRule.1.type", "days")
            param("keepRule.1.days", "10")
        }
    }
}

// DevPubApp refreshable token은 발급한 프로젝트(와 하위)에서만, 그리고 발급할 때의 저장소에만 유효하다
// (엔진 토큰 → LordMaker, LordMaker 토큰 → LordMakerServer 모두 'Repository not found').
// GameVcs 토큰 = LordMaker 프로젝트에서 발급, ServerVcs 토큰 = 상위 DevPub 프로젝트에서 발급(VCS 루트 DevPub_LordMakerServer).
object GameVcs : GitVcsRoot({
    name = "LordMaker"
    url = "https://github.com/senariel/LordMaker"
    branch = "refs/heads/main"
    branchSpec = "refs/heads/*"
    // ClaudeBridge(에디터 전용, private) 서브모듈은 CI에 불필요 → 체크아웃 안 함
    checkoutSubmodules = GitVcsRoot.CheckoutSubmodules.IGNORE
    authMethod = token {
        userName = "oauth2"
        tokenId = "tc_token_id:CID_3ab2f5c96314802c7074714f2b03c3a5:-1:61fab572-6823-4a57-910f-827976630910"
    }
})

object ServerVcs : GitVcsRoot({
    name = "LordMakerServer"
    url = "https://github.com/senariel/LordMakerServer"
    branch = "refs/heads/main"
    branchSpec = "refs/heads/*"
    authMethod = token {
        userName = "oauth2"
        tokenId = "tc_token_id:CID_3ab2f5c96314802c7074714f2b03c3a5:-1:fbbb8e0e-102e-4a56-86dd-ce8092dafdb2"
    }
})

// ───────────────────────────── Client ─────────────────────────────

object Client : Project({
    name = "Client"
    description = "LordMaker 클라이언트(UE 5.8.3 설치형 엔진 D:/Shared/UE5)"

    buildType(EditorBuild)
    buildType(AndroidCompile)
    buildType(CoreGates)
    buildType(Package)
    buildTypesOrder = arrayListOf(EditorBuild, AndroidCompile, CoreGates, Package)
})

object EditorBuild : BuildType({
    name = "Editor Build"
    description = "LordMakerEditor Win64 Development 컴파일만. Core Gates 체인의 앞단(트리거는 Core Gates에 있음)."

    vcs {
        root(GameVcs)
        checkoutMode = CheckoutMode.ON_AGENT   // Git LFS는 에이전트 측 체크아웃에서만 받음
        checkoutDir = "LordMakerCI"            // Core Gates와 공유 — 에디터 바이너리 재사용
    }

    steps {
        powerShell {
            name = "Build LordMakerEditor Win64"
            id = "UBT"
            scriptMode = script {
                content = """
# UBT 컴파일만 (쿡·스테이징 없음). LordMakerEditor / Win64 는 Kotlin에서 치환
${'$'}ErrorActionPreference = 'Continue'
${'$'}build   = Join-Path ${'$'}env:UE5_ENGINE_ROOT 'Engine\Build\BatchFiles\Build.bat'
${'$'}project = Join-Path (Get-Location) 'LordMaker.uproject'
if (-not (Test-Path ${'$'}build)) { Write-Host "##teamcity[buildProblem description='Installed engine not found: ${'$'}build']"; exit 1 }
if (-not (Test-Path ${'$'}project)) { Write-Host "##teamcity[buildProblem description='LordMaker.uproject not found']"; exit 1 }
Write-Host ">> Build.bat LordMakerEditor Win64 Development"
& ${'$'}build LordMakerEditor Win64 Development "-project=${'$'}project" -waitmutex
${'$'}rc = ${'$'}LASTEXITCODE
if (${'$'}rc -ne 0) { Write-Host "##teamcity[buildProblem description='Build.bat LordMakerEditor Win64 failed (exit ${'$'}rc)']"; exit ${'$'}rc }
Write-Host ">> LordMakerEditor Win64 OK"
                """.trimIndent()
            }
        }
    }

    features {
        perfmon {
            param("teamcity.perfmon.feature.enabled", "true")
        }
    }

    requirements {
        equals("teamcity.agent.name", "Agent_Win64")
    }
})

object AndroidCompile : BuildType({
    name = "Android Compile"
    description = "LordMaker Android Development 컴파일만(쿡 없음) — clang 전용 오류(-Werror, LP64 int64_t=long) 조기 발견."

    vcs {
        root(GameVcs)
        checkoutMode = CheckoutMode.ON_AGENT
        checkoutDir = "LordMakerAndroid"       // Editor Build와 Intermediate 분리
    }

    steps {
        powerShell {
            name = "Build LordMaker Android"
            id = "UBT"
            scriptMode = script {
                content = """
# UBT 컴파일만 (쿡·스테이징 없음). LordMaker / Android 는 Kotlin에서 치환
${'$'}ErrorActionPreference = 'Continue'
${'$'}build   = Join-Path ${'$'}env:UE5_ENGINE_ROOT 'Engine\Build\BatchFiles\Build.bat'
${'$'}project = Join-Path (Get-Location) 'LordMaker.uproject'
if (-not (Test-Path ${'$'}build)) { Write-Host "##teamcity[buildProblem description='Installed engine not found: ${'$'}build']"; exit 1 }
if (-not (Test-Path ${'$'}project)) { Write-Host "##teamcity[buildProblem description='LordMaker.uproject not found']"; exit 1 }
Write-Host ">> Build.bat LordMaker Android Development"
& ${'$'}build LordMaker Android Development "-project=${'$'}project" -waitmutex
${'$'}rc = ${'$'}LASTEXITCODE
if (${'$'}rc -ne 0) { Write-Host "##teamcity[buildProblem description='Build.bat LordMaker Android failed (exit ${'$'}rc)']"; exit ${'$'}rc }
Write-Host ">> LordMaker Android OK"
                """.trimIndent()
            }
        }
    }

    triggers {
        vcs {
            branchFilter = "+:*"
        }
    }

    features {
        perfmon {
            param("teamcity.perfmon.feature.enabled", "true")
        }
    }

    requirements {
        equals("teamcity.agent.name", "Agent_Win64")
    }
})

object CoreGates : BuildType({
    name = "Core Gates"
    description = "Tools/core_gates.sh (LMSimTest 10종). exit!=0 = 실패, 마지막 성공 main 대비 DIFF = 경고."

    // 다음 빌드의 기준선이 되므로 폴더째 zip으로 게시
    artifactRules = """
        gates => gates.zip
        gates-run.log
    """.trimIndent()

    vcs {
        root(GameVcs)
        checkoutMode = CheckoutMode.ON_AGENT
        checkoutDir = "LordMakerCI"            // Editor Build와 같은 폴더 (같은 VCS 설정)
    }

    steps {
        powerShell {
            name = "core_gates.sh"
            id = "CoreGates"
            scriptMode = script {
                content = """
# LMSimTest 회귀 게이트 (Tools/core_gates.sh). 실패 = summary의 exit!=0, 기준선 DIFF = 경고만
${'$'}ErrorActionPreference = 'Continue'
${'$'}bash  = 'C:\Program Files\Git\bin\bash.exe'
${'$'}ueCmd = Join-Path ${'$'}env:UE5_ENGINE_ROOT 'Engine\Binaries\Win64\UnrealEditor-Cmd.exe'
if (-not (Test-Path ${'$'}bash))  { Write-Host "##teamcity[buildProblem description='Git Bash not found: ${'$'}bash']"; exit 1 }
if (-not (Test-Path ${'$'}ueCmd)) { Write-Host "##teamcity[buildProblem description='UnrealEditor-Cmd not found: ${'$'}ueCmd']"; exit 1 }
foreach (${'$'}d in @('gates', 'gates-baseline', 'gates-baseline.zip', 'gates-run.log')) { if (Test-Path ${'$'}d) { Remove-Item ${'$'}d -Recurse -Force } }

# 기준선 = 이 구성의 마지막 성공 main 빌드 아티팩트 gates.zip (없으면 비교 생략)
${'$'}baseArg = ''
${'$'}url  = '%teamcity.serverUrl%/httpAuth/repository/download/%system.teamcity.buildType.id%/.lastSuccessful/gates.zip?branch=main'
${'$'}cred = New-Object System.Management.Automation.PSCredential('%system.teamcity.auth.userId%', (ConvertTo-SecureString '%system.teamcity.auth.password%' -AsPlainText -Force))
try {
    Invoke-WebRequest -Uri ${'$'}url -Credential ${'$'}cred -OutFile 'gates-baseline.zip' -UseBasicParsing -ErrorAction Stop
    Expand-Archive 'gates-baseline.zip' -DestinationPath 'gates-baseline' -Force
    ${'$'}baseArg = 'gates-baseline'
    Write-Host '>> baseline: last successful main Core Gates'
} catch {
    Write-Host ('>> baseline 없음 - 비교 생략 (' + ${'$'}_.Exception.Message + ')')
}

# UE_CMD는 bash 명령줄에 직접 넘긴다. 빌드 #1은 UE_CMD가 비어 기본 경로 /c/Dev/...로 exit=127 —
# 정규식 -replace 대신 문자열 Replace 사용 (패턴 이스케이프 실수 여지 제거)
${'$'}ueCmdPosix = ${'$'}ueCmd.Replace('\', '/')
& ${'$'}bash -c "UE_CMD='${'$'}ueCmdPosix' Tools/core_gates.sh gates ${'$'}baseArg 2>&1 | tee gates-run.log"

${'$'}sums = @(Get-ChildItem 'gates' -Filter '*.summary.txt' -ErrorAction SilentlyContinue)
if (${'$'}sums.Count -eq 0) { Write-Host "##teamcity[buildProblem description='Core Gates produced no summaries']"; exit 1 }
${'$'}bad = @()
foreach (${'$'}s in ${'$'}sums) {
    ${'$'}m = Select-String -Path ${'$'}s.FullName -Pattern 'exit=(\d+)' | Select-Object -Last 1
    if (-not ${'$'}m -or ${'$'}m.Matches[0].Groups[1].Value -ne '0') { ${'$'}bad += ${'$'}s.Name }
}
${'$'}diffs = @(Select-String -Path 'gates-run.log' -Pattern '^DIFF\s+(\S+)' | ForEach-Object { ${'$'}_.Matches[0].Groups[1].Value })
if (${'$'}diffs.Count -gt 0) {
    Write-Host ("##teamcity[message text='Core Gates baseline DIFF: " + (${'$'}diffs -join ', ') + "' status='WARNING']")
    Write-Host ("##teamcity[buildStatus text='{build.status.text}; baseline DIFF " + ${'$'}diffs.Count + "']")
}
if (${'$'}bad.Count -gt 0) { Write-Host ("##teamcity[buildProblem description='Core Gates exit!=0: " + (${'$'}bad -join ', ') + "']"); exit 1 }
Write-Host (">> Core Gates OK: " + ${'$'}sums.Count + " runs, baseline DIFF " + ${'$'}diffs.Count)
                """.trimIndent()
            }
        }
    }

    triggers {
        // 체인 끝에 트리거 → 푸시마다 Editor Build → Core Gates
        vcs {
            branchFilter = "+:*"
            watchChangesInDependencies = true
        }
    }

    dependencies {
        snapshot(EditorBuild) {
            runOnSameAgent = true
            reuseBuilds = ReuseBuilds.SUCCESSFUL
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }

    failureConditions {
        executionTimeoutMin = 120
    }

    features {
        perfmon {
            param("teamcity.perfmon.feature.enabled", "true")
        }
    }

    requirements {
        equals("teamcity.agent.name", "Agent_Win64")
    }
})

object Package : BuildType({
    name = "Package"
    description = "Win64/Android Development 쿠킹·패키징. main 푸시 자동 + 수동. 산출물은 빌드 아티팩트."

    // -archivedirectory를 플랫폼별(Archive/Win64, Archive/Android)로 직접 지정하므로 경로가 고정됨.
    // 스테이징 UECommandLine.txt = Deploy to Device가 테스트 DeviceId를 붙일 기본 명령줄
    artifactRules = """
        Archive/Win64 => LordMaker-Win64-%ClientConfig%.zip
        Archive/Android => LordMaker-Android-%ClientConfig%.zip
        Archive/Android/*.apk => apk
        Saved/StagedBuilds/Android*/UECommandLine.txt => apk
    """.trimIndent()

    params {
        select("Platforms", "Win64+Android", label = "패키징 플랫폼",
                options = listOf("Win64 + Android" to "Win64+Android", "Win64" to "Win64", "Android" to "Android"))
        // 설치형 엔진이 GameConfigurations=Development만 포함 → Shipping은 엔진 재빌드 후 추가
        select("ClientConfig", "Development", label = "구성", options = listOf("Development" to "Development"))
        text("LMServerUrl", "https://lm.senariel.duckdns.org", label = "게임 서버 주소",
                description = "LM.Server.Url cvar로 주입 (Config/<Platform>/<Platform>Engine.ini [ConsoleVariables], 커밋 안 함)",
                display = ParameterDisplay.NORMAL, allowEmpty = false)
    }

    vcs {
        root(GameVcs)
        checkoutMode = CheckoutMode.ON_AGENT
        checkoutDir = "LordMakerPkg"
    }

    steps {
        powerShell {
            name = "BuildCookRun"
            id = "BuildCookRun"
            scriptMode = script {
                content = """
# 주의: PS 5.1에서 Stop + native stderr(2>) 조합은 즉시 예외 → Continue 유지, 종료코드로 판정
${'$'}ErrorActionPreference = 'Continue'
${'$'}engine  = ${'$'}env:UE5_ENGINE_ROOT
${'$'}project = Join-Path (Get-Location) 'LordMaker.uproject'
${'$'}archive = Join-Path (Get-Location) 'Archive'
${'$'}runUat  = Join-Path ${'$'}engine 'Engine\Build\BatchFiles\RunUAT.bat'

# 0) 사전 점검: 설치형 엔진 / uproject / Git LFS 실체화
if (-not (Test-Path (Join-Path ${'$'}engine 'Engine\Build\InstalledBuild.txt'))) { Write-Host "##teamcity[buildProblem description='Installed engine not found: ${'$'}engine']"; exit 1 }
if (-not (Test-Path ${'$'}project)) { Write-Host "##teamcity[buildProblem description='LordMaker.uproject not found']"; exit 1 }
git lfs version *> ${'$'}null
if (${'$'}LASTEXITCODE -ne 0) { Write-Host "##teamcity[buildProblem description='git-lfs not installed on agent']"; exit 1 }
${'$'}pointers = @(git lfs ls-files --name-only | Select-Object -First 50 | Where-Object {
    ${'$'}f = Join-Path (Get-Location) ${'$'}_
    (Test-Path ${'$'}f) -and ((Get-Item ${'$'}f).Length -lt 1024) -and ((Get-Content ${'$'}f -TotalCount 1) -like 'version https://git-lfs*')
})
if (${'$'}pointers.Count -gt 0) { Write-Host ("##teamcity[buildProblem description='LFS not pulled (pointer only): " + ${'$'}pointers[0] + "']"); exit 1 }
Write-Host ">> LFS OK"

# 1) 서버 주소 주입: 플랫폼 ini [ConsoleVariables] (작업 사본에만 — 커밋 안 함)
${'$'}url = '%LMServerUrl%'.Trim()
foreach (${'$'}p in @('Windows', 'Android')) {
    ${'$'}rel = "Config/${'$'}p/${'$'}{p}Engine.ini"
    ${'$'}ini = Join-Path (Get-Location) ${'$'}rel
    New-Item (Split-Path ${'$'}ini) -ItemType Directory -Force | Out-Null
    git ls-files --error-unmatch ${'$'}rel *> ${'$'}null
    if (${'$'}LASTEXITCODE -eq 0) { git checkout -- ${'$'}rel; ${'$'}prefix = (Get-Content ${'$'}ini -Raw) + "`r`n" } else { ${'$'}prefix = '' }
    Set-Content -Path ${'$'}ini -Encoding UTF8 -Value (${'$'}prefix + "; [TeamCity] build-time injection`r`n[ConsoleVariables]`r`nLM.Server.Url=${'$'}url`r`n")
    Write-Host ">> ${'$'}rel : LM.Server.Url=${'$'}url"
}

# 2) 플랫폼별 BuildCookRun (한 플랫폼이 실패해도 나머지는 진행)
if (Test-Path ${'$'}archive) { Remove-Item ${'$'}archive -Recurse -Force }
${'$'}failed = @()
foreach (${'$'}plat in ('%Platforms%' -split '\+')) {
    # 플랫폼별 아카이브 폴더를 직접 지정 — UE는 경로에 플랫폼 이름(에이전트 work 경로의 'Win64')이 있으면
    # 하위 폴더를 붙이지 않아(DeploymentContext.cs) Archive/Windows가 안 생기던 문제(#29) 회피
    ${'$'}platArchive = Join-Path ${'$'}archive ${'$'}plat
    ${'$'}bcrArgs = "BuildCookRun -project=`"${'$'}project`" -noP4 -utf8output -unattended -platform=${'$'}plat -clientconfig=%ClientConfig% -build -cook -stage -pak -iostore -package -archive -archivedirectory=`"${'$'}platArchive`""
    if (${'$'}plat -eq 'Android') { ${'$'}bcrArgs += ' -cookflavor=ASTC' }
    Write-Host "##teamcity[blockOpened name='BuildCookRun ${'$'}plat']"
    Write-Host ">> RunUAT ${'$'}bcrArgs"
    ${'$'}proc = Start-Process -FilePath ${'$'}runUat -ArgumentList ${'$'}bcrArgs -PassThru -NoNewWindow
    ${'$'}null = ${'$'}proc.Handle   # 종료 후 .ExitCode가 null 되는 것 방지 (핸들 캐싱)
    ${'$'}proc.WaitForExit()
    ${'$'}rc = ${'$'}proc.ExitCode
    if (${'$'}null -eq ${'$'}rc) { ${'$'}rc = 0 }
    Write-Host "##teamcity[blockClosed name='BuildCookRun ${'$'}plat']"
    if (${'$'}rc -ne 0) { ${'$'}failed += ${'$'}plat; Write-Host "##teamcity[buildProblem description='BuildCookRun ${'$'}plat failed (exit ${'$'}rc)' identity='bcr_${'$'}plat']" }
    else { Write-Host ">> ${'$'}plat OK" }
    if (Test-Path ${'$'}platArchive) { Get-ChildItem ${'$'}platArchive | ForEach-Object { Write-Host ("   archive/" + ${'$'}plat + "/" + ${'$'}_.Name) } }
}
if (${'$'}failed.Count -gt 0) { exit 1 }
                """.trimIndent()
            }
        }
    }

    triggers {
        vcs {
            branchFilter = "+:main"
        }
    }

    failureConditions {
        executionTimeoutMin = 360
    }

    features {
        perfmon {
            param("teamcity.perfmon.feature.enabled", "true")
        }
    }

    requirements {
        // 설치형 엔진(D:/Shared/UE5)·Android SDK·MSVC 14.50.35717이 있는 머신
        equals("teamcity.agent.name", "Agent_Win64")
    }
})

// ───────────────────────────── Server ─────────────────────────────

object Server : Project({
    name = "Server"
    description = "LMCore(C++ 규칙 코어)·LordMakerServer(Python) — 배포/재시작은 넣지 않음(서버 세션이 수동 관리)"

    buildType(LMCore)
    buildType(ServerTests)
    buildType(StaticDataDrift)
    buildTypesOrder = arrayListOf(LMCore, ServerTests, StaticDataDrift)
})

object LMCore : BuildType({
    name = "LMCore"
    description = "Core/ CMake 빌드 + ctest. 산출물 lmcore.cp314-win_amd64.pyd (Server Tests가 소비)."

    artifactRules = "build/lmcore*.pyd"

    vcs {
        // 필요한 경로만 부분 체크아웃 (LFS 대상 콘텐츠 제외)
        root(GameVcs, """
            +:Core
            +:Source/LMCore
            +:Content/Data
        """.trimIndent())
        checkoutMode = CheckoutMode.ON_AGENT
        checkoutDir = "LordMakerCore"
    }

    steps {
        script {
            name = "Python 3.14 check"
            id = "PyCheck"
            scriptContent = """
@echo off
rem Python 3.14 (모든 사용자 설치) 확인 — 없으면 원인을 바로 표시
if not exist "%env.LM_PYTHON%" goto nopython
"%env.LM_PYTHON%" -c "import sys; sys.exit(0 if sys.version_info[:2]==(3,14) else 1)" || goto badpython
"%env.LM_PYTHON%" --version
exit /b 0
:nopython
echo ##teamcity[buildProblem description='Python 3.14 not found: %env.LM_PYTHON% - install Python 3.14.5 for all users on the agent and restart the agent']
exit /b 1
:badpython
echo ##teamcity[buildProblem description='Python at %env.LM_PYTHON% is not 3.14']
exit /b 1
            """.trimIndent()
        }
        script {
            name = "CMake build + ctest"
            id = "CMake"
            scriptContent = """
@echo off
rem LMCore: CMake + Ninja (VS BuildTools 번들) + ctest. 산출물 build\lmcore*.pyd
set PYTHONUTF8=1
call "%env.VCVARS64%" >nul || goto novcvars
where cmake >nul 2>nul || goto nocmake
where ninja >nul 2>nul || goto nocmake
cmake --version
if exist build rmdir /s /q build
cmake -S Core -B build -G Ninja -DCMAKE_BUILD_TYPE=Release "-DPython_EXECUTABLE=%env.LM_PYTHON%" || exit /b 1
cmake --build build || exit /b 1
ctest --test-dir build --output-on-failure || exit /b 1
dir /b build\lmcore*.pyd || goto nopyd
exit /b 0
:novcvars
echo ##teamcity[buildProblem description='vcvars64.bat failed: %env.VCVARS64%']
exit /b 1
:nocmake
echo ##teamcity[buildProblem description='cmake/ninja not found after vcvars64 - install the VS BuildTools component C++ CMake tools for Windows']
exit /b 1
:nopyd
echo ##teamcity[buildProblem description='lmcore python module was not produced']
exit /b 1
            """.trimIndent()
        }
    }

    triggers {
        vcs {
            branchFilter = "+:*"
            triggerRules = """
                +:Core/**
                +:Source/LMCore/**
            """.trimIndent()
        }
    }

    requirements {
        equals("teamcity.agent.name", "Agent_Win64")
    }
})

object ServerTests : BuildType({
    name = "Server Tests"
    description = "LordMakerServer pytest. LMCore 마지막 성공 main의 .pyd + LordMaker Core/tests/fixtures(LORDMAKER_FIXTURES) 사용."

    vcs {
        root(ServerVcs)
        // 에이전트 측 체크아웃은 'a => 접두/a' 형태만 허용 (경로 이름 변경 불가)
        root(GameVcs, "+:Core/tests/fixtures => lordmaker-fixtures/Core/tests/fixtures")
        checkoutMode = CheckoutMode.ON_AGENT
        checkoutDir = "LordMakerServerTests"
    }

    steps {
        script {
            name = "Python 3.14 check"
            id = "PyCheck"
            scriptContent = """
@echo off
rem Python 3.14 (모든 사용자 설치) 확인 — 없으면 원인을 바로 표시
if not exist "%env.LM_PYTHON%" goto nopython
"%env.LM_PYTHON%" -c "import sys; sys.exit(0 if sys.version_info[:2]==(3,14) else 1)" || goto badpython
"%env.LM_PYTHON%" --version
exit /b 0
:nopython
echo ##teamcity[buildProblem description='Python 3.14 not found: %env.LM_PYTHON% - install Python 3.14.5 for all users on the agent and restart the agent']
exit /b 1
:badpython
echo ##teamcity[buildProblem description='Python at %env.LM_PYTHON% is not 3.14']
exit /b 1
            """.trimIndent()
        }
        script {
            name = "pytest"
            id = "Pytest"
            scriptContent = """
@echo off
rem LordMakerServer pytest. lmcore*.pyd = LMCore 아티팩트(체크아웃 루트), fixtures = LordMaker Core/tests/fixtures 부분 체크아웃
set PYTHONUTF8=1
set LORDMAKER_FIXTURES=%teamcity.build.checkoutDir%\lordmaker-fixtures\Core\tests\fixtures
dir /b lmcore*.pyd
if exist .venv rmdir /s /q .venv
"%env.LM_PYTHON%" -m venv .venv || exit /b 1
.venv\Scripts\python -m pip install -q --disable-pip-version-check -e ".[dev]" || exit /b 1
.venv\Scripts\python -m pytest tests -q -rs --junitxml=test-results.xml
            """.trimIndent()
        }
    }

    triggers {
        vcs {
            branchFilter = "+:*"
        }
    }

    dependencies {
        artifacts(LMCore) {
            buildRule = lastSuccessful("main")
            artifactRules = "lmcore*.pyd => ."
        }
    }

    features {
        xmlReport {
            reportType = XmlReport.XmlReportType.JUNIT
            rules = "test-results.xml"
        }
    }

    requirements {
        equals("teamcity.agent.name", "Agent_Win64")
    }
})

object StaticDataDrift : BuildType({
    name = "Static Data Drift"
    description = "클라 Content/Data ↔ 서버 data/static 해시 + RulesVersion/StaticDataVersion 대조 (서버 tools/check_static_drift.py). 매일 03:30."

    vcs {
        root(ServerVcs, "+:. => server")
        root(GameVcs, """
            +:Content/Data => client/Content/Data
            +:Source/LMCore/Public/lmcore => client/Source/LMCore/Public/lmcore
            +:Source/LordMaker/Public/Combat => client/Source/LordMaker/Public/Combat
        """.trimIndent())
        checkoutMode = CheckoutMode.ON_AGENT
        checkoutDir = "LordMakerDrift"
    }

    steps {
        script {
            name = "Python 3.14 check"
            id = "PyCheck"
            scriptContent = """
@echo off
rem Python 3.14 (모든 사용자 설치) 확인 — 없으면 원인을 바로 표시
if not exist "%env.LM_PYTHON%" goto nopython
"%env.LM_PYTHON%" -c "import sys; sys.exit(0 if sys.version_info[:2]==(3,14) else 1)" || goto badpython
"%env.LM_PYTHON%" --version
exit /b 0
:nopython
echo ##teamcity[buildProblem description='Python 3.14 not found: %env.LM_PYTHON% - install Python 3.14.5 for all users on the agent and restart the agent']
exit /b 1
:badpython
echo ##teamcity[buildProblem description='Python at %env.LM_PYTHON% is not 3.14']
exit /b 1
            """.trimIndent()
        }
        script {
            name = "check_static_drift.py"
            id = "Drift"
            scriptContent = """
@echo off
rem 정적 데이터 드리프트: 0=일치, 1=드리프트, 2=체크아웃/경로 문제
set PYTHONUTF8=1
"%env.LM_PYTHON%" server\tools\check_static_drift.py --client client --server server
if errorlevel 2 goto checkout
if errorlevel 1 goto drift
exit /b 0
:drift
echo ##teamcity[buildProblem description='Static data drift between LordMaker and LordMakerServer - see log']
exit /b 1
:checkout
echo ##teamcity[buildProblem description='Drift check could not read checkouts - partial checkout rules broken?']
exit /b 1
            """.trimIndent()
        }
    }

    triggers {
        schedule {
            schedulingPolicy = daily {
                hour = 3
                minute = 30
            }
            branchFilter = "+:<default>"
            triggerBuild = always()
            withPendingChangesOnly = false
        }
    }

    requirements {
        equals("teamcity.agent.name", "Agent_Win64")
    }
})

// ───────────────────────────── Device ─────────────────────────────

object Device : Project({
    name = "Device"
    description = "테스트 기기(Android) 등록·배포 — Wi-Fi adb(무선 디버깅), 수동 실행. 설치는 에이전트(AYA-ZZANG)의 adb."

    buildType(RegisterDevice)
    buildType(DeployToDevice)
    buildTypesOrder = arrayListOf(RegisterDevice, DeployToDevice)
})

object RegisterDevice : BuildType({
    name = "Register Device"
    description = "휴대폰 무선 디버깅 '페어링 코드로 기기 페어링' 화면의 IP:포트·6자리 코드로 adb pair (기기당 1회)."

    params {
        text("PairAddress", "", label = "페어링 주소 (IP:포트)",
                description = "휴대폰 설정 → 개발자 옵션 → 무선 디버깅 → '페어링 코드로 기기 페어링' 화면의 IP 주소 및 포트",
                display = ParameterDisplay.PROMPT, allowEmpty = false)
        password("PairCode", "", label = "페어링 코드 (6자리)",
                description = "같은 화면의 Wi-Fi 페어링 코드 — 1~2분 안에 만료되므로 화면을 띄운 직후 실행",
                display = ParameterDisplay.PROMPT)
    }

    steps {
        powerShell {
            name = "adb pair"
            id = "Pair"
            scriptMode = script {
                content = """
# --- adb 공통 (Register/Deploy 앞부분에 삽입) ---
${'$'}ErrorActionPreference = 'Continue'
${'$'}adb = Join-Path ${'$'}env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not ${'$'}env:ANDROID_HOME -or -not (Test-Path ${'$'}adb)) { Write-Host "##teamcity[buildProblem description='adb not found under ANDROID_HOME on the agent']"; exit 1 }
function Invoke-Adb([string[]]${'$'}adbArgs) {
    # 네이티브 stderr를 문자열로 합쳐 반환 (PS 5.1 ErrorRecord 장식 제거)
    ${'$'}lines = & ${'$'}adb @adbArgs 2>&1 | ForEach-Object { "${'$'}_" }
    return (${'$'}lines -join "`n")
}

function Show-AdbServerLog {
    # adb 서버는 시작 시점의 TEMP(= 에이전트 buildTmp)에 adb.log를 쓴다
    ${'$'}log = Join-Path ${'$'}env:TEMP 'adb.log'
    if (Test-Path ${'$'}log) {
        Write-Host '>> adb server log (tail):'
        Get-Content ${'$'}log -Tail 40 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host ('   ' + ${'$'}_) }
    }
}

function Restart-AdbServer {
    ${'$'}null = Invoke-Adb @('kill-server')
    Start-Sleep -Seconds 1
    ${'$'}null = Invoke-Adb @('start-server')
}

Write-Host ('>> ' + ((Invoke-Adb @('version')) -split "`n" | Select-Object -First 2) -join ' / ')
${'$'}null = Invoke-Adb @('start-server')

function Connect-WirelessDevices([string]${'$'}manualAddress) {
    # mDNS로 찾은 무선 디버깅 연결 엔드포인트(_adb-tls-connect)에 연결. 페어링된 기기만 성공한다.
    ${'$'}svc = Invoke-Adb @('mdns', 'services')
    ${'$'}found = 0
    foreach (${'$'}l in (${'$'}svc -split "`n")) {
        if (${'$'}l -match '_adb-tls-connect\._tcp\.?\s+(\S+:\d+)') {
            ${'$'}found++
            ${'$'}r = Invoke-Adb @('connect', ${'$'}Matches[1])
            Write-Host (">> adb connect " + ${'$'}Matches[1] + " : " + ${'$'}r.Trim())
        }
    }
    if (${'$'}manualAddress) {
        ${'$'}r = Invoke-Adb @('connect', ${'$'}manualAddress)
        Write-Host (">> adb connect " + ${'$'}manualAddress + " (수동) : " + ${'$'}r.Trim())
    }
    if (${'$'}found -eq 0 -and -not ${'$'}manualAddress) {
        Write-Host '>> mDNS에서 무선 디버깅 기기를 찾지 못함 (무선 디버깅 꺼짐 / 다른 서브넷 / 방화벽의 mDNS 차단). ConnectAddress로 직접 지정 가능.'
    }
    Start-Sleep -Seconds 2
}

function Get-OnlineDevices {
    # 같은 기기가 mDNS 이름과 IP:포트 두 줄로 보일 수 있어 ro.serialno로 중복 제거
    ${'$'}list = @()
    ${'$'}seen = @{}
    foreach (${'$'}l in ((Invoke-Adb @('devices', '-l')) -split "`n" | Select-Object -Skip 1)) {
        if (${'$'}l -notmatch '^(\S+)\s+device\b') { continue }
        ${'$'}serial = ${'$'}Matches[1]
        ${'$'}model = 'unknown'
        if (${'$'}l -match 'model:(\S+)') { ${'$'}model = ${'$'}Matches[1] }
        ${'$'}hw = (Invoke-Adb @('-s', ${'$'}serial, 'shell', 'getprop', 'ro.serialno')).Trim()
        if (-not ${'$'}hw) { ${'$'}hw = ${'$'}serial }
        if (${'$'}seen.ContainsKey(${'$'}hw)) { continue }
        ${'$'}seen[${'$'}hw] = ${'$'}true
        ${'$'}list += [pscustomobject]@{ Serial = ${'$'}serial; Model = ${'$'}model; HwSerial = ${'$'}hw }
    }
    return ,${'$'}list
}

# --- Register Device: adb pair (기기당 1회, 페어링 키는 에이전트 adb에 영구 저장) ---
${'$'}addr = '%PairAddress%'.Trim()
${'$'}code = '%PairCode%'.Trim()
if (${'$'}addr -notmatch '^\d{1,3}(\.\d{1,3}){3}:\d+${'$'}') { Write-Host "##teamcity[buildProblem description='PairAddress must be IP:port from the phone pairing dialog']"; exit 1 }
if (${'$'}code -notmatch '^\d{6}${'$'}') { Write-Host "##teamcity[buildProblem description='PairCode must be the 6-digit Wi-Fi pairing code']"; exit 1 }

${'$'}r = Invoke-Adb @('pair', ${'$'}addr, ${'$'}code)
Write-Host (">> adb pair " + ${'$'}addr + " : " + ${'$'}r.Trim())
if (${'$'}r -match 'protocol fault') {
    # 클라이언트가 adb 서버(5037)의 응답을 못 받음 = 서버 쪽 문제(이전 빌드의 오래된 서버 등) → 서버 재시작 후 같은 코드로 1회 재시도
    Show-AdbServerLog
    Write-Host '>> adb server restart + retry'
    Restart-AdbServer
    ${'$'}r = Invoke-Adb @('pair', ${'$'}addr, ${'$'}code)
    Write-Host (">> adb pair (retry) " + ${'$'}addr + " : " + ${'$'}r.Trim())
}
if (${'$'}r -notmatch 'Successfully paired') {
    Show-AdbServerLog
    ${'$'}tail = Get-Content (Join-Path ${'$'}env:TEMP 'adb.log') -Tail 5 -ErrorAction SilentlyContinue | Out-String
    if (${'$'}tail -match 'Handshake failed') {
        # 연결은 됐는데 TLS 핸드셰이크 즉시 실패 = 페어링 서비스가 아닌 포트(무선 디버깅 메인 화면의 연결 포트)일 가능성이 큼
        Write-Host "##teamcity[buildProblem description='Pairing TLS handshake failed - use the IP:port shown inside the Pair device with pairing code popup, not the main Wireless debugging screen']"
        exit 1
    }
    Write-Host "##teamcity[buildProblem description='adb pair failed - code expired (1-2 min), wrong address, or phone on another subnet']"
    exit 1
}

Connect-WirelessDevices ''
${'$'}devices = Get-OnlineDevices
Write-Host ">> 현재 연결된 기기:"
foreach (${'$'}d in ${'$'}devices) { Write-Host ("   " + ${'$'}d.Model + "  serial=" + ${'$'}d.HwSerial + "  (" + ${'$'}d.Serial + ")") }
Write-Host ">> 페어링 완료. 이제 Deploy to Device로 설치할 수 있습니다 (휴대폰의 무선 디버깅이 켜져 있어야 함)."
                """.trimIndent()
            }
        }
    }

    requirements {
        equals("teamcity.agent.name", "Agent_Win64")
    }
})

object DeployToDevice : BuildType({
    name = "Deploy to Device"
    description = "Package APK 설치 + 테스트 DeviceId(lmtest-<모델>-<시리얼해시8>) 명령줄 주입·검증. 검증 실패 기기는 앱 실행 금지(실계정 보호)."

    params {
        text("DeviceFilter", "", label = "대상 기기 필터",
                description = "모델명 또는 시리얼 일부. 비우면 연결된 테스트 기기 전부",
                display = ParameterDisplay.NORMAL, allowEmpty = true)
        text("ConnectAddress", "", label = "직접 연결 주소 (선택)",
                description = "mDNS로 기기를 못 찾을 때: 휴대폰 무선 디버깅 화면의 'IP 주소 및 포트'(페어링 포트와 다름)",
                display = ParameterDisplay.NORMAL, allowEmpty = true)
        checkbox("LaunchAfterInstall", "false", label = "설치 후 실행",
                description = "실행 후 logcat에서 로그인 deviceId가 lmtest-인지 확인 (아니면 강제 종료·실패)",
                checked = "true", unchecked = "false")
    }

    steps {
        powerShell {
            name = "Install + inject test DeviceId"
            id = "Deploy"
            scriptMode = script {
                content = """
# --- adb 공통 (Register/Deploy 앞부분에 삽입) ---
${'$'}ErrorActionPreference = 'Continue'
${'$'}adb = Join-Path ${'$'}env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not ${'$'}env:ANDROID_HOME -or -not (Test-Path ${'$'}adb)) { Write-Host "##teamcity[buildProblem description='adb not found under ANDROID_HOME on the agent']"; exit 1 }
function Invoke-Adb([string[]]${'$'}adbArgs) {
    # 네이티브 stderr를 문자열로 합쳐 반환 (PS 5.1 ErrorRecord 장식 제거)
    ${'$'}lines = & ${'$'}adb @adbArgs 2>&1 | ForEach-Object { "${'$'}_" }
    return (${'$'}lines -join "`n")
}

function Show-AdbServerLog {
    # adb 서버는 시작 시점의 TEMP(= 에이전트 buildTmp)에 adb.log를 쓴다
    ${'$'}log = Join-Path ${'$'}env:TEMP 'adb.log'
    if (Test-Path ${'$'}log) {
        Write-Host '>> adb server log (tail):'
        Get-Content ${'$'}log -Tail 40 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host ('   ' + ${'$'}_) }
    }
}

function Restart-AdbServer {
    ${'$'}null = Invoke-Adb @('kill-server')
    Start-Sleep -Seconds 1
    ${'$'}null = Invoke-Adb @('start-server')
}

Write-Host ('>> ' + ((Invoke-Adb @('version')) -split "`n" | Select-Object -First 2) -join ' / ')
${'$'}null = Invoke-Adb @('start-server')

function Connect-WirelessDevices([string]${'$'}manualAddress) {
    # mDNS로 찾은 무선 디버깅 연결 엔드포인트(_adb-tls-connect)에 연결. 페어링된 기기만 성공한다.
    ${'$'}svc = Invoke-Adb @('mdns', 'services')
    ${'$'}found = 0
    foreach (${'$'}l in (${'$'}svc -split "`n")) {
        if (${'$'}l -match '_adb-tls-connect\._tcp\.?\s+(\S+:\d+)') {
            ${'$'}found++
            ${'$'}r = Invoke-Adb @('connect', ${'$'}Matches[1])
            Write-Host (">> adb connect " + ${'$'}Matches[1] + " : " + ${'$'}r.Trim())
        }
    }
    if (${'$'}manualAddress) {
        ${'$'}r = Invoke-Adb @('connect', ${'$'}manualAddress)
        Write-Host (">> adb connect " + ${'$'}manualAddress + " (수동) : " + ${'$'}r.Trim())
    }
    if (${'$'}found -eq 0 -and -not ${'$'}manualAddress) {
        Write-Host '>> mDNS에서 무선 디버깅 기기를 찾지 못함 (무선 디버깅 꺼짐 / 다른 서브넷 / 방화벽의 mDNS 차단). ConnectAddress로 직접 지정 가능.'
    }
    Start-Sleep -Seconds 2
}

function Get-OnlineDevices {
    # 같은 기기가 mDNS 이름과 IP:포트 두 줄로 보일 수 있어 ro.serialno로 중복 제거
    ${'$'}list = @()
    ${'$'}seen = @{}
    foreach (${'$'}l in ((Invoke-Adb @('devices', '-l')) -split "`n" | Select-Object -Skip 1)) {
        if (${'$'}l -notmatch '^(\S+)\s+device\b') { continue }
        ${'$'}serial = ${'$'}Matches[1]
        ${'$'}model = 'unknown'
        if (${'$'}l -match 'model:(\S+)') { ${'$'}model = ${'$'}Matches[1] }
        ${'$'}hw = (Invoke-Adb @('-s', ${'$'}serial, 'shell', 'getprop', 'ro.serialno')).Trim()
        if (-not ${'$'}hw) { ${'$'}hw = ${'$'}serial }
        if (${'$'}seen.ContainsKey(${'$'}hw)) { continue }
        ${'$'}seen[${'$'}hw] = ${'$'}true
        ${'$'}list += [pscustomobject]@{ Serial = ${'$'}serial; Model = ${'$'}model; HwSerial = ${'$'}hw }
    }
    return ,${'$'}list
}

# --- Deploy to Device: 설치 → 테스트 DeviceId 명령줄 주입·검증 → (옵션) 실행·로그인 ID 확인 ---
# 명령줄 파일 push·검증이 실패한 기기에서는 앱을 실행하지 않는다 (테스트 계정이 아닌 새 게스트 계정이 생기는 것 방지)
${'$'}pkg       = 'com.devpub.lordmaker'
${'$'}remoteDir = "/sdcard/Android/data/${'$'}pkg/files/UnrealGame/LordMaker"
${'$'}filter    = '%DeviceFilter%'.Trim()
${'$'}launch    = '%LaunchAfterInstall%' -eq 'true'

${'$'}apk = Get-ChildItem 'apk' -Filter '*.apk' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not ${'$'}apk) { Write-Host "##teamcity[buildProblem description='No APK in the Package artifact (apk/*.apk)']"; exit 1 }
Write-Host (">> APK: " + ${'$'}apk.Name + " (" + [math]::Round(${'$'}apk.Length / 1MB, 1) + " MB)")

# 기본 명령줄 = Package가 게시한 스테이징 UECommandLine.txt (외부 파일이 명령줄 전체를 '교체'하므로 반드시 포함)
${'$'}baseFile = Join-Path 'apk' 'UECommandLine.txt'
if (Test-Path ${'$'}baseFile) {
    ${'$'}baseCmd = (Get-Content ${'$'}baseFile -TotalCount 1).Trim()
} else {
    ${'$'}baseCmd = '../../../LordMaker/LordMaker.uproject'
    Write-Host "##teamcity[message text='apk/UECommandLine.txt not in artifact - using default base command line' status='WARNING']"
}
Write-Host (">> base command line: " + ${'$'}baseCmd)

${'$'}manualAddress = '%ConnectAddress%'.Trim()
Connect-WirelessDevices ${'$'}manualAddress
${'$'}devices = Get-OnlineDevices
if (${'$'}filter) { ${'$'}devices = @(${'$'}devices | Where-Object { ${'$'}_.Model -like "*${'$'}filter*" -or ${'$'}_.HwSerial -like "*${'$'}filter*" }) }
if (${'$'}devices.Count -eq 0) {
    Write-Host "##teamcity[buildProblem description='No connected test device - turn on Wireless debugging, same subnet as the agent, Register Device first']"
    exit 1
}

${'$'}sha = [System.Security.Cryptography.SHA256]::Create()
${'$'}utf8 = New-Object System.Text.UTF8Encoding ${'$'}false   # BOM 없이 (BOM이 명령줄 첫 글자로 들어가면 안 됨)
${'$'}failed = @()
foreach (${'$'}d in ${'$'}devices) {
    ${'$'}s = ${'$'}d.Serial
    ${'$'}hash = (-join (${'$'}sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(${'$'}d.HwSerial)) | Select-Object -First 4 | ForEach-Object { ${'$'}_.ToString('x2') }))
    ${'$'}slug = (${'$'}d.Model.ToLower() -replace '[^a-z0-9]+', '-').Trim('-')
    if (${'$'}slug.Length -gt 40) { ${'$'}slug = ${'$'}slug.Substring(0, 40).Trim('-') }
    ${'$'}id = "lmtest-${'$'}slug-${'$'}hash"
    ${'$'}line = "${'$'}baseCmd -LMDeviceId=${'$'}id"
    Write-Host "##teamcity[blockOpened name='${'$'}(${'$'}d.Model) (${'$'}id)']"

    ${'$'}ok = ${'$'}true
    ${'$'}r = Invoke-Adb @('-s', ${'$'}s, 'install', '-r', ${'$'}apk.FullName)
    Write-Host (">> install: " + ${'$'}r.Trim())
    if (${'$'}r -notmatch 'Success') { ${'$'}ok = ${'$'}false; Write-Host '>> 설치 실패' }

    if (${'$'}ok) {
        ${'$'}null = Invoke-Adb @('-s', ${'$'}s, 'shell', 'mkdir', '-p', ${'$'}remoteDir)
        ${'$'}tmp = [System.IO.Path]::GetTempFileName()
        [System.IO.File]::WriteAllText(${'$'}tmp, ${'$'}line, ${'$'}utf8)
        ${'$'}r = Invoke-Adb @('-s', ${'$'}s, 'push', ${'$'}tmp, "${'$'}remoteDir/UECommandLine.txt")
        Remove-Item ${'$'}tmp -Force
        Write-Host (">> push: " + ${'$'}r.Trim())
        ${'$'}back = (Invoke-Adb @('-s', ${'$'}s, 'shell', 'cat', "${'$'}remoteDir/UECommandLine.txt")).Trim()
        if (${'$'}back -ne ${'$'}line) { ${'$'}ok = ${'$'}false; Write-Host (">> 명령줄 파일 검증 실패 - 읽은 값: " + ${'$'}back) }
        else { Write-Host (">> 명령줄 파일 확인: " + ${'$'}back) }
    }

    if (${'$'}ok -and ${'$'}launch) {
        ${'$'}null = Invoke-Adb @('-s', ${'$'}s, 'logcat', '-c')
        ${'$'}null = Invoke-Adb @('-s', ${'$'}s, 'shell', 'monkey', '-p', ${'$'}pkg, '-c', 'android.intent.category.LAUNCHER', '1')
        ${'$'}ids = @()
        for (${'$'}i = 0; ${'$'}i -lt 30 -and ${'$'}ids.Count -eq 0; ${'$'}i++) {
            Start-Sleep -Seconds 2
            ${'$'}log = Invoke-Adb @('-s', ${'$'}s, 'logcat', '-d')
            ${'$'}ids = @([regex]::Matches(${'$'}log, 'deviceId=([A-Za-z0-9._:-]+)') | ForEach-Object { ${'$'}_.Groups[1].Value } | Select-Object -Unique)
        }
        ${'$'}realIds = @(${'$'}ids | Where-Object { ${'$'}_ -notlike 'lmtest-*' })
        if (${'$'}ids.Count -eq 0) {
            Write-Host "##teamcity[message text='${'$'}(${'$'}d.Model): login deviceId not seen in logcat within 60s (command line file was verified)' status='WARNING']"
        } elseif (${'$'}realIds.Count -eq 0) {
            Write-Host (">> 로그인 deviceId 확인: " + (${'$'}ids -join ', '))
        } else {
            ${'$'}null = Invoke-Adb @('-s', ${'$'}s, 'shell', 'am', 'force-stop', ${'$'}pkg)
            ${'$'}ok = ${'$'}false
            Write-Host ('>> 테스트 DeviceId 주입 실패 - lmtest- 아닌 deviceId로 로그인: ' + (${'$'}realIds -join ', ') + ' → 앱 강제 종료')
        }
    }

    Write-Host "##teamcity[blockClosed name='${'$'}(${'$'}d.Model) (${'$'}id)']"
    if (-not ${'$'}ok) { ${'$'}failed += ${'$'}d.Model; Write-Host "##teamcity[buildProblem description='Deploy failed on ${'$'}(${'$'}d.Model) (${'$'}id)' identity='deploy_${'$'}hash']" }
}
if (${'$'}failed.Count -gt 0) { exit 1 }
Write-Host (">> 배포 완료: " + ${'$'}devices.Count + "대")
                """.trimIndent()
            }
        }
    }

    dependencies {
        // 기본 = Package 마지막 성공 main. 다른 빌드는 Run custom build → Dependencies에서 선택
        artifacts(Package) {
            buildRule = lastSuccessful("main")
            cleanDestination = true
            artifactRules = "apk/** => apk"
        }
    }

    requirements {
        equals("teamcity.agent.name", "Agent_Win64")
    }
})
