package com.example.bletest.data.ble
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.example.bletest.data.ConnectionState
import com.example.bletest.data.TempSendResult
import com.example.bletest.data.TemperatureAndHumidityReceiveManager
import com.example.bletest.util.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
@SuppressLint("MissingPermission")
class TemperatureAndHumidityBLEReceiveManager @Inject constructor(
    private val bluetoothAdapter:BluetoothAdapter,
    private val context: Context
) : TemperatureAndHumidityReceiveManager{

    private val DEVICE_NAME = "inkBuddy"

    private val SERVICE_UUID_DEVICE      =   "0000180a-0000-1000-8000-00805f9b34fb"
    private val CHARACTERISTIC_UUID_FW   =   "00002a26-0000-1000-8000-00805f9b34fb"
    private val CHARACTERISTIC_UUID_HW   =   "00002a27-0000-1000-8000-00805f9b34fb"

    override val data: MutableSharedFlow<Resource<TempSendResult>> = MutableSharedFlow()


    private val bleScanner by lazy{
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private var gatt: BluetoothGatt? = null

    private var isScanning = false

    private val corutineScope = CoroutineScope(Dispatchers.Default)

    private val scanCallback = object : ScanCallback(){
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if(result.device.name == DEVICE_NAME){
                corutineScope.launch {
                    data.emit(Resource.Loading(message = "Connecting to device"))
                }
                if(isScanning){
                    result.device.connectGatt(context,false,gattCallback)
                    isScanning = false
                    bleScanner.stopScan(this)
                }
            }
        }
    }
    private var currentConnectionAttempt =1
    private var MAXIMUM_CONNECTION_ATTEMPTS = 5
    private val gattCallback = object : BluetoothGattCallback(){
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if(status==BluetoothGatt.GATT_SUCCESS){
                if(newState==BluetoothProfile.STATE_CONNECTED){
                    corutineScope.launch {
                        data.emit(Resource.Loading(message = "Discovering services"))

                    }
                    gatt.discoverServices()
                    this@TemperatureAndHumidityBLEReceiveManager.gatt = gatt
                }else if(newState == BluetoothProfile.STATE_DISCONNECTED){
                    corutineScope.launch {
                        data.emit(Resource.Success(data = TempSendResult(0f,0f, ConnectionState.Disconnected)))
                    }
                    gatt.close()
                }

            }else{
                gatt.close()
                currentConnectionAttempt += 1
                corutineScope.launch {
                    data.emit(
                        Resource.Loading(
                            message = "Attempting to connect $currentConnectionAttempt/$MAXIMUM_CONNECTION_ATTEMPTS"
                        )
                    )
                }
                if(currentConnectionAttempt<=MAXIMUM_CONNECTION_ATTEMPTS){
                    startReceiving()
                }else{
                    corutineScope.launch {
                        data.emit(Resource.Error(errorMessage = "Could not connect to BLE device"))
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with(gatt){
                printGattTable()
                corutineScope.launch {
                    data.emit(Resource.Loading(message = "Adjusting MTU space"))
                }
                gatt.requestMtu(517)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val characteristic = findCharacteristics(SERVICE_UUID_DEVICE,CHARACTERISTIC_UUID_FW)
            if(characteristic == null){
                corutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "Could not find FW published"))
                    return@launch
                }
            }else {
                enableNotification(characteristic)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            with(characteristic){
                when(uuid){
                    UUID.fromString(CHARACTERISTIC_UUID_FW)->{
//                        val multiplicator = if(value.first().toInt()>0) -1 else 1
                        val fwversion = value[1].toFloat()
                        val fwversion2 = value[2].toFloat()
                        val fwVersionResult = TempSendResult(
                            fwversion,
                            fwversion2,
                            ConnectionState.Connected
                        )
                        corutineScope.launch {
                            data.emit(Resource.Success(data=fwVersionResult))
                        }
                    }
                    else -> Unit
                }
            }
        }

//        override fun onCharacteristicRead(
//            gatt: BluetoothGatt?,
//            characteristic: BluetoothGattCharacteristic?,
//            status: Int
//        ) {
//            characteristic?.value
//        }
    }


//    private fun example(){
//        val characteristic = gatt?.getService(UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"))?.getCharacteristic(UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb"))
//        gatt?.readCharacteristic(characteristic)
//    }

    private fun enableNotification(characteristic:BluetoothGattCharacteristic){
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        val payload =when {
            characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else-> return
        }
        characteristic.getDescriptor(cccdUuid)?.let {cccdDescriptor ->
            if(gatt?.setCharacteristicNotification(characteristic,true) == false){
                Log.d("BLEReceiveManager","SetCharacteristics notification failed")
                return
            }
            writeDescriptor(cccdDescriptor,payload)
        }
    }
    private fun writeDescriptor(descriptor: BluetoothGattDescriptor,payload:ByteArray){
        gatt?.let {gatt ->
            descriptor.value = payload
            gatt.writeDescriptor(descriptor)
        }?: error("Not connected to a BLE device")
    }


    private fun findCharacteristics(serviceUUID: String,characteristicsUUID: String ):BluetoothGattCharacteristic?{
        return gatt?.services?.find {service->
            service.uuid.toString() == serviceUUID
        }?.characteristics?.find{ characteristics ->
            characteristics.uuid.toString()==characteristicsUUID
        }
    }

    override fun startReceiving() {
        corutineScope.launch {
            data.emit(Resource.Loading(message = "Scanning BLE devices"))
        }
        isScanning = true
        bleScanner.startScan(null,scanSettings,scanCallback)
    }


    override fun reconnect() {
        gatt?.connect()
    }

    override fun disconnect() {
        gatt?.disconnect()
    }


    override fun closeConnection() {
        bleScanner.stopScan(scanCallback)
        val characteristic =findCharacteristics(SERVICE_UUID_DEVICE,CHARACTERISTIC_UUID_FW)
        if(characteristic != null){
            disconnectCharacteristic(characteristic)
        }
        gatt?.close()
    }
    private fun disconnectCharacteristic(characteristic: BluetoothGattCharacteristic){
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let{cccdDescriptor ->
            if(gatt?.setCharacteristicNotification(characteristic,false)==false){
                Log.d("TempHumidReceiveManager", "set characteristics notification failed")
                return
            }
            writeDescriptor(cccdDescriptor,BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }
    }


}