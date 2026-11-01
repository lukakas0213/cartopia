# 로드캔버스 (RoadCanvas)

안전하게 달린 만큼 새까만 지도가 블록 단위로 열리고, 그 땅에 목장과 숲, 내 집을 가꾸는 Pleos Connect 차량용 앱입니다.

## 주요 기능 (1차 제출 범위)

- **블록 단위 지도:** 처음에는 화면 전체가 검은색입니다. 지나간 길은 흰 선으로 남고, 흰 선이 테두리를 따라 약 35m 이상 지나간 블록(도로로 둘러싸인 면)만 회색 지도로 열립니다.
- **안전 점수와 마일:** 급가속·급제동, 방향지시등 없이 방향 전환, 안전벨트 미착용을 감점합니다. 주행이 끝나면 `거리(km) × 안전 배율(0.5~1.5) + 새 블록 × 2` 마일을 받습니다. 차량 오도미터 거리와 GPS 거리가 30% 넘게 다르면 마일을 지급하지 않습니다.
- **주행 중 잠금:** 기어가 D이면 마을 화면이 열리지 않고, P로 바꾸면 결과 화면이 나옵니다.
- **3D 내 마을:** 열린 블록 위에 응봉산 양 목장, 서울숲 사슴 숲, 중랑천 낚시터, 내 집이 블록 모양과 크기에 맞춰 놓입니다(WebView + three.js, 오프라인 동작).
- **음성 안내:** 새 블록이 열릴 때와 주행이 끝날 때 짧게 안내합니다.

## 사용 SDK

| SDK | 버전 | 용도 |
| --- | --- | --- |
| Vehicle | 2.3.2 | 기어, 브레이크 페달, 방향지시등, 안전벨트 경고, 주행거리 |
| FusedLocation | 1.1.3 | 차량 위치 |
| TextToSpeech | 3.3.1 | 음성 안내 (실패하면 Android TTS로 대체) |

## 실행 방법

1. Pleos Connect 에뮬레이터를 실행합니다 ([설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)).
2. APK를 설치합니다.
   ```
   adb install -r apk/RoadCanvas_release.apk
   ```
3. 앱을 실행하고, 오른쪽 시트의 **시연 주행 시작**을 누릅니다. 왕십리역 → 응봉역 → 성수동1가 경로를 4배속으로 재생하면서 블록이 열리고 점수가 매겨집니다.
4. 주행이 끝나면 결과 화면에서 **내 마을로 가기**를 눌러 3D 마을을 봅니다.

차량 신호가 연결된 환경에서는 기어를 D로 바꾸면 실제 위치로 주행 기록이 시작되고, P로 바꾸면 끝납니다.

## 테스트 환경

- Pleos Connect 에뮬레이터 (Android 14, 2560×1440)
- 시연 지역: 서울 성동구 응봉동·성수동1가 일대. 지도와 블록 데이터는 앱에 들어 있어서 네트워크 없이 동작합니다.

## 빌드

```
./gradlew :app:assembleRelease      # APK: app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest    # 블록 열기, 좌표 변환, 마일, 안전 점수 단위 테스트
```

## 데이터 준비 스크립트

`tools/`에는 OSM 데이터를 받아 지도 이미지와 블록 데이터를 만드는 Python 스크립트가 있습니다(shapely, Pillow 필요). `prepare_app_assets.py`가 `app/src/main/assets/map/`의 파일을 만듭니다.

## 데이터 출처

지도 데이터 © OpenStreetMap contributors (ODbL). 경로 계산 OSRM.
