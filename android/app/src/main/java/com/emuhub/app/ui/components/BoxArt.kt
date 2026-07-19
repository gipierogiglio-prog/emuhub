package com.emuhub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import java.io.File

/** Converte "#RRGGBB" do catálogo em Color, com fallback cinza. */
fun parseSystemColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Color(0xFF444444)
}

/**
 * * Capa do jogo: imagem real quando existir (EmuHub/media/<sistema>/<nome>.png),
 * senão placeholder com gradiente na cor do sistema + título.
 */
@Composable
fun BoxArt(
    title: String,
    systemColor: Color,
    systemShortName: String,
    coverPath: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (coverPath != null) {
            AsyncImage(
                model = File(coverPath),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(systemColor.copy(alpha = 0.85f), systemColor.copy(alpha = 0.35f))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Text(
                text = systemShortName,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
            )
        }
    }
}
