package dev.anilbeesetti.nextplayer.settings.screens.parent

import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.license.LicenseApi
import dev.anilbeesetti.nextplayer.core.data.repository.ContentPackRepository
import dev.anilbeesetti.nextplayer.core.data.repository.LicenseRepository
import dev.anilbeesetti.nextplayer.core.data.repository.QuizBankRepository
import dev.anilbeesetti.nextplayer.core.data.repository.RestRepository
import dev.anilbeesetti.nextplayer.core.data.repository.StudyRepository
import dev.anilbeesetti.nextplayer.core.model.ContentPackState
import dev.anilbeesetti.nextplayer.core.model.LicenseStatus
import dev.anilbeesetti.nextplayer.core.model.QuizLevelInfo
import dev.anilbeesetti.nextplayer.core.model.RestPreferences
import dev.anilbeesetti.nextplayer.core.model.RestProgress
import dev.anilbeesetti.nextplayer.core.model.StudyPreferences
import dev.anilbeesetti.nextplayer.core.model.StudyProgress
import dev.anilbeesetti.nextplayer.core.model.dayKey
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

/**
 * 家长设置页。
 *
 * 这一页挂在设置列表里，进门要输 4 位 PIN。
 * 入口显式 + PIN 的组合，替换掉原来「关于页连点 7 次 + 关闭延迟 24 小时生效」那一套：
 * 连点只是让人不知道入口在哪，一旦知道就等于没锁；而 24 小时缓冲连家长自己也一起挡了。
 *
 * 授权相关的所有入口也只在这里：播放界面不弹任何付费提示。
 */
