# 빌드 머신 리눅스 전환 (에이전트 3분할 + 게임 서버 이전) — 설계

- 날짜: 2026-10-16
- 대상: TeamCity `DevPub / UnrealEngine5`(`.teamcity/settings.kts`), `DevPub / LordMaker`(`.teamcity-lordmaker/settings.kts`), 서버 머신 192.168.219.180의 Docker compose(`/home/gray/docker/docker-compose.yml`)·systemd
- 입력: 사용자 결정(2026-10-15~16), 로드메이커 서버 세션 회신 2건(배포 절차, lmcore 결정론 규칙), 머신 조회 결과
- 이전 설계: `2026-10-06-lordmaker-ci-structure-design.md` (구성 목록·스텝 내용은 그대로 유지, 이 문서는 **어느 에이전트에서 어떻게 도는가**만 바꾼다)

## 배경

192.168.219.180(호스트 이름 `MAGI-AM02`)은 Windows 11 + WSL로 TeamCity 서버(Docker)와 유일한 에이전트 `Agent_Win64`를 함께 돌리던 머신이다. 이 머신이 **네이티브 Ubuntu 26.04로 바뀌었다.** TeamCity 서버 설정·데이터는 그대로지만 Windows 에이전트가 사라져 모든 빌드가 멈춘 상태다.

UE는 리눅스 → Win64 크로스 빌드를 지원하지 않으므로 Win64 작업은 Windows 에이전트가 필요하다. 외부 Windows PC는 디스크가 약 200GB로 엔진 소스 빌드(150~250GB)와 프로젝트 빌드를 함께 감당할 수 없다. 에이전트 작업 폴더를 SMB 공유에 두는 안은 소규모 파일 I/O 지연 때문에 기각했다.

## 결정 사항

| # | 결정 | 근거 |
|---|------|------|
| D1 | 에이전트 3분할: **Engine**(Windows) · **Project**(Windows) · **Linux**(.180) | 크로스 빌드 불가, 디스크 제약 |
| D2 | 게임 서버(LordMakerServer)를 노트북(MAGI-LAPTOP)에서 **.180 systemd 서비스로 이전**, 노트북 서버는 정리 | 서버·프록시·CI가 한 머신 |
| D3 | 서버 배포는 **수동 트리거** + 자동 확인·롤백. 트리거 기준 = 서버 저장소 main | 서버 세션 동의. 클라 머지 ↔ 서버 상수 커밋 짝이 사람 판단과 묶여 있음 |
| D4 | Horde 서버는 **Docker compose**로(.180 기존 compose에 추가) | 공식 배포 방식, Mongo·Redis 동봉, 버전 고정 쉬움 |
| D5 | 작업 폴더·DB는 NVMe(ext4), 공유·보관은 HDD(`/mnt/d`, NTFS) | NTFS(FUSE)는 권한·심볼릭 링크·성능 부적합 |
| D6 | 에이전트 배정은 에이전트 속성 `devpub.role`(engine/project/server)로 | 에이전트 이름 고정(`Agent_Win64`) 탈피, 교체 쉬움 |

## 서버 머신 현황 (2026-10-16 조회)

