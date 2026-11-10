package dev.anilbeesetti.nextplayer.core.data.license

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 授权服务客户端。
 *
 * 刻意**不引入 OkHttp**：整个模块只调 5 个接口、全是短连接 POST/GET，
 * 为此多打一个 1.5MB 的依赖不划算（项目里现有的 okhttp 是 coil 带进来的，
 * 不是直接依赖，直接用它等于把别人的传递依赖当 API 用）。
 *
 * 另一个隐性收益：HttpURLConnection 是系统实现，不涉及任何第三方许可，
 * 与 GPL 分发之间没有额外的合规问题。
 */
class LicenseApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    companion object {
        /**
         * 生产域名。**不能用 *.workers.dev**：那个子域在国内基本打不开，
         * 会导致「付款成功但授权永远不生效」这类最难排查的问题。
         */
        const val DEFAULT_BASE_URL = "https://snail.709048.xyz"

        /**
         * 冷启动/续签/试用上报：用户根本不在等这个结果，但也不能设太短。
         * 实测国内首次连 Cloudflare 的 TLS 握手经常要 2~10 秒，4 秒会把一部分本来能成功的请求切掉
         * （表现就是「开了试用、后台没记录」），所以给到 10 秒。
         */
        const val TIMEOUT_SYNC_MS = 10_000

        /**
         * 下单：用户点了按钮在等。
         *
         * 原来给 10 秒，实测在移动网络下会被切掉：Worker 本身 3ms 就启动，
         * 慢的全是「国内 → Cloudflare」这一段，运营商国际出口拥塞时单程就要十几秒。
         * 20 秒是「宁可让圈多转一会儿，也别让已经到达服务端的请求被判超时」的取舍——
         * 超时之后用户重试，服务端其实早就建好单了。
         */
        const val TIMEOUT_ORDER_MS = 20_000

        /** 轮询：付款页内的短轮询，失败下一轮会重试，不必等太久，但也要留够国际链路抖动的余量。 */
        const val TIMEOUT_POLL_MS = 8_000

