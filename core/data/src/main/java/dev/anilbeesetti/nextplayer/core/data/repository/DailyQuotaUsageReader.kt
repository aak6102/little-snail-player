package dev.anilbeesetti.nextplayer.core.data.repository

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import dev.anilbeesetti.nextplayer.core.model.UsageEvent
import dev.anilbeesetti.nextplayer.core.model.UsageEventType
import dev.anilbeesetti.nextplayer.core.model.toUsageIntervals
import org.koin.core.annotation.Single

/** 一个可被选择为「学习 App」的已安装应用。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * 读取「别的 App 今天用了多久」。
 *
 * 这是整套奖励机制唯一的平台依赖，也是它唯一的硬前提：Android 只给
 * `UsageStatsManager` 这一个口子，而它要求用户在系统设置里手动授予「使用情况访问权限」，
 * App 无法自行申请。没授权时这里一律返回空表，奖励恒为 0——设置页必须显式告诉家长这件事，
 * 否则表现就是「配了规则但永远不涨」，会被当成 Bug。
 */
interface DailyQuotaUsageReader {

    /** 是否已获得「使用情况访问权限」。 */
    fun hasUsageAccess(): Boolean

    /**
     * [sinceMs]..[untilMs] 之间各 App 的前台时长（毫秒），口径与系统「数字健康」一致。
     *
     * 未授权时返回空表。
     */
    fun foregroundDurations(sinceMs: Long, untilMs: Long): Map<String, Long>

    /** 桌面有启动图标的 App 列表，用于家长挑学习 App。 */
    fun launchableApps(): List<InstalledApp>
}

@Single
class AndroidDailyQuotaUsageReader(context: Context) : DailyQuotaUsageReader {

    private val appContext = context.applicationContext

    private val ownPackages = setOf(appContext.packageName)

