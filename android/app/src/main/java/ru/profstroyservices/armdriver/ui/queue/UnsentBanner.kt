package ru.profstroyservices.armdriver.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.profstroyservices.armdriver.data.repository.QueueState

// Инвариант 6 из AGENTS.md: неотправленные события видны всегда, а не на
// одном экране. Залипшая очередь ломает утренний выпуск машины на линию,
// поэтому баннер живёт в оболочке и виден на любой вкладке. «Закрыть» не
// удаляет проблему (счётчик и причина никуда не деваются из БД) — только
// прячет баннер, пока проблема не изменится (другое число или другой текст
// ошибки), тогда он появится снова сам.
@Composable
fun UnsentBanner(state: QueueState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (state.pendingCount == 0) return

    val signature = "${state.pendingCount}|${state.networkError}|${state.rejectionReason}|${state.photoRejectionReason}"
    var dismissedSignature by remember { mutableStateOf<String?>(null) }
    if (signature == dismissedSignature) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
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
            // События и фото — разные потоки к 1С (разные эндпоинты), у
            // каждого своя причина: событие может уйти нормально, а фото в
            // это же время не приниматься (и наоборот). Обе строки — не
            // одна, чтобы не потерять любую из них.
            listOfNotNull(
                state.networkError,
                state.rejectionReason?.let { "1С отклонила событие: $it" },
                state.photoRejectionReason?.let { "Фото: $it" }
            ).forEach { text ->
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
        IconButton(onClick = { dismissedSignature = signature }) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Скрыть",
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
