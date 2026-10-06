# LordMaker CI 구성 (작업 종류별 분리) — 설계

- 날짜: 2026-10-06
- 대상: TeamCity `DevPub / LordMaker` (설정 = 이 저장소 `.teamcity-lordmaker/settings.kts`, Versioned Settings `settingsPath`)
- 입력: 사용자 요청("에디터 빌드 / 쿠킹·패키징 / 서버 / 테스트용 모바일 등록 등 종류별 구분"), 로드메이커(클라) 세션·로드메이커 서버 세션 회신

## 목표

LordMaker 관련 CI를 작업 종류별 하위 프로젝트로 나눠, 브랜치 푸시마다 빠른 검증(에디터·Android 컴파일, 회귀 게이트, 서버 테스트)이 돌고 무거운 패키징은 main 머지·수동으로만 돌게 한다.

## 범위 밖 (이번에 만들지 않음)

- **Device**(테스트 기기 등록·설치): 기기 OS·연결 방식(USB/Wi-Fi adb)·대상 기기·단일 APK(`bPackageDataInsideApk`) 여부가 미정. 사용자 결정 후 별도 설계. 실계정 데이터 보호를 위해 테스트 기기는 별도 DeviceId 필수(클라 세션 요청).
- **서버 배포·재시작**: 서버 세션이 버전 동기·`.pyd` 교체·DB 마이그레이션과 묶어 수동 관리. 서비스화(S3) 때 함께 설계.
- **Shipping 패키징**: 디버그 기능 제거·HTTPS·정식 서명 선행 필요.
- **LMBot(-run=LMBot) 통합 봇**: 실서버·테스트 계정을 건드리므로 자동 실행 제외.

## 구조

```
DevPub / LordMaker                (VCS 루트: GameVcs = senariel/LordMaker, ServerVcs = senariel/LordMakerServer)
├── Client
│   ├── Editor Build              모든 브랜치 푸시
│   ├── Android Compile           모든 브랜치 푸시
│   ├── Core Gates                Editor Build 성공 후 (스냅샷 의존)
│   └── Package                   main 푸시 자동 + 수동
└── Server
    ├── LMCore                    LordMaker 푸시 중 Core/**, Source/LMCore/** 변경 시
    ├── Server Tests              LordMakerServer 모든 브랜치 푸시
    └── Static Data Drift         매일 야간
```

- VCS 루트는 **LordMaker 프로젝트 레벨**에 정의한다. refreshable token(DevPubApp, `…61fab572…`)이 LordMaker 프로젝트에 묶여 있으므로, 하위 프로젝트의 구성은 이 루트를 공유해 쓴다. DevPubApp은 모든 저장소 접근이 허용돼 있어 ServerVcs도 같은 토큰을 쓴다.
- 모든 구성은 `Agent_Win64` 고정(설치형 엔진 `D:\Shared\UE5`, MSVC 14.50, Android SDK 보유).
- 서브모듈(ClaudeBridge, 에디터 전용·private)은 체크아웃하지 않는다.

## 구성별 상세

### Client / Editor Build
- `D:\Shared\UE5\Engine\Build\BatchFiles\Build.bat LordMakerEditor Win64 Development -project=<uproject> -waitmutex`
- 체크아웃 폴더 `LordMakerCI` — Core Gates와 공유(같은 VCS 설정)해 에디터 바이너리를 재사용.
- 실패 조건: 종료 코드 ≠ 0.

### Client / Android Compile
- `Build.bat LordMaker Android Development -project=<uproject>` (쿡·스테이징 없음). clang 전용 오류(`-Werror`, LP64 `int64_t`=`long`) 조기 발견.
- 체크아웃 폴더 `LordMakerAndroid` (Editor Build와 Intermediate 충돌 방지).

### Client / Core Gates
- 스냅샷 의존: Editor Build (같은 리비전, 같은 에이전트·체크아웃 폴더 `LordMakerCI`).
- `bash Tools/core_gates.sh <out> [<baseline>]`, `UE_CMD=D:\Shared\UE5\Engine\Binaries\Win64\UnrealEditor-Cmd.exe`.
- 기준선: 아티팩트 의존 = Core Gates의 **마지막 성공 main 빌드** 산출물(선택적 — 없으면 비교 생략).
- 판정: 각 summary의 `exit=0` 아니면 실패. 기준선 대비 `DIFF`는 **경고만**(규칙 변경 PR은 의도적으로 기준선이 바뀜).
- 산출물: `<out>` 폴더 전체를 아티팩트로 게시(다음 비교의 기준선).

