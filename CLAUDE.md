# UnrealEngine5 Build Pipeline — Operational Notes

이 저장소는 **TeamCity가 빌드 파이프라인 설정을 코드로 저장**하는 곳입니다.
UE5 엔진 코드(github.com/senariel/UnrealEngine)와는 별도로 분리되어 있습니다.

## 구조 한 눈에

```
TeamCity 프로젝트 트리
├── DevPub (루트)
│   └── UnrealEngine5  ← Versioned Settings = 이 repo (Config) ↔ Kotlin DSL 양방향 sync
│       ├── Fetch Source     (VCS trigger, 같은 에이전트 강제)
│       └── Build Editor     (Snapshot Dep on FetchSource, runOnSameAgent=true)
```

- VCS 동기화 대상 repo: `https://github.com/senariel/Config` (이 repo, branch `main`)
- 엔진 소스 repo: `https://github.com/senariel/UnrealEngine` (branch `release`)
- 두 repo는 **물리적으로 분리**되어 있음 — 엔진 커밋과 빌드 설정 변경이 섞이지 않도록

## 왜 별도 repo로 뺐나

UE5 엔진 repo는 100GB+. TeamCity Versioned Settings는 설정 repo를 주기적으로 poll/clone하므로,
설정 변경 하나마다 거대한 엔진 repo를 건드리는 건 비효율적이고 위험. 작은 별도 repo가 정석.

## Build Editor 워크스페이스 공유 규칙 (절대 깨지 마)

1. 두 컨피그 모두 `checkoutDir = "UE5"` (같은 디렉토리)
2. Build Editor의 VCS는 `checkoutMode = CheckoutMode.MANUAL` — **자체 체크아웃 안 함**
3. Snapshot Dependency `runOnSameAgent = true` — 동일 에이전트 강제

→ Fetch Source가 받아둔 트리를 Build Editor가 그대로 사용. 이게 깨지면 Build Editor가 빈 워크스페이스에서 실행되어 실패함.

## CleanMode 파라미터

프로젝트 레벨 select 파라미터 `CleanMode`. 수동 Run 시 드롭다운으로 선택, VCS 자동 트리거는 기본값 `Incremental` 사용.

| 값 | Fetch Source 동작 | Build Editor 동작 | 용도 |
|---|---|---|---|
| `Incremental` | clean 스킵 | UAT 일반 빌드 | 평소 (UBA 캐시 활용) |
| `CleanSource` | `git clean -fd -- Engine/Source Engine/Plugins Engine/Shaders` | UAT 일반 빌드 | 고아 파일 의심 시 |
| `FullRebuild` | `git clean -fdx -- Engine` | UAT `-clean` 플래그 추가 | 완전 재빌드 |

## Horde / UBA 설정 (중요 — 헤매기 쉬움)

설정 파일 위치: **`%PROGRAMDATA%\Unreal Engine\UnrealBuildTool\BuildConfiguration.xml`**

- `%PROGRAMDATA%`로 둬야 머신 공통. `%APPDATA%`/`%USERPROFILE%`은 **계정별로 다른 폴더**라 TeamCity 에이전트 서비스 계정과 로그인 사용자 사이에서 안 맞음
- 이 파일은 git 관리 밖. 엔진 repo의 `Engine/Saved/`나 다른 곳에 두지 말 것
- XML 루트는 반드시 `<Configuration xmlns="https://www.unrealengine.com/BuildConfiguration">`
- Horde 서버 URL은 `<Horde><Server>http://...</Server></Horde>` (UBA Host/Port가 아님!)

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

## 알려진 함정 (이미 한 번씩 당함)

### 1. 모듈 로딩 실패 — 원인이 "고아 파일"인 경우
플러그인 모듈이 다른 위치로 옮겨졌는데 구 위치의 `Source/.../*.Build.cs`가 git pull로도 안 지워져서 **중복 모듈 이름**으로 UBT가 혼란.

예시: `Plugins/Experimental/MeshModelingToolsetExp/Source/SkeletalMeshModifiers/`가 uplugin에서 빠졌는데 디스크에 남아있어서, 같은 이름의 `Plugins/Runtime/MeshModelingToolset/Source/SkeletalMeshModifiers/`와 충돌.

→ **해결**: CleanMode = `CleanSource` 한 번 돌리면 git이 untracked 파일을 정리함.

### 2. `.Build.cs` vs `.build.cs` 대소문자
Windows NTFS는 대소문자 비구분이라 통과되지만, Linux UBA 워커에서는 `*.Build.cs` 글롭에 안 걸림. 하이브리드 환경 빌드 시 결과가 다름. 반드시 `*.Build.cs` (대문자 B).

### 3. Fetch Source가 정상 종료해도 Build Editor가 실패
체크아웃 디렉토리 공유가 깨졌을 가능성. 위의 "워크스페이스 공유 규칙" 3가지를 확인.

### 4. Versioned Settings UI에서 안 보임
**프로젝트 페이지**에서만 보이는 메뉴. 빌드 컨피그 페이지에는 없음. URL: `/admin/editProject.html?projectId=DevPub_UnrealEngine5&tab=versionedSettings`

### 5. `.modules` 매니페스트 BuildId vs DLL BuildId 불일치
모듈 로드 실패의 또 다른 원인. clean 빌드를 했는데 일부 DLL만 갱신 안 됐을 때 발생. `Binaries/Win64/UnrealEditor.modules`의 BuildId와 실제 DLL의 BuildId가 일치해야 함. 의심되면 `FullRebuild`.

## 운영 — 자주 하는 작업

### 빌드 설정 변경하기
- **간단한 변경**: TeamCity UI에서 직접 변경 → 자동으로 이 repo의 `settings.kts`에 커밋됨 (Two-way sync)
- **큰 변경 / 리뷰 필요**: 이 repo 직접 수정 → PR → merge → TeamCity가 감지·반영

### 이 repo 직접 수정 시 push 권한
TeamCity가 사용하는 GitHub App: `DevPubApp` (All repositories 액세스).
사람이 직접 commit·push할 때는 본인 GitHub 계정 필요.

### Versioned Settings 일시 정지
Project Settings → Versioned Settings → "Synchronization disabled" 또는 "Use settings from a parent project". UI 변경이 git에 안 올라감.

### Force VCS 동기화
Project Settings → Versioned Settings → 페이지 하단의 `Load project settings from VCS...` 또는 `Commit current project settings...`

## 파일 구조

```
.teamcity/
├── settings.kts    ← 모든 TeamCity 설정 (프로젝트, 빌드 컨피그, VCS Root)
└── pom.xml         ← Maven 빌드 정의 (DSL 컴파일·검증용, TeamCity가 자동 관리)
README.md
CLAUDE.md           ← 이 파일
```

## 다음에 할 만한 것 (TODO 후보)

- [ ] Fetch Source 끝에 `git status --porcelain` 검증 스텝 추가 — 예상 외 untracked 파일 자동 감지
- [ ] Build Editor 앞단에 Horde 헬스체크 (`curl http://localhost:PORT/api/v1/server/info`) — 빨리 실패
- [ ] Build Editor 아티팩트로 `Binaries/Win64/*.modules` + DLL 해시 publish — 모듈 로딩 디버깅용
- [ ] Failure Condition: 빌드 로그에 `Module ... could not be loaded` 포함 시 실패 마킹
- [ ] 주간 정기 트리거 (일요일 야간) — `CleanMode=FullRebuild` 강제로 누적 쓰레기 정리

## 변경 이력 (요약)

- 2026-04: Versioned Settings 도입, Config repo 분리, CleanMode 파라미터 도입 (IsClean checkbox 대체)

