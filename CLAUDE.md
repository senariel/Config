# UnrealEngine5 Build Pipeline — Operational Notes

이 저장소는 **TeamCity가 빌드 파이프라인 설정을 코드로 저장**하는 곳입니다.
UE5 엔진 코드(github.com/senariel/UnrealEngine)와는 별도로 분리되어 있습니다.

> **전체 운영 위키**: Obsidian 볼트 `senariel/ObsidianVault`의 **`BuildMachine/`** 폴더(홈: `00 - 홈.md`). 인프라·TeamCity·엔진·Horde·LordMaker CI·테스트 기기·운영 절차·함정·결정 기록·미결 과제를 세션 인계용으로 정리. 설정을 바꾸면 위키의 해당 페이지도 함께 갱신할 것.

## 운영 모드: One-way Versioned Settings

**원칙: `settings.kts`가 단일 소스. UI에서 직접 수정 불가.**

설정 변경 절차:
1. 이 repo의 `.teamcity/settings.kts` 수정
2. `git push`
3. TeamCity가 ~30초 내 자동 적용 (별도 조작 불필요)

### 비상시 UI 직접 변경 절차 (드물게 사용)
1. UnrealEngine5 프로젝트 → Settings → Versioned Settings → **Synchronization disabled**
2. UI에서 변경
3. 변경 내용을 `settings.kts`에도 반영 → push
4. **Synchronization enabled** 다시 켜기

→ 절대 patch 파일이 누적되게 두지 말 것 (이전 사고 원인 의심).

## 구조

```
TeamCity 프로젝트 트리
└── DevPub                              (UI-managed 일반 폴더, 카테고리)
    └── UnrealEngine5                   (One-way VS, 이 repo와 연결)
        ├── 파라미터: CleanMode (NORMAL display)
        ├── Sync Fork                   (스케줄 트리거, GitHub merge-upstream API로 포크 동기화)
        ├── Fetch Source                (트리거 없음, Agent_Win64 고정)
        └── Build Editor                (VCS trigger 보유, snapshot dep로 FetchSource 선행, Agent_Win64 고정)
                                        (파라미터: WithAndroid 체크박스 기본 on, MaxParallelActions)
```

- 에이전트: `Agent_Win64`(엔진 빌드 전용, UE5 체크아웃·Android SDK) / `MAGI_Main`(작업 PC, 게임 프로젝트 등). 엔진 빌드 두 구성은 **이름으로 Agent_Win64에 고정**해서 MAGI_Main에 100GB+ 체크아웃이 생기지 않게 함.

- VCS 동기화 대상 repo: `https://github.com/senariel/Config` (이 repo, branch `main`)
- 엔진 소스 repo: `https://github.com/senariel/UnrealEngine` (branch `release`)
- 두 repo는 **물리적으로 분리** (엔진 커밋과 빌드 설정 변경이 섞이지 않음)

## 포크 동기화 (Sync Fork)

엔진 repo(`senariel/UnrealEngine`)는 Epic 본가(`EpicGames/UnrealEngine`)의 포크지만 **자동 갱신되지 않음** → 새 엔진 버전이 들어오지 않아 빌드 체인이 영영 안 돎.

**Sync Fork**가 이를 해결: 매일 03:00(서버 시간) **GitHub 서버사이드 fork 동기화 API**(`merge-upstream`)를 호출해 upstream `release`를 포크 `release`에 ff/merge. 갱신이 생기면 아래 트리거 흐름의 `[엔진 repo commit]`이 자동 발생.

```
[스케줄 03:00] → [Sync Fork: POST /repos/senariel/UnrealEngine/merge-upstream {branch:release}]
        │
        ▼ (merge_type=fast-forward|merge → 포크 release 갱신)
[엔진 repo commit]  ── 아래 흐름으로 연결
```

- **클론 없음**: GitHub 서버에서 동기화가 끝나므로 에이전트에 엔진 트리를 받지 않음(디스크 0, 거의 즉시). 빌드용 `UE5` 체크아웃과 완전 분리.
- **전제**: `senariel/UnrealEngine`이 `EpicGames/UnrealEngine`의 **정식 GitHub fork**(repo `parent` 관계 존재)여야 merge-upstream 동작. (독립 미러였다면 API 불가 → 로컬 clone+merge 방식으로 회귀해야 함.)
- **응답 처리**: `merge_type` = `fast-forward`/`merge`면 갱신, `none`이면 이미 최신(체인 트리거 안 됨). **409**(분기·충돌)면 빌드 실패시켜 사람이 수동 머지 — 미래에 포크 독자 커밋이 생겨 자동 ff가 안 될 때.
- **비밀값(`env.GIT_PUSH_TOKEN`)**: GitHub PAT(classic, `repo` 스코프, EpicGames org 멤버 계정). one-way 모드에서 UI 비상절차(Sync disabled → 값 입력 → enabled)로 주입하면 TeamCity가 `credentialsJSON:<uuid>` 토큰으로 서버 credentials에 저장하고 kts에 그 참조를 써넣음(되써짐). **kts의 `credentialsJSON:...` 참조는 실제 토큰이므로 절대 빈 값으로 되돌리지 말 것.**
- **PAT 권한**: 해당 GitHub 계정이 EpicGames org 멤버여야 upstream(private) 동기화 가능.

## 빌드 체인 트리거 흐름

```
[엔진 repo commit]
        │
        ▼
[Build Editor 큐잉]
        │
        ▼ (snapshot dep, runOnSameAgent=true)
[Fetch Source 실행 — 같은 에이전트, 같은 checkoutDir]
        │
        ▼
[Build Editor 실행 — Fetch Source가 받은 트리 사용]
        │
        ▼
[Distribute (robocopy /MIR로 destination 동기화)]
```

