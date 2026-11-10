package dev.anilbeesetti.nextplayer.settings.screens.parent

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 二维码渲染边长（像素）。固定 720 而不是随 dp 走：屏幕再密也够清晰，且只算一次。 */
private const val QR_BITMAP_PX = 720

/**
 * 二维码静区（quirt zone）。
 *
 * 规范要求至少 4 个模块，这里给 3——留白越多码越小、越难扫，
 * 而我们外面还有一层白底 padding 兜着，实际静区是够的。
 * 太多人栽在「为了好看把白边去掉」，旧机型识别率会明显下降。
 */
private const val QR_MARGIN_MODULES = 3

/**
 * 本地渲染二维码。
 *
 * 为什么不用服务端返回的图片：服务端在 Workers 里只能出 SVG（没有 canvas），
 * 而 Android 原生不支持 SVG。让客户端用 ZXing 现画，既不用引 SVG 解析库，
 * 也能按屏幕密度出高清位图。
 *
 * [content] 变化时重画；画失败返回 null，调用方负责显示兜底文案，
 * 绝不能因为这个失败让用户卡在空白的支付页。
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content) {
        runCatching { encodeQr(content) }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                // 白底 + 圆角：黑码落在深色界面上会损失对比度，且圆角更贴合整体视觉
                .clip(RoundedCornerShape(12.dp))
                .background(ComposeColor.White)
                .padding(12.dp),
        )
    }
}

private fun encodeQr(content: String): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        // M 级容错约 15%，兼顾尺寸与「屏幕反光/轻微遮挡」的容错
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to QR_MARGIN_MODULES,
    )
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        QR_BITMAP_PX,
        QR_BITMAP_PX,
        hints,
    )

    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val rowOffset = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[rowOffset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }
}
