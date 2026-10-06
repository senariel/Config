# LordMaker CI 구성 분리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `DevPub / LordMaker`를 Client(Editor Build·Android Compile·Core Gates·Package)와 Server(LMCore·Server Tests·Static Data Drift) 하위 프로젝트로 재구성한다.

**Architecture:** 설정은 Config 저장소 `.teamcity-lordmaker/settings.kts` 한 파일(Kotlin DSL, settingsPath로 연결). VCS 루트·공통 파라미터는 LordMaker 프로젝트 레벨, 하위 프로젝트는 구성만 가진다. 각 빌드 단계 스크립트는 PowerShell(클라)·cmd(서버)이며, 검증은 TeamCity 실빌드로 한다.

**Tech Stack:** TeamCity 2026.2 Kotlin DSL(2025.11), UE 5.8.3 설치형 엔진(UBT Build.bat, RunUAT BuildCookRun), Git Bash, CMake/Ninja(VS BuildTools 18), Python 3.14.5, pytest.

설계: `docs/superpowers/specs/2026-10-06-lordmaker-ci-structure-design.md`

---

## 파일

- Modify: `.teamcity-lordmaker/settings.kts` — 전체 재작성(프로젝트·VCS 루트·7개 구성)
- Modify: `CLAUDE.md` — LordMaker 섹션 갱신
- 빌드 단계 스크립트 원본(작업용, 저장소 밖): `steps/ubt.ps1`, `gates.ps1`, `package.ps1`, `pycheck.cmd`, `lmcore.cmd`, `servertests.cmd`, `drift.cmd` → settings.kts 원시 문자열에 삽입(PowerShell의 `$`는 `${'$'}`로 이스케이프)

## 구성 ID (상대 ID → LordMaker_ 접두)

| 객체 | ID | 비고 |
|---|---|---|
| GameVcs / ServerVcs | LordMaker_GameVcs / LordMaker_ServerVcs | 토큰 `…61fab572…` 공유 |
| Client / Server | LordMaker_Client / LordMaker_Server | 하위 프로젝트 |
| EditorBuild / AndroidCompile / CoreGates / Package | LordMaker_EditorBuild … LordMaker_Package | Package는 기존 ID 유지(이력 보존) |
| LMCore / ServerTests / StaticDataDrift | LordMaker_LMCore … | |

---

### Task 1: settings.kts 작성

- [ ] **Step 1:** 템플릿(`settings.tmpl.kts`)에 스크립트를 삽입해 `.teamcity-lordmaker/settings.kts` 생성.
  - UBT 스크립트(`ubt.ps1`)는 `@TARGET@ @PLATFORM@`을 `LordMakerEditor Win64` / `LordMaker Android`로 치환해 두 번 사용:
    ```powershell
    $build   = Join-Path $env:UE5_ENGINE_ROOT 'Engine\Build\BatchFiles\Build.bat'
    $project = Join-Path (Get-Location) 'LordMaker.uproject'
    & $build @TARGET@ @PLATFORM@ Development "-project=$project" -waitmutex
    if ($LASTEXITCODE -ne 0) { Write-Host "##teamcity[buildProblem description='Build.bat @TARGET@ @PLATFORM@ failed (exit $LASTEXITCODE)']"; exit $LASTEXITCODE }
    ```
  - Package: 플랫폼별 `-archivedirectory=Archive\<plat>` (#29 아티팩트 미게시 수정 — UE `DeploymentContext.cs`는 경로에 `Win64` 구성요소가 있으면 `Windows` 하위 폴더를 붙이지 않음), 아티팩트 규칙 `Archive/Win64`, `Archive/Android`, `Archive/Android/*.apk`.
  - Core Gates: 기준선 = `%teamcity.serverUrl%/httpAuth/repository/download/%system.teamcity.buildType.id%/.lastSuccessful/gates.zip?branch=main`(빌드 자격 증명, 없으면 비교 생략) → `bash -c "Tools/core_gates.sh gates <baseline> 2>&1 | tee gates-run.log"` → summary의 `exit=` ≠ 0이면 실패, `^DIFF` 줄은 WARNING 메시지.
  - LMCore: `call vcvars64` → `cmake -S Core -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DPython_EXECUTABLE=%env.LM_PYTHON%` → `cmake --build build` → `ctest --output-on-failure`(`PYTHONUTF8=1`), 아티팩트 `build/lmcore*.pyd`.
  - Server Tests: LMCore 마지막 성공 main의 `.pyd` 아티팩트 의존, `LORDMAKER_FIXTURES=%teamcity.build.checkoutDir%\lordmaker-fixtures`, venv → `pip install -e ".[dev]"` → `pytest tests -q -rs --junitxml=test-results.xml` + JUnit 리포트.
  - Static Data Drift: `python server\tools\check_static_drift.py --client client --server server`, 종료 코드 1=드리프트, 2=체크아웃 문제.
- [ ] **Step 2: 정적 검사**
  - Run: `python lm/check_kts.py .teamcity-lordmaker/settings.kts`
  - Expected: `unescaped $ refs: []`, `illegal escapes: []`
  - Run: PowerShell `Parser::ParseInput`로 `ubt.ps1`·`gates.ps1`·`package.ps1` 파싱
  - Expected: 각 0 errors
  - cmd 스크립트에 TeamCity 파라미터 외 `%…%` 참조가 없는지 grep (TeamCity가 파라미터로 해석함).

### Task 2: 반영 및 적용 확인

- [ ] **Step 1:** CLAUDE.md LordMaker 섹션을 새 구조로 갱신.
- [ ] **Step 2:** 커밋·푸시 (`git push origin HEAD:main`).
- [ ] **Step 3:** `GET /app/rest/projects/id:LordMaker/versionedSettings/status` 가 해당 커밋으로 `type=info`(applied)인지 확인. 실패 시 `/admin/editProject.html?projectId=LordMaker&tab=versionedSettings` 의 `settings.kts [줄:열]` 오류를 읽어 수정.
- [ ] **Step 4:** `affectedProject:LordMaker` 구성 목록에 7개 ID가 모두 있는지 확인.

### Task 3: Client 검증

- [ ] **Step 1:** Core Gates를 main으로 실행(Editor Build 체인 포함). Expected: Editor Build 성공 → Core Gates 성공, "baseline 없음 - 비교 생략", 아티팩트 `gates.zip`.
- [ ] **Step 2:** Android Compile을 `fix/android-compile`(54c8ae4)로 실행. Expected: 성공 → 결과를 로드메이커 세션에 전달(PR #99 머지 판단용).
- [ ] **Step 3:** PR #99 머지 후 Package가 main 트리거로 실행됨. Expected: Win64·Android 모두 성공, 아티팩트 `LordMaker-Win64-Development.zip`, `LordMaker-Android-Development.zip`, `apk/*.apk`.
- [ ] **Step 4:** Core Gates 두 번째 main 실행에서 기준선 다운로드 후 DIFF 0(SAME) 확인.

### Task 4: Server 검증 (Python 3.14.5 설치 후)

- [ ] **Step 1:** LMCore 실행. Expected: cmake/ninja 발견, ctest 통과, 아티팩트 `lmcore.cp314-win_amd64.pyd`. cmake/ninja 없으면 buildProblem("C++ CMake tools for Windows" 컴포넌트 설치 안내).
- [ ] **Step 2:** Server Tests 실행. Expected: 56건 실행, lmcore 관련 skip 0.
- [ ] **Step 3:** Static Data Drift 수동 실행. Expected: exit 0("[OK] 14종 동일").
- [ ] **Step 4:** 결과를 로드메이커 서버 세션에 전달.
