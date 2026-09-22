package ru.profstroyservices.armdriver.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.profstroyservices.armdriver.BuildConfig
import ru.profstroyservices.armdriver.ui.components.LabeledField

private val ButtonHeight = 56.dp

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showService by remember { mutableStateOf(false) }

    if (showService) {
        ServiceScreen(
            onClose = {
                showService = false
                viewModel.load()
            }
        )
        return
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Настройки", style = MaterialTheme.typography.headlineSmall)

            uiState.driverName?.let { LabeledField(label = "Водитель", value = it) }
            uiState.vehicle?.let { LabeledField(label = "Машина", value = it) }

            if (BuildConfig.DISPATCHER_PHONE.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${BuildConfig.DISPATCHER_PHONE}"))
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(ButtonHeight)
                ) {
                    Text("Позвонить диспетчеру", style = MaterialTheme.typography.labelLarge)
                }
            }

            LabeledField(label = "Версия приложения", value = BuildConfig.VERSION_NAME)

            TextButton(onClick = { showService = true }) {
                Text("Служебный вход")
            }
        }
    }
}
