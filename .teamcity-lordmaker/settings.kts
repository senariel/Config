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
DevPub / LordMaker — 게임 CI (Client / Server / Device 하위 프로젝트).
UnrealEngine5(엔진) 설정은 같은 저장소의 .teamcity/ 에 있고, 이 프로젝트는
Versioned Settings의 settingsPath = .teamcity-lordmaker 로 따로 연결돼 있다.
설계: docs/superpowers/specs/2026-10-06-lordmaker-ci-structure-design.md
에이전트 배정: docs/superpowers/specs/2026-10-16-linux-build-machine-design.md
  - Client = Windows 에이전트(devpub.role=project), Server·Device = 리눅스 에이전트(devpub.role=server, .180 호스트)
  - 경로 파라미터는 OS가 달라 하위 프로젝트마다 따로 둔다.
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

    // Windows 에이전트 경로 (P5에서 Project 에이전트 확정 시 갱신)
    params {
        param("env.UE5_ENGINE_ROOT", """D:\Shared\UE5""")
        // 패키지 최신본(항상 1개, 매 빌드 교체)과 선택적 zip 보관 위치 — 아카이브는 배포 폴더 밖이어야 함(robocopy /MIR)
        param("env.LM_DIST_PATH", """D:\Shared\LordMaker""")
        param("env.LM_ARCHIVE_PATH", """D:\Shared\LordMaker_Archives""")
    }
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
        equals("devpub.role", "project")
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
        equals("devpub.role", "project")
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
        equals("devpub.role", "project")
    }
})

