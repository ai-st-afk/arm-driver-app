package ru.profstroyservices.armdriver.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Телефон ещё не привязан к водителю. Полей ввода здесь нет специально:
// водитель получает аппарат уже настроенным, а разбираться с
// идентификаторами — не его работа. Для того, кто выдаёт телефон, внизу
// неприметная ссылка на служебный экран.
@Composable
fun NotConfiguredScreen() {
    var showService by remember { mutableStateOf(false) }

    if (showService) {
        ServiceScreen(onClose = { showService = false })
        return
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Телефон не настроен",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Обратитесь к диспетчеру, чтобы получить настроенный телефон.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp)
            )
            TextButton(
                onClick = { showService = true },
                modifier = Modifier.padding(top = 32.dp)
            ) {
                Text("Служебный вход")
            }
        }
    }
}
