package com.shinjo.shonanandroid

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.shinjo.shonanandroid.core.AppSettings
import com.shinjo.shonanandroid.diagnostics.FileLogger
import com.shinjo.shonanandroid.dvbs2.PlutoTuner
import com.shinjo.shonanandroid.rx.RxController
import com.shinjo.shonanandroid.tx.TxController
import com.shinjo.shonanandroid.tx.TxNetworkStats
import com.shinjo.shonanandroid.net.Esp32PttClient
import com.shinjo.shonanandroid.net.NetworkBinder
import com.shinjo.shonanandroid.net.PlutoSettingsWriter
import com.shinjo.shonanandroid.net.PlutoUdpTsController
import androidx.camera.core.Preview
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** libiio IIODの既定TCPポート。 */
private const val IIOD_PORT = 30431

/** アプリ起動からPA_Power/PTTコントローラの12V電源をONにするまでの待ち(Pi5版と同じ5秒)。 */
private const val PTT_CONTROLLER_POWER_ON_DELAY_MS = 5_000L

/**
 * libiioの`iio_create_context`は到達不能なIPに対してネイティブクラッシュ(SIGSEGV)する
 * ことがあるため、ネイティブセッションを開く前にTCP到達性を確認する。
 */
private suspend fun isPlutoReachable(ip: String, timeoutMs: Int = 2000): Boolean =
    withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(ip, IIOD_PORT), timeoutMs) }
            true
        } catch (e: IOException) {
            false
        }
    }