@KoinViewModel
class ParentPreferencesViewModel(
    private val studyRepository: StudyRepository,
    private val quizBankRepository: QuizBankRepository,
    private val licenseRepository: LicenseRepository,
    private val contentPackRepository: ContentPackRepository,
    private val restRepository: RestRepository,
    @InjectedParam internal var output: Output,
) : MviViewModel<ParentPreferencesUiState, ParentPreferencesUiEvent>() {

    data class Output(
        val navigateUp: () -> Unit,
        val navigateToReport: () -> Unit,
    )

    private val stateInternal = MutableStateFlow(ParentPreferencesUiState())
    override val state: StateFlow<ParentPreferencesUiState> = stateInternal.asStateFlow()

    private var pollJob: Job? = null

    private companion object {
        /** 与 LicenseApi.Result.Err 的网络层错误码保持一致，只有它才值得重试。 */
        const val NETWORK_ERROR_CODE = "NETWORK_ERROR"
    }

    init {
        viewModelScope.launch {
            studyRepository.progress.collect(::publish)
        }
        viewModelScope.launch {
            restRepository.progress.collect(::publishRest)
        }
        viewModelScope.launch {
            // 授权状态是本地算的，这一行不会触发任何网络请求
            licenseRepository.status.collect(::publishLicense)
        }
        // 档位元数据要读 assets，放到 IO 线程；只加载一次，切换档位不改变清单本身
        reloadLevels()
        viewModelScope.launch {
            contentPackRepository.state.collect { pack ->
                stateInternal.update { it.copy(wordPack = pack) }
                // 下载完成后词表已经换了内容，档位与词数要重读一遍；
                // 不重读的话，用户点完「更新词库」回来看到的还是那 220 词
                if (pack.installedVersion != lastPackVersion) {
                    lastPackVersion = pack.installedVersion
                    reloadLevels()
                }
            }
        }
        // 进页面顺手校准一次：失败无提示，只影响授权页显示的「剩余天数」精度
        viewModelScope.launch { licenseRepository.refresh() }
    }

    /** 记住上一次看到的包版本，用来判断「是不是刚更新完」 */
    private var lastPackVersion = -1

    /**
     * 重读词表元数据。
     *
     * [QuizBankRepository] 内部会按内容包版本自动清缓存，所以这里直接读就是最新的。
     */
    private fun reloadLevels() {
        viewModelScope.launch(Dispatchers.IO) {
            val bank = quizBankRepository.bank(quizBankRepository.defaultBankId())
            stateInternal.update {
                it.copy(levels = bank.levels, wordPackBaseOnly = bank.baseOnly)
            }
        }
    }

    /** 家长手动点「更新词库」。失败要能看到原因，所以 reportErrors = true。 */
    private fun updateWordPack() {
        viewModelScope.launch {
            contentPackRepository.sync(reportErrors = true)
        }
    }

    override fun onAction(action: ParentPreferencesUiEvent) {
        when (action) {
            ParentPreferencesUiEvent.NavigateUp -> output.navigateUp()
            ParentPreferencesUiEvent.OpenReport -> output.navigateToReport()

            ParentPreferencesUiEvent.ToggleStudyMode -> toggleStudyMode()
            ParentPreferencesUiEvent.ToggleRestMode -> toggleRestMode()
            ParentPreferencesUiEvent.ConfirmStartTrial -> confirmStartTrial()

            ParentPreferencesUiEvent.ToggleSeekThrottle -> toggleSeekThrottle()

            ParentPreferencesUiEvent.OpenIntervalOptions ->
                showDialog(ParentPreferencesDialog.IntervalOptions)
            ParentPreferencesUiEvent.OpenQuestionsOptions ->
                showDialog(ParentPreferencesDialog.QuestionsOptions)
            ParentPreferencesUiEvent.OpenSeekIntervalOptions -> openSeekIntervalOptions()
            ParentPreferencesUiEvent.OpenLevelOptions ->
                showDialog(ParentPreferencesDialog.LevelOptions)
            ParentPreferencesUiEvent.UpdateWordPack -> updateWordPack()

            ParentPreferencesUiEvent.OpenWindowOptions ->
                showDialog(ParentPreferencesDialog.WindowOptions)
            ParentPreferencesUiEvent.OpenRestOptions ->
                showDialog(ParentPreferencesDialog.RestOptions)
            ParentPreferencesUiEvent.OpenCrazyIntervalOptions ->
                showDialog(ParentPreferencesDialog.CrazyIntervalOptions)
            ParentPreferencesUiEvent.OpenPreWarnOptions ->
                showDialog(ParentPreferencesDialog.PreWarnOptions)

            is ParentPreferencesUiEvent.ShowDialog -> showDialog(action.value)
            is ParentPreferencesUiEvent.SetIntervalMinutes -> setInterval(action.minutes)
            is ParentPreferencesUiEvent.SetQuestionsPerLock -> setQuestions(action.count)
            is ParentPreferencesUiEvent.SetSeekIntervalMinutes -> setSeekInterval(action.minutes)
            is ParentPreferencesUiEvent.SetActiveLevel -> setActiveLevel(action.level)

            is ParentPreferencesUiEvent.SetWindowMinutes -> setWindow(action.minutes)
            is ParentPreferencesUiEvent.SetRequiredRestMinutes -> setRest(action.minutes)
            is ParentPreferencesUiEvent.SetCrazyIntervalMinutes -> setCrazyInterval(action.minutes)
            is ParentPreferencesUiEvent.SetPreWarnSeconds -> setPreWarn(action.seconds)

            // ---- 购买 ----
            ParentPreferencesUiEvent.OpenPurchase -> openPurchase()
            ParentPreferencesUiEvent.ClosePurchase -> closePurchase()
            is ParentPreferencesUiEvent.SelectPlan -> selectPlan(action.planKey)
            ParentPreferencesUiEvent.BackToPlans -> backToPlans()
            ParentPreferencesUiEvent.SubmitPurchase -> submitPurchase()
            ParentPreferencesUiEvent.RefreshOrder -> refreshOrder()
            ParentPreferencesUiEvent.RetrySync -> retrySync()
        }
    }

    private fun publish(progress: StudyProgress) {
        val preferences = progress.preferences
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val todayStats = progress.dailyStats.takeIf { it.date == today }
        val level = preferences.activeLevelClamped
        // 家长页要能看出「系统正在替他降档」：否则他只看到孩子老答错，
        // 却不知道出题难度已经自动调低，会误以为降档没生效
        val effectiveLevel = progress.adaptive.clampedTo(level).effectiveLevel(level)
        stateInternal.update {
            it.copy(
                studyEnabled = preferences.enabled,
                studyActive = preferences.isActiveAt(now),
                intervalMinutes = preferences.intervalMinutesClamped,
                questionsPerLock = preferences.questionsPerLockClamped,
                seekThrottleEnabled = preferences.seekThrottleEnabled,
                seekIntervalMinutes = preferences.seekIntervalMinutesClamped,
                activeLevel = level,
                effectiveLevel = effectiveLevel,
                todayAnswered = todayStats?.answered ?: 0,
                todayCorrect = todayStats?.correct ?: 0,
            )
        }
    }

    private fun publishRest(progress: RestProgress) {
        val preferences = progress.preferences
        stateInternal.update {
            it.copy(
                restEnabled = preferences.enabled,
                restActive = preferences.enabled,
                windowMinutes = preferences.windowMinutesClamped,
                requiredRestMinutes = preferences.requiredRestMinutesClamped,
                crazyIntervalMinutes = preferences.crazyIntervalMinutesClamped,
                preWarnSeconds = preferences.preWarnSecondsClamped,
            )
        }
    }

    private fun publishLicense(status: LicenseStatus) {
        stateInternal.update {
            it.copy(
                license = status,
                deviceId = status.deviceId.ifBlank { licenseRepository.deviceId },
                contactEmail = licenseRepository.contactEmail,
            )
        }
    }

    private fun showDialog(value: ParentPreferencesDialog?) {
        stateInternal.update { it.copy(showDialog = value) }
    }

    /**
     * 开关的两态语义。
     *
     * 关闭方向现在**立即生效**：能走进这个页面说明家长刚输过 4 位 PIN，
     * 再让他等 24 小时属于自找麻烦。原来的「延迟生效」是隐藏入口时代的补丁，
     * 用来防孩子摸进来误关，现在由 PIN 直接挡住了。
     *
     * - 未授权：没过试用 → 先问要不要开始试用；试用已结束 → 直接开购买页；
     * - 已授权（含试用中）：立即开/关。
     */
    private fun toggleStudyMode() {
        val current = stateInternal.value

        // 打开方向才需要授权，关闭不需要 —— 否则等于把「退订」也锁在付费墙后面
        if (!current.studyActive && !current.license.pro) {
            if (current.canStartTrial) {
                showDialog(ParentPreferencesDialog.StartTrial)
            } else {
                openPurchase()
            }
            return
        }

        updatePreferences { it.copy(enabled = !current.studyActive) }
    }

    private fun enableStudyModeNow() {
        updatePreferences { it.copy(enabled = true) }
    }

    /**
     * 确认开始试用。
     *
     * 先本地生效再上报（[LicenseRepository.startTrial] 内部就是乐观更新）：
     * 家长点了「确定」，开关必须立刻打开。联网失败不影响这次开启，
     * 只是服务端的试用起点会晚一点补上——那一段时间里本地已经算在试用期内了。
     */
    private fun confirmStartTrial() {
        showDialog(null)
        viewModelScope.launch {
            // NonCancellable 不能省：家长点完「确定」常常立刻退出这个页面，
            // viewModelScope 一取消，开关写入和服务端上报都会被掐断，
            // 结果就是「App 显示试用中、后台却没有任何试用记录」。
            withContext(NonCancellable) {
                enableStudyModeNow()
                licenseRepository.startTrial()
            }
        }
    }

    /**
     * 快进限制的「自定义间隔 / 关闭」是 Pro 权益（方案 C）。
     * 免费版固定 2 分钟常开，点开关不再切换，而是引导去开通。
     */
    private fun toggleSeekThrottle() {
        val current = stateInternal.value
        if (!current.license.pro) {
            offerPro()
            return
        }
        val target = !current.seekThrottleEnabled
        updatePreferences { it.copy(seekThrottleEnabled = target) }
    }

    private fun openSeekIntervalOptions() {
        if (!stateInternal.value.license.pro) {
            offerPro()
            return
        }
        showDialog(ParentPreferencesDialog.SeekIntervalOptions)
    }

    /**
     * 非 Pro 用户点到付费项时的统一引导：没过试用就先问要不要开始试用；
     * 试用已结束（或从未正式开始）就直接进购买页。
     */
    private fun offerPro() {
        if (stateInternal.value.canStartTrial) {
            showDialog(ParentPreferencesDialog.StartTrial)
        } else {
            openPurchase()
        }
    }

    /**
     * 写入三项自定义值，各自按允许范围 clamp。
     *
     * 读取端也有保护，但存档里留着 9999 会让人看不出问题出在哪；
     * 而夹在写入这一处，就不用再信任 UI 传来的数字。
     */
    private fun setInterval(minutes: Int) {
        updatePreferences {
            it.copy(
                intervalMinutes = minutes.coerceIn(
                    StudyPreferences.MIN_INTERVAL_MINUTES,
                    StudyPreferences.MAX_INTERVAL_MINUTES,
                ),
            )
        }
        showDialog(null)
    }

    private fun setQuestions(count: Int) {
        updatePreferences {
            it.copy(
                questionsPerLock = count.coerceIn(
                    StudyPreferences.MIN_QUESTIONS_PER_LOCK,
                    StudyPreferences.MAX_QUESTIONS_PER_LOCK,
                ),
            )
        }
        showDialog(null)
    }

    private fun setSeekInterval(minutes: Int) {
        updatePreferences {
            it.copy(
                seekIntervalMinutes = minutes.coerceIn(
                    StudyPreferences.MIN_SEEK_INTERVAL_MINUTES,
                    StudyPreferences.MAX_SEEK_INTERVAL_MINUTES,
                ),
            )
        }
        showDialog(null)
    }

    private fun setActiveLevel(level: Int) {
        // 只改上限，不动 wordStats：进度按词面存，换档位后拼写相同的词照样算学过。
        // 也不重置自适应偏移：孩子从初中调到小学时，之前降的那一档会被 clampedTo 自动收掉。
        updatePreferences { it.copy(activeLevel = level) }
        showDialog(null)
    }

    private fun updatePreferences(transform: (StudyPreferences) -> StudyPreferences) {
        viewModelScope.launch {
            studyRepository.updatePreferences(transform)
        }
    }

    /* ---------------- 防沉迷（休息） ---------------- */

    /**
     * 开关的两态语义与学习模式一致：关闭不需要授权（否则等于把「退订」锁在付费墙后），
     * 打开方向才需要授权——没过试用就问要不要开始试用，试用已结束就直接开购买页。
     */
    private fun toggleRestMode() {
        val current = stateInternal.value
        if (!current.restActive && !current.license.pro) {
            if (current.canStartTrial) {
                showDialog(ParentPreferencesDialog.StartTrial)
            } else {
                openPurchase()
            }
            return
        }
        updateRestPreferences { it.copy(enabled = !current.restActive) }
    }

    private fun setWindow(minutes: Int) {
        updateRestPreferences {
            it.copy(
                windowMinutes = minutes.coerceIn(
                    RestPreferences.MIN_WINDOW_MINUTES,
                    RestPreferences.MAX_WINDOW_MINUTES,
                ),
            )
        }
        showDialog(null)
    }

    private fun setRest(minutes: Int) {
        updateRestPreferences {
            it.copy(
                requiredRestMinutes = minutes.coerceIn(
                    RestPreferences.MIN_REST_MINUTES,
                    RestPreferences.MAX_REST_MINUTES,
                ),
            )
        }
        showDialog(null)
    }

    private fun setCrazyInterval(minutes: Int) {
        updateRestPreferences {
            it.copy(
                crazyIntervalMinutes = minutes.coerceIn(
                    RestPreferences.MIN_CRAZY_INTERVAL_MINUTES,
                    RestPreferences.MAX_CRAZY_INTERVAL_MINUTES,
                ),
            )
        }
        showDialog(null)
    }

    private fun setPreWarn(seconds: Int) {
        updateRestPreferences {
            it.copy(
                preWarnSeconds = seconds.coerceIn(
                    RestPreferences.MIN_PREWARN_SECONDS,
                    RestPreferences.MAX_PREWARN_SECONDS,
                ),
            )
        }
        showDialog(null)
    }

    private fun updateRestPreferences(transform: (RestPreferences) -> RestPreferences) {
        viewModelScope.launch {
            restRepository.updatePreferences(transform)
        }
    }

    /* ---------------- 购买流程 ---------------- */

    /**
     * 打开购买弹窗。
     *
     * 每次都从「选套餐」开始，**不能**沿用上一次的 orderNo / qrContent。
     *
     * 之前这里只改了 visible/error/paid 三个字段，订单相关字段原样留着，于是：
     * 家长看了一年 15 元、退出、想换 29 元永久再点进来，弹窗因为 `hasOrder == true`
     * 直接跳到二维码页（那个分支根本不渲染套餐列表），他根本没有换的入口，
     * 看到、扫到、付掉的都还是上一单的 15 元。
     *
     * 只保留套餐偏好（上次选中哪一档），订单状态一律清空。
     * 服务端下单时按 device_id + plan_key 复用未过期订单，所以清空本地不会产生废单。
     */
    private fun openPurchase() {
        showDialog(null)
        pollJob?.cancel()
        pollJob = null
        stateInternal.update {
            it.copy(
                purchase = PurchaseUiState(
                    visible = true,
                    selectedPlanKey = it.purchase.selectedPlanKey,
                ),
            )
        }
    }

    private fun closePurchase() {
        pollJob?.cancel()
        pollJob = null
        stateInternal.update { it.copy(purchase = PurchaseUiState()) }
    }

    private fun selectPlan(planKey: String) = resetToPlanPicker(planKey)

    /** 已经生成了二维码，但又想回去看看另一档：旧码作废，回到选套餐。 */
    private fun backToPlans() =
        resetToPlanPicker(stateInternal.value.purchase.selectedPlanKey)

    /**
     * 回到选套餐，并丢掉当前订单。
     *
     * 换套餐 = 换订单。旧单的轮询必须一起停：它的闭包里握着旧的 orderNo，
     * 用户真扫了旧码付款，那一轮照样会把 paid 置上，而界面已经在等新单了，状态就串了。
     */
    private fun resetToPlanPicker(planKey: String) {
        pollJob?.cancel()
        pollJob = null
        stateInternal.update {
            it.copy(purchase = PurchaseUiState(visible = true, selectedPlanKey = planKey))
        }
    }

    /**
     * 下单。
     *
     * 这是全 App 唯一允许把错误显示给用户的接口——他刚点了「立即购买」，
     * 静默失败会让他以为按钮坏了。
     */
    private fun submitPurchase() {
        val snapshot = stateInternal.value.purchase
        // 连点两下不能下两单：第二下要么是手抖，要么是界面没来得及反馈
        if (snapshot.loading) return
        val key = snapshot.selectedPlanKey
        stateInternal.update {
            // 顺手清掉旧订单字段：万一上一轮下单失败了，残留的 qrContent 会让
            // 「生成中」和「旧二维码」两张画面互相抢，看起来像点了没反应
            it.copy(
                purchase = it.purchase.copy(
                    loading = true,
                    error = null,
                    orderNo = null,
                    qrContent = null,
                    amountCents = 0,
                    expireInSeconds = 0,
                    paid = false,
                    timedOut = false,
                ),
            )
        }
        viewModelScope.launch {
            var result = licenseRepository.createOrder(key)

            // 国内连 Cloudflare 的第一次连接经常整包丢掉（TLS 握手就走不完），
            // 第二次几乎都能通。这里补一次重试，失败原因只有 NETWORK_ERROR 才算数：
            // 业务错误（套餐下架之类）重试没有意义。
            // 重试不会产生重复订单 —— 服务端按 device_id + plan_key 复用未过期的 pending 单，
            // 第一次就算已经建好了，第二次拿回来的还是同一张码。
            if (result is LicenseApi.Result.Err && result.code == NETWORK_ERROR_CODE) {
                delay(800)
                result = licenseRepository.createOrder(key)
            }

            when (val r = result) {
                is LicenseApi.Result.Ok -> {
                    stateInternal.update {
                        it.copy(
                            purchase = it.purchase.copy(
                                loading = false,
                                orderNo = r.value.orderNo,
                                qrContent = r.value.qrContent,
                                amountCents = r.value.amountCents,
                                expireInSeconds = r.value.expireIn,
                            ),
                        )
                    }
                    startPolling(r.value.orderNo)
                }

                is LicenseApi.Result.Err -> {
                    stateInternal.update {
                        it.copy(
                            purchase = it.purchase.copy(
                                loading = false,
                                error = r.message.ifBlank { "下单失败，请稍后重试" },
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun refreshOrder() {
        val orderNo = stateInternal.value.purchase.orderNo ?: return
        viewModelScope.launch { pollOnce(orderNo, userInitiated = true) }
    }

    /**
     * 轮询付款结果。
     *
     * 起始 2 秒一次，30 秒后退避到 5 秒——支付宝确认通常几秒内就到，
     * 但用户扫码慢的时候持续 2 秒一次纯属浪费电。两分钟还没结果就停，
     * 服务端 Cron 会继续补单，用户下次进来会自动恢复。
     */
    private fun startPolling(orderNo: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var elapsedMs = 0L
            while (isActive && elapsedMs < 120_000L) {
                if (pollOnce(orderNo, userInitiated = false)) return@launch
                val interval = if (elapsedMs < 30_000L) 2_000L else 5_000L
                delay(interval)
                elapsedMs += interval
            }
            stateInternal.update {
                it.copy(purchase = it.purchase.copy(timedOut = true))
            }
        }
    }

    /** @return true 表示这一单已有结论（付成功或已关闭），外层循环该停了。 */
    private suspend fun pollOnce(orderNo: String, userInitiated: Boolean): Boolean {
        if (userInitiated) {
            stateInternal.update { it.copy(purchase = it.purchase.copy(polling = true)) }
        }
        val result = licenseRepository.pollOrder(orderNo)
        stateInternal.update { it.copy(purchase = it.purchase.copy(polling = false)) }

        return when (result) {
            is LicenseApi.Result.Ok -> when {
                result.value.isPaid -> {
                    // 票据已经由 pollOrder() → applyPaid() 落盘，status 会立刻翻转，
                    // 界面这一帧就是已授权了。原来这里还同步 await 一次完整 refresh，
                    // 等于在「已解锁」三个字前面又塞了一个网络往返，纯属自找延迟；
                    // 换绑次数、售后邮箱这类信息不着急，放到后台慢慢同步。
                    stateInternal.update {
                        it.copy(purchase = it.purchase.copy(paid = true, error = null))
                    }
                    // 付完立刻把开关打开：用户的目标是「孩子能继续背单词」，不是「买到了」
                    enableStudyModeNow()
                    viewModelScope.launch { licenseRepository.refresh() }
                    true
                }

                result.value.isClosed -> {
                    stateInternal.update {
                        it.copy(purchase = it.purchase.copy(error = "订单已关闭，请重新下单"))
                    }
                    true
                }

                else -> false
            }

            // 轮询失败不打扰用户：网络抖一下就弹错误，会让人以为付款出了问题
            is LicenseApi.Result.Err -> false
        }
    }

    private fun retrySync() {
        viewModelScope.launch { licenseRepository.refresh() }
    }
}

data class ParentPreferencesUiState(
    /** 存储里的原始开关值。 */
    val studyEnabled: Boolean = false,
    /** 此刻是否真的在拦人。等于 [studyEnabled]，保留独立字段是为了让消费端语义清晰。 */
    val studyActive: Boolean = false,
    val intervalMinutes: Int = StudyPreferences.DEFAULT_INTERVAL_MINUTES,
    val questionsPerLock: Int = StudyPreferences.DEFAULT_QUESTIONS_PER_LOCK,
    val seekThrottleEnabled: Boolean = true,
    val seekIntervalMinutes: Int = StudyPreferences.DEFAULT_SEEK_INTERVAL_MINUTES,
    /** 家长设定的难度上限。 */
    val activeLevel: Int = StudyPreferences.DEFAULT_LEVEL,
    /** 自适应降级后的实际档位；与 [activeLevel] 相等表示没有降级。 */
    val effectiveLevel: Int = StudyPreferences.DEFAULT_LEVEL,
    /** 可选档位，来自五档词表；加载完成前为空。 */
    val levels: List<QuizLevelInfo> = emptyList(),
    /**
     * 当前词表是否只含内置的基础两档（起步 / 进阶）。
     *
     * 用来在档位选择器里提示「完整五档需联网更新」，而不是默默让家长
     * 选了高中却只能刷到 220 个词。
     */
    val wordPackBaseOnly: Boolean = false,
    /** 云端完整词库的安装状态，见 [ContentPackState]。 */
    val wordPack: ContentPackState = ContentPackState(),
    val todayAnswered: Int = 0,
    val todayCorrect: Int = 0,
    val showDialog: ParentPreferencesDialog? = null,
    /** 防沉迷：存储里的原始开关值。 */
    val restEnabled: Boolean = false,
    /** 防沉迷：此刻是否真的在拦人。等于 [restEnabled]。 */
    val restActive: Boolean = false,
    val windowMinutes: Int = RestPreferences.DEFAULT_WINDOW_MINUTES,
    val requiredRestMinutes: Int = RestPreferences.DEFAULT_REST_MINUTES,
    val crazyIntervalMinutes: Int = RestPreferences.DEFAULT_CRAZY_INTERVAL_MINUTES,
    val preWarnSeconds: Int = RestPreferences.DEFAULT_PREWARN_SECONDS,
    /** 授权状态，来自本地验签，不依赖网络。 */
    val license: LicenseStatus = LicenseStatus(),
    val deviceId: String = "",
    val contactEmail: String = "",
    val purchase: PurchaseUiState = PurchaseUiState(),
) {
    /**
     * 当前档位条目，用于展示名称与词数。
     *
     * 元数据没读出来时返回 null（显示「正在读取词表…」），
     * 而不是硬编码一个档位名——那会在词表加载失败时给出错误信息。
     */
    val activeLevelInfo: QuizLevelInfo?
        get() = levels.firstOrNull { it.level == activeLevel } ?: levels.firstOrNull()

    /** 实际出题档位：降级时与 [activeLevelInfo] 不同。 */
    val effectiveLevelInfo: QuizLevelInfo?
        get() = levels.firstOrNull { it.level == effectiveLevel } ?: activeLevelInfo

    /** 系统是否正在替孩子降档出题。 */
    val isDowngraded: Boolean
        get() = effectiveLevel < activeLevel

    /** 还没开始过试用才有「免费试用」这条路；试用用完了只能买。 */
    val canStartTrial: Boolean
        get() = !license.trialStarted && !license.pro

    /** 授权页副标题：一句话说清现在是免费、试用还是已购买。 */
    val licenseHeadline: String
        get() = license.headline

    /** 机器码按 4-4-4-4 分段，客服口头核对时不会串行。 */
    val formattedDeviceId: String
        get() = deviceId.chunked(4).joinToString("-").uppercase()
}

/**
 * 购买弹窗状态。
 *
 * `qrContent` 是**内容**不是图片：渲染交给 UI 用 ZXing 生成，
 * 这样不依赖服务端出图，也避免 SVG 在 Android 上没法直接显示的问题。
 */
data class PurchaseUiState(
    val visible: Boolean = false,
    val selectedPlanKey: String = PurchasePlan.DEFAULT_KEY,
    val loading: Boolean = false,
    val polling: Boolean = false,
    val orderNo: String? = null,
    val qrContent: String? = null,
    val amountCents: Int = 0,
    val expireInSeconds: Int = 0,
    val error: String? = null,
    val paid: Boolean = false,
    val timedOut: Boolean = false,
) {
    val hasOrder: Boolean get() = !qrContent.isNullOrBlank()
    val amountText: String get() = "¥%.0f".format(amountCents / 100.0)
}

/** 套餐展示信息。价格以服务端为准，这里只用于本地排版与默认选中。 */
data class PurchasePlan(
    val key: String,
    val title: String,
    val subtitle: String,
    val priceText: String,
    val badge: String? = null,
) {
    companion object {
        const val SOLO = "solo"
        const val SOLO_ONE_YEAR = "solo_1y"
        const val DEFAULT_KEY = SOLO

        /**
         * 顺序即说服力：把「永久」放前面并加角标，
         * 因为一次性付费的家长最怕的是「明年又要再交一次」。
         */
        val ALL = listOf(
            PurchasePlan(
                key = SOLO,
                title = "永久版",
                subtitle = "一次买断，换手机免费换绑 2 次/年",
                priceText = "¥29",
                badge = "推荐",
            ),
            PurchasePlan(
                key = SOLO_ONE_YEAR,
                title = "一年版",
                subtitle = "一年内不限设备功能，到期前可续",
                priceText = "¥15",
            ),
        )

        fun find(key: String): PurchasePlan = ALL.firstOrNull { it.key == key } ?: ALL.first()
    }
}

sealed interface ParentPreferencesDialog {
    data object StartTrial : ParentPreferencesDialog
    data object IntervalOptions : ParentPreferencesDialog
    data object QuestionsOptions : ParentPreferencesDialog
    data object SeekIntervalOptions : ParentPreferencesDialog
    data object LevelOptions : ParentPreferencesDialog

    // ---- 防沉迷（休息） ----
    data object WindowOptions : ParentPreferencesDialog
    data object RestOptions : ParentPreferencesDialog
    data object CrazyIntervalOptions : ParentPreferencesDialog
    data object PreWarnOptions : ParentPreferencesDialog
}

sealed interface ParentPreferencesUiEvent {
    data object NavigateUp : ParentPreferencesUiEvent
    data object OpenReport : ParentPreferencesUiEvent
    data object ToggleStudyMode : ParentPreferencesUiEvent
    data object ConfirmStartTrial : ParentPreferencesUiEvent
    data object ToggleSeekThrottle : ParentPreferencesUiEvent
    data object OpenIntervalOptions : ParentPreferencesUiEvent
    data object OpenQuestionsOptions : ParentPreferencesUiEvent
    data object OpenSeekIntervalOptions : ParentPreferencesUiEvent
    data object OpenLevelOptions : ParentPreferencesUiEvent

    /** 家长手动点「更新词库」，联网拉取完整五档词库包 */
    data object UpdateWordPack : ParentPreferencesUiEvent

    // ---- 防沉迷（休息） ----
    data object ToggleRestMode : ParentPreferencesUiEvent
    data object OpenWindowOptions : ParentPreferencesUiEvent
    data object OpenRestOptions : ParentPreferencesUiEvent
    data object OpenCrazyIntervalOptions : ParentPreferencesUiEvent
    data object OpenPreWarnOptions : ParentPreferencesUiEvent
    data class SetWindowMinutes(val minutes: Int) : ParentPreferencesUiEvent
    data class SetRequiredRestMinutes(val minutes: Int) : ParentPreferencesUiEvent
    data class SetCrazyIntervalMinutes(val minutes: Int) : ParentPreferencesUiEvent
    data class SetPreWarnSeconds(val seconds: Int) : ParentPreferencesUiEvent

    data class ShowDialog(val value: ParentPreferencesDialog?) : ParentPreferencesUiEvent
    data class SetIntervalMinutes(val minutes: Int) : ParentPreferencesUiEvent
    data class SetQuestionsPerLock(val count: Int) : ParentPreferencesUiEvent
    data class SetSeekIntervalMinutes(val minutes: Int) : ParentPreferencesUiEvent
    data class SetActiveLevel(val level: Int) : ParentPreferencesUiEvent

    data object OpenPurchase : ParentPreferencesUiEvent
    data object ClosePurchase : ParentPreferencesUiEvent
    data class SelectPlan(val planKey: String) : ParentPreferencesUiEvent
    data object BackToPlans : ParentPreferencesUiEvent
    data object SubmitPurchase : ParentPreferencesUiEvent
    data object RefreshOrder : ParentPreferencesUiEvent
    data object RetrySync : ParentPreferencesUiEvent
}
