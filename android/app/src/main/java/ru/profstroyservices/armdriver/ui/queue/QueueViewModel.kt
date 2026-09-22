package ru.profstroyservices.armdriver.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.repository.QueueRepository
import ru.profstroyservices.armdriver.data.repository.QueueState
import javax.inject.Inject

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val queue: QueueRepository
) : ViewModel() {

    val state: StateFlow<QueueState> = queue.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QueueState())

    fun onRetry() {
        viewModelScope.launch { queue.flush() }
    }
}
