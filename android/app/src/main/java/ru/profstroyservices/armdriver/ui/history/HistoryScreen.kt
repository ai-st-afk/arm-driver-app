package ru.profstroyservices.armdriver.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.profstroyservices.armdriver.ui.components.formatEventDateTime

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsState()

    Scaffold { innerPadding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Событий пока нет", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.id }) { item -> HistoryRow(item) }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItemUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = item.typeLabel,
                style = MaterialTheme.typography.bodyLarge,
                // Отменённое действие видно в истории (водитель сам это
                // отменил), но зачёркнуто — чтобы не путать с реально
                // случившимся шагом.
                textDecoration = if (item.cancelled) TextDecoration.LineThrough else null,
                color = if (item.cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Text(text = formatEventDateTime(item.time), style = MaterialTheme.typography.bodySmall)
            Text(
                text = when {
                    item.cancelled -> "отменено водителем"
                    item.sent -> "отправлено"
                    else -> "в очереди"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!item.cancelled && !item.sent) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            item.error?.let { error ->
                Text(
                    text = "1С отклонила: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
