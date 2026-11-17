package dev.anilbeesetti.nextplayer.core.datastore.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import dev.anilbeesetti.nextplayer.core.model.DailyQuotaProgress
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `quota.json` 的序列化器，模式与 [RestProgressSerializer] 保持一致。
 *
 * 独立文件的理由同前两者：家长想「重置今天的额度」时删掉这一个文件即可，
 * 不会牵连答题进度（`study.json`）和休息窗口（`rest.json`）。
 *
 * 读失败抛 Corruption 而不是静默退回默认值：额度归零意味着孩子立刻被解锁一整份额度，
 * 悄悄吞掉损坏比崩溃更难排查。DataStore 收到 CorruptionException 走它自己的恢复流程。
 */
object DailyQuotaSerializer : Serializer<DailyQuotaProgress> {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    override val defaultValue: DailyQuotaProgress
        get() = DailyQuotaProgress()

    override suspend fun readFrom(input: InputStream): DailyQuotaProgress {
        try {
            return jsonFormat.decodeFromString(
                deserializer = DailyQuotaProgress.serializer(),
                string = input.readBytes().decodeToString(),
            )
        } catch (exception: SerializationException) {
            throw CorruptionException("Cannot read daily quota progress", exception)
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: DailyQuotaProgress, output: OutputStream) {
        output.write(
            jsonFormat.encodeToString(
                serializer = DailyQuotaProgress.serializer(),
                value = t,
            ).encodeToByteArray(),
        )
    }
}
