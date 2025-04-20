package com.zalf.trust

// 안드로이드 알림 정보를 감시하고 처리하기 위한 기본 클래스
import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

// 디스코드로 메시지를 보내기 위해 필요한 HTTP 통신 라이브러리
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType

// JSON으로 메시지를 만들기 위한 도구
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

import java.io.IOException

class NotificationListener : NotificationListenerService() {

    // Webhook 주소는 외부 파일(Secrets.kt)에 숨겨서 관리함
    private val webhookUrl = Secrets.webhookUrl

    // 최근에 전송한 메시지를 기억해서, 같은 알림이 여러 번 가지 않도록 함
    private var lastMessage: String? = null

    // 무시하고 싶은 앱 이름들 (안드로이드 시스템 관련 알림 등)
    private val ignoredAppLabels = listOf(
        "Android 시스템",
        "시스템 UI"
    )

    // 앱 이름이 제대로 표시되지 않는 경우, 사람이 읽기 좋게 매핑해주는 테이블
    private val appNameOverrides = mapOf(
        "com.google.android.gm" to "Gmail",
        "com.kakao.talk" to "카카오톡",
        "com.google.android.youtube" to "YouTube"
    )

    // 새로운 알림이 도착하면 자동으로 실행되는 함수
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName // 예: com.kakao.talk
        val extras = sbn.notification.extras // 알림 속 추가 정보들

        // 알림 제목과 내용을 가져옴 (비어 있으면 기본값으로 처리)
        val titleRaw = extras.getCharSequence("android.title")?.toString() ?: ""
        val textRaw = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequenceArray("android.textLines")?.joinToString(" | ")
            ?: ""

        // 줄바꿈, 중복 공백 제거하여 정리
        val title = titleRaw.trim().replace(Regex("\\s+"), " ")
        val text = textRaw.trim().replace("\n", " ").replace(Regex("\\s+"), " ")

        Log.d("🛡️Trust/Debug", "📎 titleRaw='$titleRaw', title='$title'")

        // 앱 이름 가져오기 (문제가 생기면 수동 테이블에서 대체)
        val appLabel = try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            val fallback = appNameOverrides[packageName]
            if (fallback != null) {
                Log.w("🛡️Trust/Label", "⚠️ 앱 이름 직접 매핑 사용: $packageName → $fallback")
                fallback
            } else {
                Log.w("🛡️Trust/Label", "⚠️ 앱 이름 가져오기 실패: $packageName (${e.message})")
                packageName
            }
        }

        // 무시 목록에 있는 앱이면 그냥 넘김
        if (appLabel in ignoredAppLabels) {
            Log.d("🛡️Trust/Skip", "🚫 무시된 앱 이름: $appLabel")
            return
        }

        // 제목과 내용이 없으면 기본 문구로 대체
        val finalTitle = if (title.isBlank()) "(제목 없음)" else title
        val finalText = if (text.isBlank()) "(내용 없음)" else text

        // 디스코드로 보낼 메시지 형태
        val message = """
            앱) $appLabel
            제목) $finalTitle
            내용) $finalText
        """.trimIndent()

        Log.d("🛡️Trust/Notify", "📦 감지됨: $message")

        // 알림에 포함된 전체 정보 키-값을 출력 (디버깅용)
        for (key in extras.keySet()) {
            Log.d("🛡️Trust/Extras", "🔍 $key = ${extras.get(key)}")
        }

        // 이전과 같은 메시지면 전송하지 않음 (중복 방지)
        if (message == lastMessage) {
            Log.d("🛡️Trust/Skip", "🟡 동일한 알림, 전송 생략됨")
            return
        }
        lastMessage = message

        // 디스코드로 실제 전송
        sendToDiscord(message)
    }

    // 서비스가 처음 연결될 때 실행됨
    override fun onListenerConnected() {
        super.onListenerConnected()
        sendToDiscord("---------------------------- Trust 시작됨 ----------------------------\n")
    }

    // 서비스가 끊겼을 때 실행됨
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        sendToDiscord("Trust 중단됨\n")
    }

    // 디스코드 웹훅으로 메시지를 보내는 함수
    private fun sendToDiscord(message: String) {
        Log.d("🛡️Trust/Send", "📤 디스코드 전송 예정 메시지:\n$message")

        val client = OkHttpClient()
        val gson = Gson()

        // 디스코드 웹훅 형식에 맞게 JSON 만들기
        val payload = DiscordMessage(
            username = "TrustBot", // 디스코드에 표시될 이름
            content = message      // 실제 메시지 본문
        )

        val json = gson.toJson(payload)
        val body = RequestBody.create("application/json".toMediaType(), json)

        val request = Request.Builder()
            .url(webhookUrl)
            .post(body)
            .build()

        // 비동기 요청으로 디스코드에 메시지 전송
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("🛡️Trust/Error", "디스코드 전송 실패: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d("🛡️Trust/Send", "✅ 디스코드 전송 성공: ${response.code}")
            }
        })
    }

    // 디스코드 메시지를 만들기 위한 데이터 클래스
    data class DiscordMessage(
        @SerializedName("username") val username: String,
        @SerializedName("content") val content: String
    )
}
