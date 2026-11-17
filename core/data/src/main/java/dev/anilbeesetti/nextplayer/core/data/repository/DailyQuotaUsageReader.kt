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
import dev.anilbeesetti.nextplayer.core.model.exclusiveForegroundDurations
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
     * [sinceMs]..[untilMs] 之间各 App 的**独占**前台时长（毫秒）。
     *
     * 已扣掉与本应用重叠的部分（分屏时不算边看边学），未授权时返回空表。
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
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun foregroundDurations(sinceMs: Long, untilMs: Long): Map<String, Long> {
        if (!hasUsageAccess() || untilMs <= sinceMs) return emptyMap()
        val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()
        // 权限可能在检查之后、查询之前被撤销（家长在另一个界面关掉了），
        // 这里必须兜住 SecurityException，否则一次结算就能把播放器打崩。
        return try {
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
            exclusiveForegroundDurations(toUsageIntervals(events, untilMs), ownPackages)
        } catch (exception: SecurityException) {
            emptyMap()
        } catch (exception: RuntimeException) {
            // 个别 ROM 的 queryEvents 会抛 IllegalStateException 等运行时异常，
            // 授权状态与事件库都可能不一致；结算失败最多是奖励晚点到账，不该崩。
            emptyMap()
        }
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
    }
}
