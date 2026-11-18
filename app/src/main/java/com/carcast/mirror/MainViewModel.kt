package com.carcast.mirror

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carcast.mirror.engine.NetworkInfoState
import com.carcast.mirror.model.ConnectedClient
import com.carcast.mirror.model.ConnectionStatus
import com.carcast.mirror.model.DisplayConfig
import com.carcast.mirror.service.AndroidReceiverDiagnostics
import com.carcast.mirror.service.CarCastService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedClient = MutableStateFlow<ConnectedClient?>(null)
    val connectedClient: StateFlow<ConnectedClient?> = _connectedClient.asStateFlow()

    private val _networkInfo = MutableStateFlow(NetworkInfoState())
    val networkInfo: StateFlow<NetworkInfoState> = _networkInfo.asStateFlow()

    private val _displayConfig = MutableStateFlow(DisplayConfig())
    val displayConfig: StateFlow<DisplayConfig> = _displayConfig.asStateFlow()

    private val _streamAspectRatio = MutableStateFlow(16f / 9f)
    val streamAspectRatio: StateFlow<Float> = _streamAspectRatio.asStateFlow()

    private val _androidDiagnostics = MutableStateFlow(AndroidReceiverDiagnostics())
    val androidDiagnostics: StateFlow<AndroidReceiverDiagnostics> = _androidDiagnostics.asStateFlow()

    var isSettingsDialogOpen = MutableStateFlow(false)

    fun initBroadcastName(name: String) {
        if (name.isNotBlank()) {
            _displayConfig.value = _displayConfig.value.copy(broadcastName = name)
        }
    }

    fun bindService(service: CarCastService) {
        viewModelScope.launch {
            service.connectionStatus.collect { status ->
                _connectionStatus.value = status
            }
        }
        viewModelScope.launch {
            service.connectedClient.collect { client ->
                _connectedClient.value = client
            }
        }
        viewModelScope.launch {
            service.getNetworkState().collect { netInfo ->
                _networkInfo.value = netInfo
            }
        }
        viewModelScope.launch {
            service.displayConfig.collect { config ->
                _displayConfig.value = config
            }
        }
        viewModelScope.launch {
            service.streamAspectRatio.collect { aspect ->
                _streamAspectRatio.value = aspect
            }
        }
        viewModelScope.launch {
            service.androidDiagnostics.collect { diag ->
                _androidDiagnostics.value = diag
            }
        }
    }

    fun updateConfig(config: DisplayConfig, service: CarCastService?) {
        _displayConfig.value = config
        service?.updateDisplayConfig(config)
    }

    fun updateDeviceName(name: String, service: CarCastService?) {
        _displayConfig.value = _displayConfig.value.copy(broadcastName = name)
        service?.updateBroadcastName(name)
    }

    fun disconnectStream(service: CarCastService?) {
        service?.disconnectActiveStream()
    }
}