### Client / Package
- 현재 구성 유지(Win64 + Android ASTC Development, `LMServerUrl` ini 주입, 아티팩트 게시).
- 트리거 추가: main 푸시(VCS 트리거 `+:refs/heads/main`). 수동 실행은 브랜치 선택 가능.

### Server / LMCore
- GameVcs, 트리거 경로 필터 `+:Core/**`, `+:Source/LMCore/**`, 모든 브랜치.
- VS BuildTools 환경(vcvars64)에서 `cmake -S Core -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DPython_EXECUTABLE=<py3.14.5>` → `cmake --build build` → `ctest --test-dir build --output-on-failure` (`PYTHONUTF8=1` 필수 — cp949 콘솔 UnicodeEncodeError).
- 산출물: `lmcore.cp314-win_amd64.pyd`.
- 전제: 에이전트에 **Python 3.14.5**(모든 사용자 설치, dev headers 포함). CMake ≥ 3.20·Ninja는 VS BuildTools 번들 사용(없으면 설치 필요 — 첫 빌드에서 확인).

### Server / Server Tests
- ServerVcs, 모든 브랜치 푸시.
- 아티팩트 의존: LMCore 마지막 성공 main 빌드의 `.pyd` → 체크아웃 루트.
- GameVcs 보조 체크아웃(체크아웃 규칙 `+:Core/tests/fixtures => lordmaker-fixtures`)으로 골든 fixtures 제공 — env `LORDMAKER_FIXTURES=<체크아웃>\lordmaker-fixtures` (서버 main `087deb7`부터 지원, 경로 없으면 해당 테스트 skip).
- 공통 env `PYTHONUTF8=1` (cp949 콘솔 출력 함정).
- `python -m venv` → `pip install -e ".[dev]"` → `python -m pytest tests -q --junitxml=...` + JUnit 리포트 게시.
- 주의: 클라 머지 ~ 서버 동기 커밋 사이 RulesVersion 어긋남 시 lmcore분 테스트 skip은 정상.

### Server / Static Data Drift
- 스케줄 트리거(매일 03:30), GameVcs main + ServerVcs main.
- 비교 로직은 **서버 저장소 스크립트**를 CI가 실행만 한다: `python tools/check_static_drift.py --client <LordMaker 체크아웃> --server <서버 체크아웃>` (표준 라이브러리만, 종료 코드 0=일치 / 1=드리프트(차이 목록 stdout, 상수 추출 실패 포함) / 2=체크아웃·경로 문제(예: 클라 XML 0개 — 부분 체크아웃 규칙 깨짐). 서버 `6f6b659`부터). 검사 = 클라 `Content/Data/*.xml` ↔ 서버 `data/static/*` 개행 정규화 sha256, RulesVersion(클라 `Source/LMCore/Public/lmcore/Simulation.h` ↔ 서버 `app/main.py`), StaticDataVersion(클라 `Source/LordMaker/Public/Combat/LMSiegeInfo.h` ↔ 서버).
- GameVcs 체크아웃 규칙(→ `client/`): `+:Content/Data`, `+:Source/LMCore/Public/lmcore/Simulation.h`, `+:Source/LordMaker/Public/Combat/LMSiegeInfo.h` — LFS 대상 아님, 전체 체크아웃 불필요. ServerVcs → `server/`.
- env `PYTHONUTF8=1`.

## 오류 처리

- 모든 PowerShell 단계는 PS 5.1 함정 회피 규칙을 따른다(`$ErrorActionPreference='Continue'` + 종료 코드 판정, `Start-Process` 핸들 캐싱).
- 사전 점검 실패(엔진/Python/LFS 없음)는 `##teamcity[buildProblem]`로 원인을 바로 표시하고 종료.

## 검증

- 각 구성 첫 실행: main(Android 수정 머지 후 커밋)으로 수동 실행해 성공 확인.
- Core Gates: 첫 main 성공 빌드가 기준선이 됨을 확인(두 번째 실행에서 SAME).
- Server Tests: `.pyd` 의존 시 56건 실행(skip 0) 확인.

## 선행 조건 / 요청 사항

| 항목 | 담당 |
|---|---|
| Agent_Win64에 Python 3.14.5 (모든 사용자) 설치 | 사용자 |
| ~~Server Tests용 fixtures 경로 env 지원~~ | 완료 — 서버 `087deb7` (`LORDMAKER_FIXTURES`) |
| ~~Static Data Drift 비교 스크립트~~ | 완료 — 서버 `087deb7` (`tools/check_static_drift.py`) |
| Android 컴파일 수정(fix/android-compile) main 머지 | 로드메이커 세션 + 사용자 승인 |
| Device 요구사항(기기·연결·단일 APK) | 사용자 |
