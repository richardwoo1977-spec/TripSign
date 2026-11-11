<!-- ver 1.2.2 | 2026-11-11 16:01 -->
# 트립사인 (TripSign)

**차가 직접 기록하고 서명하는 업무용승용차 운행기록부** — Pleos Connect 차량용 앱 (팀: 주식회사 비욘드스파크)

## APK
- 위치: [`apk/BeyondSpark_TripSign_release.apk`](apk/BeyondSpark_TripSign_release.apk)
- 버전: 0.5.2 (빌드 13), release 빌드
- 서명: 공모전 설치용으로 로컬 디버그 키로 서명했습니다. 같은 키로 서명한 이전 빌드 위에 덮어 설치할 수 있습니다.

## 시연 영상
- [`docs/demo_TripSign.mp4`](docs/demo_TripSign.mp4) (4분 44초, 1920×1080)
- Pleos Connect 에뮬레이터와 실제 폰 화면을 녹화했습니다. 온보딩 → 가입 본인확인 → 차량 정보 → 시동 음성 → 정상 운행 → 로그인 없는 출발(미인증 운행) → 폰 사후 확인 → 관리자 구분 → QR로 보내기 → 폰 검증 페이지 순서입니다.
- 대기 · 주행 시간은 배속하거나 줄였고, 개인정보가 보이는 장면은 잘라 냈습니다.

