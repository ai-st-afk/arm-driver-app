package ru.profstroyservices.armdriver.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.profstroyservices.armdriver.data.repository.QueueState

// Инвариант 6 из AGENTS.md: неотправленные события видны всегда, а не на
// одном экране. Залипшая очередь ломает утренний выпуск машины на линию,
// поэтому баннер живёт в оболочке и виден на любой вкладке.
@Composable
fun UnsentBanner(state: QueueState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (state.pendingCount == 0) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Не отправлено: ${state.pendingCount}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            // networkError важнее: если сейчас нет связи вообще, это и есть
            // причина, а отказ 1С по прошлой попытке — уже не главное.
            val reasonText = state.networkError ?: state.rejectionReason?.let { "1С отклонила: $it" }
            reasonText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(start = 12.dp)) {
            Text("Повторить")
        }
    }
}