### 수동 실행 시
- **Run Build Editor**: 위와 동일하게 둘 다 실행
- **Run Fetch Source 단독**: Fetch Source만 실행 (디버깅용)

## Android 타깃 플랫폼 (WithAndroid)

Build Editor의 **`WithAndroid` 체크박스(기본 on)** 로 Win64에 더해 Android 타깃을 installed build에 포함. BuildGraph 인자에 `-set:WithAndroid=%WithAndroid%`.

- **왜 HostPlatformOnly=true를 그대로 두나**: `InstalledEngineBuild.xml`에서 `HostPlatformOnly`는 각 플랫폼의 *기본값*(`DefaultWithPlatform` 등)만 끔. `WithAndroid`를 명시하면 기본값을 덮어써서 **Android만 켜지고 Mac/Linux/iOS는 꺼진 채** 유지. Win64는 호스트라 계속 포함. 엔진 소스 수정 없음.
- **아키텍처**: `AndroidArchitectures`가 Option이 아니라 Property(`arm64+x64`)라 `-set`으로 못 바꿈 → arm64·x64(에뮬레이터) 둘 다 컴파일. 빌드 시간/메모리 증가.
- **요구 SDK (UE 5.8.3, `Engine/Config/Android/Android_SDK.json`)**: NDK r27c(`27.2.12479018`, 허용 r27c~r29), platform `android-36`, build-tools `36.0.0`, cmake `3.22.1`.
- **사전 점검**: 스텝 시작 시 `WithAndroid=true`인데 `ANDROID_HOME`/`NDKROOT`가 없거나 경로가 없으면 **즉시 실패**(buildProblem). 1h+ 컴파일 뒤에 실패하지 않게.
- **Horde/UBA 워커엔 SDK 불필요**: Android **컴파일**은 UBA 원격 실행 대상이지만, 워커는 clang·헤더·소스를 이니시에이터(Agent_Win64)의 UBA 스토리지 서버에서 받아 실행(파일 가상화)하므로 NDK 설치가 필요 없음. Android **링크**는 `AndroidToolChain`이 `bCanExecuteRemotely = false`라 항상 Agent_Win64에서 로컬 실행. 단 원격 워커는 Windows여야 함(Windows용 clang.exe 실행 — Linux Horde 서버는 못 받음). 첫 Android 빌드 때 NDK 파일이 워커로 전송돼 네트워크가 잠깐 몰림.
- **설치된 엔진을 받아 쓰는 PC**: 그 엔진으로 게임을 Android로 **패키징**하려면 그 PC에 Android Studio/SDK/JDK가 따로 필요(빌드 팜과 별개).
- **JAVA_HOME**: 엔진 빌드(라이브러리 컴파일)엔 불필요. 게임을 APK로 패키징할 때 필요 — 그땐 JDK 22 말고 Android Studio의 `jbr` 권장.

