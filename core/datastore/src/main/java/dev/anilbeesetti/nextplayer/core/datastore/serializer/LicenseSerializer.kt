package dev.anilbeesetti.nextplayer.core.datastore.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import dev.anilbeesetti.nextplayer.core.model.LicenseData
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `license.json` 的序列化器。
 *
 * 单独一个文件而不是塞进 `app_preferences.json`：授权数据要能整体清掉（客服重置、换绑重装），
 * 而用户的播放器偏好不该跟着一起丢。
 */
object LicenseSerializer : Serializer<LicenseData> {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    override val defaultValue: LicenseData
        get() = LicenseData()

    override suspend fun readFrom(input: InputStream): LicenseData {
        try {
            return jsonFormat.decodeFromString(
                deserializer = LicenseData.serializer(),
                string = input.readBytes().decodeToString(),
            )
        } catch (exception: SerializationException) {
            // 文件损坏时退回默认值而不是抛 Corruption：授权丢失只会让用户退回试用，
            // 而 Corruption 会让 DataStore 整个不可用，连带把播放器设置也拖下水。
            return LicenseData()
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: LicenseData, output: OutputStream) {
        output.write(
            jsonFormat.encodeToString(
                serializer = LicenseData.serializer(),
                value = t,
            ).encodeToByteArray(),
        )
    }
}
