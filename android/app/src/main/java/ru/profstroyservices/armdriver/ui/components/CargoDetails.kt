package ru.profstroyservices.armdriver.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.profstroyservices.armdriver.data.network.CargoPositionsDto

@Composable
fun CargoDetails(
    positions: CargoPositionsDto?,
    weight: String?,
    volume: String?,
    legacyComposition: String? = null
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (positions == null) {
            legacyComposition?.takeIf { it.isNotBlank() }?.let {
                LabeledField(label = "Описание груза", value = it)
            }
            Text("Количество по позициям ещё не передано", style = MaterialTheme.typography.bodySmall)
        } else if (positions.items.isEmpty()) {
            Text("Нет позиций груза", style = MaterialTheme.typography.bodyMedium)
        } else {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text("Товары (${positions.items.size})", modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Свернуть товары" else "Показать товары"
                )
            }
            if (expanded) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(positions.items) { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LabeledField(label = item.name, value = "${item.quantity} ${item.unit}".trim())
                            Text(
                                text = "Вес: ${item.weight} т · Объём: ${item.volume} м³",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
        weight?.takeIf { it.isNotBlank() }?.let { LabeledField(label = "Общий вес", value = "$it т") }
        volume?.takeIf { it.isNotBlank() }?.let { LabeledField(label = "Общий объём", value = "$it м³") }
    }
}
