# 카토피아 (Cartopia)

주행하며 발견한 동네에 집과 목장을 짓고 숲을 꾸미는 Pleos Connect 차량용 게임입니다.

## 주요 기능

- **3D 마을 지도:** 주행할 때와 꾸밀 때 같은 3D 마을 지도(WebView + three.js, 오프라인 동작)를 씁니다. 아직 발견하지 않은 곳은 하늘색으로 표시됩니다.
- **기어에 따른 자동 전환:** 기어가 D이면 주행 지도로 바뀌어 차를 따라가고, 달린 길에 닿은 구역이 자동으로 열립니다. 주행 중에는 꾸미기를 할 수 없습니다. P로 바꾸면 주행 기록이 저장되고 주행 결과를 확인할 수 있습니다.
- **마을 꾸미기:** 빈 구역을 눌러 숲, 꽃밭, 연못, 양 목장, 사슴 쉼터, 내 집, 카페, 빵집, 낚시터 중 하나를 고릅니다. 아이템은 구역의 모양과 크기에 맞춰 배치됩니다. 예를 들어 울타리는 구역 경계를 따라가고, 양과 나무 수는 면적에 비례합니다. 가격도 구역 크기(소형·중형·대형)에 따라 달라집니다.
- **아이템 관리:** 놓은 아이템은 다른 구역으로 옮기거나, 배치를 섞거나, 보관함에 넣을 수 있습니다. 되돌리기와 다시 하기도 됩니다.
- **생산과 업그레이드:** 양 목장과 낚시터는 주행 10 km마다 양털과 물고기를 만듭니다. 안전 점수가 90점 이상이면 생산량이 두 배가 됩니다. 녹지에 있는 양 목장은 20%를 추가로 생산합니다. 쌓인 생산물은 주차한 뒤 판매해 코인을 받고, 코인로 아이템 레벨을 올립니다. 꾸민 구역 수에 따라 마을 레벨이 오르고, 마을 Lv.4가 되면 카페와 빵집이 열립니다.
- **안전 점수와 코인:** 급가속·급제동, 방향지시등 없이 방향 전환, 안전벨트 미착용을 감점합니다. 주행이 끝나면 `거리(km) × 안전 배율(0.5~1.5) + 새 구역 × 2` 코인을 받습니다. 차량 오도미터 거리와 GPS 거리가 30% 넘게 다르면 코인을 지급하지 않습니다.
- **음성 안내:** 새 구역이 열릴 때와 주행이 끝날 때 짧게 안내합니다.

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
   adb install -r apk/Cartopia_release.apk
   ```
3. 앱을 실행하면 꾸미기 화면(기어 P)이 열립니다. 차량 신호가 없으면 왼쪽 아래의 **주행 체험하기**를 누릅니다. 현대자동차 강남대로 사옥(스케일타워) → 테헤란로 → 선릉로 → 청담 한강 경로를 재생하면서 주행 지도에서 구역이 열립니다.
4. 시연 주행이 끝나면 자동으로 저장되고 주행 요약이 나옵니다. **마을 꾸미기**를 누른 뒤 지도에서 + 표시가 있는 구역을 눌러 아이템을 배치합니다.

차량 신호가 연결된 환경에서는 기어를 D로 바꾸면 실제 위치로 주행 기록이 시작되고, P로 바꾸면 저장 후 꾸미기 화면이 열립니다.

## 테스트 환경

- Pleos Connect 에뮬레이터 (Android 14, 2560×1440)
- 지도 범위: 서울 전역. 지도와 구역 데이터는 앱에 들어 있어서 네트워크 없이 동작합니다.
- 체험 주행 경로: 현대자동차 강남대로 사옥(스케일타워) → 테헤란로 → 선릉로 → 청담 한강, 약 6.9 km. 이 경로는 시연용이며 지도 지원 범위를 제한하지 않습니다.

## 빌드

```
./gradlew :app:assembleRelease      # APK: app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest    # 구역 열기, 좌표 변환, 코인, 안전 점수 단위 테스트
```

## 데이터 준비 스크립트

`tools/`에는 OSM 데이터를 받아 지도 이미지와 구역 데이터를 만드는 Python 스크립트가 있습니다(shapely, Pillow, numpy 필요). 아래 순서로 실행합니다.

```
python tools/prepare_app_assets.py <osm> <view> app/src/main/assets/map          # base_map.png, blocks.json, routes.json
python tools/classify_blocks.py app/src/main/assets/map/base_map.png app/src/main/assets/map/blocks.json   # 녹지·물가·면적·섬 윤곽
python tools/cute_ground.py app/src/main/assets/map/base_map.png app/src/main/assets/village/ground.jpg --no-route --width 2048
```

## 데이터 출처

지도 데이터 © OpenStreetMap contributors (ODbL). 경로 계산 OSRM.
