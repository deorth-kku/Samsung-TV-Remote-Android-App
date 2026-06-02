package com.vibecode.tvremote

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("tv_remote_prefs", Context.MODE_PRIVATE)
    private val scanner = NetworkScanner(context)
    
    var isScanning by mutableStateOf(false)
        private set
        
    val discoveredTvs = mutableStateListOf<DiscoveredTv>()
    
    var currentTvIp by mutableStateOf<String?>(null)
        private set
        
    var currentTvName by mutableStateOf<String?>(null)
        private set
        
    var currentTvMacAddress by mutableStateOf<String?>(null)
        private set
        
    var connectionState by mutableStateOf(SamsungTvClient.State.DISCONNECTED)
        private set

    var isWaitingForWol by mutableStateOf(false)
        private set

    private var tvClient: SamsungTvClient? = null

    init {
        currentTvIp = prefs.getString("saved_tv_ip", null)
        currentTvName = prefs.getString("saved_tv_name", "Paired Samsung TV")
        currentTvMacAddress = prefs.getString("saved_tv_mac", null)
        val savedToken = currentTvIp?.let { ip ->
            prefs.getString("saved_tv_token_$ip", null) ?: prefs.getString("saved_tv_token", null)
        }

        tvClient = SamsungTvClient(
            appName = "VibeRemote",
            onStateChanged = { state ->
                connectionState = state
            },
            onTokenReceived = { token ->
                saveToken(token)
            }
        )

        currentTvIp?.let { ip ->
            tvClient?.connect(ip, savedToken)
        }
    }

    fun selectTv(tv: DiscoveredTv) {
        disconnect()
        currentTvIp = tv.ip
        currentTvName = tv.name
        
        prefs.edit()
            .putString("saved_tv_ip", tv.ip)
            .putString("saved_tv_name", tv.name)
            .apply()

        val savedToken = prefs.getString("saved_tv_token_${tv.ip}", null) ?: prefs.getString("saved_tv_token", null)
        tvClient?.connect(tv.ip, savedToken)
    }

     fun setTvMacAddress(mac: String) {
        currentTvMacAddress = mac
        prefs.edit().putString("saved_tv_mac", mac).apply()
    }

    fun wakeTv() {
        val mac = currentTvMacAddress ?: return
        val ip = currentTvIp ?: return
        isWaitingForWol = true
        viewModelScope.launch(Dispatchers.IO) {
            WakeOnLanSender.sendWolPacket(mac, ip)
            // Poll for TV to become reachable after WOL
            var attempts = 0
            while (isWaitingForWol && attempts < 60) {
                attempts++
                delay(3000)
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(ip, 8002), 2000)
                        // TV is reachable, try to reconnect
                        tvClient?.reconnect()
                        isWaitingForWol = false
                    }
                } catch (_: Exception) {
                    // TV not reachable yet, continue polling
                }
            }
        }
    }

    fun connectManually(ip: String) {
        disconnect()
        currentTvIp = ip
        currentTvName = "Manual TV Connection"
        currentTvMacAddress = prefs.getString("saved_tv_mac_$ip", null)
        
        prefs.edit()
            .putString("saved_tv_ip", ip)
            .putString("saved_tv_name", "Manual TV Connection")
            .apply()
            
        val savedToken = prefs.getString("saved_tv_token_$ip", null) ?: prefs.getString("saved_tv_token", null)
        tvClient?.connect(ip, savedToken)
    }

    fun setTvMacAddress(ip: String, mac: String) {
        currentTvMacAddress = mac
        prefs.edit().putString("saved_tv_mac_$ip", mac).apply()
    }

    fun setTvName(name: String) {
        currentTvName = name
        prefs.edit().putString("saved_tv_name", name).apply()
    }

    fun setTvIp(oldIp: String, newIp: String) {
        disconnect()
        currentTvIp = newIp
        currentTvMacAddress = prefs.getString("saved_tv_mac_$oldIp", null)

        val editor = prefs.edit()
            .putString("saved_tv_ip", newIp)
            .putString("saved_tv_name", currentTvName ?: "Samsung TV")

        // Migrate MAC from old IP key
        prefs.getString("saved_tv_mac_$oldIp", null)?.let { mac ->
            editor.putString("saved_tv_mac_$newIp", mac)
        }

        // Migrate token
        prefs.getString("saved_tv_token_$oldIp", null)?.let { token ->
            editor.putString("saved_tv_token_$newIp", token)
        }
        editor.apply()

        val savedToken = prefs.getString("saved_tv_token_$newIp", null)
        tvClient?.connect(newIp, savedToken)
    }

    fun disconnect() {
        tvClient?.disconnect()
        connectionState = SamsungTvClient.State.DISCONNECTED
    }

    fun forgetTv() {
        val ip = currentTvIp
        disconnect()
        currentTvIp = null
        currentTvName = null
        currentTvMacAddress = null
        val editor = prefs.edit()
            .remove("saved_tv_ip")
            .remove("saved_tv_name")
            .remove("saved_tv_mac")
            .remove("saved_tv_token")
        if (ip != null) {
            editor.remove("saved_tv_token_$ip")
            editor.remove("saved_tv_mac_$ip")
        }
        editor.apply()
    }

    fun sendKey(key: String) {
        tvClient?.sendKey(key)
    }

    fun sendText(text: String) {
        tvClient?.sendText(text)
    }

    fun launchApp(appId: String) {
        tvClient?.launchApp(appId)
    }

    fun startSubnetScan() {
        if (isScanning) return
        isScanning = true
        discoveredTvs.clear()
        
        viewModelScope.launch {
            scanner.scanSubnet(
                onDeviceFound = { tv ->
                    if (!discoveredTvs.any { it.ip == tv.ip }) {
                        discoveredTvs.add(tv)
                    }
                },
                onScanComplete = {
                    isScanning = false
                }
            )
        }
    }

    private fun saveToken(token: String) {
        val ip = currentTvIp ?: return
        prefs.edit()
            .putString("saved_tv_token_$ip", token)
            .putString("saved_tv_token", token)
            .apply()
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