## 무엇을 하나요
- **완전 자동 운행 감지**: 기어 P → D/R이면 출발, P로 3초 유지되면 도착. 이동 없는 기어 조작은 걸러냅니다.
- **계기판 거리 그대로 기록**: Pleos Vehicle SDK의 Odometer 값으로 ⑤ 주행 전 · ⑥ 주행 후 거리를 남기고, 속도 적분 거리도 함께 저장합니다.
- **운전자 확인**: 시동이 걸리면 화면과 음성(Pleos TTS)으로 운전자를 고르라고 안내합니다. 아무도 로그인하지 않고 출발한 운행은 **미인증 운행**으로 기록됩니다.
- **폰 본인확인**: 가입과 미인증 운행의 사후 확인은 차 화면의 QR → 폰 → 메일 인증번호 → 폰에 뜬 확인번호를 차에 입력하는 순서로 합니다.
- **원터치 구분**: 도착하면 업무 / 출퇴근 / 개인 중 한 번만 누릅니다. 미인증 운행의 구분은 관리자가 정합니다.
- **무결성 기록**: 모든 기록은 SHA-256 해시로 앞뒤가 연결되고 기기 Keystore의 EC P-256 키로 서명됩니다. 수정·삭제 기능이 없고, 바뀌는 내용은 새 기록으로 덧붙습니다.
- **미기록 구간**: 앱이 기록하지 않은 사이 계기판 거리가 늘면 그 거리를 '미기록 구간'으로 남기고 업무로 집계하지 않습니다.
- **국세청 서식 그대로**: 홈 화면이 업무용승용차 운행기록부 표(③~⑨)와 사업연도 ⑪⑫⑬입니다. 거리는 계기판 방식 정수 km(⑦ = ⑥ − ⑤)입니다.
- **QR로 보내기**: 기록을 QR 여러 장으로 폰에 보내고, 폰 검증 페이지(https://tripsign.beyondspark.kr)에서 서명을 검증한 뒤 국세청 서식 엑셀로 내려받습니다. 운행 기록은 서버로 보내지 않습니다.

## 실행 방법
### 1. 테스트 환경
| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect 에뮬레이터 v2.1.0 (AVD `Pleos_Connect_v2.1.0`, Android Automotive, 앱은 사용자 10으로 실행) |
| 화면 규격 | 2560 × 1440 px, 160 dpi (`adb shell wm size` · `wm density` 결과) |
| 차량 신호 | Pleos Virtual Console (`http://127.0.0.1:8088`, 기어 · 속도 · 계기판 거리) |
| 폰 | 카메라와 브라우저가 있는 스마트폰 (본인확인 · 검증 페이지) |
| 네트워크 | 에뮬레이터와 폰 모두 인터넷 연결 (본인확인 서버 `tripsign.beyondspark.kr`) |

### 2. 설치와 권한
```
adb install -r apk/BeyondSpark_TripSign_release.apk
adb shell pm grant --user 10 io.tripsign.app android.car.permission.CAR_SPEED
adb shell pm grant --user 10 io.tripsign.app android.permission.POST_NOTIFICATIONS
adb shell am start --user 10 -n io.tripsign.app/.ui.MainActivity
```
속도 · 알림 권한은 앱 첫 화면에서도 요청합니다. 위 `pm grant`는 같은 권한을 미리 켜는 명령입니다.

### 3. 시연 순서
1. 온보딩을 넘기고 **가입하기**: 부서 · 이름 · 4자리 번호를 넣고, 차 화면의 QR을 폰으로 찍어 메일 인증을 마칩니다. 첫 가입자가 관리자입니다.
2. **설정 → 운행기록부 정보**에서 ① 차종 · ② 자동차등록번호를 넣습니다 (정차 중에만).
3. 운전자를 고르고 4자리 번호로 로그인한 뒤, Virtual Console에서 기어를 D로 바꾸고 속도와 계기판 거리를 올립니다. 기어를 P로 바꾸면 도착 카드가 뜹니다.
4. 로그인하지 않고 출발하면 음성 경고와 함께 미인증 운행으로 기록됩니다. 도착 후 **미인증 운행 확인**에서 실제 운전자가 폰으로 본인확인을 합니다.
5. 운행기록부에서 **QR로 보내기** → 폰으로 찍기 → 검증 페이지에서 결과 확인과 엑셀 받기.

Virtual Console 화면은 계기판 거리를 10 km 단위로만 바꿀 수 있습니다. 1 km 단위 시연에는 `tools/demo/`의 스크립트를 씁니다 (`adb forward tcp:8088 tcp:8088` 필요).
```
bash tools/demo/odo.sh 12340        # 계기판을 12,340 km로 (값을 줄일 수는 없음)
bash tools/demo/drive.sh 40 90      # 시속 90 km로 40초 주행 = 1 km, 그 뒤 P
```

## 빌드
- Android Studio, JDK 17, Android SDK 35 (`platforms/android-35/optional/android.car.jar` 필요)
- Pleos SDK (Pleos Nexus 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`)
  - `ai.pleos.playground:Vehicle:2.3.2` (주행거리)
  - `ai.pleos.playground:TextToSpeech:3.3.1` (음성 안내)
- 단위 테스트: `./gradlew testDebugUnitTest`
- 릴리스 APK: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

## 구조
| 경로 | 내용 |
|---|---|
| `app/src/main/java/io/tripsign/app/core/` | 순수 Kotlin: 운행 판정 엔진, 해시 체인, 운행기록부(정수 규칙), 내보내기 · QR, 본인확인 증명 |
| `app/src/main/java/io/tripsign/app/car/` | 차량 신호: 기어 · 속도(Android Car API), 주행거리(Pleos Vehicle SDK) |
| `app/src/main/java/io/tripsign/app/data/` | 추가 전용 원장(`ledger.jsonl`), Keystore 서명, 설정, 진단 로그 |
| `app/src/main/java/io/tripsign/app/service/` | 포그라운드 기록 서비스, 운전자 확인 음성, 도착 알림, 부팅 시 자동 시작 |
| `app/src/main/java/io/tripsign/app/ui/` | Compose 화면: 운행기록부, 로그인 · 가입, 미인증 운행 확인, QR로 보내기, 검색, 설정 |
| `app/src/test/` | 단위 테스트 (운행 엔진, 해시 체인, 운행기록부, 내보내기, 본인확인 규칙 등) |
| `verify/` | 폰 검증 페이지 원본(`index.src.html`)과 빌드 결과(`index.html`, 한 파일) |
| `server/` | 본인확인 중계 서버(`tripsign_auth.py`)와 폰 본인확인 페이지(`www/auth/`). 메일 발송 키는 서버 환경 파일에만 있고 저장소에는 없습니다 |
| `tools/` | 시연 스크립트(`demo/`), 검증 페이지 빌드 · 시뮬레이션 도구 |

## 사용한 차량 데이터와 권한
| 데이터 | API | 권한 |
|---|---|---|
| 기어 | `VehiclePropertyIds.GEAR_SELECTION` | `android.car.permission.CAR_POWERTRAIN` |
| 속도 | `VehiclePropertyIds.PERF_VEHICLE_SPEED` | `android.car.permission.CAR_SPEED` (실행 중 요청) |
| 주행거리 | Pleos `Odometer.getCurrentDistanceDriven` / `registerDistanceDriven` | `pleos.car.permission.CAR_MILEAGE` |
| 음성 안내 | Pleos `OnDeviceStreamTts` | `pleos.car.permission.TTS_SERVICE` |

## 진단 로그 (에뮬레이터 시험용)
```
adb shell run-as io.tripsign.app --user 10 cat files/tripsign_log.txt
```
