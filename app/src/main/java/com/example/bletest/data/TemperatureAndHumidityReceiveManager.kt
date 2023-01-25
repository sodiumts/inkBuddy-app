package com.example.bletest.data
import com.example.bletest.util.Resource
import kotlinx.coroutines.flow.MutableSharedFlow
interface TemperatureAndHumidityReceiveManager {

    val data: MutableSharedFlow<Resource<ReadResult>>
    fun reconnect()

    fun disconnect()

    fun startReceiving()

    fun closeConnection()

    fun readCharacteristic()

    fun writeImage(payload:ByteArray)
}