package com.example.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.NudgyDatabase
import com.example.data.repository.CalendarRepository
import com.example.data.repository.MemoryRepository
import com.example.data.repository.TaskRepository
import com.example.reminders.ReminderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sqrt

/**
 * WakeWordForegroundService
 *
 * Implements a true custom "Always-On" voice listener for "Hey Nudgy" / "Nudgy":
 * 1. Runs as an Android Foreground Service with low-power acoustic thresholding (VAD).
 * 2. When voice activity is sensed above ambient floor, SpeechRecognizer parses the phrase.
 * 3. If "Hey Nudgy" or "Nudgy" is called with an action command (e.g. "Hey Nudgy, add task buy milk at 6 PM"
 *    or "Hey Nudgy, plan my day" or "Hey Nudgy, find free time"), ClientIntentPreFilter executes the work
 *    directly on device in Room DB, schedules reminders, blocks calendar time, and notifies/speaks the result.
 */
class WakeWordForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    @Volatile
    private var isRunning = false
    private val audioLock = Any()
    private var audioRecord: AudioRecord? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizing = false

    private lateinit var taskRepository: TaskRepository
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var intentPreFilter: ClientIntentPreFilter
    private lateinit var reminderManager: ReminderManager
    private lateinit var voiceManager: VoiceAssistantManager
    private lateinit var chatRepository: com.example.data.repository.ChatRepository

    override fun onCreate() {
        super.onCreate()
        taskRepository = TaskRepository(applicationContext)
        calendarRepository = CalendarRepository(applicationContext)
        memoryRepository = MemoryRepository(applicationContext)
        intentPreFilter = ClientIntentPreFilter(taskRepository, calendarRepository, memoryRepository)
        reminderManager = ReminderManager(applicationContext)
        voiceManager = VoiceAssistantManager(applicationContext)
        chatRepository = com.example.data.repository.ChatRepository(applicationContext)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            isRunning = true
            _isWakeWordActive.value = true
            val notification = buildForegroundNotification("Listening for \"Hey Nudgy\"...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            startAcousticWakeWordLoop()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nudgy Always-On Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background listening for 'Hey Nudgy' commands"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WakeWordForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Nudgy Always-On Voice")
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Turn Off", stopPendingIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
    }

    /**
     * Energy-efficient voice detection loop:
     * Reads small PCM buffers. When RMS amplitude exceeds speech threshold,
     * it triggers recognizer to check for "Hey Nudgy [command]".
     */
    private fun startAcousticWakeWordLoop() {
        serviceScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = (minBufferSize * 2).coerceAtLeast(2048)

            var consecutiveFailures = 0
            while (isRunning && isActive) {
                try {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )

                    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        Log.w(TAG, "AudioRecord not initialized, retrying in 3s...")
                        consecutiveFailures++
                        delay((consecutiveFailures * 1000L).coerceAtMost(10000L))
                        continue
                    }

                    audioRecord?.startRecording()
                    val buffer = ShortArray(1024)
                    consecutiveFailures = 0

                    while (isRunning && isActive) {
                        if (isRecognizing) {
                            delay(500)
                            continue
                        }

                        val read = synchronized(audioLock) {
                            if (!isRunning) 0 else audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        }
                        if (read > 0) {
                            var sum = 0.0
                            for (i in 0 until read) {
                                sum += buffer[i] * buffer[i]
                            }
                            val rms = sqrt(sum / read)

                            // Speech onset threshold (~1500-2000 RMS)
                            if (rms > 1600) {
                                Log.d(TAG, "Voice onset detected (RMS=$rms), checking for 'Hey Nudgy'...")
                                pauseAudioRecordAndRecognize()
                            }
                        }
                        delay(80)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Missing RECORD_AUDIO permission: ${e.message}")
                    break
                } catch (e: Exception) {
                    consecutiveFailures++
                    Log.e(TAG, "Wake word loop glitch (retry $consecutiveFailures): ${e.message}")
                    delay((consecutiveFailures * 1000L).coerceAtMost(10000L))
                } finally {
                    releaseAudioRecord()
                }
            }
        }
    }

    private fun pauseAudioRecordAndRecognize() {
        isRecognizing = true
        serviceScope.launch(Dispatchers.Main) {
            synchronized(audioLock) {
                try {
                    audioRecord?.stop()
                } catch (e: Exception) {
                    Log.d(TAG, "AudioRecord pause: ${e.message}")
                }
            }

            recognizeUtterance()
        }
    }

    private fun recognizeUtterance() {
        if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
            isRecognizing = false
            resumeAudioRecord()
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    isRecognizing = false
                    resumeAudioRecord()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull() ?: ""
                    Log.d(TAG, "Recognized phrase: \"$spokenText\"")

                    handleWakeWordPhrase(spokenText)
                    isRecognizing = false
                    resumeAudioRecord()
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            isRecognizing = false
            resumeAudioRecord()
        }
    }

    private fun resumeAudioRecord() {
        serviceScope.launch(Dispatchers.IO) {
            delay(400)
            synchronized(audioLock) {
                try {
                    if (isRunning && audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord?.startRecording()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Resume AudioRecord: ${e.message}")
                }
            }
        }
    }

    /**
     * Parses spoken text for wake phrases like:
     * - "Hey Nudgy, add task Buy groceries at 5 PM"
     * - "Nudgy, what's my schedule today?"
     * - "Hey Nudgy, find free time"
     * - "Nudgy, plan my day"
     */
    private fun handleWakeWordPhrase(phrase: String) {
        val lower = phrase.lowercase().trim()
        val hasWakeWord = lower.contains("hey nudgy") || lower.contains("nudgy") ||
                phrase.contains("নাজি") || phrase.contains("হে নাজি")

        if (!hasWakeWord) {
            return
        }

        // Clean out the wake word prefix to leave the pure command
        var command = lower
            .replace("hey nudgy", "")
            .replace("ok nudgy", "")
            .replace("nudgy", "")
            .replace("হে নাজি", "")
            .replace("নাজি", "")
            .trim()

        if (command.startsWith(",") || command.startsWith(":") || command.startsWith(".")) {
            command = command.substring(1).trim()
        }

        if (command.isBlank()) {
            voiceManager.speak("Yes! What would you like to schedule or get done?")
            updateNotification("Active: Heard \"Hey Nudgy\"")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            val currentUserId = com.example.auth.AuthManager.getActiveUserId(applicationContext)
            val db = NudgyDatabase.getInstance(applicationContext)
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val startOfDay = cal.timeInMillis

            cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
            cal.set(java.util.Calendar.MINUTE, 59)
            cal.set(java.util.Calendar.SECOND, 59)
            val endOfDay = cal.timeInMillis

            val todayTasks = db.taskDao().getTasksForDayOnce(currentUserId, startOfDay, endOfDay)
            val events = db.calendarDao().getEventsForDayOnce(currentUserId, startOfDay, endOfDay)

            // Save user spoken command to chat history
            val userMsg = com.example.ui.screens.ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                senderIsUser = true,
                text = command
            )
            chatRepository.addMessage(currentUserId, userMsg)

            val result = intentPreFilter.tryHandle(
                userId = currentUserId,
                userPrompt = command,
                todayTasks = todayTasks,
                events = events
            )

            when (result) {
                is PreFilterResult.Handled -> {
                    result.createdTask?.let { created ->
                        if (created.reminderSet) {
                            reminderManager.scheduleReminder(
                                taskId = created.id,
                                taskTitle = created.title,
                                triggerEpochMillis = created.dueDateEpochMillis
                            )
                        }
                    }
                    val handledMsg = com.example.ui.screens.ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        senderIsUser = false,
                        text = result.responseText,
                        actionType = result.actionType,
                        associatedTask = result.createdTask,
                        associatedEvent = result.createdEvent,
                        focusStartMillis = result.focusStartMillis,
                        focusEndMillis = result.focusEndMillis,
                        focusDurationMinutes = result.focusDurationMinutes,
                        quickSuggestions = result.quickSuggestions
                    )
                    chatRepository.addMessage(currentUserId, handledMsg)

                    voiceManager.speak(result.responseText)
                    updateNotification("Executed: ${result.responseText.take(40)}...")
                }
                is PreFilterResult.Unhandled -> {
                    // Direct task creation fallback
                    val taskCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, 2) }
                    val newTask = taskRepository.createTask(
                        userId = currentUserId,
                        title = command.replaceFirstChar { it.uppercase() },
                        dueEpochMillis = taskCal.timeInMillis,
                        reminderSet = true
                    )
                    val reply = "Got it! Added task '${newTask.title}' to your schedule."
                    val fallbackMsg = com.example.ui.screens.ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        senderIsUser = false,
                        text = reply,
                        actionType = com.example.ui.screens.ChatActionType.TASK_PREVIEW,
                        associatedTask = newTask
                    )
                    chatRepository.addMessage(currentUserId, fallbackMsg)

                    voiceManager.speak(reply)
                    updateNotification(reply)
                }
            }
        }
    }

    private fun releaseAudioRecord() {
        synchronized(audioLock) {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.d(TAG, "AudioRecord release: ${e.message}")
            }
            audioRecord = null
        }
    }

    private fun stopForegroundService() {
        isRunning = false
        _isWakeWordActive.value = false
        try {
            serviceScope.coroutineContext.cancelChildren()
        } catch (e: Exception) {
            Log.d(TAG, "Coroutine cancellation: ${e.message}")
        }
        releaseAudioRecord()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.d(TAG, "SpeechRecognizer destroy: ${e.message}")
        }
        speechRecognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundService()
        voiceManager.destroy()
        try {
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.d(TAG, "ServiceScope cancel: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "nudgy_wake_word_channel"
        const val ACTION_START = "com.example.nudgy.ACTION_START_WAKE_WORD"
        const val ACTION_STOP = "com.example.nudgy.ACTION_STOP_WAKE_WORD"

        private val _isWakeWordActive = MutableStateFlow(false)
        val isWakeWordActive: StateFlow<Boolean> = _isWakeWordActive.asStateFlow()

        fun start(context: Context) {
            try {
                val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start wake word foreground service: ${e.message}")
                _isWakeWordActive.value = false
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop wake word service: ${e.message}")
            }
        }
    }
}
