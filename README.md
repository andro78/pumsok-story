# 품속동화
엄마 아빠 목소리로 들려주는 동화. 스마트폰·태블릿용 Android 앱.

## 현재 범위
- 화면 너비에 따른 단일/두 열 UI
- DRM 없는 EPUB의 OPF spine 순서에 따른 본문 가져오기 및 기본 샘플 동화 2편 탑재
- 앱 내부 EPUB 보관 및 마지막 책 다시 열기
- 마이크 권한 요청, 부모님 참조 녹음과 대본 저장, 녹음 재생/삭제
- 기본 Android TTS로 문단 낭독 및 읽던 문단 저장
- 앱 시작 시 d32.org `version.json` 확인을 통한 자체 업데이트 및 직접 설치 (`Updater.kt`)
- 음성 복제 엔진 인터페이스와 미지원 상태 표시

**현재 기본 TTS는 부모님 목소리를 복제하지 않습니다.**
부모님 음성 복제, 결제, 계정, 서버 동기화, 백그라운드 재생은 후속 구현입니다.
본 버전은 내부 개발용이며 유료 출시용이 아닙니다. 녹음·책은 외부로 업로드하지 않습니다.

## 빌드 및 배포
Android Studio에서 열고 JDK 17 / SDK 35를 설치합니다.

```sh
# 테스트 및 디버그 빌드
./gradlew testDebugUnitTest assembleDebug

# 배포용 릴리스 패키지 생성 (APK & AAB)
./gradlew assembleRelease bundleRelease
```

- 산출물: `app/build/outputs/apk/release/app-release.apk`, `app/build/outputs/bundle/release/app-release.aab`
- 배포 페이지: [d32.org 품속동화](https://d32.org/pumsok/) (APK 및 Play 업로드용 AAB 다운로드 제공)
- 스토어 출시 문서: [`docs/play-store-release-guide.md`](file:///srv/nas/projects/pumsok-story/docs/play-store-release-guide.md)

## 기기 검증
폰 세로/가로, 태블릿 및 분할 화면에서 확인합니다.
마이크 거절, 녹음 중 앱 이탈, 빈/손상 EPUB, TTS 미설치, 앱 재실행을 확인합니다.
오디오북 복제 모델은 모델 재배포·상업 이용 조건 및 실제 한국어 ARM64 추론 속도 확인 후 선정합니다.
