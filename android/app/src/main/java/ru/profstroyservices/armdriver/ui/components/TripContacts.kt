package ru.profstroyservices.armdriver.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.profstroyservices.armdriver.data.network.TripContactDto

@Composable
fun TripContacts(contacts: List<TripContactDto>) {
    val context = LocalContext.current
    contacts.forEach { contact ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${contact.purpose} · ${contact.role}", style = MaterialTheme.typography.titleSmall)
            if (contact.name.isNotBlank()) {
                Text(contact.name, style = MaterialTheme.typography.bodyLarge)
            }
            contact.phones.forEach { phone ->
                val number = phone.dialNumber()
                val display = firstNotBlank(phone.display, phone.number)
                if (display != null) {
                    LabeledField(
                        label = "Телефон",
                        value = display,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        onClick = number?.let {
                            {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", it, null)))
                                } catch (e: ActivityNotFoundException) {
                                    Log.e("TripContacts", "Dialer unavailable", e)
                                    Toast.makeText(context, "Не удалось открыть приложение для звонка", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
