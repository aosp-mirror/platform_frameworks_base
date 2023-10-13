package com.android.systemui.communal.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun CommunalHub(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.White),
    ) {
        Text(
            modifier = Modifier.align(Alignment.Center),
            text = "Hello Communal!",
        )
    }
}
