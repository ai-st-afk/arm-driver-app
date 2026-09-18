package ru.profstroyservices.armdriver.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SetupScreen(onContinue: () -> Unit, viewModel: SetupViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Настройка", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Временный экран: пока нет решения, как приложение " +
                    "узнаёт своего водителя автоматически, GUID вводится вручную.",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = uiState.driverIdInput,
                onValueChange = viewModel::onDriverIdInputChange,
                label = { Text("GUID водителя (из 1С)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = viewModel::onSaveAndCheck,
                enabled = uiState.driverIdInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сохранить и запросить разнарядку")
            }

            uiState.savedDriverId?.let {
                Text(
                    text = "Сохранено: $it",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text("Перейти к разнарядке")
                }
            }

            when (val state = uiState.loadState) {
                is AssignmentLoadState.Idle -> Unit
                is AssignmentLoadState.Loading -> CircularProgressIndicator()
                is AssignmentLoadState.Success -> SelectionContainer {
                    Text(
                        text = state.raw,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is AssignmentLoadState.Error -> Text(
                    text = "Ошибка: ${state.message}",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
