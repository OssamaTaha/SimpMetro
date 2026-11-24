package com.metrolist.shared.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
actual fun QrCodeView(
    content: String,
    modifier: Modifier,
    sizeDp: Dp,
    backgroundColor: Color,
    codeColor: Color,
) {
    val bitMatrix = remember(content) {
        runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 256, 256, hints)
        }.getOrNull()
    }

    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitMatrix != null) {
            Canvas(modifier = Modifier.fillMaxSize().aspectRatio(1f)) {
                val matrixWidth = bitMatrix.width
                val matrixHeight = bitMatrix.height
                val cellWidth = size.width / matrixWidth
                val cellHeight = size.height / matrixHeight

                for (x in 0 until matrixWidth) {
                    for (y in 0 until matrixHeight) {
                        if (bitMatrix.get(x, y)) {
                            drawRect(
                                color = codeColor,
                                topLeft = Offset(x * cellWidth, y * cellHeight),
                                size = Size(cellWidth + 0.5f, cellHeight + 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}
