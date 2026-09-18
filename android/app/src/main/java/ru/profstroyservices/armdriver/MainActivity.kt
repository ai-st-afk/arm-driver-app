package ru.profstroyservices.armdriver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import ru.profstroyservices.armdriver.ui.AppNavHost
import ru.profstroyservices.armdriver.ui.theme.ArmDriverTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArmDriverTheme {
                AppNavHost()
            }
        }
    }
}