    override fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appContext.packageName,
        )
        if (mode == AppOpsManager.MODE_ALLOWED) return true
        // 兜底：MIUI / EMUI / ColorOS 这类自带权限管理的 ROM，用户明明在系统设置里
        // 开了「使用情况访问」，checkOpNoThrow 仍返回 MODE_DEFAULT。只看 AppOps 会得出
        // 「未授权」的结论，于是结算被整条跳过——表现就是孩子学满了、时长一分不涨，
        // 而设置页还写着「已开启」。这里用一次真实查询判定：能读到数据就是有权限。
        return probeUsageAccess()
    }

    /** 真查一次：最近 7 天有任何一条使用记录，说明权限实际可用。 */
    private fun probeUsageAccess(): Boolean {
        val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false
        return try {
            val now = System.currentTimeMillis()
            manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - PROBE_WINDOW_MS,
                now,
            ).orEmpty().any { it.totalTimeInForeground > 0L }
        } catch (exception: RuntimeException) {
            false
        }
    }

    /**
     * 今日各 App 的前台时长。
     *
     * **主口径是系统自己的统计**（`queryUsageStats.totalTimeInForeground`），与系统设置里
     * 「数字健康 / 应用使用时间」显示的完全同源。早先这里是从 `queryEvents` 事件流重建区间再
     * 相减，真机上出现过「学满 30 分钟却算出 0」：只要本应用自身有一次 RESUMED 没配上
     * PAUSED（进程被杀、ROM 少发事件），那条区间就会一直开到「现在」，把之后所有学习 App
     * 的时长整个盖掉。事件流适合做「谁在前台」这种瞬时判断，不适合做时长账本。
     *
     * 事件流保留为**逐包兜底**：个别 ROM 的 `queryUsageStats` 有延迟或返回空，
     * 这时用事件结果顶上（取两者较大值，宁可多算也不把孩子的学习时间吞掉）。
     *
     * [sinceMs]..[untilMs] 之间各 App 的前台毫秒数。
     */
    override fun foregroundDurations(sinceMs: Long, untilMs: Long): Map<String, Long> {
        if (untilMs <= sinceMs) return emptyMap()
        val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()
        val fromStats = try {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, sinceMs, untilMs)
                .orEmpty()
                .groupBy { it.packageName }
                .mapValues { (_, stats) -> stats.sumOf { it.totalTimeInForeground } }
                .filterValues { it > 0L }
        } catch (exception: SecurityException) {
            // 权限可能在检查之后、查询之前被撤销（家长在另一个界面关掉了）。
            emptyMap()
        } catch (exception: RuntimeException) {
            emptyMap()
        }
        val fromEvents = eventBasedDurations(manager, sinceMs, untilMs)
        if (fromStats.isEmpty()) return fromEvents
        // 逐包取「系统统计优先，缺失才用事件兜底」：系统统计是账本，事件流只补它没有的包。
        // 这里不做 max——事件流在真机上可能把一段没闭合的区间一路开到「现在」，
        // 取最大值等于把多算的那几个小时也一起发给用户。
        return (fromStats.keys + fromEvents.keys)
            .associateWith { pkg -> fromStats[pkg]?.takeIf { it > 0L } ?: (fromEvents[pkg] ?: 0L) }
            .filterValues { it > 0L }
    }

    /**
     * 事件流兜底。**不再做分屏抵扣**：抵扣依赖「本应用区间与学习区间重叠」，
     * 而本应用区间一旦没被正常关闭就会把学习时长整个吞掉（见 [foregroundDurations] 注释）。
     * 可靠性优先于防作弊。
     */
    private fun eventBasedDurations(
        manager: UsageStatsManager,
        sinceMs: Long,
        untilMs: Long,
    ): Map<String, Long> = try {
        val events = ArrayList<UsageEvent>()
        val usageEvents = manager.queryEvents(sinceMs, untilMs)
        val event = UsageEvents.Event()
        // UsageEvents 自身继承 Iterator，没有 hasNext()；getNextEvent 返回 false 即取完。
        while (usageEvents.getNextEvent(event)) {
            val type = when (event.eventType) {
                EVENT_RESUMED -> UsageEventType.FOREGROUND
                EVENT_PAUSED, EVENT_STOPPED -> UsageEventType.BACKGROUND
                else -> null
            } ?: continue
            val pkg = event.packageName ?: continue
            events += UsageEvent(pkg, type, event.timeStamp)
        }
        toUsageIntervals(events, untilMs)
            .filterNot { it.packageName in ownPackages }
            .groupBy { it.packageName }
            .mapValues { (_, intervals) -> intervals.sumOf { it.durationMs } }
    } catch (exception: SecurityException) {
        emptyMap()
    } catch (exception: RuntimeException) {
        // 个别 ROM 的 queryEvents 会抛 IllegalStateException 等运行时异常，
        // 授权状态与事件库都可能不一致；结算失败最多是奖励晚点到账，不该崩。
        emptyMap()
    }

    override fun launchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            appContext.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_ALL,
            )
        } catch (exception: RuntimeException) {
            emptyList()
        }
        return resolved
            .asSequence()
            .filter { it.activityInfo?.packageName != appContext.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(appContext.packageManager).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
            .toList()
    }

    companion object {
        /**
         * 事件类型取值。
         *
         * 直接写整数而不是引用 `UsageEvents.Event.ACTIVITY_RESUMED`：后者是 API 29 才有的常量，
         * 而这两个值与古老的 `MOVE_TO_FOREGROUND` / `MOVE_TO_BACKGROUND` 完全相同（1 / 2），
         * minSdk 24 引常量会在旧设备上编译告警、运行无差别。23 是 `ACTIVITY_STOPPED`。
         */
        private const val EVENT_RESUMED = 1
        private const val EVENT_PAUSED = 2
        private const val EVENT_STOPPED = 23

        /** 授权兜底探测的窗口：7 天内只要有任意一次使用记录，就认为权限可用。 */
        private const val PROBE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    }
}
