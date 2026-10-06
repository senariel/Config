# LordMaker CI 구성 (작업 종류별 분리) — 설계

- 날짜: 2026-10-06
- 대상: TeamCity `DevPub / LordMaker` (설정 = 이 저장소 `.teamcity-lordmaker/settings.kts`, Versioned Settings `settingsPath`)
- 입력: 사용자 요청("에디터 빌드 / 쿠킹·패키징 / 서버 / 테스트용 모바일 등록 등 종류별 구분"), 로드메이커(클라) 세션·로드메이커 서버 세션 회신

## 목표

LordMaker 관련 CI를 작업 종류별 하위 프로젝트로 나눠, 브랜치 푸시마다 빠른 검증(에디터·Android 컴파일, 회귀 게이트, 서버 테스트)이 돌고 무거운 패키징은 main 머지·수동으로만 돌게 한다.

## 범위 밖 (이번에 만들지 않음)

- ~~Device~~ → 아래 "Device (추가 설계, 2026-10-06)"로 범위에 포함됨.
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
├── Server
│   ├── LMCore                    LordMaker 푸시 중 Core/**, Source/LMCore/** 변경 시
│   ├── Server Tests              LordMakerServer 모든 브랜치 푸시
│   └── Static Data Drift         매일 야간
└── Device
    ├── Register Device           수동 (기기당 1회)
    └── Deploy to Device          수동
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
- MSVC 버전: vcvars64는 에이전트의 최신 툴셋(현재 14.51.36231)을 고른다. UE는 14.50.35717이지만 **고정하지 않는다** — UE ↔ 코어 비트 등가는 컴파일러 버전이 아니라 ctest의 `lmcore_python_tests` 골든 대조(UE 산출 픽스처)가 지키며, 서버 라이브 .pyd도 14.51 빌드다. 규칙: 골든 대조가 통과하는 한 혼용 수용. **골든 불일치가 나면 1차 조치 = `call vcvars64.bat -vcvars_ver=14.50`으로 고정**(서버 세션 합의, 2026-10-06).
- 산출물: `lmcore.cp314-win_amd64.pyd`.
- 전제: 에이전트에 **Python 3.14.5**(모든 사용자 설치, dev headers 포함). CMake ≥ 3.20·Ninja는 VS BuildTools 번들 사용(없으면 설치 필요 — 첫 빌드에서 확인).

### Server / Server Tests
- ServerVcs, 모든 브랜치 푸시.
- 아티팩트 의존: LMCore 마지막 성공 main 빌드의 `.pyd` → 체크아웃 루트.
- GameVcs 보조 체크아웃(체크아웃 규칙 `+:Core/tests/fixtures => lordmaker-fixtures/Core/tests/fixtures` — 에이전트 측 체크아웃은 경로 이름 변경 불가, 접두만 가능)으로 골든 fixtures 제공 — env `LORDMAKER_FIXTURES=<체크아웃>\lordmaker-fixtures` (서버 main `087deb7`부터 지원, 경로 없으면 해당 테스트 skip).
- 공통 env `PYTHONUTF8=1` (cp949 콘솔 출력 함정).
- `python -m venv` → `pip install -e ".[dev]"` → `python -m pytest tests -q --junitxml=...` + JUnit 리포트 게시.
- 주의: 클라 머지 ~ 서버 동기 커밋 사이 RulesVersion 어긋남 시 lmcore분 테스트 skip은 정상.

### Server / Static Data Drift
- 스케줄 트리거(매일 03:30), GameVcs main + ServerVcs main.
- 비교 로직은 **서버 저장소 스크립트**를 CI가 실행만 한다: `python tools/check_static_drift.py --client <LordMaker 체크아웃> --server <서버 체크아웃>` (표준 라이브러리만, 종료 코드 0=일치 / 1=드리프트(차이 목록 stdout, 상수 추출 실패 포함) / 2=체크아웃·경로 문제(예: 클라 XML 0개 — 부분 체크아웃 규칙 깨짐). 서버 `6f6b659`부터). 검사 = 클라 `Content/Data/*.xml` ↔ 서버 `data/static/*` 개행 정규화 sha256, RulesVersion(클라 `Source/LMCore/Public/lmcore/Simulation.h` ↔ 서버 `app/main.py`), StaticDataVersion(클라 `Source/LordMaker/Public/Combat/LMSiegeInfo.h` ↔ 서버).
- GameVcs 체크아웃 규칙(→ `client/`): `+:Content/Data`, `+:Source/LMCore/Public/lmcore/Simulation.h`, `+:Source/LordMaker/Public/Combat/LMSiegeInfo.h` — LFS 대상 아님, 전체 체크아웃 불필요. ServerVcs → `server/`.
- env `PYTHONUTF8=1`.

## Device (추가 설계, 2026-10-06)

사용자 결정: Android만 / **Wi-Fi adb**(무선 디버깅 페어링) / **단일 APK** / **수동 배포**. 설치는 빌드 에이전트(AYA-ZZANG)의 Android SDK adb가 한다.

### 게임 저장소 쪽 전제 (로드메이커 세션 변경, 사용자 승인·머지 대기)
- `bPackageDataInsideApk=True` — 단일 APK.
- `bUseExternalFilesDir=True` — 외부 명령줄 파일 경로가 앱 전용 폴더가 됨(Android 11+ 범위 저장소에서 읽기 가능).
- `ULMNetworkSubsystem::GetDeviceId()`가 비Shipping에서 명령줄 `-LMDeviceId=<id>`를 읽음. 우선순위: 봇/콘솔 덮어쓰기 > `-LMDeviceId` > 플랫폼 로그인 ID. (`LM.Net.DeviceId`는 cvar가 아니라 콘솔 명령이라 `-ini:` 주입 불가.)
- Android 명령줄(LaunchAndroid.cpp, 비Shipping): 외부 `/sdcard/Android/data/com.devpub.lordmaker/files/UnrealGame/LordMaker/UECommandLine.txt`가 있으면 명령줄 전체를 그 파일 첫 줄로 **교체**한다 → 파일 = 스테이징 기본 명령줄 + ` -LMDeviceId=<id>` 한 줄.

### Client / Package 변경
- 아티팩트에 스테이징된 기본 명령줄 추가: `Saved/StagedBuilds/Android/UECommandLine.txt => apk` (스테이징 폴더명은 첫 단일 APK 빌드에서 확인, `Android`/`Android_ASTC`).

### Device / Register Device (수동, 기기당 1회)
- 파라미터(실행 시 입력): `PairAddress`(휴대폰 "페어링 코드로 기기 페어링" 화면의 IP:포트), `PairCode`(6자리, 1~2분 내 만료).
- `adb pair <PairAddress> <PairCode>` → 페어링 키는 에이전트(LocalSystem 프로필)의 adb에 영구 저장. 이어서 mDNS로 연결을 시도하고 `adb devices -l` 결과(모델·시리얼)를 로그에 남김.
- 기기 목록은 따로 관리하지 않는다(adb 페어링 상태가 곧 등록).

### Device / Deploy to Device (수동)
- 아티팩트 의존: Package 마지막 성공 main의 `apk/*` (Run custom build에서 다른 빌드 선택 가능).
- 파라미터: `DeviceFilter`(모델/시리얼 부분 일치, 비우면 연결된 기기 전부), `LaunchAfterInstall`(기본 false).
- 기기별 절차 — 하나라도 실패하면 그 기기는 실패, **앱 실행 금지**:
  1. `adb mdns services`로 `_adb-tls-connect` 엔드포인트를 찾아 `adb connect` (재부팅 등으로 바뀐 포트 대응).
  2. `adb -s <serial> install -r <apk>`.
  3. `adb -s <serial> shell mkdir -p /sdcard/Android/data/com.devpub.lordmaker/files/UnrealGame/LordMaker`.
  4. DeviceId = `lmtest-<모델 소문자·영숫자·하이픈>-<시리얼 SHA-256 앞 8자>` → 파일 = 기본 명령줄 + ` -LMDeviceId=<id>` → `adb push`.
  5. `adb shell cat`으로 다시 읽어 내용 일치 확인.
  6. (`LaunchAfterInstall`일 때만) `adb shell monkey -p com.devpub.lordmaker 1` 실행 후 logcat에서 `deviceId=lmtest-` 확인(최대 60초) — 실계정 ID가 보이면 즉시 `am force-stop` 후 실패.
- 연결된 대상 기기가 0대면 원인(무선 디버깅 꺼짐/다른 서브넷/미등록)을 buildProblem으로 표시하고 실패.

### 테스트 계정 (정정 2026-10-06)
- 테스트 계정 식별 규약 = `lmtest-` 접두(서버 기록 a552f80). 서버는 deviceId에 형식 제약 없음, S3 실배포 때 서버가 테스트 접두 가드 구현 예정.
- ~~위험: 파일 없이 실행하면 실계정 로그인~~ → **정정**: Android `FPlatformMisc::GetLoginId()`는 설치마다 무작위 GUID(내부 저장소 login-identifier.txt)라, 파일이 없어도 새 게스트 계정이 생길 뿐 다른 기기의 실계정과 겹치지 않는다.
- `-LMDeviceId`의 목적: ① 재설치·데이터 삭제 후에도 같은 테스트 계정 유지(없으면 재설치마다 고아 계정 누적) ② `lmtest-` 접두로 식별·정리. logcat 검사는 보호 장치가 아니라 **주입 성공 확인** 용도(실패 시 강제 종료·실패 처리는 유지).

### 선행 조건
- 휴대폰과 AYA-ZZANG이 같은 서브넷, Android 11+(무선 디버깅), 개발자 옵션의 무선 디버깅 켜짐.
- 위 게임 저장소 변경이 main에 머지된 뒤의 Package 빌드(단일 APK + 기본 명령줄 아티팩트).

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
