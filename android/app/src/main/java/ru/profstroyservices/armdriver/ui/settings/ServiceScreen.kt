package ru.profstroyservices.armdriver.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private val ButtonHeight = 64.dp

@Composable
fun ServiceScreen(
    onClose: () -> Unit,
    viewModel: ServiceViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Служебный вход", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Привязка телефона к водителю. Идентификатор водителя " +
                    "берётся из 1С, водителю его вводить не нужно.",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = uiState.driverIdInput,
                onValueChange = viewModel::onDriverIdInputChange,
                label = { Text("Идентификатор водителя из 1С") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = viewModel::onBind,
                enabled = uiState.driverIdInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(ButtonHeight)
            ) {
                Text("Привязать телефон", style = MaterialTheme.typography.labelLarge)
            }

            when (val state = uiState.bindState) {
                is BindState.Idle -> Unit

                is BindState.Checking -> CircularProgressIndicator()

                is BindState.Bound -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.driverName ?: "Телефон привязан",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (state.assignments > 0) {
                            "Разнарядок доступно: ${state.assignments}"
                        } else {
                            "Разнарядок сейчас нет — это нормально, если их ещё не выпустили"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                is BindState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error
                )
            }

            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Закрыть")
            }
        }
    }
}
