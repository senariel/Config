import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.powerShell
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.VcsTrigger
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

    vcsRoot(HttpsGithubComSenarielUnrealEngineRefsHeadsRelease)

    buildType(FetchSource)
    buildType(BuildEditor)
    buildTypesOrder = arrayListOf(FetchSource, BuildEditor)
}

object BuildEditor : BuildType({
    name = "Build Editor"

    params {
        param("env.UE5_DIST_PATH", """D:\Shared\UE5""")
        checkbox("env.IsClean", "false",
                  checked = "true", unchecked = "false")
    }

    vcs {
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
                    
                    # Build flags
                    ${'$'}ExtraFlags = @()
                    if (${'$'}env:IsClean -eq 'true') { ${'$'}ExtraFlags += '-clean' }
                    
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
                    robocopy ${'$'}source ${'$'}destination /E /Z /ZB /R:5 /W:5
                    if (${'$'}LastExitCode -ge 8) { write-error "Robocopy failed" }
                """.trimIndent()
            }
        }
    }

    features {
        perfmon {
        }
    }

    dependencies {
        snapshot(FetchSource) {
            runOnSameAgent = true
        }
    }
})

object FetchSource : BuildType({
    name = "Fetch Source"

    vcs {
        root(HttpsGithubComSenarielUnrealEngineRefsHeadsRelease)

        checkoutDir = "UE5"
    }

    steps {
        script {
            name = "Setup"
            id = "Setup"
            scriptContent = """.\Engine\Binaries\DotNET\GitDependencies\win-x64\GitDependencies.exe --force"""
        }
    }

    triggers {
        vcs {
            quietPeriodMode = VcsTrigger.QuietPeriodMode.USE_DEFAULT
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

object HttpsGithubComSenarielUnrealEngineRefsHeadsRelease : GitVcsRoot({
    name = "https://github.com/senariel/UnrealEngine#refs/heads/release"
    url = "https://github.com/senariel/UnrealEngine"
    branch = "refs/heads/release"
    branchSpec = "refs/heads/*"
    authMethod = token {
        userName = "oauth2"
        tokenId = "tc_token_id:CID_3ab2f5c96314802c7074714f2b03c3a5:-1:526a4bef-e5ea-4247-849b-0a92dd5e4288"
    }
})
