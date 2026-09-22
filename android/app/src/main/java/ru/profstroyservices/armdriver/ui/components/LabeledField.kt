package ru.profstroyservices.armdriver.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

// Подпись сверху, значение снизу — вместо "Подпись: значение" в одну строку.
// Разбивает разнарядку/рейс на визуально отдельные пункты, а не сплошной
// абзац (жалоба автора: "нет визуального разделения на пункты").
//
// onClick задаётся там, где по значению можно куда-то перейти (адрес →
// навигатор). Тогда значение подчёркнуто и окрашено акцентом: скрытых
// кликабельных зон на этом экране быть не должно, водитель не обязан
// догадываться, что текст нажимается.
@Composable
fun LabeledField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Column(modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (onClick != null) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (onClick != null) TextDecoration.Underline else null,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
