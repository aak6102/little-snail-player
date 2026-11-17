package dev.anilbeesetti.nextplayer.core.model

/**
 * 使用事件类型。刻意只保留两种语义：开始在前台、离开前台。
 *
 * Android 的 `UsageEvents.Event` 里有 RESUMED / PAUSED / STOPPED 等多种，
 * 映射到这里时 STOPPED 与 PAUSED 同等对待——部分设备的 ROM 只发其中一种，
 * 只认一种会让时长在某些机型上永远算成 0（这个坑在包可见性上已经踩过一次）。
 */
enum class UsageEventType { FOREGROUND, BACKGROUND }

/**
 * 一条前台使用事件。
 *
 * 用自定义类型而不是直接吃 `UsageEvents.Event`，是为了让整套判定逻辑能在 JVM 上单测——
 * 跨零点、未闭合区间、分屏重叠这些边界，跑真机验证一次要等一整天。
 */
data class UsageEvent(
    val packageName: String,
    val type: UsageEventType,
    val timestampMs: Long,
)

/** 一个 App 处于前台的时间区间。 */
data class UsageInterval(
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * 把事件流折成区间列表。
 *
 * 三条规则：
 *  1. 同一包重复 FOREGROUND 只认第一次（有些 ROM 会连发两次 RESUMED）；
 *  2. BACKGROUND 时若无对应起点则丢弃（跨天查询的起点可能落在窗口外）；
 *  3. 到 [nowMs] 仍未闭合的区间以 now 收尾——正在用的那个 App 就是这种情况。
 *
 * 返回的区间按开始时间升序。
 */
fun toUsageIntervals(events: List<UsageEvent>, nowMs: Long): List<UsageInterval> {
    val open = LinkedHashMap<String, Long>()
    val result = ArrayList<UsageInterval>(events.size / 2 + 1)
    events.sortedBy { it.timestampMs }.forEach { event ->
        when (event.type) {
            UsageEventType.FOREGROUND -> open.putIfAbsent(event.packageName, event.timestampMs)
            UsageEventType.BACKGROUND -> {
                val start = open.remove(event.packageName) ?: return@forEach
                if (event.timestampMs > start) {
                    result += UsageInterval(event.packageName, start, event.timestampMs)
                }
            }
        }
    }
    open.forEach { (pkg, start) ->
        if (nowMs > start) result += UsageInterval(pkg, start, nowMs)
    }
    return result.sortedBy { it.startMs }
}

/**
 * 算出每个 App 的**独占**前台时长，扣掉与本应用重叠的部分。
 *
 * 为什么必须扣：分屏/小窗时学习 App 和播放器可以同时处于 RESUMED，
 * 系统会把两边都算满。不扣的话孩子只要开着分屏就能「边看边赚」，
 * 这套激励立刻退化成一句空话。扣掉之后，学习时长按「独占」计——
 * 也就是只有他真的把播放器放下、专心用学习 App 的那段时间才算数。
 *
 * @param ownPackages 本应用的包名（通常只有一个，留成集合是为了兼容分包场景）。
 */
fun exclusiveForegroundDurations(
    intervals: List<UsageInterval>,
    ownPackages: Set<String>,
): Map<String, Long> {
    val own = intervals.filter { it.packageName in ownPackages }
    val perPackage = LinkedHashMap<String, Long>()
    intervals.forEach { interval ->
        if (interval.packageName in ownPackages) return@forEach
        val covered = overlapMs(interval, own)
        val exclusive = (interval.durationMs - covered).coerceAtLeast(0L)
        perPackage[interval.packageName] = (perPackage[interval.packageName] ?: 0L) + exclusive
    }
    return perPackage
}

/** 一条区间被一组（互不重叠的）区间覆盖的总毫秒数。 */
private fun overlapMs(interval: UsageInterval, others: List<UsageInterval>): Long =
    others.sumOf { other ->
        val start = maxOf(interval.startMs, other.startMs)
        val end = minOf(interval.endMs, other.endMs)
        (end - start).coerceAtLeast(0L)
    }

/**
 * 判定哪些规则今天可以发奖励。
 *
 * 只返回**本次新增**的部分，已发过的规则不会出现在结果里（幂等由这里保证，
 * 调用方可以放心地重复结算）。
 *
 * 发放顺序按配置表顺序，先到先得——总量触顶时，排在后面的规则本轮拿不到；
 * 这个行为要在设置页写明「总奖励上限」的作用，否则家长会以为是 Bug。
 */
fun grantEligibleRules(
    preferences: DailyQuotaPreferences,
    usageMsByPackage: Map<String, Long>,
    alreadyGranted: Map<String, Int>,
): Map<String, Int> {
    val cap = preferences.maxRewardMinutesClamped
    // 家长事后调低上限时，已发放可能超过新上限，这里按上限封住、不再倒扣。
    var used = alreadyGranted.values.sum().coerceAtMost(cap)
    val result = LinkedHashMap<String, Int>()
    preferences.activeRules.forEach { rule ->
        if (rule.id in alreadyGranted) return@forEach
        val usedMs = usageMsByPackage[rule.packageName] ?: 0L
        if (usedMs < rule.requiredMs) return@forEach
        val room = cap - used
        if (room <= 0) return@forEach
        val grant = rule.rewardMinutesClamped.coerceAtMost(room)
        if (grant <= 0) return@forEach
        result[rule.id] = grant
        used += grant
    }
    return result
}

/**
 * 定时提醒的档位：累计观看跨过第几档。
 *
 * 用「累计观看 / 间隔」的整数商作为档位，比记「距上次提醒过了多久」稳：
 * 后者在暂停、切后台、ticker 跳秒时会漂移，最终变成「有时提醒有时不提醒」。
 */
fun noticeBucketFor(watchedMs: Long, intervalMinutes: Int): Int {
    if (intervalMinutes <= 0) return 0
    return (watchedMs / (intervalMinutes * 60_000L)).toInt()
}

/**
 * 低余额档位：剩余时长刚刚跨过 10 / 5 / 1 分钟里的哪一档，返回该档分钟数。
 *
 * 只在「本次计算刚刚跨过」时返回非空，靠 [DailyQuotaProgress.lowBalanceNotified]
 * 去重，避免剩余 9 分 59 秒时每秒钟都判定一次。
 *
 * **剩余分钟数向上取整**，与界面显示的「还剩 X 分钟」保持同一口径。
 * 用向下取整会在剩余 1 分 49 秒时就判成「剩 1 分钟」并弹出
 * 「还剩 1 分钟，到点会自动暂停」——提示比事实提前了近一分钟，
 * 家长第一次看到就会认为计时不准。只有当剩余真的不足 1 分钟（含整 1 分钟）时才报 1。
 */
fun lowBalanceStepFor(remainingMs: Long, preferences: DailyQuotaPreferences): Int? {
    if (!preferences.lowBalanceWarn) return null
    if (remainingMs <= 0L) return null
    val remainingMinutes = ((remainingMs + 59_999L) / 60_000L).toInt()
    return DailyQuotaPreferences.LOW_BALANCE_STEPS.firstOrNull { it == remainingMinutes }
}

/**
 * 当天 00:00 的时间戳（本地时区）。
 *
 * 用 `Calendar` 而不是「now 减去对 24 小时取模」：夏令时与跨时区旅行会让
 * 「减去 24 小时」落到昨天或今天的错误一侧，而查询起点错一小时就足以漏掉一整段学习时长。
 */
fun startOfDayMs(nowMs: Long): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = nowMs
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}
