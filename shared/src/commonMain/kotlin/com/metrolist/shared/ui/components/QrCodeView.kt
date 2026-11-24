package com.metrolist.shared.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
expect fun QrCodeView(
    content: String,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 200.dp,
    backgroundColor: Color = Color.White,
    codeColor: Color = Color.Black,
)