object Package : BuildType({
    name = "Package"
    description = "Win64/Android Development 쿠킹·패키징. main 푸시 자동 + 수동. 최신본 1개만 D:/Shared/LordMaker/<플랫폼>에 교체, ArchiveBuild 체크 시에만 zip 보관."

    // TeamCity 아티팩트로는 게시하지 않는다(빌드마다 서버에 쌓임 — 디스크 여유 부족).
    // 산출물: LM_DIST_PATH\Win64, LM_DIST_PATH\Android(APK + 기본 명령줄 UECommandLine.txt + BUILD_INFO.txt)

    params {
        checkbox("ArchiveBuild", "false", label = "빌드 아카이빙 (Zip)",
                description = "체크하면 이번 결과를 D:/Shared/LordMaker_Archives에 zip으로 따로 보관(자동 삭제 안 함). 기본은 최신본 1개만 유지",
                checked = "true", unchecked = "false")
        select("Platforms", "Win64+Android", label = "패키징 플랫폼",
                options = listOf("Win64 + Android" to "Win64+Android", "Win64" to "Win64", "Android" to "Android"))
        // 설치형 엔진이 GameConfigurations=Development만 포함 → Shipping은 엔진 재빌드 후 추가
        select("ClientConfig", "Development", label = "구성", options = listOf("Development" to "Development"))
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

# 1) 예전 서버 주소 주입 파일 정리 — 서버 주소는 클라가 직접 가진다(DefaultGame.ini 서버 목록, 기본 Live; main 6207355~).
#    예전 빌드가 작업 사본에 써 둔 ini는 Git 추적 밖이라 체크아웃 정리(브랜치 변경 시만)로 안 지워진다 → 남으면 패키지에 다시 들어감.
#    다른 서버로 붙여야 하면 빌드가 아니라 실행 시 -LMServer=<Live|Lan|Local|Test|URL>.
foreach (${'$'}p in @('Windows', 'Android')) {
    ${'$'}rel = "Config/${'$'}p/${'$'}{p}Engine.ini"
    if (-not (Test-Path ${'$'}rel)) { continue }
    git ls-files --error-unmatch ${'$'}rel *> ${'$'}null
    if (${'$'}LASTEXITCODE -eq 0) { git checkout -- ${'$'}rel; Write-Host ">> ${'$'}rel : 저장소 원본으로 복원" }
    else { Remove-Item -LiteralPath ${'$'}rel -Force; Write-Host ">> ${'$'}rel : 예전 주입 파일 삭제" }
}

# 배포: 최신 1개만 유지 (TeamCity 아티팩트로 쌓지 않음 — 디스크 여유 부족)
#   LM_DIST_PATH\<plat>   = 최신 빌드 (robocopy /MIR로 교체, 실패한 플랫폼은 이전 것 유지)
#   LM_ARCHIVE_PATH       = ArchiveBuild 체크 시에만 zip 보관 (자동 삭제 안 함)
${'$'}distRoot    = ${'$'}env:LM_DIST_PATH
${'$'}archiveRoot = ${'$'}env:LM_ARCHIVE_PATH
${'$'}doArchive   = '%ArchiveBuild%' -eq 'true'
${'$'}distFull    = [IO.Path]::GetFullPath(${'$'}distRoot).TrimEnd('\') + '\'      # 끝에 구분자 — LordMaker_Archives가 LordMaker로 오판되지 않게
${'$'}archiveFull = [IO.Path]::GetFullPath(${'$'}archiveRoot).TrimEnd('\') + '\'
if (${'$'}doArchive -and ${'$'}archiveFull.StartsWith(${'$'}distFull, [StringComparison]::OrdinalIgnoreCase)) {
    # 배포 폴더 안이면 다음 빌드의 robocopy /MIR이 지워 버림 (엔진 쪽과 같은 함정)
    Write-Host "##teamcity[buildProblem description='LM_ARCHIVE_PATH must not be inside LM_DIST_PATH']"; exit 1
}

function Publish-Platform([string]${'$'}plat, [string]${'$'}src) {
    if (-not (Test-Path ${'$'}src)) { Write-Host "##teamcity[buildProblem description='${'$'}plat archive folder missing: ${'$'}src']"; return ${'$'}false }
    if (${'$'}plat -eq 'Android') {
        # Deploy to Device가 테스트 DeviceId를 붙일 기본 명령줄 (스테이징 원본)
        ${'$'}cmd = Get-ChildItem (Join-Path (Get-Location) 'Saved\StagedBuilds') -Directory -Filter 'Android*' -ErrorAction SilentlyContinue |
               ForEach-Object { Join-Path ${'$'}_.FullName 'UECommandLine.txt' } | Where-Object { Test-Path ${'$'}_ } | Select-Object -First 1
        if (${'$'}cmd) { Copy-Item ${'$'}cmd (Join-Path ${'$'}src 'UECommandLine.txt') -Force }
        else { Write-Host "##teamcity[message text='Staged Android UECommandLine.txt not found' status='WARNING']" }
    }
    ${'$'}info = @(
        "build=%build.number%", "revision=%build.vcs.number%", "branch=%teamcity.build.branch%",
        "config=%ClientConfig%", "platform=${'$'}plat", ("date=" + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    )
    Set-Content -Path (Join-Path ${'$'}src 'BUILD_INFO.txt') -Value ${'$'}info -Encoding UTF8

    ${'$'}dest = Join-Path ${'$'}distRoot ${'$'}plat
    New-Item ${'$'}dest -ItemType Directory -Force | Out-Null
    ${'$'}p = Start-Process robocopy.exe -ArgumentList "`"${'$'}src`" `"${'$'}dest`" /MIR /R:2 /W:5 /NFL /NDL /NP /NJH" -PassThru -NoNewWindow
    ${'$'}null = ${'$'}p.Handle   # 핸들 캐싱 (종료 후 ExitCode null 방지)
    ${'$'}p.WaitForExit()
    ${'$'}rcCopy = ${'$'}p.ExitCode
    if (${'$'}null -eq ${'$'}rcCopy) { ${'$'}rcCopy = 0 }
    if (${'$'}rcCopy -ge 8) { Write-Host "##teamcity[buildProblem description='robocopy to ${'$'}dest failed (exit ${'$'}rcCopy)']"; return ${'$'}false }
    Write-Host (">> " + ${'$'}plat + " -> " + ${'$'}dest + " (robocopy " + ${'$'}rcCopy + ")")
    Get-ChildItem ${'$'}dest | ForEach-Object { Write-Host ("   " + ${'$'}_.Name) }

    if (${'$'}doArchive) {
        New-Item ${'$'}archiveRoot -ItemType Directory -Force | Out-Null
        ${'$'}zip = Join-Path ${'$'}archiveRoot ("LordMaker_" + ${'$'}plat + "_%ClientConfig%_%build.number%_" + (Get-Date -Format 'yyyyMMdd-HHmm') + ".zip")
        ${'$'}sevenZip = (Get-Command '7z.exe' -ErrorAction SilentlyContinue).Source
        if (-not ${'$'}sevenZip -and (Test-Path "${'$'}env:ProgramFiles\7-Zip\7z.exe")) { ${'$'}sevenZip = "${'$'}env:ProgramFiles\7-Zip\7z.exe" }
        ${'$'}zipOk = ${'$'}false
        if (${'$'}sevenZip) { & ${'$'}sevenZip a -tzip -mx=1 "${'$'}zip" "${'$'}dest\*" | Out-Null; ${'$'}zipOk = (${'$'}LASTEXITCODE -le 1) }
        else { try { Compress-Archive -Path "${'$'}dest\*" -DestinationPath ${'$'}zip -Force -ErrorAction Stop; ${'$'}zipOk = ${'$'}true } catch { Write-Host (">> Compress-Archive 실패: " + ${'$'}_) } }
        if (${'$'}zipOk) { Write-Host (">> archived: " + ${'$'}zip) }
        else { Write-Host "##teamcity[buildProblem description='Archiving ${'$'}plat failed (latest copy in ${'$'}dest is fine)']" }
    }

    # 에이전트 작업 폴더의 중간 사본 정리 (Archive, 스테이징) — 배포 폴더에 한 벌만 남김
    Remove-Item ${'$'}src -Recurse -Force -ErrorAction SilentlyContinue
    Get-ChildItem (Join-Path (Get-Location) 'Saved\StagedBuilds') -Directory -ErrorAction SilentlyContinue |
        Where-Object { ${'$'}_.Name -like (${'$'}(if (${'$'}plat -eq 'Win64') { 'Windows*' } else { 'Android*' })) } |
        ForEach-Object { Remove-Item ${'$'}_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
    return ${'$'}true
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
    if (${'$'}rc -ne 0) { ${'$'}failed += ${'$'}plat; Write-Host "##teamcity[buildProblem description='BuildCookRun ${'$'}plat failed (exit ${'$'}rc)' identity='bcr_${'$'}plat']"; continue }
    Write-Host ">> ${'$'}plat OK"
    if (-not (Publish-Platform ${'$'}plat ${'$'}platArchive)) { ${'$'}failed += ${'$'}plat }
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
        // Windows Project 에이전트: 설치형 엔진·Android SDK·MSVC 14.50.35717
        equals("devpub.role", "project")
    }
})

// ───────────────────────────── Server ─────────────────────────────

object Server : Project({
    name = "Server"
    description = "LMCore(C++ 규칙 코어)·LordMakerServer(Python) — 리눅스 에이전트(.180). 배포는 별도(Deploy Server, 설계서 4장)"

    buildType(LMCore)
    buildType(ServerTests)
    buildType(StaticDataDrift)
    buildTypesOrder = arrayListOf(LMCore, ServerTests, StaticDataDrift)

    params {
        // 시스템 Python 3.14 (apt python3.14 + python3.14-venv/-dev). 게임 서버도 같은 인터프리터로 돈다 → lmcore .so ABI 일치
        param("env.LM_PYTHON", "/usr/bin/python3.14")
    }
})

// 리눅스 에이전트 공통: Python 3.14 확인 (없으면 원인을 바로 표시)
// 스크립트 조각은 object 안의 val로 둔다 — 최상위 fun/val은 스크립트 클래스 멤버라 object(빌드 구성)가 참조하면
// DSL 컴파일 오류('captures the script class instance'), 최상위 val은 선언 순서 문제도 있다. object 멤버는 첫 사용 때 초기화.
object PyCheck {
val script = """
#!/usr/bin/env bash
set -uo pipefail
if ! "%env.LM_PYTHON%" -c 'import sys; sys.exit(0 if sys.version_info[:2]==(3,14) else 1)'; then
  echo "##teamcity[buildProblem description='Python 3.14 not found at %env.LM_PYTHON% - apt install python3.14 python3.14-venv python3.14-dev on the agent']"
  exit 1
fi
"%env.LM_PYTHON%" --version
""".trimIndent()
}

object LMCore : BuildType({
    name = "LMCore"
    description = "Core/ CMake 빌드 + ctest (리눅스 gcc). 산출물 lmcore.cpython-314-x86_64-linux-gnu.so (Server Tests·서버 배포가 소비)."

    artifactRules = "build/lmcore*.so"

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
            scriptContent = PyCheck.script
        }
        script {
            name = "CMake build + ctest"
            id = "CMake"
            scriptContent = """
#!/usr/bin/env bash
# LMCore: CMake + Ninja + ctest. 산출물 build/lmcore*.so
# FP 결정론 규칙(설계서 2장): Core/CMakeLists.txt의 -ffp-contract=off -fno-fast-math(PUBLIC)를 덮어쓰지 않는다.
# 추가만 허용: -march=x86-64-v2 고정(native 금지). -Ofast·-ffast-math 계열 금지 — 아래에서 실제 플래그를 검사한다.
set -euo pipefail
echo ">> $(g++ --version | head -1)"
echo ">> $(cmake --version | head -1) / ninja $(ninja --version)"
rm -rf build
cmake -S Core -B build -G Ninja -DCMAKE_BUILD_TYPE=Release \
      -DPython_EXECUTABLE="%env.LM_PYTHON%" -DCMAKE_CXX_FLAGS=-march=x86-64-v2 \
      -DCMAKE_EXPORT_COMPILE_COMMANDS=ON
# 실제 컴파일 플래그 기록 + 금지 플래그 검사 (결정론 게이트 실패 시 1차 대조 자료)
"%env.LM_PYTHON%" - <<'EOF'
import json, shlex, sys
cmds = json.load(open('build/compile_commands.json'))
bad = ('-Ofast', '-ffast-math', '-funsafe-math-optimizations', '-fassociative-math',
       '-freciprocal-math', '-ffinite-math-only', '-ffp-contract=fast', '-m32', '-mfpmath=387', '-march=native')
seen, problems = set(), []
for c in cmds:
    args = c.get('arguments') or shlex.split(c['command'])
    flags = ' '.join(a for a in args if a.startswith(('-O', '-f', '-m', '-std')))
    if flags not in seen:
        seen.add(flags)
        print('>> flags:', flags)
    if 'ThirdParty' in c['file']:
        continue
    problems += [a for a in args if a in bad]
    if '-ffp-contract=off' not in args:
        problems.append('missing -ffp-contract=off in ' + c['file'])
if problems:
    print("##teamcity[buildProblem description='LMCore FP determinism flags violated - see log']")
    for p in sorted(set(problems)):
        print('>> BAD:', p)
    sys.exit(1)
EOF
cmake --build build
ctest --test-dir build --output-on-failure
if ! ls build/lmcore*.so; then
  echo "##teamcity[buildProblem description='lmcore python module was not produced']"
  exit 1
fi
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
        equals("devpub.role", "server")
    }
})

object ServerTests : BuildType({
    name = "Server Tests"
    description = "LordMakerServer pytest. LMCore 마지막 성공 main의 .so + LordMaker Core/tests/fixtures(LORDMAKER_FIXTURES) 사용."

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
            scriptContent = PyCheck.script
        }
        script {
            name = "pytest"
            id = "Pytest"
            scriptContent = """
#!/usr/bin/env bash
# LordMakerServer pytest. lmcore*.so = LMCore 아티팩트(체크아웃 루트), fixtures = LordMaker Core/tests/fixtures 부분 체크아웃
set -euo pipefail
export LORDMAKER_FIXTURES="%teamcity.build.checkoutDir%/lordmaker-fixtures/Core/tests/fixtures"
ls lmcore*.so
rm -rf .venv
"%env.LM_PYTHON%" -m venv .venv
.venv/bin/python -m pip install -q --disable-pip-version-check -e ".[dev]"
.venv/bin/python -m pytest tests -q -rs --junitxml=test-results.xml
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
            artifactRules = "lmcore*.so => ."
        }
    }

    features {
        xmlReport {
            reportType = XmlReport.XmlReportType.JUNIT
            rules = "test-results.xml"
        }
    }

    requirements {
        equals("devpub.role", "server")
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
            scriptContent = PyCheck.script
        }
        script {
            name = "check_static_drift.py"
            id = "Drift"
            scriptContent = """
#!/usr/bin/env bash
# 정적 데이터 드리프트: 0=일치, 1=드리프트, 2=체크아웃/경로 문제
set -uo pipefail
"%env.LM_PYTHON%" server/tools/check_static_drift.py --client client --server server
rc=$?
if [ "${'$'}rc" -ge 2 ]; then
  echo "##teamcity[buildProblem description='Drift check could not read checkouts - partial checkout rules broken?']"
  exit 1
elif [ "${'$'}rc" -eq 1 ]; then
  echo "##teamcity[buildProblem description='Static data drift between LordMaker and LordMakerServer - see log']"
  exit 1
fi
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
        equals("devpub.role", "server")
    }
})

// ───────────────────────────── Device ─────────────────────────────

object Device : Project({
    name = "Device"
    description = "테스트 기기(Android) 등록·배포·로그 — Wi-Fi adb(무선 디버깅), 수동 실행. 리눅스 에이전트(.180)의 adb."

    buildType(RegisterDevice)
    buildType(DeployToDevice)
    buildType(CollectDeviceLogs)
    buildTypesOrder = arrayListOf(RegisterDevice, DeployToDevice, CollectDeviceLogs)

    params {
        // Google platform-tools zip을 /opt/android에 설치 (Wi-Fi 페어링 때문에 최신판)
        param("env.ADB", "/opt/android/platform-tools/adb")
        // Package 최신본 — Windows Project 에이전트가 samba(\\192.168.219.180\Shared)로 쓰고, 여기서는 로컬로 읽는다
        param("env.LM_DIST_PATH", "/mnt/d/Shared/LordMaker")
    }
})

// adb 공통 (Register/Deploy/Collect 앞부분) — bash
// adb shell은 stdin을 읽으므로 while-read 루프 안에서 부르는 adb는 반드시 </dev/null (adbq가 처리)
object AdbCommon {
val script = """
#!/usr/bin/env bash
set -uo pipefail
ADB="%env.ADB%"
if [ ! -x "${'$'}ADB" ]; then
  echo "##teamcity[buildProblem description='adb not found at %env.ADB% on the agent']"
  exit 1
fi
adbq() { "${'$'}ADB" "${'$'}@" </dev/null 2>&1 | tr -d '\r'; }

show_adb_server_log() {
  # adb 서버는 시작 시점의 TMPDIR(이전 빌드의 buildTmp일 수 있음) 또는 /tmp에 adb.<uid>.log를 쓴다
  local f
  for f in "${'$'}{TMPDIR:-/tmp}/adb.$(id -u).log" "/tmp/adb.$(id -u).log"; do
    if [ -f "${'$'}f" ]; then
      echo ">> adb server log (tail): ${'$'}f"
      tail -n 40 "${'$'}f" | sed 's/^/   /'
      return
    fi
  done
}

restart_adb_server() {
  adbq kill-server >/dev/null
  sleep 1
  adbq start-server >/dev/null
}

echo ">> $(adbq version | head -2 | tr '\n' ' ')"
adbq start-server >/dev/null

connect_wireless_devices() {
  # mDNS로 찾은 무선 디버깅 연결 엔드포인트(_adb-tls-connect)에 연결. 페어링된 기기만 성공한다.
  local manual="${'$'}1" found=0 line ep re='_adb-tls-connect\._tcp\.?[[:space:]]+([^[:space:]]+:[0-9]+)'
  while IFS= read -r line; do
    if [[ "${'$'}line" =~ ${'$'}re ]]; then
      found=$((found + 1))
      ep="${'$'}{BASH_REMATCH[1]}"
      echo ">> adb connect ${'$'}ep : $(adbq connect "${'$'}ep")"
    fi
  done < <(adbq mdns services)
  if [ -n "${'$'}manual" ]; then
    echo ">> adb connect ${'$'}manual (수동) : $(adbq connect "${'$'}manual")"
  fi
  if [ "${'$'}found" -eq 0 ] && [ -z "${'$'}manual" ]; then
    echo '>> mDNS에서 무선 디버깅 기기를 찾지 못함 (무선 디버깅 꺼짐 / 다른 서브넷 / 방화벽의 mDNS 차단). ConnectAddress로 직접 지정 가능.'
  fi
  sleep 2
}

# 연결된 기기 목록 → 배열 DEV_SERIAL / DEV_MODEL / DEV_HW
# 같은 기기가 mDNS 이름과 IP:포트 두 줄로 보일 수 있어 ro.serialno로 중복 제거. 필터(모델·시리얼 일부, 대소문자 무시) 적용
get_online_devices() {
  local filter="${'$'}{1:-}" serial state rest model hw re='model:([^[:space:]]+)'
  local -A seen=()
  DEV_SERIAL=(); DEV_MODEL=(); DEV_HW=()
  while read -r serial state rest; do
    [ "${'$'}state" = "device" ] || continue
    model=unknown
    if [[ "${'$'}rest" =~ ${'$'}re ]]; then model="${'$'}{BASH_REMATCH[1]}"; fi
    hw="$(adbq -s "${'$'}serial" shell getprop ro.serialno)"
    [ -n "${'$'}hw" ] || hw="${'$'}serial"
    [ -z "${'$'}{seen[${'$'}hw]:-}" ] || continue
    seen[${'$'}hw]=1
    if [ -n "${'$'}filter" ] && [[ "${'$'}{model,,}" != *"${'$'}{filter,,}"* ]] && [[ "${'$'}{hw,,}" != *"${'$'}{filter,,}"* ]]; then continue; fi
    DEV_SERIAL+=("${'$'}serial"); DEV_MODEL+=("${'$'}model"); DEV_HW+=("${'$'}hw")
  done < <(adbq devices -l | tail -n +2)
}
""".trimIndent()
}

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
        script {
            name = "adb pair"
            id = "Pair"
            scriptContent = AdbCommon.script + "\n" + """
# --- Register Device: adb pair (기기당 1회, 페어링 키는 에이전트 계정의 ~/.android에 영구 저장) ---
addr="$(printf '%%s' '%PairAddress%' | tr -d '[:space:]')"
code="$(printf '%%s' '%PairCode%' | tr -d '[:space:]')"
if [[ ! "${'$'}addr" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]+${'$'} ]]; then
  echo "##teamcity[buildProblem description='PairAddress must be IP:port from the phone pairing dialog']"; exit 1
fi
if [[ ! "${'$'}code" =~ ^[0-9]{6}${'$'} ]]; then
  echo "##teamcity[buildProblem description='PairCode must be the 6-digit Wi-Fi pairing code']"; exit 1
fi

r="$(adbq pair "${'$'}addr" "${'$'}code")"
echo ">> adb pair ${'$'}addr : ${'$'}r"
if [[ "${'$'}r" == *"protocol fault"* ]]; then
  # 클라이언트가 adb 서버(5037)의 응답을 못 받음 = 서버 쪽 문제(이전 빌드의 오래된 서버 등) → 서버 재시작 후 같은 코드로 1회 재시도
  show_adb_server_log
  echo '>> adb server restart + retry'
  restart_adb_server
  r="$(adbq pair "${'$'}addr" "${'$'}code")"
  echo ">> adb pair (retry) ${'$'}addr : ${'$'}r"
fi
if [[ "${'$'}r" != *"Successfully paired"* ]]; then
  show_adb_server_log
  if grep -qs 'Handshake failed' "${'$'}{TMPDIR:-/tmp}/adb.$(id -u).log" "/tmp/adb.$(id -u).log"; then
    # 연결은 됐는데 TLS 핸드셰이크 즉시 실패 = 페어링 서비스가 아닌 포트(무선 디버깅 메인 화면의 연결 포트)일 가능성이 큼
    echo "##teamcity[buildProblem description='Pairing TLS handshake failed - use the IP:port shown inside the Pair device with pairing code popup, not the main Wireless debugging screen']"
    exit 1
  fi
  echo "##teamcity[buildProblem description='adb pair failed - code expired (1-2 min), wrong address, or phone on another subnet']"
  exit 1
fi

connect_wireless_devices ''
get_online_devices
echo ">> 현재 연결된 기기:"
for i in "${'$'}{!DEV_SERIAL[@]}"; do echo "   ${'$'}{DEV_MODEL[${'$'}i]}  serial=${'$'}{DEV_HW[${'$'}i]}  (${'$'}{DEV_SERIAL[${'$'}i]})"; done
echo ">> 페어링 완료. 이제 Deploy to Device로 설치할 수 있습니다 (휴대폰의 무선 디버깅이 켜져 있어야 함)."
            """.trimIndent()
        }
    }

    requirements {
        equals("devpub.role", "server")
    }
})

object DeployToDevice : BuildType({
    name = "Deploy to Device"
    description = "Package 최신 APK(/mnt/d/Shared/LordMaker/Android)를 연결된 테스트 기기에 install -r. 기기 ID는 앱이 스스로 만든다(주입 안 함)."

    params {
        text("ApkDir", "", label = "APK 폴더 (선택)",
                description = "비우면 Package 최신본 /mnt/d/Shared/LordMaker/Android. 아카이브를 풀어 둔 폴더 등 다른 빌드를 설치할 때 지정",
                display = ParameterDisplay.NORMAL, allowEmpty = true)
        text("DeviceFilter", "", label = "대상 기기 필터",
                description = "모델명 또는 시리얼 일부. 비우면 연결된 테스트 기기 전부",
                display = ParameterDisplay.NORMAL, allowEmpty = true)
        text("ConnectAddress", "", label = "직접 연결 주소 (선택)",
                description = "mDNS로 기기를 못 찾을 때: 휴대폰 무선 디버깅 화면의 'IP 주소 및 포트'(페어링 포트와 다름)",
                display = ParameterDisplay.NORMAL, allowEmpty = true)
        checkbox("LaunchAfterInstall", "false", label = "설치 후 실행",
                description = "설치 후 앱을 실행하고 logcat의 로그인 deviceId를 참고용으로 출력",
                checked = "true", unchecked = "false")
    }

    steps {
        script {
            name = "Install (+ launch)"
            id = "Deploy"
            scriptContent = AdbCommon.script + "\n" + """
# --- Deploy to Device: 연결 → install -r → (옵션) 실행·로그인 deviceId 참고 출력 ---
# 기기 ID(계정)는 배포 도구가 정하지 않는다 — 앱 첫 실행 때 엔진이 무작위 ID를 만들어 내부 저장소에 저장하고,
# install -r 해도 유지된다(사용자 결정, 2026-10-07). 그래서 ID 주입·검증 단계는 없다.
pkg='com.devpub.lordmaker'
launch='%LaunchAfterInstall%'

# APK 위치 = Package가 교체해 두는 최신 배포 폴더(LM_DIST_PATH/Android). 다른 폴더(풀어 둔 아카이브 등)는 ApkDir로 지정
apkDir='%ApkDir%'
[ -n "${'$'}apkDir" ] || apkDir="%env.LM_DIST_PATH%/Android"
apk="$(ls -1 "${'$'}apkDir"/*.apk 2>/dev/null | head -1)"
if [ -z "${'$'}apk" ]; then
  echo "##teamcity[buildProblem description='No APK in ${'$'}apkDir - run Package (Android) first']"; exit 1
fi
echo ">> APK: ${'$'}apk ($(du -m "${'$'}apk" | cut -f1) MB)"
# BUILD_INFO.txt는 Windows 에이전트가 쓴다(CRLF)
if [ -f "${'$'}apkDir/BUILD_INFO.txt" ]; then tr -d '\r' < "${'$'}apkDir/BUILD_INFO.txt" | sed 's/^/   /'; fi

connect_wireless_devices '%ConnectAddress%'
get_online_devices '%DeviceFilter%'
if [ "${'$'}{#DEV_SERIAL[@]}" -eq 0 ]; then
  echo "##teamcity[buildProblem description='No connected test device - turn on Wireless debugging, same subnet as the agent, Register Device first']"
  exit 1
fi

failed=0
for i in "${'$'}{!DEV_SERIAL[@]}"; do
  s="${'$'}{DEV_SERIAL[${'$'}i]}"
  label="${'$'}{DEV_MODEL[${'$'}i]} ${'$'}{DEV_HW[${'$'}i]}"
  echo "##teamcity[blockOpened name='${'$'}label']"

  ok=1
  r="$(adbq -s "${'$'}s" install -r "${'$'}apk")"
  echo ">> install: ${'$'}r"
  if [[ "${'$'}r" != *Success* ]]; then ok=0; echo '>> 설치 실패'; fi

  if [ "${'$'}ok" -eq 1 ] && [ "${'$'}launch" = "true" ]; then
    adbq -s "${'$'}s" logcat -c >/dev/null
    adbq -s "${'$'}s" shell monkey -p "${'$'}pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
    # 로그인 deviceId는 판정 없이 참고용으로만 출력 (최대 60초 대기)
    # 게임 로그인 줄(LogLMLogin)만 — 시스템 로그에도 'deviceId=14' 같은 줄이 많다
    ids=''
    for _ in $(seq 1 30); do
      sleep 2
      ids="$(adbq -s "${'$'}s" logcat -d | grep -oE 'LogLMLogin.*deviceId=[A-Za-z0-9._:-]+' | sed 's/.*deviceId=//' | sort -u | paste -sd, -)"
      [ -z "${'$'}ids" ] || break
    done
    if [ -n "${'$'}ids" ]; then echo ">> 로그인 deviceId (참고): ${'$'}ids"
    else echo '>> 60초 안에 로그인 deviceId 로그가 보이지 않음 (참고) — 문제면 Collect Device Logs 실행'; fi
  fi

  echo "##teamcity[blockClosed name='${'$'}label']"
  if [ "${'$'}ok" -eq 0 ]; then
    failed=$((failed + 1))
    echo "##teamcity[buildProblem description='Deploy failed on ${'$'}label' identity='deploy_${'$'}{DEV_HW[${'$'}i]}']"
  fi
done
[ "${'$'}failed" -eq 0 ] || exit 1
echo ">> 배포 완료: ${'$'}{#DEV_SERIAL[@]}대"
            """.trimIndent()
        }
    }

    requirements {
        equals("devpub.role", "server")
    }
})

object CollectDeviceLogs : BuildType({
    name = "Collect Device Logs"
    description = "크래시 직후 실행: 테스트 기기의 logcat(crash/all)·UE 로그(Saved/Logs)·크래시 폴더를 아티팩트 device-logs.zip으로 수집."

    artifactRules = "device-logs => device-logs.zip"

    params {
        text("DeviceFilter", "", label = "대상 기기 필터",
                description = "모델명 또는 시리얼 일부. 비우면 연결된 테스트 기기 전부",
                display = ParameterDisplay.NORMAL, allowEmpty = true)
        text("ConnectAddress", "", label = "직접 연결 주소 (선택)",
                description = "mDNS로 기기를 못 찾을 때: 휴대폰 무선 디버깅 화면의 'IP 주소 및 포트'",
                display = ParameterDisplay.NORMAL, allowEmpty = true)
    }

    steps {
        script {
            name = "Collect logcat + UE logs"
            id = "Collect"
            scriptContent = AdbCommon.script + "\n" + """
# --- Collect Device Logs: 크래시 직후 실행 — logcat(main/crash 버퍼)·UE 로그·크래시 폴더를 아티팩트로 ---
# adb 출력은 UTF-8 바이트 그대로 저장한다 (리눅스라 Windows cp949 변환 문제 없음)
pkg='com.devpub.lordmaker'
ueRoot="/sdcard/Android/data/${'$'}pkg/files/UnrealGame/LordMaker/LordMaker/Saved"
outRoot="${'$'}PWD/device-logs"
rm -rf "${'$'}outRoot"; mkdir -p "${'$'}outRoot"

connect_wireless_devices '%ConnectAddress%'
get_online_devices '%DeviceFilter%'
if [ "${'$'}{#DEV_SERIAL[@]}" -eq 0 ]; then
  echo "##teamcity[buildProblem description='No connected test device - turn on Wireless debugging']"; exit 1
fi

for i in "${'$'}{!DEV_SERIAL[@]}"; do
  s="${'$'}{DEV_SERIAL[${'$'}i]}"
  dir="${'$'}outRoot/${'$'}{DEV_MODEL[${'$'}i]}_${'$'}{DEV_HW[${'$'}i]}"
  mkdir -p "${'$'}dir"
  echo ">> ${'$'}{DEV_MODEL[${'$'}i]} (${'$'}{DEV_HW[${'$'}i]})"

  # 기기 정보
  {
    for p in ro.product.model ro.build.version.release ro.build.version.sdk ro.hardware ro.board.platform ro.hardware.vulkan ro.hardware.egl; do
      echo "${'$'}p=$(adbq -s "${'$'}s" shell getprop "${'$'}p")"
    done
    # adb shell은 인자를 이어 기기 셸에서 실행 → 파이프가 든 명령은 한 인자로 넘긴다
    echo "app=$(adbq -s "${'$'}s" shell "dumpsys package ${'$'}pkg | grep -E 'versionName|lastUpdateTime'" | tr -s ' \n' ' ')"
  } > "${'$'}dir/device.txt"
  sed 's/^/   /' "${'$'}dir/device.txt"

  # logcat: 크래시 버퍼(네이티브 tombstone 요약·Java 예외) + 전체 main/system 버퍼(UE 로그 태그 UE 포함)
  for buf in crash all; do
    "${'$'}ADB" -s "${'$'}s" logcat -d -b "${'$'}buf" -v threadtime </dev/null > "${'$'}dir/logcat-${'$'}buf.txt" 2>&1
  done

  # UE 로그·크래시 (bUseExternalFilesDir=True → 앱 전용 외부 폴더)
  for sub in Logs Crashes; do
    echo "   pull ${'$'}sub: $(adbq -s "${'$'}s" pull "${'$'}ueRoot/${'$'}sub" "${'$'}dir/${'$'}sub" | tail -1)"
  done

  # 콘솔에 핵심만: 치명 오류·네이티브 크래시·UE Fatal
  echo '>> logcat crash buffer (tail 60):'
  tail -n 60 "${'$'}dir/logcat-crash.txt" | sed 's/^/   /'
  echo '>> UE Fatal/Error lines (last 60):'
  grep -E 'Fatal|Assertion failed|Unhandled Exception|SIGSEGV|SIGABRT|signal [0-9]+|LogAndroid.*Error|Error:' "${'$'}dir/logcat-all.txt" | tail -n 60 | sed 's/^/   /'
  # 참고용(판정 없음): 서버 주소·네트워크·로그인 — 접속 문제를 볼 때 먼저 보는 줄
  echo '>> network/login lines (last 30, reference):'
  grep -E 'LM\.Server\.Url|LogLMNetwork|LogLMLogin|deviceId=|LogHttp: Warning|LogSsl|LogCurl' "${'$'}dir/logcat-all.txt" | tail -n 30 | sed 's/^/   /'
done
echo ">> 로그는 빌드 아티팩트 device-logs.zip 에 있습니다"
            """.trimIndent()
        }
    }

    requirements {
        equals("devpub.role", "server")
    }
})
