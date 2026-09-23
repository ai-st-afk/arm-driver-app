package ru.profstroyservices.armdriver.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Номера диспетчера нет в контракте с 1С — один на всех, задан автором.
const val DISPATCHER_PHONE = "+79229611634"

// Срыв рейса, отказ от разнарядки, любые изменения — только через
// диспетчера: он правит разнарядку в 1С, приложение получает новую версию.
// ACTION_DIAL открывает звонилку с номером и не требует разрешения на звонки.
@Composable
fun CallDispatcherButton(text: String = "Позвонить диспетчеру", modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$DISPATCHER_PHONE")))
            }
        },
        // heightIn, а не height: подпись в две строки иначе обрезалась снизу.
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
    ) {
        Icon(Icons.Filled.Call, contentDescription = null)
        Text(text, modifier = Modifier.padding(start = 8.dp))
    }
}