### 에이전트 준비 (Agent_Win64, 1회)
1. Android Studio 설치 후 한 번 실행 (SDK 설치)
2. 엔진의 `Engine\Extras\Android\SetupAndroid.bat` 실행 → NDK/cmake/build-tools 설치
3. **관리자 PowerShell로 env를 Machine 범위로 승격 + 에이전트 재시작** (함정 #17):
   ```powershell
   foreach ($n in 'ANDROID_HOME','NDKROOT','NDK_ROOT') {
     $v = [Environment]::GetEnvironmentVariable($n,'User')
     if ($v) { [Environment]::SetEnvironmentVariable($n,$v,'Machine'); "$n = $v" } else { "$n 없음" }
   }
   Restart-Service "TCBuildAgent*"
   ```
4. TeamCity Agent 페이지 → Agent Parameters에 `env.NDKROOT`/`env.ANDROID_HOME`이 보이면 완료.

## CleanMode 파라미터

수동 실행 시 Run Custom Build 다이얼로그에서 변경. 일반 Run은 기본값(Incremental).

| 값 | Fetch Source | Build Editor | 용도 |
|---|---|---|---|
| `Incremental` | clean 스킵 | UAT 일반 빌드 | 평소 (UBA 캐시 활용) |
| `CleanSource` | `git clean -fd Engine/{Source,Plugins,Shaders}` | UAT 일반 빌드 | 고아 파일 의심 시 |
| `FullRebuild` | `git clean -fdx Engine` | UAT `-clean` 추가 | 완전 재빌드 |

## Distribute의 핵심: `robocopy /MIR`

`/MIR` = `/E` + `/PURGE` — destination을 source와 정확히 일치시킴. 새 빌드에 없는 옛 파일은 자동 삭제.

이게 없으면 옛 빌드의 잔해(예: 9개월 전 SkeletalMeshModifiers.dll)가 distribution 경로에 누적되어 모듈 로드 크래시 유발.

**`/MIR` 주의 — 배포 폴더엔 원본에 없는 파일을 두면 안 됨.** 다음 빌드가 지움. 그래서 `ArchiveBuild` zip은 **`UE5_ARCHIVE_PATH`(기본 `D:\Shared\UE5_Archives`, 배포 폴더 밖)** 에 저장하고 `ArchiveKeepCount`(기본 3)개만 유지. 아카이브 경로가 배포 폴더 안이면 스텝이 거부함. zip이 실패하면 오래된 zip을 지우지 않음. 7-Zip이 있으면 사용(서비스 PATH에 없어도 `C:\Program Files\7-Zip\7z.exe` 확인), 없으면 `Compress-Archive` — PS 5.1에선 2GB 넘는 파일을 못 넣으므로 **에이전트에 7-Zip 설치 권장**. (초기 UI 버전은 zip을 배포 폴더 안에 만들어 다음 빌드 때 사라졌음.)

## Failure Conditions

빌드 로그에서 다음 패턴 자동 감지 → 빌드 실패 처리:
- `"could not be loaded"` — 모듈 로딩 실패
- `"BuildId mismatch"` — DLL과 .modules 매니페스트 불일치

빌드는 Success로 끝났는데 런타임에서 깨지는 패턴을 미리 잡기 위함.

## Watchdog 패턴 — 무출력 hang 방지

**TeamCity 한계**: 빌드 컨피그 레벨에 "no output for N minutes" failure condition이 없음. 전체 시간 timeout(`executionTimeoutMin`)만 가능. 그러나 빌드 머신이 미니 PC라 정상 빌드도 5h+ 걸릴 수 있어 전체 timeout은 부적합.

**해결**: 두 PowerShell 스텝(BuildGraph, Distribute)을 모두 watchdog로 감싸서 무출력 hang을 강제 종료.

### Step 1 (BuildGraph) — Watchdog (60분 무활동 시 kill)

**'무출력'이 아니라 '무활동(파일 변경 없음)'으로 판정한다.** UBA 원격 분산 빌드는 stdout이 수십 분 조용해도 정상이기 때문(빌드 #31 오탐, 함정 #13). 활동 신호 (아래 중 하나라도 갱신되면 alive):
- **파일 mtime**: stdout 임시 redirect 파일 / `Engine/Programs/AutomationTool/Saved/Logs/*`(UAT·BuildGraph·`UBA-*.txt`) / `Engine/Programs/UnrealBuildTool/{Log.txt, Trace.uba}`
- **프로세스 트리 누적 I/O 바이트**: RunUAT 자손 트리의 `ReadTransferCount+WriteTransferCount`(Win32_Process) 합. 파일 mtime이 못 잡는 **Make Installed Build의 LocalBuilds 대용량 복사**(24만 파일 등) 단계를 커버 (함정 #15).

```powershell
$proc = Start-Process -FilePath ".\Engine\Build\BatchFiles\RunUAT.bat" -ArgumentList $args -RedirectStandardOutput $tmpLog -PassThru -NoNewWindow
while (!$proc.HasExited) {
    Start-Sleep -Seconds 30
    # stdout 새 내용 콘솔로 흘림 (실시간 로그)
    # 활동신호(위 파일/디렉터리들의 newest mtime)가 갱신되면 lastActivity 리셋
    # 60분 무변화 시 taskkill /T /F (트리 전체) + UBA/UBT 잔류 정리 + exit 124
}
```

이유: 정상 빌드도 (a) UBT 의존성 분석(8000+ 액션, ~27분 무출력), (b) UBA가 느린 단일 워커로 원격 컴파일 시 완료 배치까지 무출력 — 둘 다 stdout만 보면 오탐. 파일 활동을 보면 살아있음을 안다. 그래도 안 변하면(예: Horde 연결 대기) 진짜 hang.

**중요 — 반드시 트리 전체를 죽일 것.** `$proc.Kill()`은 RunUAT **부모만** 죽이고 손자(UnrealBuildTool=dotnet, UbaServer/UbaAgent)는 남긴다. 좀비 UBT가 `Global\UnrealBuildTool_Mutex_...`를 계속 쥐어 **다음 빌드가 GenerateProjectFiles에서 `ConflictingInstance`로 실패**한다(함정 #11). 그래서 `taskkill /T /F /PID $proc.Id`로 트리 전체를 죽이고, 분리됐을 수 있는 UBA/UBT를 `Get-CimInstance Win32_Process`로 한 번 더 정리한다. (`.Kill($true)` 트리 종료는 .NET Framework/PowerShell 5.1엔 없음.)

### Step 2 (Distribute) — Heartbeat (60초마다 강제 출력)

robocopy의 `\r`-only 진행률 출력 때문에 큰 파일 복사 중 newline 없음 → TeamCity 무출력 오인.

```powershell
$proc = Start-Process -FilePath 'robocopy.exe' -ArgumentList @(... '/MIR', '/Z', '/NP', '/NDL') ...
while (!$proc.HasExited) {
    Start-Sleep -Seconds 60
    # heartbeat 라인 출력 (no-output timeout 회피)
}
```

### 핵심 원리

- `Start-Process -RedirectStandardOutput`로 stdout을 파일로 받음
- 폴링 루프에서 (a) 파일 새 내용을 stdout으로 흘려 실시간 로그 유지 + (b) 활동 감지/heartbeat 발화
- Step 1은 hang 감지 목적 (kill), Step 2는 false-positive 방지 목적 (heartbeat만)
- 둘 다 `\r`-only 출력 문제 회피 (PowerShell이 stdout 받을 때 newline 단위로 처리되므로)

### 알려진 경험치

- robocopy 200MB 파일 단일: CR 231개 vs LF 31개 — 진행률은 거의 다 `\r`
- (옛 가정) "BuildGraph는 분당 수백 라인 → 30분이면 안전"은 **UBA 원격 분산에서 깨짐**. 원격 컴파일·의존성 분석은 수십 분 무출력 가능 → 파일활동 기반 + 60분으로 변경 (함정 #13)
- watchdog 종료 시 exit code 124 (GNU timeout 관례)

### Kotlin DSL ↔ PowerShell 함정: 백틱 line continuation
Kotlin `"""..."""` raw string에서 PowerShell 백틱 line continuation을 쓸 때 **백틱 1개**만 써야 함.

```kotlin
// 틀림: 백틱 2개 → PowerShell이 escape된 literal backtick으로 해석
${'$'}proc = Start-Process -FilePath '...' ``
    -ArgumentList ${'$'}args ``
    -PassThru

// 맞음: 한 줄로 합치거나 백틱 1개
${'$'}proc = Start-Process -FilePath '...' -ArgumentList ${'$'}args -PassThru
```

증상: `'-ArgumentList' 용어가 cmdlet, 함수, 스크립트 파일 또는 실행할 수 있는 프로그램 이름으로 인식되지 않습니다`. 빌드가 GenerateProjectFiles 직후 ~2분 만에 실패. (Build #23이 이걸로 fail함, 단일 라인으로 수정.)

## Horde / UBA 설정 (중요 — 헤매기 쉬움)

설정 파일 위치: **`%PROGRAMDATA%\Unreal Engine\UnrealBuildTool\BuildConfiguration.xml`**

- `%PROGRAMDATA%`로 둬야 머신 공통. `%APPDATA%`/`%USERPROFILE%`은 **계정별 폴더**라 TeamCity 에이전트 서비스 계정과 로그인 사용자 사이에서 안 맞음
- 이 파일은 git 관리 밖
- XML 루트는 반드시 `<Configuration xmlns="https://www.unrealengine.com/BuildConfiguration">`
- Horde 서버 URL은 `<Horde><Server>http://...</Server></Horde>`

샘플:
```xml
<?xml version="1.0" encoding="utf-8" ?>
<Configuration xmlns="https://www.unrealengine.com/BuildConfiguration">
    <BuildConfiguration>
        <bAllowUBAExecutor>true</bAllowUBAExecutor>
        <bAllowUBALocalExecutor>true</bAllowUBALocalExecutor>
    </BuildConfiguration>
    <Horde>
        <Server>http://localhost:13340</Server>
    </Horde>
</Configuration>
```

## 부트스트랩 절차 (이 repo로부터 TeamCity에 새로 구축할 때)

1. **DevPub 프로젝트 생성** (TeamCity UI, `<Root project>` 아래)
2. **UnrealEngine5 sub-project 생성** (DevPub 아래)
3. **Config VCS Root 생성** (UnrealEngine5에 부착)
   - URL: `https://github.com/senariel/Config`
   - Auth: Refreshable access token, DevPubApp 연결로 Generate new
4. **Versioned Settings 활성화** (UnrealEngine5)
   - Synchronization enabled
   - VCS root: 위에서 만든 Config root
   - Format: Kotlin
   - **Allow editing project settings via UI: 체크 해제 (One-way 모드)**
   - Apply → Import settings from VCS
5. **Engine VCS Root 토큰 부착** (kts에 `tokenId = ""`로 비어있음)
   - 일시적으로 Versioned Settings → Synchronization disabled
   - UnrealEngine5 → VCS Roots → "UnrealEngine release" 편집
   - Authentication method: Refreshable access token + Generate new (DevPubApp)
   - 새 tokenId 복사해서 settings.kts의 EngineVcs `tokenId = "..."` 채우기
   - kts push
   - Synchronization enabled 다시 켜기
6. **검증 빌드 1회** — Build Editor 수동 트리거 → 둘 다 정상 동작 확인

## 알려진 함정

### 1. 모듈 로딩 실패 — Distribute 누적이 원인일 때
새 빌드는 정상이고 distributed 결과물에 옛 파일만 남아있는 경우. **`/MIR` 플래그로 해결됨.** 만약 또 발생하면 destination 폴더의 타임스탬프가 source와 다른 파일이 있는지 확인.

### 2. 모듈 로딩 실패 — 소스 고아 파일이 원인일 때
플러그인이 다른 위치로 옮겨졌는데 구 위치 `Source/.../*.Build.cs`가 git pull로 안 지워지는 경우. CleanMode = `CleanSource`로 한 번 돌리면 untracked 파일 정리됨. tracked 파일이면 엔진 repo에서 직접 `git rm` 필요.

### 3. `.Build.cs` vs `.build.cs` 대소문자
Windows NTFS는 대소문자 무관해서 통과되지만, Linux UBA 워커에서는 글롭에 안 걸림. 반드시 대문자 B.

### 4. `%APPDATA%`는 계정별 폴더
빌드 에이전트가 서비스 계정으로 돌면 로그인 사용자의 `%APPDATA%`와 다른 폴더를 봄. Horde/UBA 설정은 `%PROGRAMDATA%`에.

### 5. Versioned Settings 메뉴는 프로젝트 레벨에만
빌드 컨피그 페이지에 없음. URL: `/admin/editProject.html?projectId=<id>&tab=versionedSettings`

### 6. TeamCity Kotlin DSL `select()` 의 Pair 순서
**`Pair<displayLabel, value>`** — 라벨이 first, 실제 값이 second.

```kotlin
options = listOf(
    "빠른 빌드 (클린 없음)" to "Incremental",   // ← UI 표시 to 실제 값
    "소스 정리"            to "CleanSource",
    "전체 재빌드"          to "FullRebuild"
)
```

### 7. Snapshot Dependency는 한 방향만
`BuildEditor.dependencies.snapshot(FetchSource)` 는 "Build Editor가 시작될 때 Fetch Source가 끝나있어야 한다". Fetch Source 트리거 → Build Editor 자동 호출이 **아님**.

해결: 트리거를 Build Editor에 두면, snapshot dep로 Fetch Source가 자동 선행 실행됨. (이 repo의 현재 설계)

### 8. Two-way 모드의 patch 파일 누적
UI에서 변경 시 `.teamcity/patches/...` 파일이 자동 생성됨. 누적되면 충돌과 혼란 야기 (의심: 프로젝트 사라진 사고의 원인).

→ **One-way 모드 채택**으로 근본 해결. 변경은 kts 직접 수정으로만.

**재발 사례 (2026-10)**: UI에서 바꾼 설정이 `patches/`로 쌓여 있는 상태에서 settings.kts의 Build Editor 스텝을 고치자, patch의 `expectSteps`(옛 스텝 내용 기대)가 안 맞아 **DSL 적용 실패**(`UI changes error`, `patches/buildTypes/BuildEditor.kts`). one-way라 기존 설정은 유지됨. 해결: patch의 `update`/`add` 내용을 settings.kts에 그대로 반영 → `patches/` 삭제 → push. **UI 편집을 계속 쓰면 다시 쌓이므로**, 바꾼 뒤엔 바로 kts로 옮길 것.

### 9. Horde 모듈 빌드 산출물이 git untracked로 남음
`Engine/Source/Programs/Horde/.../bin/...` 등이 `.gitignore`에 안 잡혀 누적. CleanSource가 정리하긴 하지만 엔진 repo `.gitignore` 추가가 이상적.

### 10. Branch filter `+:<default>` 의 모호성
`finishBuildTrigger`에서 `<default>`는 watched build의 기본 브랜치를 가리키는데 컨텍스트에 따라 해석이 안 될 수 있음. 명시적인 브랜치명이나 `+:*` 권장. (단, 현재 설계는 finishBuildTrigger를 안 쓰므로 무관)

### 11. GenerateProjectFiles `ConflictingInstance` — watchdog 좀비 잔류
증상: GenerateProjectFiles가 ~0.4초 만에 실패, 로그에 `A conflicting instance of Global\UnrealBuildTool_Mutex_... is already running. Result: Failed (ConflictingInstance)`.

원인: 직전 빌드가 watchdog(exit 124) 또는 외부에서 강제종료될 때 RunUAT **부모만** 죽고 손자 UBT(dotnet)/UbaServer가 살아남아 뮤텍스를 점유. (예: Horde 서버 IP 오설정으로 UBA가 `GET http://<ip>:13340/api/v1/server/auth` 무한 재시도 → 30분 무출력 → watchdog kill → 좀비 잔류.)

즉시 해결(에이전트 머신에서):
```powershell
Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match 'UbaAgent|UbaServer' -or $_.CommandLine -match 'UnrealBuildTool|AutomationTool|RunUAT'
} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```
또는 에이전트 재부팅. 근본 해결은 watchdog의 트리 종료(`taskkill /T /F`) — Watchdog 섹션 참조.

### 12. Horde 서버 주소 오설정 → UBA 무한 재시도 hang
UBA executor가 Horde에 `GET http://<server>:13340/api/v1/server/auth`로 인증 시도. 주소가 틀리면(예: 에이전트 IP가 DHCP로 바뀜) `failed ((null))` 무한 재시도 → 무출력 → watchdog가 exit 124로 kill. 주소는 **에이전트의** `%PROGRAMDATA%\Unreal Engine\UnrealBuildTool\BuildConfiguration.xml` 의 `<Horde><Server>`. (git 관리 밖, 머신 로컬.) 같은 머신이면 `http://localhost:13340` 권장.

### 13. watchdog 오탐 — UBA 원격 분산 시 장시간 무출력(정상인데 kill)
증상: 빌드가 컴파일 도중(예: `[2218/8353]`) exit 124로 죽는데 **컴파일 에러는 없음**. 로그에 `[RemoteExecutor: <worker>]` 보임.

원인: Horde/UBA가 원격 워커로 컴파일을 분산하면 로컬 stdout이 수십 분 조용함(완료 배치 단위로만 `[N/8353]` 출력). UBT 의존성 분석 단계(~27분)도 무출력. 옛 watchdog는 stdout/Log.txt만 봐서 정상 빌드를 hang으로 오판.

해결: watchdog를 **파일 활동(`Saved/Logs`의 `UBA-*.txt` 등 newest mtime)** 기반으로 변경 + 임계값 30→60분 (Watchdog 섹션). **쿠킹/패키징도 같은 위험** → 각 단계 추가 시 그 산출물 디렉터리(`Saved/Cooked`, `Saved/StagedBuilds` 등)를 활동 신호 `$activityDirs`에 포함할 것.

성능 참고: 단일 워커(예: 노트북 1대)가 8000+ 모듈을 받으면 느려 무활동 구간이 길어짐 — UBA 워커 증설/로컬 코어 병행으로 완화(아래 에이전트 설정 참조).

### 14. 메모리 부족(OOM) → UBA 크래시 (access violation)
증상: 컴파일 도중 `RunUAT failed with exit code`(0xC0000005). 크래시 직전 로그에 `UbaSessionServer - Killed process ... (Link) - Low on memory (29.6gb/31.1gb)` 가 줄줄이, 콜스택 `uba::ProcessImpl::ThreadExit`.

원인: **Link 단계가 메모리 폭식** → 물리 RAM 한계 도달 → UBA가 Link 프로세스를 연쇄 kill/retry → 그 난장 속 UBA 자체가 access violation. 컴파일은 원격(UBA) 분산되지만 **Link는 로컬에서 실행**돼 에이전트 RAM이 병목. DebugGame까지 빌드하면 에디터 Link가 2배라 더 심함. (31GB는 풀 installed build엔 marginal; Epic 권장 64GB+.)

완화: **`MaxParallelActions` 파라미터** (Build Editor) — 단, **UBA executor는 이 클래식 설정을 무시함**(로그: "Using Unreal Build Accelerator executor"). 검증 결과 OOM에 효과 없었음. → 실효 대책은 아래.
- **진짜 트리거(빌드 #33 분석)**: 최근 추가한 **워커 머신(MAGI)이 공유 작업 PC**라 UnrealEditor(3.7GB)+Chrome+Claude가 ~20GB를 점유, 빌드 헤드룸 ~10GB뿐. UBA 안전망(메모리 kill)은 *전용 머신* 가정이라 공유 머신에선 쓰래싱→크래시. Horde 에이전트 `agent.json`이 비어 있어(자원 예약 없음) **머신 전체를 빌드에 제공**한다고 보고하는 게 핵심 갭.
- 대책: 워커가 코어/메모리를 **적게 보고하도록 Horde 에이전트 자원 예약** 설정 / 전용(여유 RAM) 머신 사용 / 빌드 중 포그라운드 앱 종료. RAM 증설(64GB+) 또는 DebugGame 제거도 유효. 페이지파일은 UBA가 *물리 RAM* 기준 kill이라 효과 없음.

### 15. watchdog 오탐 — Make Installed Build의 대용량 복사 단계 (빌드 #34)
증상: 컴파일은 끝났는데 `Copying NNNNNN files ... to LocalBuilds\Engine\Windows` 직후 60분 무활동으로 watchdog kill(exit 124).

원인: "Make Installed Build" 최종 단계가 **24만+ 파일을 `LocalBuilds`로 복사** — 미니 PC 디스크로 1시간+. 이 동안 stdout·`Saved/Logs`·UBT 로그가 안 바뀜(복사는 `LocalBuilds`에만 씀) → 파일 mtime 기반 watchdog가 무활동으로 오판.

해결: watchdog에 **프로세스 트리 누적 I/O 바이트** 신호 추가(Watchdog 섹션). 복사는 디스크 I/O 폭증이라 확실히 잡힘. (쿠킹/패키징의 대용량 I/O 단계도 같이 커버됨.)

### 16. 빌드 성공인데 exit code로 실패 처리 (빌드 #35)
증상: 로그에 `BUILD SUCCESSFUL` + `AutomationTool exiting with ExitCode=0 (Success)` + `Process exited with code 0` 인데, watchdog wrapper가 `RunUAT failed with exit code `(코드값 **비어있음**)로 실패 처리.

원인: PowerShell `Start-Process -PassThru`로 받은 프로세스는 **핸들을 미리 캐싱하지 않으면 종료 후 `.ExitCode`가 `$null`**이 됨(.NET/PS 함정). `if ($uatProc.ExitCode -ne 0)` 가 `$null -ne 0` → **참** → 성공인데 실패 분기.

해결: Start-Process **직후 `$null = $uatProc.Handle`로 핸들 캐싱** → 종료 후 `.ExitCode`가 0으로 정상 반환. 추가로 `WaitForExit()` 후 읽고, null이면 0으로 간주하는 안전망. **Step 2(Distribute robocopy)도 같은 패턴이라 동일 수정** — 이쪽은 false 실패가 아니라, null ExitCode면 `>=8` 검사가 거짓이 되어 **robocopy 실제 실패를 못 잡는** 잠재 버그였음.

### 17. SetupAndroid.bat은 env를 User 범위로만 설정 → 에이전트가 못 봄
증상: Android SDK를 설치하고 `SetupAndroid.bat`까지 돌렸는데 빌드가 사전 점검에서 `missing ANDROID_HOME, NDKROOT`로 실패.

원인: `SetupAndroid.bat`은 `ANDROID_HOME`/`NDKROOT`/`NDK_ROOT`/`JAVA_HOME`을 `SetEnvironmentVariable(..., 'User')`로 **로그인 사용자 범위**에만 씀. TeamCity 에이전트는 **LocalSystem 서비스**라 그 값을 못 봄 (함정 #4와 같은 계열).

해결: 위 "에이전트 준비" 3번처럼 **Machine 범위로 승격 후 에이전트 재시작**. 에이전트는 env를 시작 시에만 읽으므로 재시작 필수. SDK 경로가 사용자 폴더(`C:\Users\<user>\AppData\Local\Android\Sdk`)여도 LocalSystem은 읽을 수 있어 그대로 사용 가능.

## LordMaker CI (`DevPub / LordMaker`)

구조 (설계: `docs/superpowers/specs/2026-10-06-lordmaker-ci-structure-design.md`):

| 하위 프로젝트 | 구성 | 트리거 | 하는 일 |
|---|---|---|---|
| Client | Editor Build | Core Gates 체인 | `Build.bat LordMakerEditor Win64 Development` (컴파일만) |
| Client | Android Compile | 모든 브랜치 푸시 | `Build.bat LordMaker Android Development` (쿡 없음) |
| Client | Core Gates | 모든 브랜치 푸시 (Editor Build 스냅샷 의존, 같은 체크아웃 폴더 `LordMakerCI`) | `Tools/core_gates.sh`. exit≠0 실패, 마지막 성공 main `gates.zip` 대비 DIFF는 경고 |
| Client | Package | main 푸시 + 수동 | BuildCookRun → **최신본 1개만** `D:\Shared\LordMaker\<Win64|Android>`에 robocopy /MIR 교체(TeamCity 아티팩트로 게시 안 함 — 서버 디스크 여유 부족). `ArchiveBuild` 체크 시에만 `D:\Shared\LordMaker_Archives`에 zip(자동 삭제 없음). 작업 폴더의 Archive·StagedBuilds 사본은 배포 후 삭제 |
| Server | LMCore | LordMaker `Core/**`·`Source/LMCore/**` 변경 | vcvars64 → CMake/Ninja → ctest(`PYTHONUTF8=1`), 아티팩트 `lmcore*.pyd` |
| Server | Server Tests | LordMakerServer 모든 브랜치 | LMCore main `.pyd` + `LORDMAKER_FIXTURES` → pytest(JUnit) |
| Server | Static Data Drift | 매일 03:30 | 서버 `tools/check_static_drift.py` (0 일치 / 1 드리프트 / 2 체크아웃 문제) |
| Device | Register Device | 수동 (기기당 1회) | 휴대폰 무선 디버깅 페어링 IP:포트·6자리 코드로 `adb pair` (키는 에이전트 adb에 영구 저장) |
| Device | Deploy to Device | 수동 | `D:\Shared\LordMaker\Android`(또는 `ApkDir`)의 APK 설치 → `UECommandLine.txt`(스테이징 기본 명령줄 + `-LMDeviceId=lmtest-<모델>-<시리얼해시8>`) push·재검증 → (옵션) 실행 후 logcat 로그인 ID 확인 |

- **Device 테스트 DeviceId**: Android는 외부 `UECommandLine.txt`가 명령줄 전체를 교체한다. 파일이 없으면 `FPlatformMisc::GetLoginId()` = 설치마다 무작위 GUID라 **새 게스트 계정**이 생길 뿐 실계정과 겹치지 않는다(2026-10-06 정정 — 처음엔 실계정 로그인 위험으로 잘못 판단). `-LMDeviceId`의 목적은 ① 재설치·데이터 삭제 후에도 같은 테스트 계정 유지(없으면 고아 계정 누적) ② `lmtest-` 접두로 테스트 계정 식별·정리. Deploy는 push·검증 실패 기기에서 앱을 실행하지 않고, logcat에 `lmtest-` 아닌 deviceId가 보이면 주입 실패로 보고 강제 종료·실패 처리. 게임 쪽 전제: `bPackageDataInsideApk=True`, `bUseExternalFilesDir=True`, `-LMDeviceId` 지원(비Shipping).

- 공통 파라미터(프로젝트 레벨): `env.UE5_ENGINE_ROOT`, `env.LM_PYTHON`(`C:\Program Files\Python314\python.exe` — **모든 사용자 설치** 필요, LocalSystem 에이전트), `env.VCVARS64`.
- 서버 배포·재시작은 넣지 않음(서버 세션이 버전 동기·.pyd 교체·DB와 묶어 수동 관리). Device(테스트 기기)·Shipping은 미구현.
- 함정: Package `-archivedirectory`는 **플랫폼별 경로를 직접** 준다. UE(`DeploymentContext.cs`)는 경로에 플랫폼 이름이 들어 있으면 `Windows`/`Android_ASTC` 하위 폴더를 붙이지 않는데, 에이전트 work 경로 `D:\teamcity\agent\Win64\...`의 `Win64` 때문에 Win64 결과가 `Archive\` 바로 아래로 가서 아티팩트가 비었었다(#29).

### Package / 공통 세부

- **설정 위치가 엔진과 다름**: `.teamcity-lordmaker/settings.kts`. TeamCity 프로젝트 `LordMaker`(DevPub 하위)의 Versioned Settings가 같은 Config 저장소를 `settingsPath=.teamcity-lordmaker`로 따로 읽는다(VCS 루트 `DevPub_Config`). 엔진(`.teamcity/`)과 독립적으로 반영·실패함.
- VCS 토큰: TeamCity의 DevPubApp(GitHub App) refreshable token은 **발급한 프로젝트(와 하위)에서만, 발급할 때의 저장소에만** 유효하다. 다른 저장소에 재사용하면 `Repository not found`, 다른 프로젝트면 `token is associated with other projects`.
  - GameVcs(LordMaker) `…61fab572…` — LordMaker 프로젝트에서 발급
  - ServerVcs(LordMakerServer) `…fbbb8e0e…` — 상위 **DevPub** 프로젝트의 VCS 루트 `DevPub_LordMakerServer`로 발급(LordMaker는 UI 편집 꺼짐). **이 VCS 루트는 지우지 말 것**(토큰 보관처).
  - 새 저장소를 추가할 때: DevPub(UI 편집 가능)에 그 저장소 URL로 VCS 루트 생성 → Refreshable token → DevPubApp → 생성된 `tokenId`를 REST(`/app/rest/vcs-roots/id:<id>?fields=properties(...)`)로 읽어 DSL에 넣는다.
- 게임 저장소 `senariel/LordMaker`(main, Git LFS). 설치형 엔진 `D:\Shared\UE5`(Build Editor 산출물)로 `RunUAT BuildCookRun`. Agent_Win64 고정(LordMaker 전 구성 동일).
- 파라미터: `Platforms`(Win64+Android / Win64 / Android), `ClientConfig`(Development만 — 설치형 엔진이 `GameConfigurations=Development`로 빌드됨. Shipping은 엔진 재빌드 필요), `LMServerUrl`.
- 서버 주소: 게임 코드 수정 없이 작업 사본의 `Config/<Platform>/<Platform>Engine.ini`에 `[ConsoleVariables] LM.Server.Url=...`를 주입(커밋 안 함). `LM.Server.Url`은 ECVF_Default cvar라 ini로 덮어써짐.
- Android는 `-cookflavor=ASTC` 고정, 단일 APK(`bPackageDataInsideApk=True`). 산출물 위치는 위 표(배포 폴더 1개 + 선택적 zip).
- 서브모듈 `Plugins/ClaudeBridge`(에디터 전용, private)는 체크아웃 안 함. 체크아웃 폴더는 `LordMakerPkg`.
- 옛 UI 구성(루트 직속 `LordMaker` 프로젝트의 Build Android·Register Mobile Device·Deploy to Mobile·Build Engine)은 2026-10-06 이 DSL로 대체되며 삭제됨. 프로젝트 ID `LordMaker`는 그대로 재사용.
- 게임 코드의 Android 전용 컴파일 오류(clang `-Werror`: 주석 안 `/*`, `int64`(long long) vs `int64_t`(long))는 MSVC에선 안 보임 → Android 빌드로만 잡힘.

## 파일 구조

```
.teamcity/
└── settings.kts        ← DevPub / UnrealEngine5 (엔진) 설정
.teamcity-lordmaker/
└── settings.kts        ← DevPub / LordMaker (게임 패키징) 설정 — settingsPath로 별도 연결
README.md
CLAUDE.md               ← 이 파일
```

> **Note**: `.teamcity/patches/` 폴더가 생기면 무언가 잘못된 것. 즉시 settings.kts에 병합하고 patch 폴더 삭제할 것.

## 변경 이력 (요약)

- 2026-04: 초기 Versioned Settings 도입. CleanMode/finishBuildTrigger 추가, robocopy /MIR 발견.
- 2026-04 (2차): One-way 모드로 전환, VCS trigger를 Build Editor에 통합, Failure Conditions 추가, 부트스트랩 절차 정립.
- 2026-06: **Sync Fork** 추가 — 포크가 upstream에서 자동 갱신되지 않던 문제 해결. 초안은 로컬 clone+merge였으나, 정식 fork임을 확인 후 GitHub `merge-upstream` API(클론 0)로 교체.
- 2026-06 (2차): watchdog kill을 `taskkill /T /F`(트리 전체)+UBA/UBT 잔류 정리로 수정 — `.Kill()`이 부모만 죽여 좀비가 뮤텍스 점유 → `ConflictingInstance` 유발하던 문제(함정 #11, #12).
- 2026-06 (3차): watchdog를 '무출력'→'무활동(파일 mtime)' 기반으로 재설계 + 임계값 60분 + buildProblem 메시지 ASCII화. UBA 원격 분산 빌드가 정상인데 죽던 오탐 해결(함정 #13).
- 2026-06 (4차): `MaxParallelActions` 파라미터 추가 — Build Editor 스텝이 에이전트 BuildConfiguration.xml에 XML 머지로 주입(Horde 보존, 엔진 소스 무관). Link 메모리 OOM→UBA 크래시 완화(함정 #14). *(주: UBA executor는 이 설정 무시 — 효과 없음 확인. 진짜 원인은 공유 워커 머신 메모리.)*
- 2026-06 (5차): watchdog에 **프로세스 트리 I/O 신호** 추가 — Make Installed Build의 LocalBuilds 대용량 복사 단계가 무활동으로 오판되던 문제(함정 #15).
- 2026-06 (6차): Start-Process 핸들 캐싱(`$null = $proc.Handle`) — `.ExitCode`가 null로 잡혀 **빌드 성공(ExitCode=0)인데 실패 처리**되던 문제 수정(함정 #16). **빌드 #35에서 엔진 빌드 자체는 첫 완주 성공(1h50m).**
- 2026-07~09 (UI에서 변경 → `.teamcity/patches/`로 저장돼 있던 것, 2026-10에 settings.kts로 병합 후 patches 삭제): Build Editor에 `ArchiveBuild`(zip 보관) 체크박스, BuildConfiguration.xml 머지에 `MaxLinkActions=1`(링크 동시 1개로 OOM 완화)·Horde `MaxIdle=60`·deprecated `bAllowUBALocalExecutor`/잘못된 `UBAAccelerator` 노드 제거, **빌드 성공 후 `Engine\Intermediate\Build`·UAT 로그 삭제**(디스크 절약 — 대신 다음 빌드의 증분 캐시가 사라짐), Fetch Source의 GitDependencies `--force` 제거, perfmon 활성화, 프로젝트 정리 규칙(10일 보관).
- 2026-10: **Android 타깃 추가** — `WithAndroid` 체크박스(기본 on) + `-set:WithAndroid` + SDK 사전 점검(fail-fast). Build Editor/Fetch Source를 `Agent_Win64`에 이름 고정(새 에이전트 MAGI_Main 배정 방지). 함정 #17(SetupAndroid.bat의 User 범위 env).
- 2026-10 (2차): `ArchiveBuild` zip을 배포 폴더 밖 `UE5_ARCHIVE_PATH`로 이동 + `ArchiveKeepCount` 보관 개수 제한 + 실패 시 기존 zip 보존. (배포 폴더 안에 만들면 다음 빌드의 robocopy /MIR이 지우던 문제.)
- 2026-10 (3차): **LordMaker 패키징 구성** 추가(Win64+Android Development, 서버 주소 ini 주입, 아티팩트 게시). 루트에 UI로 만들어져 있던 `LordMaker / Build Android`(모든 브랜치 VCS 트리거)는 일시 정지 — 서버 재부팅 때 밀린 브랜치 빌드가 Agent_Win64를 몇 시간씩 점유하던 문제.
- 2026-10 (5차): LordMaker를 Client(Editor Build·Android Compile·Core Gates·Package)/Server(LMCore·Server Tests·Static Data Drift) 하위 프로젝트로 분리. Package 아티팩트 미게시(#29) 수정.
- 2026-10 (4차): LordMaker를 `DevPub / LordMaker`로 재배치 — 엔진 DSL 하위 프로젝트가 아니라 **별도 settingsPath(`.teamcity-lordmaker/`)** 로 분리. 엔진 DSL 루트를 DevPub으로 올리는 안은 Sync Fork 보안 토큰(UnrealEngine5 프로젝트에 저장) 유실 위험 때문에 보류. 옛 UI 구성 4개 삭제.

## 다음에 할 만한 것 (TODO 후보)

- [ ] `Engine/Source/Programs/Horde/.../bin/` 을 엔진 repo `.gitignore`에 추가 PR
- [ ] Build Editor 앞단에 Horde 헬스체크 (`curl http://localhost:PORT/api/v1/server/info`)
- [ ] Build Editor 아티팩트로 `.modules` + DLL 해시 publish (모듈 로딩 디버깅용)
- [ ] 주간 정기 트리거 — `CleanMode=FullRebuild` 강제로 누적 쓰레기 정리
- [ ] LordMaker Device 실기기 검증 — 게임 저장소의 단일 APK·외부 명령줄 경로·-LMDeviceId 변경 머지 후
- [ ] 에이전트 추가 — 브랜치 푸시마다 3개 빌드가 Agent_Win64 하나에 몰림(Package 수 시간 중엔 대기)
