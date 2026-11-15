# 대리 인계 카드

대리운전으로 차를 맡기고 돌려받을 때 전조등·창문·연료 같은 차 상태와 기사가 말한 주차 위치를 기록해, 차주와 기사에게 같은 인계 카드를 보내는 차량 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작의 시제품 소스입니다. Pleos Connect 에뮬레이터에서 동작을 확인했습니다.

## APK 위치
- release APK: [`apk/모연_대리인계카드_release.apk`](apk/모연_대리인계카드_release.apk)
- 시연 영상(에뮬레이터 화면 녹화): 이 저장소의 [Releases](../../releases) 에 있는 `모연_대리인계카드_시연.mp4`

## 테스트 환경
- Pleos Connect 에뮬레이터 v2.1.0 (Android API 34, 화면 2560×1440) — 설치 안내: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
- Android Studio, JDK 17, Gradle Wrapper(포함), Android Gradle Plugin 8.7.0, Kotlin 2.1.0, minSdk 28 · targetSdk 34
- Pleos Playground SDK: Vehicle 2.3.2 · NaviHelper 3.0.5 · TextToSpeech 3.3.1 · SpeechToText 3.3.1 · FusedLocation 1.1.3
  - 공개 Maven `https://nexus-playground.pleos.ai/repository/maven-releases/` 에서 받습니다(계정 필요 없음).

## 실행 방법
1. Pleos Connect 에뮬레이터를 켭니다.
2. APK 를 설치합니다: `adb install -r apk/모연_대리인계카드_release.apk`
3. 위치 권한(처음 한 번): `adb shell pm grant --user 10 dev.pleos3.valet pleos.car.permission.FUSED_LOCATION`
4. 앱 목록에서 «대리 인계 카드»을 실행합니다.

직접 빌드할 때:
```
./gradlew :valet:assembleDebug      # 디버그 빌드(에뮬레이터 시험 입력을 받음)
./gradlew :valet:assembleRelease    # release 빌드(서명 설정은 각자)
./gradlew test                            # 단위 테스트
```
`local.properties` 에 Android SDK 경로(`sdk.dir=...`)가 있어야 합니다. 경로에 한글이 있으면 `"-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=COMPAT"` 를 붙여 실행합니다.

## 필요한 설정
- 앱이 쓰는 Pleos 권한은 `AndroidManifest.xml` 에 적혀 있고, 에뮬레이터에서는 설치하면 허용됩니다.
- 디버그 빌드는 에뮬레이터가 주지 않는 차 신호를 시험 입력(방송)으로 받습니다. release 빌드는 실제 SDK 값만 씁니다.

## 구성
- 모듈: `:car` · `:valet`
- 앱 ID: `dev.pleos3.valet`

## 지금 되는 것과 안 되는 것
- 음성 인식(SpeechToText)은 에뮬레이터에서 합성 음성을 가상 마이크에 넣어 확인했습니다. 사람 목소리·실차에서는 확인하지 못했습니다.
- 인계 카드는 차 안 미리보기로 보여 줍니다. 서버·문자·휴대폰 웹 카드는 아직 없습니다.
- 경고등·연료 %·문 잠김은 에뮬레이터가 주지 않아, 디버그 빌드의 시험 입력으로 확인했습니다.
