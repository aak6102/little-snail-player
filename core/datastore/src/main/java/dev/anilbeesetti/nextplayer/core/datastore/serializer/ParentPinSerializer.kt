package dev.anilbeesetti.nextplayer.core.datastore.serializer

import androidx.datastore.core.Serializer
import dev.anilbeesetti.nextplayer.core.model.ParentPinData
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `parent_pin.json` 的序列化器。
 *
 * 单独一个文件：家长想换 PIN、或者彻底忘掉要重置时，删掉这个文件即可，
 * 不影响学习进度和播放器偏好。
 *
 * 读失败一律退回默认值（= 没设过 PIN），不抛 Corruption。
 * 代价是极端情况下文件损坏会变成「无需 PIN」，但那需要先能写坏应用私有目录 ——
 * 到这个前提，攻击者已经能直接改 `study.json` 把锁关掉了。
 */
object ParentPinSerializer : Serializer<ParentPinData> {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    override val defaultValue: ParentPinData
        get() = ParentPinData()

    override suspend fun readFrom(input: InputStream): ParentPinData {
        try {
            return jsonFormat.decodeFromString(
                deserializer = ParentPinData.serializer(),
                string = input.readBytes().decodeToString(),
            )
        } catch (exception: SerializationException) {
            return ParentPinData()
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: ParentPinData, output: OutputStream) {
        output.write(
            jsonFormat.encodeToString(
                serializer = ParentPinData.serializer(),
                value = t,
            ).encodeToByteArray(),
        )
    }
}
