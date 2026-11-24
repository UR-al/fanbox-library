# FANBOX 보관함

Android와 Windows에서 FANBOX 공식 사이트를 열고, 직접 저장한 그림과 작업 파일을 작가·연도·게시물별로 보는 비공식 앱입니다. 각 기기는 독립적으로 작동합니다. 회원가입은 필요하지 않습니다.

[Windows 설치본과 Android 미리보기 APK 다운로드](https://github.com/UR-al/fanbox-library/releases/tag/v1.0.0)

## 처음 사용하기

1. Android에서는 APK를, Windows에서는 EXE 또는 MSI 한 가지를 설치합니다. Android 미리보기판은 정식판과 별도로 설치됩니다.
2. **FANBOX 열기**에서 공식 사이트에 로그인합니다. 게시물에서 보고 싶은 이미지를 열고 원본 표시를 선택해 저장하세요. ZIP 등 첨부는 게시물의 다운로드 링크를 사용합니다. 로그인과 다운로드는 기기의 브라우저가 처리합니다.
3. **파일 가져오기**에서 다운로드한 파일들을 함께 선택합니다. 게시물 주소를 함께 등록하면 같은 글에 나중에 가져온 파일도 모입니다. Android에서는 브라우저의 공유 메뉴로 게시물 주소를 보내거나 파일 앱에서 여러 파일을 선택하여 공유할 수 있습니다.
4. Windows의 **작가 폴더 연결**은 기존 파일을 제자리에서 색인합니다. 예를 들어 작가 폴더 아래의 `2025/작품명 [F123456]`을 읽으면 연도와 게시물 번호가 연결됩니다. 원본 위치를 옮겼다면 새 위치에서 해당 작가 폴더를 다시 연결하세요.

작가·연도·검색으로 작품을 찾고, 이미지는 확대하거나 넘겨볼 수 있습니다. 첨부 탭에서는 ZIP·PSD 등을 확인할 수 있습니다. PSD 편집과 ZIP 내부 열람에는 해당 파일을 지원하는 외부 앱이 필요합니다. 작품에 잘못 넣은 파일은 **작품으로 묶기**로 옮길 수 있습니다.

## 폰과 PC에서 선택한 작품 맞추기

1. 기기들을 같은 Wi-Fi 또는 Tailscale에 연결합니다. PC의 **기기 연결**에서 사용할 IP와 보낼 작품을 고릅니다. 폰의 자료를 받으려면 **이 PC로 받기**도 켭니다.
2. **연결 코드 만들기**를 누르고 폰에 코드를 붙여 넣습니다. 이 코드를 가진 기기는 연결이 열려 있는 동안 선택한 작품에 접근할 수 있습니다.
3. 폰에서 받기 또는 보내기를 고릅니다. 작가·제목으로 작품을 고르거나 파일 목록을 열어 개별 첨부만 선택할 수 있습니다. 받기 화면에서 **누락분 확인**을 실행하면 PC가 공유한 목록과 폰 보관함을 비교합니다. 변경된 판본은 기존 파일과 함께 보관합니다.
4. 완료 후 PC의 연결 창을 닫습니다. 연결 코드는 30분 후 만료됩니다. Windows 방화벽이 연결을 묻는다면 사용 중인 사설망에만 허용하세요.

휴대폰과 PC 간 비교는 두 기기가 가진 자료를 대상으로 합니다. FANBOX 전체에서 놓친 글이나 아직 다운로드한 적 없는 첨부까지 판정하려면 서비스가 제공하는 게시물·파일 목록이 필요합니다. 공식 사이트에서 한 글 전체 또는 한 작가의 누락분을 자동 다운로드하는 기능은 연동 방식 확인 전까지 제공하지 않습니다. [연동 조건과 pixiv 문의 초안](docs/provider-integration.ko.md)

## 빌드

JDK 17, Android SDK Platform 36과 Build Tools 36.0.0이 필요합니다. Windows 설치본은 Windows x64에서 전체 JDK의 `jpackage`와 WiX 3.14 도구를 사용해 빌드합니다. WiX의 `candle.exe`와 `light.exe` 디렉터리를 PATH에 추가하고 프로젝트 루트에서 실행하세요.

```powershell
.\gradlew.bat :shared:test :transfer:test :desktopApp:test
.\gradlew.bat :desktopApp:run
.\gradlew.bat :androidApp:assembleDebug :androidApp:bundleRelease
.\gradlew.bat :desktopApp:packageExe :desktopApp:packageMsi
```

연결된 Android 기기나 에뮬레이터에서 `:androidApp:connectedDebugAndroidTest`로 오프라인 열람과 브라우저 열기를 검증할 수 있습니다. 루트에는 Gradle Wrapper와 배포 파일을 빌드하는 GitHub Actions가 포함돼 있습니다. [배포 방법](docs/distribution.ko.md) · [개인정보 처리 안내](docs/privacy.ko.md)
