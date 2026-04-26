# UnrealEngine5 Build Pipeline — Operational Notes

이 저장소는 **TeamCity가 빌드 파이프라인 설정을 코드로 저장**하는 곳입니다.
UE5 엔진 코드(github.com/senariel/UnrealEngine)와는 별도로 분리되어 있습니다.

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
        ├── Fetch Source                (트리거 없음)
        └── Build Editor                (VCS trigger 보유, snapshot dep로 FetchSource 선행)
```

- VCS 동기화 대상 repo: `https://github.com/senariel/Config` (이 repo, branch `main`)
- 엔진 소스 repo: `https://github.com/senariel/UnrealEngine` (branch `release`)
- 두 repo는 **물리적으로 분리** (엔진 커밋과 빌드 설정 변경이 섞이지 않음)

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

## Failure Conditions

빌드 로그에서 다음 패턴 자동 감지 → 빌드 실패 처리:
- `"could not be loaded"` — 모듈 로딩 실패
- `"BuildId mismatch"` — DLL과 .modules 매니페스트 불일치

빌드는 Success로 끝났는데 런타임에서 깨지는 패턴을 미리 잡기 위함.

## Watchdog 패턴 — 무출력 hang 방지

**TeamCity 한계**: 빌드 컨피그 레벨에 "no output for N minutes" failure condition이 없음. 전체 시간 timeout(`executionTimeoutMin`)만 가능. 그러나 빌드 머신이 미니 PC라 정상 빌드도 5h+ 걸릴 수 있어 전체 timeout은 부적합.

**해결**: 두 PowerShell 스텝(BuildGraph, Distribute)을 모두 watchdog로 감싸서 무출력 hang을 강제 종료.

### Step 1 (BuildGraph) — Watchdog (30분 무출력 시 kill)

```powershell
$proc = Start-Process -FilePath ".\Engine\Build\BatchFiles\RunUAT.bat" `
    -ArgumentList @('BuildGraph', ...) `
    -RedirectStandardOutput $tmpLog `
    -PassThru -NoNewWindow

while (!$proc.HasExited) {
    Start-Sleep -Seconds 30
    # 새 로그 라인 stdout으로 흘림
    # 로그 사이즈 변화 감지 → lastChangeTime 갱신
    # 30분 무변화 시 $proc.Kill() + exit 124
}
```

이유: BuildGraph는 정상 동작 시 매 초 다수의 컴파일/링크 라인 emit. 30분 무출력은 거의 확실히 hang.

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
- BuildGraph는 정상 시 분당 수백 라인 출력 — 30분 timeout은 매우 안전한 임계값
- watchdog 종료 시 exit code 124 (GNU timeout 관례)

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

### 9. Horde 모듈 빌드 산출물이 git untracked로 남음
`Engine/Source/Programs/Horde/.../bin/...` 등이 `.gitignore`에 안 잡혀 누적. CleanSource가 정리하긴 하지만 엔진 repo `.gitignore` 추가가 이상적.

### 10. Branch filter `+:<default>` 의 모호성
`finishBuildTrigger`에서 `<default>`는 watched build의 기본 브랜치를 가리키는데 컨텍스트에 따라 해석이 안 될 수 있음. 명시적인 브랜치명이나 `+:*` 권장. (단, 현재 설계는 finishBuildTrigger를 안 쓰므로 무관)

## 파일 구조

```
.teamcity/
└── settings.kts        ← 모든 TeamCity 설정 (단일 소스)
README.md
CLAUDE.md               ← 이 파일
```

> **Note**: `.teamcity/patches/` 폴더가 생기면 무언가 잘못된 것. 즉시 settings.kts에 병합하고 patch 폴더 삭제할 것.

## 변경 이력 (요약)

- 2026-04: 초기 Versioned Settings 도입. CleanMode/finishBuildTrigger 추가, robocopy /MIR 발견.
- 2026-04 (2차): One-way 모드로 전환, VCS trigger를 Build Editor에 통합, Failure Conditions 추가, 부트스트랩 절차 정립.

## 다음에 할 만한 것 (TODO 후보)

- [ ] `Engine/Source/Programs/Horde/.../bin/` 을 엔진 repo `.gitignore`에 추가 PR
- [ ] Build Editor 앞단에 Horde 헬스체크 (`curl http://localhost:PORT/api/v1/server/info`)
- [ ] Build Editor 아티팩트로 `.modules` + DLL 해시 publish (모듈 로딩 디버깅용)
- [ ] 주간 정기 트리거 — `CleanMode=FullRebuild` 강제로 누적 쓰레기 정리
- [ ] LordMaker 게임 프로젝트도 DevPub 아래로 통합 (현재는 root 직속)
