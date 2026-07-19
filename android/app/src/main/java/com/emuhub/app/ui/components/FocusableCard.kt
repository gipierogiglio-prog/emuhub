package com.emuhub.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.emuhub.app.ui.theme.EmuHubRed
import com.emuhub.app.ui.theme.EmuHubSurface

/**
 * * Cartão focável padrão do EmuHub: quando focado por D-pad (ou hover),
 * ganha borda vermelha e escala, no estilo EmulationStation.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    backgroundColor: Color = EmuHubSurface,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "focusScale")
    val shape = RoundedCornerShape(10.dp)

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(backgroundColor, shape)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) EmuHubRed else Color(0x33FFFFFF),
                shape = shape,
            )
            // Botão A do gamepad = click (Enter/DPAD_CENTER o clickable já trata)
            .onKeyEvent { event ->
                if (event.key == Key.ButtonA) {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                } else {
                    false
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        content(focused)
    }
}
