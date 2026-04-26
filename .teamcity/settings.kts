import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.powerShell
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.failureConditions.BuildFailureOnText
import jetbrains.buildServer.configs.kotlin.failureConditions.failOnText
import jetbrains.buildServer.configs.kotlin.triggers.VcsTrigger
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

    buildType(FetchSource)
    buildType(BuildEditor)
    buildTypesOrder = arrayListOf(FetchSource, BuildEditor)
}

// ==========================================================================
// VCS Root: Engine source repository
// ==========================================================================
object EngineVcs : GitVcsRoot({
    name = "UnrealEngine release"
    url = "https://github.com/senariel/UnrealEngine"
    branch = "refs/heads/release"
    branchSpec = "refs/heads/*"
    // tokenId는 부트스트랩 후 UI에서 attach (CLAUDE.md 부트스트랩 절차 참조)
    authMethod = token {
        userName = "oauth2"
        tokenId = ""
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

                    # CleanMode가 FullRebuild면 UAT -clean 플래그 추가
                    ${'$'}ExtraFlags = @()
                    if ('%CleanMode%' -eq 'FullRebuild') {
                        Write-Host ">> CleanMode = FullRebuild → UAT -clean 적용"
                        ${'$'}ExtraFlags += '-clean'
                    }

                    # Step 1: Generate project files
                    & ".\GenerateProjectFiles.bat"
                    if (${'$'}LASTEXITCODE -ne 0) {
                        Write-Host "##teamcity[buildProblem description='GenerateProjectFiles failed']"
                        exit ${'$'}LASTEXITCODE
                    }

                    # Step 2: Run UAT BuildGraph
                    & ".\Engine\Build\BatchFiles\RunUAT.bat" BuildGraph -script="Engine/Build/InstalledEngineBuild.xml" -target="Make Installed Build Win64" -set:WithDDC=false -set:HostPlatformOnly=true -set:GameConfigurations=Development @ExtraFlags
                    exit ${'$'}LASTEXITCODE
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

                    # /MIR = /E + /PURGE → destination을 source와 정확히 일치
                    # (옛 빌드 잔해 자동 삭제, 모듈 로딩 크래시 예방)
                    robocopy ${'$'}source ${'$'}destination /MIR /Z /ZB /R:5 /W:5
                    if (${'$'}LastExitCode -ge 8) { write-error "Robocopy failed" }
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
