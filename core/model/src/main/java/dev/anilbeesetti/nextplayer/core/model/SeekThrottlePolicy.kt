package dev.anilbeesetti.nextplayer.core.model

/**
 * 计算当前生效的快进冷却时长（毫秒），返回 `null` 表示不做任何节流。
 *
 * 收费分层（方案 C）：
 * - **已授权（Pro / 试用期）**：完全按家长设置——关掉就返回 `null`，开着就返回自定义间隔。
 * - **未授权**：强制常开、固定 [StudyPreferences.DEFAULT_SEEK_INTERVAL_MINUTES] 分钟，
 *   忽略存档里的开关与自定义间隔。
 *
 * 这里刻意只在“读取端”强制，不改存档结构：免费用户即使旧存档写着“关闭/自定义”，
 * 运行时也按固定 2 分钟拦截；将来升级 Pro 时，原有设置自动恢复，无需迁移数据。
 */
fun effectiveSeekIntervalMillis(
    pro: Boolean,
    preferences: StudyPreferences,
): Long? {
    if (!pro) return StudyPreferences.DEFAULT_SEEK_INTERVAL_MINUTES * 60_000L
    return if (preferences.seekThrottleEnabled) preferences.seekIntervalMillis else null
}