| 항목 | 값 |
|------|----|
| 하드웨어 | AYANEO AM02 미니 PC, 16스레드, RAM 22GB(평시 사용 ~4GB), 스왑 8GB |
| OS | Ubuntu 26.04.1 LTS, 커널 7.0, 사용자 `gray` |
| 디스크 | NVMe 477GB ext4 `/`(여유 ~405GB) / HDD 1.9TB NTFS `/mnt/d`(ntfs-3g, `uid=33,gid=33,umask=007`) |
| `/mnt/d` 내용 | `Shared/`(`UE5` 91GB = 이전 Windows 설치형 엔진, `LordMaker` 1.8GB = 패키지 최신본 #45, `Careers` = 개인), `docker/`(컨테이너 데이터). 이전 에이전트 작업 폴더·Horde 워커 데이터 등은 2026-10-16 삭제 |
| Docker compose | `Caddy`(80/443/8086, `host.docker.internal:host-gateway` 있음), `TeamCity`(8111, PostgreSQL DB), `samba`(공유 `Shared` = `/mnt/d/Shared`, 계정 `gray`, uid 33), `Nextcloud`·`nextcloud-cron`, `PostgreSQL`, `PgBouncer`, `Redis`(Nextcloud용), `docker-socket-proxy` |
| 설치된 도구 | git 2.53, Python 3.14.4, zip, ufw(비활성) |
| 없음 | git-lfs, Java, cmake, ninja, gcc/g++, adb, 7z, Horde 서버 |

> `/mnt/d`는 `www-data`(33) 소유·그룹 쓰기 가능. 접근이 필요한 계정은 `www-data` 그룹에 넣는다(`gray`는 추가 완료).

## 목표 구조

```
TeamCity 서버 (Docker, .180:8111)
├── 에이전트 Engine   (Windows, 외부 PC A)  devpub.role=engine   — UnrealEngine5 전부
├── 에이전트 Project  (Windows, 외부 PC B)  devpub.role=project  — LordMaker/Client 전부 + Infra/Sync Engine
└── 에이전트 Linux    (.180 호스트, systemd) devpub.role=server   — LordMaker/Server, LordMaker/Device, Server/Deploy Server

.180 호스트
├── Docker compose: Caddy, TeamCity, samba, Nextcloud…  + Horde(신규: horde-server, horde-mongo, horde-redis)
├── systemd: teamcity-agent, lordmaker@8800, lordmaker@8801
└── /mnt/d/Shared (samba \\192.168.219.180\Shared)
    ├── UE5_Archives/   엔진 설치형 zip (Engine → Project 전달)
    ├── LordMaker/      패키지 최신본 (Project 씀 → Linux 읽음: 기기 배포)
    ├── LordMaker_Archives/  패키지 보관 zip (ArchiveBuild=true일 때)
    ├── LordMaker_Backups/   게임 DB 백업 (Linux 씀)
    └── DDC/            공유 DDC (Windows 에이전트들)
```

## 1. 리눅스 에이전트

### 계정·경로
| 항목 | 값 |
|------|----|
| 계정 | `teamcity` (시스템 계정, 로그인 셸 bash, 홈 `/home/teamcity`), 보조 그룹 `www-data`, `lordmaker` |
| 설치 | `/opt/teamcity-agent` (서버에서 받은 `buildAgentFull.zip`) |
| 작업·임시·시스템 | `/srv/teamcity/work`, `/srv/teamcity/temp`, `/srv/teamcity/system` |
| 서비스 | systemd `teamcity-agent.service` (`User=teamcity`, `Restart=on-failure`) |

### `buildAgent.properties`
```
serverUrl=http://localhost:8111          # 헤어핀 NAT 미지원 — 공개 도메인 쓰지 않음
name=Agent_Linux
workDir=/srv/teamcity/work
tempDir=/srv/teamcity/temp
systemDir=/srv/teamcity/system
devpub.role=server
env.LANG=C.UTF-8
env.TZ=Asia/Seoul
```
- 서버 옵션 `-Dteamcity.agent.autoUpgrade=false`라 에이전트 자동 업그레이드가 없다. TeamCity 서버를 올릴 때 에이전트도 서버의 `buildAgentFull.zip`으로 다시 설치한다.
- 첫 연결 후 TeamCity UI에서 승인(Authorize)한다.

### 툴셋
| 도구 | 설치 | 용도 |
|------|------|------|
| OpenJDK 21 JRE (headless) | apt | 에이전트 |
| git-lfs | apt | GameVcs(LFS) 체크아웃 |
| build-essential(gcc/g++), cmake(≥3.20), ninja-build | apt | LMCore |
| python3.14-venv, python3.14-dev | apt | 서버 venv, pybind11 빌드(헤더) |
| Android platform-tools (adb) | Google 배포 zip → `/opt/android/platform-tools` | 기기 등록·배포·로그. Wi-Fi 페어링(`adb pair`) 때문에 최신판 사용 |
| 7zip, unzip, curl, jq, sqlite3 | apt | 보관·REST·DB 백업/점검 |

- Python은 시스템 3.14.4를 쓴다(Windows CI는 3.14.5 — 패치 차이는 ABI 동일).
- pybind11은 클라 저장소에 동봉(`Core/ThirdParty/pybind11` 3.1.0)이라 별도 설치 없음. CMake가 `PYBIND11_FINDPYTHON`(FindPython)을 쓰므로 인자는 `-DPython_EXECUTABLE=`.

## 2. LordMaker / Server 구성의 리눅스 전환

스텝 내용은 이전 설계와 같고, PowerShell/cmd → bash로 다시 쓴다. 요구 조건은 `devpub.role = server`.

### LMCore
```
cmake -S Core -B build -G Ninja -DCMAKE_BUILD_TYPE=Release \
      -DPython_EXECUTABLE=/usr/bin/python3.14 -DCMAKE_CXX_FLAGS=-march=x86-64-v2 \
      -DCMAKE_EXPORT_COMPILE_COMMANDS=ON
cmake --build build && ctest --test-dir build --output-on-failure
```
- 산출물: `build/lmcore.cpython-314-x86_64-linux-gnu.so` (아티팩트). Windows `.pyd`는 더 만들지 않는다(서버가 리눅스로 이전하므로 소비처 없음).
- **FP 결정론 규칙** (클라 세션 확정, 2026-10-15):
  - `Core/CMakeLists.txt`가 GCC/Clang에 `-ffp-contract=off -fno-fast-math`를 PUBLIC으로 설정 — **CI에서 덮어쓰지 않는다**(전역 플래그는 대상 옵션 앞에 오므로 추가만 허용).
  - 허용: Release 기본(`-O3 -DNDEBUG`) 또는 `-O2`, `-march=x86-64-v2` 고정(`native` 금지).
  - 금지: `-Ofast`, `-ffast-math` 계열(unsafe/associative/reciprocal/finite-math), `-m32`·`-mfpmath=387`, `fesetround`.
  - 근거: 코어는 정확 반올림 연산만 사용(+−×÷, sqrt, floor, nearbyint), 초월 함수 0건, 정수 난수, 정렬 타이브레이커 완비.
- 빌드 로그에 컴파일러 버전(`g++ --version`)과 `compile_commands.json`의 실제 플래그를 출력한다 — 결정론 게이트 실패 시 1차 대조 자료.

### Server Tests
- 아티팩트 의존: LMCore 마지막 성공 main의 `.so` → 서버 체크아웃 루트.
- `LORDMAKER_FIXTURES=<체크아웃>/lordmaker-fixtures` (기존 체크아웃 규칙 유지). `PYTHONUTF8`은 리눅스에서 불필요.
- `python3.14 -m venv .venv` → `pip install -e ".[dev]"` → `pytest tests -q --junitxml=…`.

### Static Data Drift
- 변경 없음(경로 구분자만). 매일 03:30, 에이전트 시간대 `Asia/Seoul`.

## 3. LordMaker / Device 구성의 리눅스 전환

- 요구 조건 `devpub.role = server`. adb = `/opt/android/platform-tools/adb`.
- APK 경로 기본값: `/mnt/d/Shared/LordMaker/Android` (Project 에이전트가 samba로 쓴 것을 로컬로 읽음).
- adb 키는 `/home/teamcity/.android/`에 새로 생긴다 → **폰 재페어링 필요**(Register Device 1회).
- 로그 수집은 adb 출력을 바이트로 받아 UTF-8로 저장(Windows cp949 깨짐 문제는 리눅스에서 사라짐). UE 로그 경로 탐색(`/sdcard/Android/data/com.devpub.lordmaker/files/` 아래 `LordMaker*.log`)은 미결 과제 그대로.
- 무선 디버깅 활성화는 여전히 실행 전에 사용자에게 요청한다.

## 4. 게임 서버 (systemd)

### 계정·경로
| 항목 | 값 |
|------|----|
| 실행 계정 | `lordmaker` (시스템 계정, 로그인 불가) |
| 릴리스 | `/srv/lordmaker/releases/<서버rev>-<클라rev>/` (서버 체크아웃 + `.venv` + `lmcore*.so` + `DEPLOY_INFO.txt`) |
| 현재 버전 | `/srv/lordmaker/current-8800`, `/srv/lordmaker/current-8801` (심볼릭 링크, 인스턴스별) |
| 데이터 | `/var/lib/lordmaker/8800/lordmaker.db`, `/var/lib/lordmaker/8801/lordmaker-fast.db` |
| 환경 | `/etc/lordmaker/8800.env`, `/etc/lordmaker/8801.env` (권한 640, `root:lordmaker`) |
| 백업 | `/mnt/d/Shared/LordMaker_Backups/<포트>/<날짜시각>-<rev>.db` (다른 디스크) |

- 인스턴스별 링크를 두는 이유: 8801(테스트)에 먼저 배포해 확인한 뒤 8800에 올릴 수 있게.
- `teamcity`는 `lordmaker` 그룹 — 릴리스 폴더를 쓰고 링크를 바꿀 수 있게.

### 유닛 `lordmaker@.service` (템플릿, `%i` = 포트)
```
[Service]
User=lordmaker
WorkingDirectory=/srv/lordmaker/current-%i
EnvironmentFile=/etc/lordmaker/%i.env
ExecStart=/srv/lordmaker/current-%i/.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port %i
Restart=on-failure

[Install]
WantedBy=multi-user.target
```
- `systemctl enable lordmaker@8800 lordmaker@8801` — **재부팅 자동 기동이 이번 이전의 1차 목적**(서버 세션).
- **`--workers` 금지(1 고정)**: 스케줄러·LMCore 워커가 게이트웨이 프로세스 내 스레드라 다중 워커 = 중복 틱 + SQLite 경합. 별도 워커 유닛 불필요.
- 바인딩은 `0.0.0.0`: Caddy가 Docker 컨테이너라 컨테이너의 `127.0.0.1`은 호스트가 아니다. 대신 ufw로 막는다(아래). 서버 세션 초안(`127.0.0.1`)에서 변경.
- 마이그레이션 단계 없음: `init_db()`가 기동 시 CREATE TABLE/ADD COLUMN을 멱등 수행.
- env 이름(값은 문서·커밋에 쓰지 않음): `LORDMAKER_DB`, `CYCLE_MINUTES`, `RECOVERY_PER_MINUTE`, `OFFLINE_SCALE_WITH_CYCLE`, `SCHEDULER`, `INPROC_WORKER`, `RETENTION_DAYS`, `FIRST_SIEGE_GRACE_MINUTES`, `SELL_RATIO`, `EXPEDITION_SLOT_LIMIT`, `ALLOW_DEBUG_SIEGE`, `ALLOW_STATE_RESET`, `MIN_CLIENT_VERSION`, `WORKER_TOKEN` (모두 `LORDMAKER_` 접두). 운영값: 8800 = 30분 주기·회복 12.0·offline 스케일 on / 8801 = 별도 DB·5분·72.0·on.
- `WORKER_TOKEN` 값은 노트북 `data/server-8800.env.txt`에만 있다 → **사용자가 직접** `/etc/lordmaker/8800.env`로 옮긴다.

### 권한 (sudoers)
`/etc/sudoers.d/teamcity-lordmaker` — `teamcity`가 비밀번호 없이 실행할 수 있는 것은 아래뿐:
```
teamcity ALL=(root) NOPASSWD: /usr/bin/systemctl restart lordmaker@8800.service, /usr/bin/systemctl restart lordmaker@8801.service, /usr/bin/systemctl is-active lordmaker@8800.service, /usr/bin/systemctl is-active lordmaker@8801.service
```

### 리버스 프록시·방화벽
- Caddyfile: `lm.senariel.duckdns.org` → `reverse_proxy host.docker.internal:8800` (현재 `192.168.219.182:8800`). 같은 블록에서 `/internal/*`는 `respond 403`(기존 합의).
- `/v1/debug/*` 차단은 **2026-10-07 사용자 결정("테스트 기간 차단 안 함")을 바꾸는 것**이라 사용자 확인 후 적용한다(미결 6).
- ufw 활성화(현재 비활성):
  - `default deny incoming`, `allow 22/tcp`, `allow 80,443/tcp`
  - `8800,8801/tcp`: `192.168.219.0/24`(집 안 LAN — 클라 Lan 프로필 직결)와 `172.16.0.0/12`(Docker 대역 — Caddy)에서만 허용
  - 13340·13342(Horde)는 Docker가 연 포트라 ufw를 거치지 않는다. 공유기가 80/443만 포워딩하는 전제에서 LAN 한정.
  - **SSH 허용을 먼저 넣고 활성화**한다(원격 접속 차단 방지).
  - LAN에 8800/8801을 열면 `/internal/*`도 LAN에서 닿는다(`X-Worker-Token` 보호라 개발 기간 수용). S3 체크리스트: "LAN 직결 제거 또는 토큰 강화".

### Server / Deploy Server (신규 구성, 수동)
- 요구 조건 `devpub.role = server`. VCS: ServerVcs(main 기본, 브랜치 선택 가능) + GameVcs(`Core/` 부분 체크아웃).
- 파라미터: `Instance` = `8801` | `8800` (기본 8801), `ClientRev`(기본 = LMCore 마지막 성공 main의 리비전).
- 순서:
  1. 릴리스 폴더 생성: 서버 체크아웃 복사, `python3.14 -m venv .venv`, `pip install .`
  2. lmcore 빌드(2장 LMCore와 같은 명령·플래그) + ctest → `.so`를 릴리스 루트에
  3. `DEPLOY_INFO.txt`: 서버 rev, 클라 rev, 빌드 번호, 일시, 컴파일러 버전
  4. DB 백업: `sqlite3 <db> ".backup '<백업 경로>'"` (온라인 백업, WAL 아님 전제) → `PRAGMA quick_check`
  5. 링크 전환: `current-<포트>` → 새 릴리스 (이전 대상은 `previous-<포트>`로 기록)
  6. `sudo systemctl restart lordmaker@<포트>`
  7. 확인 (localhost 직결, 프록시 경유 아님):
     - `GET /v1/static-data/version` — `rulesVersion`·`staticDataVersion`이 체크아웃 상수와 일치(비인증 크로스체크)
     - `POST /internal/selfcheck` (헤더 `X-Worker-Token`, 서버 `c3ae055`~) — 기대: HTTP 200, `ok:true`, `workerThread:true`, `schedulerThread:true`. 서버가 starter 세이브 → 고정 공격대 스냅샷 → 잡 큐 enqueue(defender 없음 — 기록·정산 비대상) → 시뮬 완주 → 점검 잡 삭제까지 수행하므로 코어·워커·스케줄러가 한 번에 검증된다. `ALLOW_DEBUG_SIEGE=0`이어도 동작(2026-10-16 8800·8801 실측 ok, 54턴).
     - 토큰은 `/etc/lordmaker/<포트>.env`의 `LORDMAKER_WORKER_TOKEN`을 스텝이 읽어 쓴다(로그에 출력하지 않음 — `teamcity`가 env 파일을 읽도록 그룹 `lordmaker`·권한 640).
  8. 실패 시: 링크를 `previous-<포트>`로 되돌리고 재시작, 빌드 실패 처리. DB는 되돌리지 않는다(멱등 스키마, 필요 시 백업에서 수동 복구).
- 오래된 릴리스는 인스턴스별 최근 5개만 남기고 정리.

### lmcore 리눅스 첫 투입 — 결정론 전환 게이트 (1회 필수)
라이브(8800) 투입 전 아래 4가지를 모두 통과해야 한다. 하나라도 실패하면 투입하지 않고, 첫 불일치 시드의 trace를 클라 세션에 보내 분석받는다.

| # | 검사 | 실행 |
|---|------|------|
| G1 | ctest 골든 100% | LMCore 구성 |
| G2 | 서버 pytest 워커 골든 대조(`LORDMAKER_FIXTURES`, 잡 큐 경유 포함) | `LORDMAKER_FIXTURES=<fixtures 경로> ./.venv/bin/python -m pytest tests -q` — 기준 **62 passed, skipped 0**. skip이 하나라도 있으면(코어 버전 불일치·픽스처 부재) 게이트 실패 |
| G3 | 클라 sweep 200시드의 코어 결과 SHA256이 Windows 기대값과 동일 | 클라 `sweep_gate` (구현 중, 클라 세션이 완료 통지): Windows에서 sweep 스냅샷 입력을 코어 JSON 픽스처로 커밋(UE 결과와 대조해 생성) → `python Core/python/sweep_gate.py --fixtures Core/tests/fixtures`가 lmcore로 시뮬, 비결정 필드(`endedAt`) 제거 후 SHA256 대조. `ctest -R sweep_gate`로도 등록 예정 → **사실상 G1(ctest)에 흡수**. 약 30초. UE 커맨드릿 sweep CSV는 리눅스 `.so`만으로 재현할 수 없어 이 방식으로 대체(Windows Core Gates의 CSV SHA는 그대로 유지) |
| G4 | 시뮬레이터 같은 시드 CSV가 Windows 런과 동일 | `./.venv/bin/python -m bots.simulate --bots 20 --cycles 48 --seed 1 --csv /tmp/sim-linux.csv` → `python tools/compare_sim_csv.py tests/fixtures/sim-gate-seed1-v28.csv /tmp/sim-linux.csv` — exit 0 = 통과(불일치 시 첫 차이 행·열 출력 → 서버 세션). 기준 CSV는 서버 저장소에 포함(`01b9d37`, Rules 28 / Data 23, 960행) — **전환 시점 버전이 28/23과 다르면 서버 세션에 재생성 요청** |

## 5. Horde 서버 (Docker compose)

```
horde-server   ghcr.io/epicgames/horde-server:5.8.3   ports 13340(HTTP), 13342(gRPC)   mem_limit 2g
horde-mongo    mongo (공식 이미지)                     볼륨 /srv/horde/mongo            mem_limit 1g
horde-redis    redis:alpine (Nextcloud용과 분리)        볼륨 /srv/horde/redis            mem_limit 256m
```
- 엔진 = 포크 release **UE 5.8.3** → Horde 서버 이미지 태그를 맞춘다(UBA·에이전트 버전 불일치 방지).
- 이미지 출처 2안: ① ghcr 공식 이미지 — Epic Games 조직에 연결된 GitHub 계정과 `read:packages` 토큰 필요 ② 포크 소스의 Horde Dockerfile로 직접 빌드(버전 정확히 일치, 빌드 시간 소요). **①을 먼저 시도**, 접근이 안 되면 ②.
- 데이터는 NVMe(`/srv/horde/*`) — Mongo를 NTFS(FUSE)에 두지 않는다.
- 포트를 이전과 같게 유지 → Windows 머신들의 UBT `BuildConfiguration.xml`(Horde 서버 주소) 변경 불필요.
- 이 머신은 **Windows 컴파일 워커가 아니다.** 원격 컴파일 워커는 MAGI, MAGI-LAPTOP(Horde 에이전트 `MAGI-2` 계정 — 노트북 게임 서버 정리 후에도 **유지**).

## 6. Windows 에이전트 (Engine, Project)

머신이 정해지면 상세를 보강한다. 원칙만 정한다.

| 항목 | Engine | Project |
|------|--------|---------|
| 담당 | UnrealEngine5: Sync Fork, Fetch Source, Build Editor(+ 설치형 zip 게시) | LordMaker/Client: Editor Build, Android Compile, Core Gates, Package + Infra/Sync Engine |
| 디스크 | 300GB 이상 권장(소스+중간 파일) | ~150GB(설치형 엔진 + 체크아웃 1개 + 로컬 DDC 상한) |
| 공유 | `\\192.168.219.180\Shared\UE5_Archives`에 zip 쓰기 | 엔진 zip 읽기, `LordMaker`·`LordMaker_Archives` 쓰기, `DDC` |

- **엔진 전달**: Build Editor 마지막 스텝이 설치형 엔진을 7z로 묶어 `UE5_Archives/UE5-<버전>-<rev>.7z` + `latest.txt`를 쓴다. Project 에이전트의 `Infra / Sync Engine`(Build Editor 완료 트리거, `devpub.role=project`)이 버전이 바뀐 경우에만 받아 로컬(`<드라이브>:\UE5`)에 푼다. TeamCity 아티팩트는 300MB 제한·서버 디스크 때문에 쓰지 않는다.
- **초기 시드**: `/mnt/d/Shared/UE5`(91GB, 이전 에이전트가 만든 Windows 설치형 엔진)를 Project 에이전트 첫 세팅에 쓴다. 크기 절감(디버그 심볼 제외, 대상 플랫폼 Win64·Android 한정)은 Build Editor 옵션 조정으로 후속 처리.
- **LordMaker 체크아웃 통합**: Editor Build·Core Gates·Package가 같은 체크아웃 폴더를 쓴다(동시 실행 안 함). Android Compile은 Intermediate 충돌 때문에 별도 유지 여부를 첫 운영에서 판단.
- **공유 접근 인증**: 에이전트 서비스는 매핑 드라이브를 못 본다. 서비스를 전용 로컬 사용자로 실행하고 그 사용자로 `cmdkey /add:192.168.219.180 /user:gray`를 **사용자가 직접** 등록한다(비밀번호는 설정·로그에 남기지 않는다). 설정의 경로는 UNC(`\\192.168.219.180\Shared\…`).
- **디스크 상한**: 빌드 구성에 Free disk space 기능(필요 여유 30GB), 로컬 DDC 크기 상한.
- 외부 PC가 집 공유기 밖이면 SMB·Horde 접근이 불가하므로 VPN(WireGuard 등)이 선행 조건 — 미결.

## 7. DSL 변경 요약

| 구성 | 현재 요구 조건 | 변경 |
|------|----------------|------|
| UnrealEngine5: Sync Fork, Fetch Source, Build Editor | `teamcity.agent.name = Agent_Win64` | `devpub.role = engine`, Build Editor 배포 스텝 → 공유 zip 게시 |
| LordMaker/Client 4종 | `Agent_Win64` | `devpub.role = project`, 엔진 경로 = 로컬 설치형, Package 게시 경로 → UNC |
| LordMaker/Server 3종 | `Agent_Win64` | `devpub.role = server`, 스텝 bash 재작성, LMCore 산출물 `.so` |
| LordMaker/Device 3종 | `Agent_Win64` | `devpub.role = server`, 스텝 bash 재작성, APK 기본 경로 `/mnt/d/Shared/LordMaker/Android` |
| 신규 | — | `LordMaker/Server/Deploy Server`(수동), `LordMaker/Infra/Sync Engine` |

프로젝트 파라미터 `env.UE5_ENGINE_ROOT`, `env.LM_DIST_PATH`, `env.LM_ARCHIVE_PATH`, `env.LM_PYTHON`, `env.VCVARS64`는 OS별 값이 달라지므로 각 하위 프로젝트(Client = Windows 값, Server·Device = 리눅스 값)로 내린다.

## 8. 전환 순서

| 단계 | 내용 | 완료 조건 |
|------|------|-----------|
| P0 | `/mnt/d` 정리, 마운트 umask 007, `gray` → `www-data` | **완료(2026-10-16)** |
| P1 | 리눅스 에이전트 설치·승인, 툴셋. Server·Device 구성 리눅스 전환 | LMCore·Server Tests·Drift 녹색, 폰 재페어링·Deploy 성공 |
| P2 | Horde 서버 compose 기동 | MAGI·MAGI-LAPTOP 워커가 Horde에 붙음 |
| P3 | 게임 서버: 계정·경로·유닛·ufw. 8801을 새 DB로 먼저 기동 → Deploy Server로 배포 확인 → 결정론 게이트 G1~G4 | 8801에서 확인 2종 통과, G1~G4 통과 |
| P4 | **전환 당일** (아래) | 실서버 트래픽이 .180으로 |
| P5 | Windows 에이전트 Engine·Project 설치, Client·UnrealEngine5 구성 이전, 엔진 전달 경로 | Editor Build·Package 녹색 |
| P6 | 문서: 위키(01·02·05·06·07·08·09·10), Config `CLAUDE.md` | 위키만 보고 운영 가능 |

### P4 전환 당일 순서 (서버 세션 제안)
1. 노트북 양 인스턴스(8800·8801) 정지
2. 8800 DB 파일(`lordmaker.db`) 복사 → `/var/lib/lordmaker/8800/` → `PRAGMA quick_check` (정지 상태 복사, WAL 미사용 전제)
3. `/etc/lordmaker/8800.env` 작성(사용자가 `WORKER_TOKEN` 이전)
4. Deploy Server(Instance=8800) — 확인 2종 통과
5. Caddy 업스트림 `host.docker.internal:8800`으로 전환, `/internal/*` 차단 확인(`/v1/debug/*`는 미결 6 결정에 따름)
6. 클라 세션에 통지: 서버 목록 Lan(`192.168.219.180:8800`)·Test(8801) 프로필 주소 갱신
7. 모바일 데이터로 외부 경로 확인(guest 로그인 → `/v1/compat` 200 → 던전 진입)
8. 노트북 게임 서버 정리(Horde 워커는 유지)

## 범위 밖

- 서버 배포 자동화(main 머지 트리거) — 리눅스 전환이 안정된 뒤 재논의.
- 엔진 설치형 빌드 크기 절감 — P5 이후.
- Shipping 패키징, 통합 봇 자동 실행 — 이전 설계와 같음.

## 미결 (확인 필요)

1. **Windows 에이전트 머신**: Engine·Project를 둘 PC, 같은 공유기 안인지(밖이면 VPN), 디스크 용량.
2. ~~배포 확인의 디버그 공성~~ → **해소(2026-10-16)**: `POST /internal/selfcheck`로 대체(4장 Deploy Server 7단계).
3. **Horde 이미지 출처**: ghcr 접근 가능 여부.
4. ~~G3 실행 방법~~ → **방식 확정(2026-10-16)**: 클라 `sweep_gate`(4장 표). 구현 완료 통지는 클라 세션이 직접. G2·G4 명령도 확정.
5. **Android Compile 체크아웃 분리 유지 여부**(Project 에이전트 디스크).
6. **프록시 `/v1/debug/*` 차단**: 2026-10-07 결정("테스트 기간 차단 안 함")을 이전 시점에 바꿀지 — 사용자 확인.