        /** 服务端时间为北京时间，固定 +8 解析，不要依赖设备时区。 */
        private const val SERVER_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
    }

    /* ---------------- 结果类型 ---------------- */

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>

        /** [code] 为服务端业务码（如 BAD_PLAN），网络层失败时为 [NETWORK_ERROR]。 */
        data class Err(val code: String, val message: String) : Result<Nothing>
    }

    /** 授权同步结果（bootstrap / trial/start / renew 三个接口共用的形状）。 */
    data class SyncResponse(
        /** 服务端当前时间，用于校准时钟。0 表示响应里没带 */
        val serverTimeMs: Long = 0L,
        val trialStartedAtMs: Long = 0L,
        val trialExpireAtMs: Long = 0L,
        val ticket: String? = null,
        val token: String = "",
        val licensed: Boolean = false,
        /**
         * 服务端明确停用了这台设备（换绑走了 / 人工封禁）。
         *
         * 与「未购买」不同：未购买是 license 为 null，这里是有授权但不许再用。
         * 收到它要立刻清掉本地票据，否则旧设备会一直拿着有效票据当 pro，
         * 踢下线就变成「等票据自己过期」，最长 30 天。
         */
        val disabled: Boolean = false,
        val permanent: Boolean = false,
        val expireAtMs: Long = 0L,
        val rebindLimit: Int = 0,
        val rebindUsed: Int = 0,
        val contactEmail: String = "",
    )

    data class PurchaseOrder(
        val orderNo: String,
        val amountCents: Int,
        /** `qr`（当面付扫码）或 `app`（App 支付，当前未启用） */
        val method: String,
        /** 二维码内容。**注意是内容不是图片**：渲染由客户端用 ZXing 生成，见 QrEncoder */
        val qrContent: String,
        /** 二维码剩余有效秒数 */
        val expireIn: Int,
    )

    data class OrderState(
        val orderNo: String,
        /** `pending` / `paid` / `closed` */
        val status: String,
        val ticket: String? = null,
        val token: String = "",
        val licensed: Boolean = false,
        val permanent: Boolean = false,
        val expireAtMs: Long = 0L,
        val serverTimeMs: Long = 0L,
    ) {
        val isPaid: Boolean get() = status == "paid"
        val isClosed: Boolean get() = status == "closed"
    }

    /* ---------------- 接口 ---------------- */

    suspend fun bootstrap(deviceId: String, appVersion: String, localTrialStartedMs: Long = 0L): Result<SyncResponse> {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("app_version", appVersion)
        // 补报本地试用起点：trial/start 那次请求可能因为断网、超时或进程被杀而没到服务端，
        // 导致「家长明明开了试用，后台却没有记录」。带上这个值，服务端会 COALESCE 补写，
        // 只在服务端为空时生效，不会覆盖真实起点。
        if (localTrialStartedMs > 0L) body.put("trial_started_ms", localTrialStartedMs)
        return post("/api/v1/app/bootstrap", body, TIMEOUT_SYNC_MS) { data ->
            SyncResponse(
                serverTimeMs = parseTime(data.optString("server_time")),
                trialStartedAtMs = parseTime(data.optJSONObject("trial")?.optString("started_at")),
                trialExpireAtMs = parseTime(data.optJSONObject("trial")?.optString("expire_at")),
                ticket = data.optString("ticket").takeIf { it.isNotBlank() && it != "null" },
                token = data.optJSONObject("license")?.optString("token").orEmpty(),
                licensed = data.optJSONObject("license")?.optBoolean("valid") == true,
                disabled = data.optJSONObject("license")?.optBoolean("disabled") == true,
                permanent = data.optJSONObject("license")?.optBoolean("permanent") == true,
                expireAtMs = parseTime(data.optJSONObject("license")?.optString("expire_at")),
                rebindLimit = data.optJSONObject("rebind")?.optInt("limit") ?: 0,
                rebindUsed = data.optJSONObject("rebind")?.optInt("used") ?: 0,
                contactEmail = data.optString("contact_email"),
            )
        }
    }

    suspend fun startTrial(deviceId: String, appVersion: String): Result<SyncResponse> {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("app_version", appVersion)
        return post("/api/v1/trial/start", body, TIMEOUT_SYNC_MS) { data ->
            // trial/start 的 trial 字段在根部而不是嵌套，和 bootstrap 不一致
            SyncResponse(
                serverTimeMs = parseTime(data.optString("server_time")),
                trialStartedAtMs = parseTime(data.optString("started_at")),
                trialExpireAtMs = parseTime(data.optString("expire_at")),
                ticket = data.optString("ticket").takeIf { it.isNotBlank() && it != "null" },
                token = data.optJSONObject("license")?.optString("token").orEmpty(),
                licensed = data.optJSONObject("license")?.optBoolean("valid") == true,
                disabled = data.optJSONObject("license")?.optBoolean("disabled") == true,
                permanent = data.optJSONObject("license")?.optBoolean("permanent") == true,
                expireAtMs = parseTime(data.optJSONObject("license")?.optString("expire_at")),
            )
        }
    }

    suspend fun createOrder(deviceId: String, planKey: String): Result<PurchaseOrder> {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("plan_key", planKey)
        return post("/api/v1/order/create", body, TIMEOUT_ORDER_MS) { data ->
            PurchaseOrder(
                orderNo = data.optString("order_no"),
                amountCents = data.optInt("amount"),
                method = data.optString("method", "qr"),
                qrContent = data.optString("qr_content"),
                expireIn = data.optInt("expire_in", 900),
            )
        }
    }

    suspend fun getOrder(orderNo: String): Result<OrderState> = request(
        path = "/api/v1/order/$orderNo",
        method = "GET",
        body = null,
        timeoutMs = TIMEOUT_POLL_MS,
    ) { data ->
        OrderState(
            orderNo = data.optString("order_no", orderNo),
            status = data.optString("status", "pending"),
            ticket = data.optString("ticket").takeIf { it.isNotBlank() && it != "null" },
            token = data.optJSONObject("license")?.optString("token").orEmpty(),
            licensed = data.optJSONObject("license")?.optBoolean("valid") == true,
            permanent = data.optJSONObject("license")?.optBoolean("permanent") == true,
            expireAtMs = parseTime(data.optJSONObject("license")?.optString("expire_at")),
            serverTimeMs = parseTime(data.optString("server_time")),
        )
    }

    suspend fun renew(deviceId: String, token: String): Result<SyncResponse> {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("token", token)
        return post("/api/v1/license/renew", body, TIMEOUT_SYNC_MS) { data ->
            SyncResponse(
                serverTimeMs = parseTime(data.optString("server_time")),
                ticket = data.optString("ticket").takeIf { it.isNotBlank() && it != "null" },
                token = data.optString("token"),
                licensed = data.optBoolean("valid"),
                permanent = data.optBoolean("permanent"),
                expireAtMs = parseTime(data.optString("expire_at")),
            )
        }
    }

    /* ---------------- 底层 ---------------- */

    private suspend fun <T> post(
        path: String,
        body: JSONObject,
        timeoutMs: Int,
        parse: (JSONObject) -> T,
    ): Result<T> = request(path, "POST", body.toString(), timeoutMs, parse)

    /**
     * 所有网络调用的唯一出口。
     *
     * 这层 [withContext] 不能省。`suspend` 只是给调用方一个挂起标记，
     * 函数体里的 `HttpURLConnection` 连接、握证书、读响应全是**阻塞**调用，
     * 跑在哪个线程就堵哪个线程。之前它就是直接跑在 `viewModelScope`（主线程）上：
     * 下单最多冻 10 秒、每 2 秒一次的轮询各冻 6 秒 —— 表现就是
     * 「点去支付卡住、转圈不转、点取消没反应、付款后半天不解锁」，
     * 其实是 UI 线程被 IO 占死了，不是 Cloudflare 慢。
     */
    private suspend fun <T> request(
        path: String,
        method: String,
        body: String?,
        timeoutMs: Int,
        parse: (JSONObject) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        requestBlocking(path, method, body, timeoutMs, parse)
    }

    private fun <T> requestBlocking(
        path: String,
        method: String,
        body: String?,
        timeoutMs: Int,
        parse: (JSONObject) -> T,
    ): Result<T> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }

            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            parseEnvelope(code, text, parse)
        } catch (io: IOException) {
            // 断网、DNS 失败、超时都归到这里：调用方一律静默处理，绝不弹错误
            Result.Err("NETWORK_ERROR", io.message ?: "网络不可用")
        } catch (t: Throwable) {
            Result.Err("CLIENT_ERROR", t.message ?: "请求失败")
        } finally {
            connection?.disconnect()
        }
    }

    private fun <T> parseEnvelope(httpCode: Int, text: String, parse: (JSONObject) -> T): Result<T> {
        if (text.isBlank()) return Result.Err("EMPTY_RESPONSE", "服务无响应（HTTP $httpCode）")
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return Result.Err("BAD_RESPONSE", "响应不是合法 JSON")

        if (!root.optBoolean("ok", false)) {
            val error = root.optJSONObject("error")
            return Result.Err(
                code = error?.optString("code").orEmpty().ifBlank { "HTTP_$httpCode" },
                message = error?.optString("message").orEmpty().ifBlank { "服务返回失败" },
            )
        }
        val data = root.optJSONObject("data") ?: return Result.Err("BAD_RESPONSE", "缺少 data")
        return runCatching { Result.Ok(parse(data)) }
            .getOrElse { Result.Err("BAD_RESPONSE", it.message ?: "解析失败") }
    }

    /** 服务端统一返回北京时间的 `yyyy-MM-dd HH:mm:ss`；空串或不存在返回 0。 */
    internal fun parseTime(value: String?): Long {
        if (value.isNullOrBlank() || value == "null") return 0L
        return runCatching {
            SimpleDateFormat(SERVER_TIME_PATTERN, Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
                .parse(value)
                ?.time ?: 0L
        }.getOrDefault(0L)
    }
}
