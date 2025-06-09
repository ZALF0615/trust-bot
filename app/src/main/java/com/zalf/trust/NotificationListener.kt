package com.zalf.trust

// 안드로이드 알림 정보를 감시하고 처리하기 위한 기본 클래스
import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.os.Handler
import android.os.Looper

// 디스코드로 메시지를 보내기 위해 필요한 HTTP 통신 라이브러리
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType

// JSON으로 메시지를 만들기 위한 도구
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

import java.io.IOException

// 시간 포맷을 위한 도구
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

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
        "com.google.android.youtube" to "YouTube",
        "com.discord" to "Discord",
        "viva.republica.toss" to "Toss"
    )

    // 마지막 실패 시각을 기록하는 변수
    private var lastFailedTime: Long? = null

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
            val info = packageManager.getApplicationInfo(packageName, 0)
            val label = packageManager.getApplicationLabel(info)?.toString()
            if (!label.isNullOrBlank()) {
                label
            } else {
                Log.w("🛡️Trust/Label", "⚠️ 앱 이름이 비어 있어 packageName 사용: $packageName")
                packageName
            }
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
        val finalTitle = if (title.isBlank()) "-" else title
        val finalText = if (text.isBlank()) "-" else text

        // 알림 발생 시각을 'HH:mm' 포맷으로 변환 (예: 18:42)
        val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(Date(sbn.postTime))

        // 디스코드로 보낼 메시지 형태 (앱 이름 옆에 알림 시간 포함)
        val message = """
            ========================================
            [$appLabel] $timestamp
             **$finalTitle**
              $finalText
        """.trimIndent()

        Log.d("🛡️Trust/Notify", "📦 감지됨: $message")

        // 알림에 포함된 전체 정보 키-값을 출력 (디버깅용)
        for (key in extras.keySet()) {
            // Log.d("🛡️Trust/Extras", "🔍 $key = ${extras.get(key)}")
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
    private var hasNotifiedStart = false

    override fun onListenerConnected() {
        super.onListenerConnected()

        if (!hasNotifiedStart) {
            hasNotifiedStart = true
            val timestamp = getCurrentTimestamp()

            // 🔻 마지막 종료 시각 가져오기
            val prefs = getSharedPreferences("trustbot", MODE_PRIVATE)
            val lastStopped = prefs.getLong("last_stopped_time", -1)
            val now = System.currentTimeMillis()

            val gapMessage = if (lastStopped > 0) {
                val gapMillis = now - lastStopped
                val minutes = gapMillis / 60_000
                val seconds = (gapMillis % 60_000) / 1000
                "💤 비활성 기간: ${minutes}분 ${seconds}초"
            } else null

            val mainMessage = "@everyone ✅ TrustBot 시작됨 ($timestamp)"

            Log.d("🛡️Trust/Start", "NotificationListener 연결됨")

            // 🔻 전송
            sendToDiscord(mainMessage)
            gapMessage?.let { sendToDiscord(it) }
        } else {
            Log.d("🛡️Trust/Start", "이미 시작 메시지를 전송했으므로 생략")
        }
    }


    // 서비스가 종료될 때 실행됨
    private var hasNotifiedStop = false

    override fun onDestroy() {
        super.onDestroy()
        if (!hasNotifiedStop) {
            hasNotifiedStop = true
            val timestamp = getCurrentTimestamp()
            val message = "@everyone 🛑 TrustBot 종료됨 ($timestamp)"
            Log.w("🛡️Trust/Destroy", message)
            sendToDiscord(message)

            // 🔻 종료 시각 저장 (밀리초 단위)
            val prefs = getSharedPreferences("trustbot", MODE_PRIVATE)
            prefs.edit().putLong("last_stopped_time", System.currentTimeMillis()).apply()
        }
    }


    // 디스코드 웹훅으로 메시지를 보내는 함수
    private fun sendToDiscord(message: String, retryCount: Int = 0) {
        val client = OkHttpClient()
        val gson = Gson()

        val payload = DiscordMessage(username = "TrustBot", content = message)
        val json = gson.toJson(payload)
        val body = RequestBody.create("application/json".toMediaType(), json)

        val request = Request.Builder()
            .url(webhookUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("🛡️Trust/Error", "디스코드 전송 실패: ${e.message}")

                if (retryCount < 2) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendToDiscord(message, retryCount + 1)
                    }, 10_000)
                } else {
                    // 최종 실패 시 저장
                    val prefs = getSharedPreferences("trustbot", MODE_PRIVATE)
                    prefs.edit().putString("pending_message", message).apply()
                    Log.w("🛡️Trust/Error", "📦 전송 실패 메시지 저장됨")
                }
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

    private fun getCurrentTimestamp(): String {
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(now))
    }

}

class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)

        val isConnected = capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        val prefs = context.getSharedPreferences("trustbot", Context.MODE_PRIVATE)
        val wasConnected = prefs.getBoolean("was_connected", true)

        if (isConnected && !wasConnected) {
            // 인터넷 복구됨
            val timestamp = getCurrentTimestamp()
            val message = "@everyon 🔌 인터넷 복구됨 ($timestamp)"
            Log.i("🛡️Trust/Network", message)
            NotificationSender.send(context, message)

            // 실패했던 메시지가 있으면 다시 보냄
            val pending = prefs.getString("pending_message", null)
            if (pending != null) {
                NotificationSender.send(context, pending)
                prefs.edit().remove("pending_message").apply()
            }
        } else if (!isConnected && wasConnected) {
            // 인터넷 끊김
            val timestamp = getCurrentTimestamp()
            val message = "@everyon ❌ 인터넷 끊김 ($timestamp)"
            Log.w("🛡️Trust/Network", message)
            NotificationSender.send(context, message)
        }

        prefs.edit().putBoolean("was_connected", isConnected).apply()
    }

    private fun getCurrentTimestamp(): String {
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(now))
    }
}


object NotificationSender {
    fun send(context: Context, message: String) {
        val client = OkHttpClient()
        val gson = Gson()
        val json = gson.toJson(NotificationListener.DiscordMessage("TrustBot", message))
        val body = RequestBody.create("application/json".toMediaType(), json)

        val request = Request.Builder()
            .url(Secrets.webhookUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("🛡️Trust/Recover", "❌ 복구 전송 실패: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d("🛡️Trust/Recover", "✅ 복구 전송 성공: ${response.code}")
            }
        })
    }
}
