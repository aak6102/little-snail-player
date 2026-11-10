package dev.anilbeesetti.nextplayer.core.model

/**
 * 词库内容包的安装状态。
 *
 * 给「家长设置 → 词库更新」用，也是 [dev.anilbeesetti.nextplayer.core.data.repository.QuizBankRepository]
 * 判断要不要重读词表的依据（[installedVersion] 变了就清缓存）。
 */
data class ContentPackState(
    /** 本地已安装的包版本；0 表示还在用内置基础包 */
    val installedVersion: Int = 0,
    /** 服务端当前下发的版本；0 表示服务端没发布过，或还没查到 */
    val availableVersion: Int = 0,
    /** 是否已经成功问过一次服务端（UI 用来区分「没查」和「查了没有」） */
    val checked: Boolean = false,
    /** 正在下载 / 校验 */
    val busy: Boolean = false,
    /** 需要展示给用户的一句话；空表示无事发生 */
    val message: String = "",
    /** 最近一次失败原因，仅用于「更新失败」文案 */
    val error: String = "",
) {
    /** 服务端有更新的包，且不是在忙着下 */
    val hasUpdate: Boolean get() = availableVersion > installedVersion

    /** 还有档位没下发到本地（内置包只含前两档） */
    val incomplete: Boolean get() = installedVersion == 0
}
