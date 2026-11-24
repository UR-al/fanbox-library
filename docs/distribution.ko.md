# Android와 Windows 배포

Google Play에는 본인 소유 업로드 키로 서명한 AAB가 필요합니다. Windows 설치 파일과 Android APK는 GitHub Releases에 첨부할 수 있습니다. 프로젝트 루트를 저장소 루트로 사용하세요. 기존 개인 데이터가 있는 작업 폴더 전체를 업로드하지 마세요.

## Android 서명

배포자 환경에 다음 네 환경 변수를 설정한 다음 `.\gradlew.bat :androidApp:assembleRelease :androidApp:bundleRelease`를 실행합니다. 키가 설정되지 않은 빌드는 서명되지 않은 릴리스 산출물을 만듭니다.

| 변수 | 값 |
| --- | --- |
| `FOLIO_UPLOAD_STORE` | 본인이 소유한 업로드 키 저장소의 절대 경로 |
| `FOLIO_STORE_PASSWORD` | 저장소 비밀번호 |
| `FOLIO_KEY_ALIAS` | 업로드 키 별칭 |
| `FOLIO_KEY_PASSWORD` | 키 비밀번호 |

출력 위치는 `androidApp/build/outputs/apk/release`와 `androidApp/build/outputs/bundle/release`입니다. 미리보기용 APK는 디버그 키와 별도 패키지 ID를 사용합니다. Play와 GitHub 설치본 사이에서 업데이트 호환성이 필요하면 동일한 앱 서명 인증서로 배포해야 합니다. Play App Signing을 사용할 경우 실제로 Play가 배포하는 APK의 인증서를 확인하세요.

## 배포자 확인

- **Google Play** — 배포자 계정과 패키지명, 연락처를 확정합니다. 앱의 실제 콘텐츠와 외부 사이트 접근에 맞춰 콘텐츠 등급, 대상 연령, 데이터 보안 양식을 작성하고 공개 가능한 개인정보 처리방침 URL을 등록합니다. 비공식 앱임을 스토어 설명에 표시합니다. 공개 이미지에는 배포 권한이 있는 테스트 자료를 사용합니다. [Google Play 개발자 정책](https://support.google.com/googleplay/android-developer/answer/18265346)
- **GitHub** — `Build app packages` 워크플로는 수동 실행으로 테스트 결과물과 설치 패키지를 생성합니다. 공개 릴리스에는 서명을 확인한 Android·Windows 파일과 SHA-256 목록을 첨부합니다. Windows용 MSI·EXE는 Authenticode 인증서를 가진 배포자가 서명할 수 있습니다.
- **FANBOX** — 현재 공개 범위는 공식 사이트 열기, 직접 저장한 파일 관리와 기기 간 보관함 비교입니다. FANBOX 서버의 게시물별·작가별 자동 저장을 홍보하기 전에는 각 연동에 대해 확인한 범위와 실제 검증 결과를 반영합니다.
