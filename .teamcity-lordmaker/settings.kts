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

// 토큰은 이 프로젝트(LordMaker)에서 DevPubApp으로 발급한 것 — 프로젝트에 묶이므로 다른 프로젝트에선 못 씀.
// DevPubApp은 모든 저장소 접근 허용이라 서버 저장소도 같은 토큰을 쓴다.
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
        tokenId = "tc_token_id:CID_3ab2f5c96314802c7074714f2b03c3a5:-1:61fab572-6823-4a57-910f-827976630910"
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

${'$'}env:UE_CMD = ${'$'}ueCmd -replace '\', '/'
& ${'$'}bash -c "Tools/core_gates.sh gates ${'$'}baseArg 2>&1 | tee gates-run.log"

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

    // -archivedirectory를 플랫폼별(Archive/Win64, Archive/Android)로 직접 지정하므로 경로가 고정됨
    artifactRules = """
        Archive/Win64 => LordMaker-Win64-%ClientConfig%.zip
        Archive/Android => LordMaker-Android-%ClientConfig%.zip
        Archive/Android/*.apk => apk
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
        root(GameVcs, "+:Core/tests/fixtures => lordmaker-fixtures")
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
set LORDMAKER_FIXTURES=%teamcity.build.checkoutDir%\lordmaker-fixtures
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
