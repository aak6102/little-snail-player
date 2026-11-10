package dev.anilbeesetti.nextplayer.feature.player.study

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 朗读能力的当前状态。UI 靠它决定要不要提示家长「这台机器念不出英文」。 */
enum class SpeakerStatus {
    /** 正在绑定引擎，还没有结论。 */
    INITIALIZING,

    /** 可以正常朗读。 */
    READY,

    /**
     * 设备上没有可用的 TTS 引擎。
     *
     * 实测过一台 D1：`cmd package query-services -a android.intent.action.TTS_SERVICE`
     * 返回 No services found，`settings get secure tts_default_synth` 为 null。
     * 这类设备靠调 AudioAttributes、调语速都救不回来，只能明确告知。
     */
    NO_ENGINE,

    /** 引擎在，但没装英文语音数据（国产 ROM 只带中文离线包时很常见）。 */
    NO_ENGLISH,
}

/**
 * 听力题的朗读器。
 *
 * TTS 初始化是异步的，初始化完成前的朗读请求会被暂存，
 * 避免「点了喇叭没声音」——那会让孩子以为按钮坏了。
 *
 * 但光有暂存不够：初始化失败时暂存永远不会被消费，界面依旧一片安静，
 * 连日志都没有。所以这里补了三件事——把每个失败点写进日志、
 * 把状态暴露给界面、允许在引擎后装的情况下重试。
 */
class QuizSpeaker(context: Context) {

    private val appContext = context.applicationContext

    private var textToSpeech: TextToSpeech? = null

    /** 尚未就绪时暂存的请求：文本 + 是否用慢速。 */
    private var pending: Pair<String, Boolean>? = null

    /** 上次尝试绑定引擎的时刻，用于判断重试是否太频繁。 */
    private var lastConnectAtMs = 0L

    /** 已经尝试绑定的次数，防止在完全没引擎的设备上空转。 */
    private var connectAttempts = 0

    var status by mutableStateOf(SpeakerStatus.INITIALIZING)
        private set

    init {
        connect()
    }

    /**
     * 绑定系统 TTS 引擎。
     *
     * 会先释放上一次的实例：家长可能刚刚装好引擎又回到这里，
     * 不该逼他把整个 App 杀掉重进。
     */
    private fun connect() {
        releaseEngine()
        status = SpeakerStatus.INITIALIZING
        lastConnectAtMs = SystemClock.elapsedRealtime()
        connectAttempts++
        Log.i(TAG, "绑定 TTS 引擎，第 $connectAttempts 次尝试")
        textToSpeech = TextToSpeech(appContext) { code -> handleInit(code) }
    }

    private fun handleInit(code: Int) {
        val engine = textToSpeech
        if (code != TextToSpeech.SUCCESS || engine == null) {
            // -1 是 ERROR：既可能没引擎，也可能引擎进程没响应
            Log.w(TAG, "TTS 初始化失败 status=$code engine=${engine != null}")
            status = SpeakerStatus.NO_ENGINE
            return
        }
        val locale = pickEnglishLocale(engine)
        if (locale == null) {
            Log.w(
                TAG,
                "TTS 无英文语音数据：engine=${engine.defaultEngine} " +
                    "US=${engine.isLanguageAvailable(Locale.US)} " +
                    "EN=${engine.isLanguageAvailable(Locale.ENGLISH)}",
            )
            status = SpeakerStatus.NO_ENGLISH
            return
        }
        Log.i(TAG, "TTS 就绪 engine=${engine.defaultEngine} locale=$locale")
        status = SpeakerStatus.READY
        pending?.let { (text, slow) -> speak(text, slow) }
        pending = null
    }

    /**
     * 逐个尝试英文区域并真正切过去。
     *
     * `isLanguageAvailable` 只说明引擎有没有这份数据，`setLanguage` 才是落定，
     * 两者都要成功才算能用——只查前者会在某些引擎上拿到「有数据但切不过去」。
     */
    private fun pickEnglishLocale(engine: TextToSpeech): Locale? {
        for (locale in PREFERRED_LOCALES) {
            val available = runCatching { engine.isLanguageAvailable(locale) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (available < TextToSpeech.LANG_AVAILABLE) continue
            val applied = runCatching { engine.setLanguage(locale) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (applied >= TextToSpeech.LANG_AVAILABLE) return locale
        }
        return null
    }

    /** [slow] 为 true 时用更慢的语速，供「再听一次」按钮使用。 */
    fun speak(text: String, slow: Boolean = false) {
        val engine = textToSpeech
        if (status != SpeakerStatus.READY || engine == null) {
            // 只留最后一次请求：孩子连点几下喇叭，没必要排队念五遍
            pending = text to slow
            // 引擎缺失时给重试机会，冷却期内不重试，避免连着点导致反复绑定
            if (status == SpeakerStatus.NO_ENGINE && shouldRetry()) connect()
            return
        }
        engine.setSpeechRate(if (slow) RATE_SLOW else RATE_NORMAL)
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak 失败 result=$result text=$text status=$status")
        }
    }

    private fun shouldRetry(): Boolean {
        if (connectAttempts >= MAX_CONNECT_ATTEMPTS) return false
        return SystemClock.elapsedRealtime() - lastConnectAtMs >= RETRY_COOLDOWN_MS
    }

    private fun releaseEngine() {
        val engine = textToSpeech ?: return
        textToSpeech = null
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
    }

    fun shutdown() {
        pending = null
        releaseEngine()
        status = SpeakerStatus.INITIALIZING
    }

    private companion object {
        const val TAG = "QuizSpeaker"
        const val UTTERANCE_ID = "quiz"

        /** 最多绑定几次：够覆盖「装完引擎回来再点一次」，又不至于空转。 */
        const val MAX_CONNECT_ATTEMPTS = 3

        /** 两次绑定之间的最小间隔，避免孩子连点喇叭触发反复 bind。 */
        const val RETRY_COOLDOWN_MS = 5_000L

        /** 美音优先，其次英式，最后泛英语。 */
        val PREFERRED_LOCALES = listOf(Locale.US, Locale.UK, Locale.ENGLISH)

        /**
         * 正常语速。系统默认是 1.0，对小学阶段的英语学习者偏快，
         * 加上合成音本身不够清晰，经常一遍听不全；0.85 的辨识度明显更好。
         */
        const val RATE_NORMAL = 0.85f

        /**
         * 「再听一次」用的语速。
         *
         * 重听若还是同一速度，等于把同样模糊的音再放一遍，孩子只会反复点；
         * 留一档更慢的，才真正给得出第二次机会。
         */
        const val RATE_SLOW = 0.6f
    }
}
