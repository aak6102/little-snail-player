package dev.anilbeesetti.nextplayer.core.datastore.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import dev.anilbeesetti.nextplayer.core.model.StudyProgress
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `study.json` 的序列化器，模式与 [PlayerPreferencesSerializer] 保持一致。
 *
 * 独立文件的意义：学习进度（错题池、待答题锁）与播放器偏好互不影响，
 * 家长重置播放器设置时不会连孩子的错题本一起清空。
 */
object StudyProgressSerializer : Serializer<StudyProgress> {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    override val defaultValue: StudyProgress
        get() = StudyProgress()

    override suspend fun readFrom(input: InputStream): StudyProgress {
        try {
            return jsonFormat.decodeFromString(
                deserializer = StudyProgress.serializer(),
                string = input.readBytes().decodeToString(),
            )
        } catch (exception: SerializationException) {
            throw CorruptionException("Cannot read study progress", exception)
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: StudyProgress, output: OutputStream) {
        output.write(
            jsonFormat.encodeToString(
                serializer = StudyProgress.serializer(),
                value = t,
            ).encodeToByteArray(),
        )
    }
}
