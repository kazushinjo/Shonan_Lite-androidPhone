package com.shinjo.shonanandroid.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * PA_Power/PTTコントローラ(ESP32+W5500、hardware/W5500_PA_PTT_Control)へのHTTP通知。
 *
 * Shonan_Lite-RasPI5(pi5/gui/backend.pyの`_send_ptt_request()`・`_send_ptt_channel_state()`)と
 * 同じAPIを使う:
 * - `GET /tx?state=on|off` … 送信開始/終了。ESP32はPTT(GPIO27)だけを切り替える(12V電源には触れない)
 * - `GET /ch?idx=0&state=on|off` … 12V電源(GPIO26、Pluto含む)のON/OFF
 * 呼び出しはブロッキングなのでメインスレッドから呼ばないこと。
 */
object Esp32PttClient {
    /** ファームウェアのチャンネル番号(0=POWER=12V電源、1=PTT)。 */
    const val CHANNEL_POWER = 0

    private const val TIMEOUT_MS = 1500

    /** 送信開始(on=true)/終了を通知する。 */
    fun notifyTx(host: String, on: Boolean): Result<String> = get(host, "/tx?state=${state(on)}")

    /** 個別チャンネルを明示的にON/OFFする。 */
    fun setChannel(host: String, idx: Int, on: Boolean): Result<String> = get(host, "/ch?idx=$idx&state=${state(on)}")

    private fun state(on: Boolean) = if (on) "on" else "off"

    private fun get(host: String, path: String): Result<String> = runCatching {
        val conn = URL("http://$host$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code" }
            conn.inputStream.bufferedReader().use { it.readText() }.trim()
        } finally {
            conn.disconnect()
        }
    }
}
