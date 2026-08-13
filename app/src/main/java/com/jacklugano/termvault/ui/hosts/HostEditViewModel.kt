package com.jacklugano.termvault.ui.hosts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacklugano.termvault.data.db.AuthMode
import com.jacklugano.termvault.data.db.ForwardType
import com.jacklugano.termvault.data.db.HostDao
import com.jacklugano.termvault.data.db.HostEntity
import com.jacklugano.termvault.data.db.PortForwardDao
import com.jacklugano.termvault.data.db.PortForwardEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HostEditState(
    val id: Long = 0,
    val name: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val tags: String = "",
    val color: Int = HostEntity.DEFAULT_COLOR,
    val jumpHostId: Long? = null,
    val authMode: AuthMode = AuthMode.KP2A,
    val kp2aQuery: String = "",
    val kp2aForPassphrase: Boolean = false,
    val localKeyAlias: String? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class HostEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val hostDao: HostDao,
    private val portForwardDao: PortForwardDao,
) : ViewModel() {

    private val hostId: Long = savedStateHandle.get<Long>("hostId") ?: 0L

    private val _state = MutableStateFlow(HostEditState())
    val state: StateFlow<HostEditState> = _state

    private val _otherHosts = MutableStateFlow<List<HostEntity>>(emptyList())
    val otherHosts: StateFlow<List<HostEntity>> = _otherHosts

    val forwards: StateFlow<List<PortForwardEntity>> =
        portForwardDao.observeForHost(hostId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val existing = if (hostId != 0L) hostDao.getById(hostId) else null
            if (existing != null) {
                _state.value = HostEditState(
                    id = existing.id,
                    name = existing.name,
                    hostname = existing.hostname,
                    port = existing.port.toString(),
                    username = existing.username,
                    tags = existing.tags,
                    color = existing.color,
                    jumpHostId = existing.jumpHostId,
                    authMode = existing.authMode,
                    kp2aQuery = existing.kp2aQuery,
                    kp2aForPassphrase = existing.kp2aForPassphrase,
                    localKeyAlias = existing.localKeyAlias,
                    loaded = true,
                )
            } else {
                _state.value = _state.value.copy(loaded = true)
            }
            _otherHosts.value = hostDao.getAllExcept(hostId)
        }
    }

    fun update(transform: (HostEditState) -> HostEditState) {
        _state.value = transform(_state.value)
    }

    fun canSave(): Boolean {
        val s = _state.value
        return s.name.isNotBlank() && s.hostname.isNotBlank() &&
            ((s.port.toIntOrNull() ?: 0) in 1..65535)
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (!canSave()) return
        viewModelScope.launch {
            hostDao.upsert(
                HostEntity(
                    id = s.id,
                    name = s.name.trim(),
                    hostname = s.hostname.trim(),
                    port = s.port.toIntOrNull() ?: 22,
                    username = s.username.trim(),
                    tags = s.tags,
                    color = s.color,
                    jumpHostId = s.jumpHostId,
                    authMode = s.authMode,
                    kp2aQuery = s.kp2aQuery.trim(),
                    kp2aForPassphrase = s.kp2aForPassphrase,
                    localKeyAlias = s.localKeyAlias,
                )
            )
            onSaved()
        }
    }

    fun addForward(type: ForwardType, bindPort: Int, targetHost: String, targetPort: Int) {
        if (hostId == 0L) return
        viewModelScope.launch {
            portForwardDao.upsert(
                PortForwardEntity(
                    hostId = hostId,
                    type = type,
                    bindPort = bindPort,
                    targetHost = targetHost.ifBlank { "127.0.0.1" },
                    targetPort = targetPort,
                )
            )
        }
    }

    fun deleteForward(forward: PortForwardEntity) {
        viewModelScope.launch { portForwardDao.delete(forward) }
    }
}
