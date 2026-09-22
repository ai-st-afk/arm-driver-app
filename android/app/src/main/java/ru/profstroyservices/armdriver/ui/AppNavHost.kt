package ru.profstroyservices.armdriver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import ru.profstroyservices.armdriver.ui.settings.NotConfiguredScreen

// Верхний уровень решает ровно один вопрос: привязан телефон к водителю или
// нет. Привязан — сразу таб-бар, вся остальная навигация внутри MainScaffold.
// Не привязан — заглушка без полей ввода: настраивает телефон тот, кто его
// выдаёт, а не водитель.
@Composable
fun AppNavHost(viewModel: AppNavViewModel = hiltViewModel()) {
    when (val state = viewModel.state.collectAsState().value) {
        is AppNavUiState.Loading -> LoadingGate()
        is AppNavUiState.Ready -> if (state.hasDriverId) {
            MainScaffold()
        } else {
            NotConfiguredScreen()
        }
    }
}

@Composable
private fun LoadingGate() {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
        }
    }
}
