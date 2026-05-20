package com.example.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainActivity
import com.example.data.db.DbEarthquakeRecord
import com.example.data.db.DbHomeSetting
import com.example.data.model.EarlyWarningEvent
import com.example.data.model.EarthquakeRecord
import com.example.data.repository.EarthquakeRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.*

class EarthquakeViewModel(
    application: Application,
    private val repository: EarthquakeRepository
) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val context = application.applicationContext

    // Text to Speech Engine
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var sirenPlayer: android.media.MediaPlayer? = null

    // EAS/Amber level digital siren sound custom play engine
    private var audioTrack: android.media.AudioTrack? = null
    private var isPlayingEasSiren = false
    private var easSirenJob: Job? = null

    // Sound alert tone generator
    private var toneGenerator: ToneGenerator? = null
    private var alarmToneJob: Job? = null

    // UI Reactivity Flows
    val homeSetting: StateFlow<DbHomeSetting> = repository.homeSettingFlow
        .map { it ?: DbHomeSetting() } // fallback to default
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DbHomeSetting()
        )

    val cachedEarthquakes: StateFlow<List<DbEarthquakeRecord>> = repository.cachedEarthquakesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current active early warning alert (triggers safety overlays!)
    private val _activeWarning = MutableStateFlow<EarlyWarningEvent?>(null)
    val activeWarning: StateFlow<EarlyWarningEvent?> = _activeWarning.asStateFlow()

    // S-Wave Live countdown representing seconds remaining before destructiveness
    private val _sWaveCountdown = MutableStateFlow(0)
    val sWaveCountdown: StateFlow<Int> = _sWaveCountdown.asStateFlow()

    // Connection states
    private val _cencConnected = MutableStateFlow(false)
    val cencConnected: StateFlow<Boolean> = _cencConnected.asStateFlow()

    private val _ceaConnected = MutableStateFlow(false)
    val ceaConnected: StateFlow<Boolean> = _ceaConnected.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages.asStateFlow()

    // WebSocket clients
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var cencWebSocket: WebSocket? = null
    private var ceaWebSocket: WebSocket? = null

    private var countdownJob: Job? = null
    private var cencReconnectJob: Job? = null
    private var ceaReconnectJob: Job? = null

    init {
        // Initialize TTS
        tts = TextToSpeech(context, this)

        // Initialize ToneGenerator
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Initialize Database defaults if not present
        viewModelScope.launch {
            val current = repository.getHomeSettingDirect()
            if (current == null) {
                repository.updateHomeSetting(DbHomeSetting())
            }
            refreshHistory()
        }

        // Start WebSockets
        connectCencWebSocket()
        connectCeaWebSocket()

        logInfo("系统初始化完成！服务守护线程已就绪。")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                logError("TTS引擎不支持中文听书，正在寻求替代输出...")
            } else {
                isTtsReady = true
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(1.1f)
                logInfo("内置中文语音(TTS)警报系统初始化成功！")
            }
        } else {
            logError("TTS警报语音引擎启动失败！")
        }
    }

    private fun logInfo(msg: String) {
        val dateStr = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logMessages.update { (listOf("[$dateStr] [INFO] $msg") + it).take(50) }
        Log.d("EQG", msg)
    }

    private fun logError(msg: String) {
        val dateStr = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logMessages.update { (listOf("[$dateStr] [WARN] $msg") + it).take(50) }
        Log.e("EQG", msg)
    }

    /**
     * Refreshes the recent CENC catalog listing from Wolfx HTTP API
     */
    fun refreshHistory() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val res = repository.fetchRecentEarthquakes()
            if (res.isSuccess) {
                logInfo("已成功同步最新的100条全国官方台网地震历史目录数据。")
            } else {
                logError("拉取静态地震历史目录失败: ${res.exceptionOrNull()?.message}")
            }
            _isRefreshing.value = false
        }
    }

    /**
     * Set new user home location parameters
     */
    fun saveHomeSetting(lat: Double, lon: Double, addressName: String) {
        viewModelScope.launch {
            val current = homeSetting.value
            val updated = current.copy(
                latitude = lat,
                longitude = lon,
                name = addressName
            )
            repository.updateHomeSetting(updated)
            logInfo("家的地理位置已成功保存：$addressName (${String.format("%.2f", lat)}, ${String.format("%.2f", lon)})")
            
            // Re-trigger intensity simulation update if warning is currently active
            _activeWarning.value?.let { active ->
                handleIncomingWarning(active)
            }
        }
    }

    /**
     * Update setting configurations (Alert thresholds, flags)
     */
    fun updateAlertThreshold(threshold: Double) {
        viewModelScope.launch {
            val current = homeSetting.value
            repository.updateHomeSetting(current.copy(alertThreshold = threshold))
        }
    }

    fun updateAlertSwitches(isSystemAlert: Boolean, isSound: Boolean, isTts: Boolean) {
        viewModelScope.launch {
            val current = homeSetting.value
            repository.updateHomeSetting(
                current.copy(
                    isSystemAlertEnabled = isSystemAlert,
                    soundEnabled = isSound,
                    playTtsEnabled = isTts
                )
            )
            logInfo("预警警报偏好设置更新完成。")
        }
    }

    /**
     * WebSocket Client for CENC history updates
     */
    private fun connectCencWebSocket() {
        if (_cencConnected.value) return
        val request = Request.Builder()
            .url("wss://ws.fanstudio.tech/cenc")
            .build()

        cencWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _cencConnected.value = true
                cencReconnectJob?.cancel()
                logInfo("已成功建立与中国地震台网历史速报的实时WebSocket长连接。")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleCencMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _cencConnected.value = false
                logError("台网检测连接异常中断: ${t.message}，正在启动自动重连机制...")
                scheduleCencReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _cencConnected.value = false
                logInfo("台网检测连接下线。原因: $reason")
                scheduleCencReconnect()
            }
        })
    }

    private fun scheduleCencReconnect() {
        cencReconnectJob?.cancel()
        cencReconnectJob = viewModelScope.launch {
            delay(10000)
            logInfo("正在尝试恢复与历史台网服务器的WebSocket长连接...")
            connectCencWebSocket()
        }
    }

    /**
     * WebSocket Client for CEA Earthquake Early Warnings (EEW)
     */
    private fun connectCeaWebSocket() {
        if (_ceaConnected.value) return
        val request = Request.Builder()
            .url("wss://ws.fanstudio.tech/cea")
            .build()

        ceaWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _ceaConnected.value = true
                ceaReconnectJob?.cancel()
                logInfo("已成功建立与中国地震预警网实时防震减灾通道(EEW)的对接入。")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleCeaMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _ceaConnected.value = false
                logError("前置地震预警通道网络崩溃: ${t.message}，开启后台重连...")
                scheduleCeaReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _ceaConnected.value = false
                logInfo("预警通道离线。")
                scheduleCeaReconnect()
            }
        })
    }

    private fun scheduleCeaReconnect() {
        ceaReconnectJob?.cancel()
        ceaReconnectJob = viewModelScope.launch {
            delay(10000)
            logInfo("尝试恢复与国家地震预警网的连接...")
            connectCeaWebSocket()
        }
    }

    private fun showNotification(title: String, content: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "earthquake_bulletins"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "地震灾害速报预报",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "用于发布最新中国台网地震灾情目录及速报监测"
                    enableLights(true)
                    lightColor = android.graphics.Color.RED
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Parse and process WebSocket CENC Earthquake Catalog updates
     */
    private fun handleCencMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            if (type == "initial" || type == "update") {
                val dataObj = json.optJSONObject("Data") ?: return
                
                val record = DbEarthquakeRecord(
                    eventId = dataObj.optString("eventId", System.currentTimeMillis().toString()),
                    time = dataObj.optString("shockTime", ""),
                    reportTime = dataObj.optString("createTime", ""),
                    placeName = dataObj.optString("placeName", ""),
                    magnitude = dataObj.optDouble("magnitude", 0.0),
                    depth = dataObj.optDouble("depth", 0.0),
                    latitude = dataObj.optDouble("latitude", 0.0),
                    longitude = dataObj.optDouble("longitude", 0.0),
                    intensity = dataObj.optDouble("epiIntensity", 0.0).toString(),
                    infoTypeName = dataObj.optString("infoTypeName", "[正式测定]"),
                    isRealTime = true
                )

                if (record.eventId.isNotEmpty() && record.placeName.isNotEmpty()) {
                    viewModelScope.launch {
                        repository.insertRealtimeRecord(record)
                        logInfo("接收到最新台网推送: M${record.magnitude} - ${record.placeName}")
                        
                        try {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            val parsedDate = sdf.parse(record.time)
                            if (parsedDate != null) {
                                val diffMs = System.currentTimeMillis() - parsedDate.time
                                val diffMin = diffMs / (1000 * 60)
                                if (diffMin in 0..30) {
                                    showNotification(
                                        "【地震速报偏护】${record.placeName} 发生 ${record.magnitude} 级地震",
                                        "发生时间：${record.time}\n震源深度：${record.depth}千米\n本地预警信息提示，请保持警惕！"
                                    )
                                }
                            }
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Parse and process WebSocket CEA real-time early warnings (EEW)
     */
    private fun handleCeaMessage(text: String) {
        try {
            val json = JSONObject(text)
            val dataObj = json.optJSONObject("Data") ?: return

            val event = EarlyWarningEvent(
                id = dataObj.optString("id", UUID.randomUUID().toString()),
                eventId = dataObj.optString("eventId", ""),
                shockTime = dataObj.optString("shockTime", ""),
                longitude = dataObj.optDouble("longitude", 0.0),
                latitude = dataObj.optDouble("latitude", 0.0),
                placeName = dataObj.optString("placeName", ""),
                magnitude = dataObj.optDouble("magnitude", 0.0),
                depth = dataObj.optDouble("depth", 10.0),
                epiIntensity = dataObj.optDouble("epiIntensity", 0.0),
                updates = dataObj.optInt("updates", 1)
            )

            if (event.eventId.isNotEmpty() && event.placeName.isNotEmpty()) {
                handleIncomingWarning(event)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Core Algorithm: Computes distance, local estimated intensity, sounds alarm, triggers overlay
     */
    private fun handleIncomingWarning(event: EarlyWarningEvent) {
        val userHome = homeSetting.value
        val lat = userHome.latitude
        val lon = userHome.longitude

        val distance = event.calculateDistanceKm(lat, lon)
        val localIntensity = event.estimateLocalIntensity(lat, lon)
        val countdown = event.getSWaveCountdownSeconds(lat, lon)

        logInfo("收到地震预警【${event.placeName} M${event.magnitude}】 震中距 ${String.format("%.1f", distance)}km, 本地预估烈度 ${String.format("%.1f", localIntensity)}度")

        // Trigger warning if local pre-estimated intensity > 0 AND > user's configured threshold
        if (localIntensity > 0.0 && localIntensity >= userHome.alertThreshold) {
            _activeWarning.value = event
            _sWaveCountdown.value = countdown
            startAlertBehavior(event, distance, localIntensity, countdown)

            // Force pop-up warning top-most by bringing MainActivity to the foreground
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("EQG", "Error pulling activity to top-most", e)
            }
        } else {
            logInfo("本地计算烈度为 ${String.format("%.1f", localIntensity)} 度，低于触发阈值或烈度0，未触发大窗口弹出。")
        }
    }

    private fun stopSiren() {
        stopEasSiren()
        try {
            sirenPlayer?.stop()
            sirenPlayer?.release()
            sirenPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playEasSiren() {
        stopEasSiren()
        isPlayingEasSiren = true
        easSirenJob = viewModelScope.launch(Dispatchers.IO) {
            val sampleRate = 44100
            // Sound is generated in short bursts: 1.5 seconds ON, 0.4 seconds OFF (EAS / Standard Amber Alert pattern)
            val onDuration = 1.5
            val offDuration = 0.4
            val sampleOnSize = (sampleRate * onDuration).toInt()
            val sampleOffSize = (sampleRate * offDuration).toInt()
            val totalSize = sampleOnSize + sampleOffSize

            val generatedSnd = ShortArray(totalSize)

            val freq1 = 853.0 // Hz
            val freq2 = 960.0 // Hz

            // Generate double tone dual frequency EAS wave for the ON section
            for (i in 0 until sampleOnSize) {
                val t = i.toDouble() / sampleRate
                val s1 = kotlin.math.sin(2.0 * kotlin.math.PI * freq1 * t)
                val s2 = kotlin.math.sin(2.0 * kotlin.math.PI * freq2 * t)
                val mixed = (s1 * 0.5 + s2 * 0.5) * Short.MAX_VALUE * 0.85
                generatedSnd[i] = mixed.toInt().toShort()
            }

            try {
                audioTrack = android.media.AudioTrack(
                    android.media.AudioManager.STREAM_ALARM,
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    totalSize * 2,
                    android.media.AudioTrack.MODE_STATIC
                )

                audioTrack?.write(generatedSnd, 0, totalSize)
                audioTrack?.setLoopPoints(0, totalSize, -1) // cycle loops

                if (isPlayingEasSiren) {
                    audioTrack?.play()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopEasSiren() {
        isPlayingEasSiren = false
        easSirenJob?.cancel()
        easSirenJob = null
        try {
            audioTrack?.let {
                if (it.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioTrack = null
        }
    }

    private fun playSiren(localIntensity: Double) {
        stopSiren()
        logInfo("系统已紧急合成本地专线国家安珀/EAS最高级别防空震灾警报音...")
        playEasSiren()
    }

    /**
     * Simulate alarms and plays speech/alert loops
     */
    private fun startAlertBehavior(event: EarlyWarningEvent, distance: Double, localIntensity: Double, countdown: Int) {
        val userHome = homeSetting.value
        
        // Start TTS vocalization if enabled
        if (userHome.playTtsEnabled && isTtsReady) {
            val ttsText = "地震预警！中国地震预警网发布紧急速报。在 ${event.placeName} 发生 ${String.format("%.1f", event.magnitude)} 级地震，本地预估烈度 ${String.format("%.1f", localIntensity)} 度，横波约 ${countdown} 秒后到达！请不要惊慌，抓紧时间就近避险！"
            speak(ttsText)
        }

        // Start repeating siren/tone generation if sound is enabled
        if (userHome.soundEnabled) {
            playSiren(localIntensity)
        }

        // Keep updating S-Wave countdown in real-time timer
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var currentSec = countdown
            while (currentSec >= 0 && _activeWarning.value != null) {
                _sWaveCountdown.value = currentSec
                
                // Beep warning sounds when S-Wave is getting closer
                if (currentSec in 1..10) {
                    try {
                        mediaPlayer?.let {
                            if (it.isPlaying) {
                                it.stop()
                                it.release()
                                mediaPlayer = null
                            }
                        }
                    } catch (e: Exception) {}

                    if (userHome.soundEnabled) {
                        try {
                            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 85)
                        } catch (e: Exception) {}
                    }

                    if (userHome.playTtsEnabled && isTtsReady) {
                        try {
                            tts?.speak(currentSec.toString(), TextToSpeech.QUEUE_FLUSH, null, "COUNTDOWN_$currentSec")
                        } catch (e: Exception) {}
                    }
                }

                // If S-Wave countdown hits 0 but alarm is still active, keep sirens active
                if (currentSec == 0) {
                    if (userHome.playTtsEnabled && isTtsReady) {
                        try {
                            tts?.speak("横波已到达！请立刻就地避险并保护头部！", TextToSpeech.QUEUE_FLUSH, null, "SWAVE_ARRIVED")
                        } catch (e: Exception) {}
                    }
                }

                delay(1000)
                currentSec--
            }
        }
    }

    private fun startSirenTones(localIntensity: Double) {
        alarmToneJob?.cancel()
        alarmToneJob = viewModelScope.launch(Dispatchers.IO) {
            // Stronger siren loop if estimated intensity is high
            val delayMs = if (localIntensity >= 4.0) 350L else 700L
            val toneType = if (localIntensity >= 4.0) ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK else ToneGenerator.TONE_PROP_PROMPT

            while (isActive && _activeWarning.value != null) {
                try {
                    toneGenerator?.startTone(toneType, 200)
                    delay(delayMs)
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    fun dismissWarning() {
        _activeWarning.value = null
        countdownJob?.cancel()
        alarmToneJob?.cancel()
        tts?.stop()
        stopSiren()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        logInfo("预警对话框已被用户手动关闭，警报状态已复位。")
    }

    private fun speak(text: String) {
        speakWithSiliconFlow(text) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ALERT_TTS")
        }
    }

    private fun speakWithSiliconFlow(text: String, onFallback: () -> Unit) {
        val apiKey = com.example.BuildConfig.SILICON_FLOW_API_KEY
        if (apiKey.isNullOrEmpty() || apiKey == "MY_SILICON_FLOW_API_KEY_DEFAULT_VALUE") {
            Log.w("EQG", "SiliconFlow API Key is empty, falling back to System TTS.")
            onFallback()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                logInfo("正在通过 SiliconFlow 语音模型合成：\"$text\"")
                
                val jsonPayload = org.json.JSONObject().apply {
                    put("model", "fnlp/MOSS-TTSD-v0.5")
                    put("input", text)
                    put("voice", "fnlp/MOSS-TTSD-v0.5:alex")
                    put("response_format", "mp3")
                    put("stream", false)
                }

                val request = Request.Builder()
                    .url("https://api.siliconflow.cn/v1/audio/speech")
                    .post(RequestBody.create(
                        "application/json; charset=utf-8".toMediaTypeOrNull(),
                        jsonPayload.toString()
                    ))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errMsg = response.body?.string() ?: ""
                        logError("SiliconFlow 接口请求失败：${response.code} - $errMsg")
                        withContext(Dispatchers.Main) { onFallback() }
                        return@launch
                    }

                    val body = response.body
                    if (body == null) {
                        logError("SiliconFlow 返回数据为空")
                        withContext(Dispatchers.Main) { onFallback() }
                        return@launch
                    }

                    val bytes = body.bytes()
                    if (bytes.isEmpty()) {
                        logError("SiliconFlow 语音合成二进制数据长度为 0")
                        withContext(Dispatchers.Main) { onFallback() }
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        playAudioBytes(bytes, onFallback)
                    }
                }
            } catch (e: Exception) {
                logError("SiliconFlow 网络合成连接报错: ${e.message}")
                withContext(Dispatchers.Main) { onFallback() }
            }
        }
    }

    private fun playAudioBytes(bytes: ByteArray, onFallback: () -> Unit) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null

            val tempFile = java.io.File.createTempFile("silicon_tts_", ".mp3", context.cacheDir)
            tempFile.deleteOnExit()
            
            java.io.FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
            }

            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    try {
                        it.release()
                    } catch (e: Exception) {
                        // ignore
                    }
                    if (mediaPlayer == this) {
                        mediaPlayer = null
                    }
                    logInfo("SiliconFlow 警报语音播报完毕。")
                }
                setOnErrorListener { mp, what, extra ->
                    logError("MediaPlayer 播放错误: what=$what, extra=$extra")
                    onFallback()
                    true
                }
            }
            logInfo("SiliconFlow 真人级警报语音开始播报。")
        } catch (e: Exception) {
            logError("MediaPlayer 装置初始化播放报错: ${e.message}")
            onFallback()
        }
    }

    /**
     * Highly visual warning simulation trigger
     */
    fun simulateWarning(magnitude: Double, distanceKm: Double, placeName: String) {
        // Calculate coordinate at specified distance to the east/north of current home
        val home = homeSetting.value
        val latOffset = distanceKm / 111.0 / sqrt(2.0)
        val lonOffset = distanceKm / (111.0 * cos(Math.toRadians(home.latitude))) / sqrt(2.0)

        // Event creation
        val mockEvent = EarlyWarningEvent(
            id = "mock_" + System.currentTimeMillis(),
            eventId = "Simulate." + java.text.SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date()) + ".01",
            shockTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(System.currentTimeMillis() - 2000)), // Occurred 2s ago
            latitude = home.latitude + latOffset,
            longitude = home.longitude + lonOffset,
            placeName = placeName,
            magnitude = magnitude,
            depth = 12.0,
            epiIntensity = (1.5 * magnitude - 0.5).coerceIn(1.0, 12.0),
            updates = 1,
            isMock = true
        )

        logInfo("📢 开始启动地震模拟系统：【$placeName M$magnitude (距家约 $distanceKm km)】")
        handleIncomingWarning(mockEvent)
    }

    override fun onCleared() {
        super.onCleared()
        cencWebSocket?.close(1000, "App exit")
        ceaWebSocket?.close(1000, "App exit")
        tts?.shutdown()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        toneGenerator?.release()
        countdownJob?.cancel()
        alarmToneJob?.cancel()
    }
}
