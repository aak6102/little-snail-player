package dev.anilbeesetti.nextplayer.core.datastore.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import dev.anilbeesetti.nextplayer.core.model.RestProgress
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `rest.json` 的序列化器，模式与 [StudyProgressSerializer] 保持一致。
 *
 * 独立文件的意义：防沉迷状态（窗口历史、疯狂标记）与播放器偏好、学习进度互不影响，
 * 家长重置播放器设置时不会连孩子的休息策略一起清空。
 */
object RestProgressSerializer : Serializer<RestProgress> {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    override val defaultValue: RestProgress
        get() = RestProgress()

    override suspend fun readFrom(input: InputStream): RestProgress {
        try {
            return jsonFormat.decodeFromString(
                deserializer = RestProgress.serializer(),
                string = input.readBytes().decodeToString(),
            )
        } catch (exception: SerializationException) {
            throw CorruptionException("Cannot read rest progress", exception)
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: RestProgress, output: OutputStream) {
        output.write(
            jsonFormat.encodeToString(
                serializer = RestProgress.serializer(),
                value = t,
            ).encodeToByteArray(),
        )
    }
}
