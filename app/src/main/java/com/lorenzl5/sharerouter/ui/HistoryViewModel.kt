package com.lorenzl5.sharerouter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lorenzl5.sharerouter.AppContainer
import com.lorenzl5.sharerouter.data.history.HistoryStore
import com.lorenzl5.sharerouter.data.history.ResponseRecord
import kotlinx.coroutines.flow.StateFlow

/** Backs the history screen: exposes stored responses and allows deleting them. */
class HistoryViewModel(private val history: HistoryStore) : ViewModel() {

    val records: StateFlow<List<ResponseRecord>> = history.records

    fun delete(id: String) = history.delete(id)

    fun clearAll() = history.clear()

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(container.history) as T
    }
}
