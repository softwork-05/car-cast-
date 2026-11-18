package com.carcast.mirror.engine.airplay

import com.carcast.mirror.model.ConnectedClient

interface AirPlayStreamListener {
    fun onClientConnected(client: ConnectedClient)
    fun onStreamStarted(client: ConnectedClient)
    fun onVideoFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long)
    fun onAudioFrame(data: ByteArray, offset: Int, length: Int)
    fun onVideoDimensions(width: Int, height: Int) {}
    fun onClientDisconnected()
}
