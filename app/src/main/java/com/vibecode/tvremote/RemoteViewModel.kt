package com.vibecode.tvremote

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val KEY_CACHED_APPS = "cached_tv_apps"
        private const val KEY_PINNED_APPS = "pinned_quick_apps"
    }

    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("tv_remote_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
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

    var isLoadingApps by mutableStateOf(false)
        private set

    var appLoadError by mutableStateOf<String?>(null)
        private set

    val installedApps = mutableStateListOf<SamsungTvApp>()
    val pinnedAppIds = mutableStateListOf<String>()

    private var tvClient: SamsungTvClient? = null

    init {
        loadCachedApps()
        loadPinnedApps()

        currentTvIp = prefs.getString("saved_tv_ip", null)
        currentTvName = prefs.getString("saved_tv_name", "Paired Samsung TV")
        currentTvMacAddress = prefs.getString("saved_tv_mac", null)
        val savedToken = currentTvIp?.let { ip ->
            prefs.getString("saved_tv_token_$ip", null) ?: prefs.getString("saved_tv_token", null)
        }

        tvClient = SamsungTvClient(
            appName = "VibeRemote",
            onStateChanged = { state ->
                viewModelScope.launch {
                    connectionState = state
                    if (state == SamsungTvClient.State.CONNECTED) {
                        refreshInstalledApps()
                    } else if (state != SamsungTvClient.State.CONNECTING && state != SamsungTvClient.State.PAIRING) {
                        isLoadingApps = false
                    }
                }
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
            // ICMP ping poll to detect when TV is reachable after WOL
            var pingAttempts = 0
            var tvReachable = false
            while (isWaitingForWol && !tvReachable && pingAttempts < 100) {
                pingAttempts++
                delay(1000)
                try {
                    val address = java.net.InetAddress.getByName(ip)
                    if (address.isReachable(3000)) {
                        tvReachable = true
                        Log.d("RemoteViewModel", "TV pingable after ${pingAttempts}s")
                    }
                } catch (_: Exception) {
                    // TV not reachable yet
                }
            }
            // Poll HTTP API (port 8001) to confirm TV is fully online
            var attempts = 0
            var tvOnline = false
            while (isWaitingForWol && !tvOnline && attempts < 20) {
                attempts++
                delay(1000)
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(ip, 8001), 3000)
                        tvOnline = true
                        socket.close()
                        Log.d("RemoteViewModel", "TV 8001 connected after ${attempts}s")
                    }
                } catch (_: Exception) {
                    // TV not reachable yet
                }
            }
            if (tvOnline && isWaitingForWol) {           
                tvClient?.reconnect()
                isWaitingForWol = false
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
        isLoadingApps = false
    }

    fun forgetTv() {
        val ip = currentTvIp
        disconnect()
        currentTvIp = null
        currentTvName = null
        currentTvMacAddress = null
        installedApps.clear()
        appLoadError = null
        pinnedAppIds.clear()
        val editor = prefs.edit()
            .remove("saved_tv_ip")
            .remove("saved_tv_name")
            .remove("saved_tv_mac")
            .remove("saved_tv_token")
            .remove(KEY_CACHED_APPS)
            .remove(KEY_PINNED_APPS)
        if (ip != null) {
            editor.remove("saved_tv_token_$ip")
            editor.remove("saved_tv_mac_$ip")
        }
        editor.apply()
    }

    fun sendKey(key: String) {
        tvClient?.sendKey(key)
    }

    fun pressKey(key: String) {
        tvClient?.pressKey(key)
    }

    fun releaseKey(key: String) {
        tvClient?.releaseKey(key)
    }

    fun sendText(text: String) {
        tvClient?.sendText(text)
    }

    fun launchApp(appId: String) {
        tvClient?.launchApp(appId)
    }

    fun launchApp(appId: String, appType: Int?) {
        tvClient?.launchApp(appId, appType)
    }

    fun launchApp(app: SamsungTvApp) {
        tvClient?.launchApp(app)
    }

    fun refreshInstalledApps() {
        if (connectionState != SamsungTvClient.State.CONNECTED) {
            appLoadError = if (installedApps.isEmpty()) "TV is offline. Using saved shortcuts." else null
            isLoadingApps = false
            return
        }

        isLoadingApps = true
        appLoadError = null
        tvClient?.getInstalledApps(
            onSuccess = { apps ->
                installedApps.clear()
                installedApps.addAll(apps.sortedBy { it.name.lowercase() })
                persistApps()
                appLoadError = if (apps.isEmpty()) "No apps were returned by the TV." else null
                isLoadingApps = false
            },
            onError = { error ->
                Log.w("RemoteViewModel", "Failed to load installed apps", error)
                appLoadError = error.message ?: "Failed to load TV apps."
                isLoadingApps = false
            }
        )
    }

    fun retryInstalledApps() {
        refreshInstalledApps()
    }

    fun isPinned(appId: String): Boolean {
        return pinnedAppIds.contains(appId)
    }

    fun togglePinnedApp(app: SamsungTvApp) {
        if (pinnedAppIds.contains(app.appId)) {
            pinnedAppIds.remove(app.appId)
        } else {
            if (pinnedAppIds.size >= 5) {
                pinnedAppIds.removeAt(0)
            }
            pinnedAppIds.add(app.appId)
        }
        persistPinnedApps()
    }

    fun setPinnedApps(appIds: List<String>) {
        pinnedAppIds.clear()
        pinnedAppIds.addAll(appIds.take(5))
        persistPinnedApps()
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

    private fun persistApps() {
        prefs.edit()
            .putString(KEY_CACHED_APPS, gson.toJson(installedApps))
            .apply()
    }

    private fun loadCachedApps() {
        val json = prefs.getString(KEY_CACHED_APPS, null) ?: return
        runCatching {
            val type = object : TypeToken<List<SamsungTvApp>>() {}.type
            val apps: List<SamsungTvApp> = gson.fromJson(json, type)
            installedApps.clear()
            installedApps.addAll(apps)
        }
    }

    private fun persistPinnedApps() {
        prefs.edit()
            .putString(KEY_PINNED_APPS, gson.toJson(pinnedAppIds))
            .apply()
    }

    private fun loadPinnedApps() {
        val json = prefs.getString(KEY_PINNED_APPS, null) ?: return
        runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            val appIds: List<String> = gson.fromJson(json, type)
            pinnedAppIds.clear()
            pinnedAppIds.addAll(appIds.take(5))
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
