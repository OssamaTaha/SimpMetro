package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

@Composable
actual fun QrCodeView(
    content: String,
    modifier: Modifier,
    sizeDp: Dp,
    backgroundColor: Color,
    codeColor: Color,
) {
    Box(modifier = modifier.size(sizeDp))
}
