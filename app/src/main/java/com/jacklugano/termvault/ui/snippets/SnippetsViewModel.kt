package com.jacklugano.termvault.ui.snippets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacklugano.termvault.data.db.SnippetDao
import com.jacklugano.termvault.data.db.SnippetEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SnippetsViewModel @Inject constructor(
    private val snippetDao: SnippetDao,
) : ViewModel() {

    val snippets: StateFlow<List<SnippetEntity>> = snippetDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(snippet: SnippetEntity) {
        viewModelScope.launch { snippetDao.upsert(snippet) }
    }

    fun delete(snippet: SnippetEntity) {
        viewModelScope.launch { snippetDao.delete(snippet) }
    }
}
