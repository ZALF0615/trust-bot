# Trust

📲 Android 앱 알림을 감지하여 디스코드 웹훅으로 전송해주는 개인용 투명성 앱

## 기능
- 시스템 전체 알림 감지
- 지정한 앱 무시 기능
- 디스코드 웹훅을 통한 실시간 알림 전송
- 간단한 설정만으로 사용 가능

## 사용 방법
1. [Releases](https://github.com/ZALF0615/trust-bot/releases) 페이지에서 최신 APK 다운로드
2. Android에 설치 후 알림 접근 권한 부여
3. `Secrets.kt` 파일에 본인의 Webhook 주소 입력 후 빌드하거나, 공개된 릴리즈 버전 사용

> 🔒 이 앱은 공개 저장소에 있지만, 본인의 Webhook 주소는 개인적으로 설정해야 합니다.

## 설정 방법
```kotlin
// Secrets.kt
object Secrets {
    const val WEBHOOK_URL = "https://discord.com/api/webhooks/..."
}
