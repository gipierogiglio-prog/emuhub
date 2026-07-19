package com.emuhub.app.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.emuhub.app.R
import com.emuhub.app.ui.navigation.LocalAppContainer
import com.emuhub.app.ui.theme.EmuHubBlack
import com.emuhub.app.ui.theme.EmuHubGray
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun SplashScreen(onDone: (onboardingDone: Boolean) -> Unit) {
    val container = LocalAppContainer.current

    LaunchedEffect(Unit) {
        val done = container.settingsRepository.onboardingDone.first()
        delay(1400)
        onDone(done)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EmuHubBlack),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.emuhub_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(160.dp),
            )
            Text(
                text = stringResource(R.string.slogan),
                style = MaterialTheme.typography.bodyMedium,
                color = EmuHubGray,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
