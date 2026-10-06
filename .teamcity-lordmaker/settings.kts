import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.powerShell
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

/*
DevPub / LordMaker — 게임 패키징 설정.
UnrealEngine5(엔진) 설정은 같은 저장소의 .teamcity/ 에 있고, 이 프로젝트는
Versioned Settings의 settingsPath = .teamcity-lordmaker 로 따로 연결돼 있다.
엔진 쪽 산출물(설치형 엔진 D:\Shared\UE5)을 사용한다.
*/

version = "2025.11"

project {
    description = "LordMaker 게임 패키징 (설치형 UE 5.8.3으로 BuildCookRun)"

    vcsRoot(GameVcs)

    buildType(Package)

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

object GameVcs : GitVcsRoot({
    name = "LordMaker main"
    url = "https://github.com/senariel/LordMaker"
    branch = "refs/heads/main"
    branchSpec = "refs/heads/*"
    // ClaudeBridge(에디터 전용, private) 서브모듈은 패키징에 불필요 → 체크아웃 안 함
    checkoutSubmodules = GitVcsRoot.CheckoutSubmodules.IGNORE
    // 토큰은 이 프로젝트(LordMaker)에서 DevPubApp으로 발급한 것 — 프로젝트에 묶이므로 다른 프로젝트에선 못 씀
    authMethod = token {
        userName = "oauth2"
        tokenId = "tc_token_id:CID_3ab2f5c96314802c7074714f2b03c3a5:-1:61fab572-6823-4a57-910f-827976630910"
    }
})

object Package : BuildType({
    name = "Package"
    description = "Win64/Android Development 패키지 (수동 실행). 산출물은 빌드 아티팩트."

    // Windows/ → Win64 zip, Android_ASTC/ → APK+OBB+설치 스크립트 zip, APK 단독도 별도 게시
    artifactRules = """
        Archive/Windows => LordMaker-Win64-%ClientConfig%.zip
        Archive/Android_ASTC => LordMaker-Android-%ClientConfig%.zip
        Archive/Android_ASTC/*.apk => apk
    """.trimIndent()

    params {
        param("env.UE5_ENGINE_ROOT", """D:\Shared\UE5""")
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
        checkoutMode = CheckoutMode.ON_AGENT   // Git LFS는 에이전트 측 체크아웃에서만 받음
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
                        ${'$'}bcrArgs = "BuildCookRun -project=`"${'$'}project`" -noP4 -utf8output -unattended -platform=${'$'}plat -clientconfig=%ClientConfig% -build -cook -stage -pak -iostore -package -archive -archivedirectory=`"${'$'}archive`""
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
                    }
                    if (${'$'}failed.Count -gt 0) { exit 1 }
                """.trimIndent()
            }
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
        // 설치형 엔진(D:\Shared\UE5)·Android SDK·MSVC 14.50.35717이 있는 머신
        equals("teamcity.agent.name", "Agent_Win64")
    }
})