/**
 * アプリ全体の設定とTx/Rxコントローラを保持する -- iOS版`AppViewModel`(実質
 * `AppSettings`+`TxSessionController`+`RxSessionController`の保有側)に相当。
 *
 * [txController]/[rxController]はここで保有し[ProcessLifecycleOwner]に束縛する
 * (Compose destinationのライフサイクルではなく)ことで、画面遷移してもカメラ/
 * 送受信セッションが破棄されないようにする(他プロジェクトの`AppViewModel`と同じ理由)。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    init {
        // インターネット未接続のWi-Fi(Pluto運用時の典型)でもPlutoとの通信が
        // OSにルーティングされるよう、起動時にプロセスをWi-Fiへ明示バインドする。
        // TODO(デバッグ中): SSH(JSch/sshj)だけがTCP接続直後のバナー読み取りで無応答に
        // なる原因切り分けのため、一時的に無効化して検証する。
        // NetworkBinder.bindToWifi(getApplication())
    }

    var settings by mutableStateOf(SettingsStore.load(getApplication()))
        private set

    fun updateSettings(update: (AppSettings) -> AppSettings) {
        settings = update(settings)
        SettingsStore.save(getApplication(), settings)
    }

    val txController = TxController(getApplication(), ProcessLifecycleOwner.get())
    var isTransmitting by mutableStateOf(false)
    // startTX()の準備処理(HTTP/SSH通信で数秒かかる)が完了する前にボタンを連打すると、
    // 前の試行がまだ結果不明のまま次のコルーチンが並行して走ってしまうためのガード。
    var isPreparingTx by mutableStateOf(false)
        private set
    private val _txError = mutableStateOf<String?>(null)
    var txError: String?
        get() = _txError.value
        set(value) {
            _txError.value = value
            value?.let { FileLogger.log("TX_ERROR", it) }
        }

    /** 実運用の送信経路はPluto向けUDP-TS送信に統一する。 */
    val activeTxPreview: Preview get() = txController.preview
    val activeTxStats: TxNetworkStats get() = txController.stats

    /**
     * Pluto起動時リブート([[HomeScreen.runPlutoStartupReboot]])をアプリ起動後に一度だけ
     * 実行済みにするためのフラグ。Home画面のComposableは他画面へ遷移するたびにコンポジションから
     * 外れて`remember`状態が失われるため、このフラグを画面をまたいで生存するViewModel側に持たせないと、
     * Rx/Tx画面からホームへ戻るたびに毎回Plutoへreboot要求が飛び、復旧待ちの全画面表示で
     * 「ホームへ戻るボタンが効かない」ように見えるバグになる。
     */
    var didRunStartupPlutoReboot = false

    /** [[HomeScreen]]の起動時Pluto自動検出([[PlutoDiscoveryClient]])を一度だけ実行済みにする
     *  フラグ。didRunStartupPlutoRebootと同じ理由でViewModel側に持たせる。 */
    var didRunStartupPlutoDiscovery = false

    val rxController = RxController(getApplication())
    var isReceiving by mutableStateOf(false)
    var isPreparingRx by mutableStateOf(false)
        private set
    private val _rxError = mutableStateOf<String?>(null)
    var rxError: String?
        get() = _rxError.value
        set(value) {
            _rxError.value = value
            value?.let { FileLogger.log("RX_ERROR", it) }
        }

    init {
        txController.onError = { txError = it }
        rxController.onError = { rxError = it }
        // アプリ起動から5秒後に、PA_Power/PTTコントローラ経由で12V電源(Pluto含む)をONにする(Pi5版と同じ)。
        viewModelScope.launch {
            delay(PTT_CONTROLLER_POWER_ON_DELAY_MS)
            notifyPttController("POWER on") { Esp32PttClient.setChannel(it, Esp32PttClient.CHANNEL_POWER, on = true) }
        }
    }

    /**
     * PA_Power/PTTコントローラ(ESP32+W5500)へ通知する。設定で使用しない場合は何もしない。
     * 未接続・応答なしでも送受信の動作は妨げず、結果はログにだけ残す。
     */
    private suspend fun notifyPttController(label: String, call: (String) -> Result<String>) {
        val host = settings.activePttControllerHost
        if (host.isEmpty()) return
        val result = withContext(Dispatchers.IO) { call(host) }
        FileLogger.log("PTT_CTRL", "$label host=$host result=${result.exceptionOrNull() ?: result.getOrNull()}")
    }

    fun startTX() {
        if (isTransmitting || isPreparingTx) return
        if (isReceiving && !settings.useOnDeviceGRDVBS2Rx) {
            txError = "受信中は送信を開始できません。受信を停止してください。"
            return
        }
        txError = null
        isPreparingTx = true
        viewModelScope.launch {
            try {
                if (!preparePlutoTx()) return@launch
                if (!tunePluto(isTx = true)) return@launch
                // PA_Power/PTTコントローラへ送信開始を通知する(ESP32側で設定した遅延の後にPTT ON)。
                // 応答が無くても送信は止めない(Pi5版と同じ)。
                notifyPttController("TX on") { Esp32PttClient.notifyTx(it, on = true) }
                txController.start(settings)
                isTransmitting = true
            } finally {
                isPreparingTx = false
            }
        }
    }

    fun stopTX() {
        txController.stop()
        isTransmitting = false
        // 送信を止めてからPA_Power/PTTコントローラへ送信終了を通知する(PTTは即時OFF)。
        viewModelScope.launch { notifyPttController("TX off") { Esp32PttClient.notifyTx(it, on = false) } }
    }

    fun startRX() {
        if (isReceiving || isPreparingRx) return
        if (isTransmitting && !settings.useOnDeviceGRDVBS2Rx) {
            rxError = "送信中は受信を開始できません。送信を停止してください。"
            return
        }
        rxError = null
        isPreparingRx = true
        viewModelScope.launch {
            try {
                if (!tunePluto(isTx = false)) return@launch
                rxController.start(settings)
                isReceiving = true
            } finally {
                isPreparingRx = false
            }
        }
    }

    fun stopRX() {
        rxController.stop()
        isReceiving = false
    }

    fun setTxAudioVolume(percent: Int) {
        updateSettings { it.copy(txAudioVolume = percent.coerceIn(0, 100)) }
        txController.setTxAudioVolume(percent)
    }

    fun setRxVolume(volume: Float) {
        updateSettings { it.copy(rxVolume = volume) }
        rxController.setVolume(volume)
    }

    /**
     * TX/RX開始時にPluto(txDestinationIP)へ実際のLOを設定する(TS自体は別経路のUDPで流れる)。
     * 失敗時はtxError/rxErrorを設定してfalseを返す -- 呼び出し側はisTransmitting/isReceivingを
     * 立てず、送受信コントローラも起動しないこと(以前はチューニング成否を待たずに立てていたため、
     * 失敗時にフラグが true のまま固まり機器試験の診断ボタンが再起動までグレーアウトし続けるバグがあった)。
     */
    private suspend fun tunePluto(isTx: Boolean): Boolean {
        val plutoIp = settings.txDestinationIP
        val frequencyHz = settings.effectiveLoHz
        val ok = withContext(Dispatchers.IO) {
            val reachable = isPlutoReachable(plutoIp)
            FileLogger.log("TUNE", "isPlutoReachable(IIOD:$IIOD_PORT) ip=$plutoIp result=$reachable")
            reachable && run {
                val tuner = PlutoTuner(plutoIp)
                val tuned = tuner.isOpen && tuner.tune(isTx, frequencyHz)
                FileLogger.log("TUNE", "PlutoTuner isOpen=${tuner.isOpen} tuned=$tuned freqHz=$frequencyHz")
                tuner.close()
                tuned
            }
        }
        if (!ok) {
            val message = "Plutoの周波数設定に失敗しました($plutoIp)"
            if (isTx) txError = message else rxError = message
        }
        return ok
    }

    private suspend fun preparePlutoTx(): Boolean {
        val host = settings.txDestinationIP
        FileLogger.log("TX_PREP", "start host=$host")
        val applied = PlutoSettingsWriter.applyTxSettings(host, settings)
        if (applied.isFailure) {
            FileLogger.log("TX_PREP", "applyTxSettings(HTTP:80) failed: ${applied.exceptionOrNull()}")
            txError = "Pluto変調設定の反映に失敗しました: ${applied.exceptionOrNull()?.message}"
            return false
        }
        FileLogger.log("TX_PREP", "applyTxSettings(HTTP:80) ok")
        val restarted = PlutoUdpTsController.restart(host, getApplication())
        if (restarted.isFailure) {
            FileLogger.log("TX_PREP", "PlutoUdpTsController.restart(SSH:22) failed: ${restarted.exceptionOrNull()}")
            txError = "Pluto UDP受信経路の起動に失敗しました: ${restarted.exceptionOrNull()?.message}"
            return false
        }
        FileLogger.log("TX_PREP", "PlutoUdpTsController.restart(SSH:22) ok")
        delay(1_000)
        return true
    }

    /**
     * ホーム画面の「終了」: 送信を止め、PA_Power/PTTコントローラのPTTと12V電源(Pluto含む)を
     * OFFにしてからプロセスを終了する(Pi5版のアプリ終了と同じ順序)。ESP32が応答しなくても終了する。
     */
    fun quitApp() {
        if (isTransmitting) txController.stop()
        viewModelScope.launch {
            notifyPttController("TX off") { Esp32PttClient.notifyTx(it, on = false) }
            notifyPttController("POWER off") { Esp32PttClient.setChannel(it, Esp32PttClient.CHANNEL_POWER, on = false) }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onCleared() {
        // 「終了」ボタン以外でアプリが閉じられた時: PTTを確実にOFFにしてから12V電源(Pluto含む)をOFFにする(Pi5版と同じ順序)。
        // viewModelScopeはここで取り消されるため、別スレッドで送る。
        val host = settings.activePttControllerHost
        if (host.isNotEmpty()) {
            Thread {
                Esp32PttClient.notifyTx(host, on = false)
                val result = Esp32PttClient.setChannel(host, Esp32PttClient.CHANNEL_POWER, on = false)
                FileLogger.log("PTT_CTRL", "POWER off host=$host result=${result.exceptionOrNull() ?: result.getOrNull()}")
            }.start()
        }
        super.onCleared()
    }
}
