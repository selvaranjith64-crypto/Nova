package com.nova.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NovaService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var running = false
    private var awaitingUntil = 0L

    private val jokes = listOf(
        "Why did the phone go to school? To get smarter.",
        "Why don't scientists trust atoms? They make up everything.",
        "I told my phone I needed space. Now it says storage full."
    )

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "nova"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channelId, "Nova", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, channelId)
            .setContentTitle("Nova is listening")
            .setContentText("Say \"Nova\" and a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        if (!running) {
            running = true
            startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { speaking = false }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { speaking = false }
            })
            ttsReady = true
        }
    }

    // ------------------------------------------------------------- recognition
    private fun startListening() {
        if (!running) return
        if (speaking) { scheduleRestart(700); return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(i)
    }

    private fun scheduleRestart(delay: Long = 300) {
        handler.postDelayed({ startListening() }, delay)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) onHeard(text)
        scheduleRestart(if (speaking) 900 else 300)
    }

    override fun onError(error: Int) {
        scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 400)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ---------------------------------------------------------------- commands
    private fun onHeard(raw: String) {
        val t = raw.lowercase().replace(Regex("[.,!?]"), "").trim()
        val now = System.currentTimeMillis()
        val wake = Regex("\\b(nova|nova|nover)\\b").find(t)
        val cmd = when {
            wake != null -> t.substring(wake.range.last + 1).trim()
            now < awaitingUntil -> t
            else -> return
        }
        awaitingUntil = 0
        if (cmd.isEmpty()) {
            awaitingUntil = now + 9000
            say("Yes?")
            return
        }
        handle(cmd)
    }

    private fun handle(cmd: String) {
        if (Regex("^(stop|cancel|quiet|be quiet)$").matches(cmd)) { tts?.stop(); speaking = false; return }
        if (Regex("^(goodbye|bye|exit|stop listening|shut down)$").matches(cmd)) {
            say("Goodbye."); handler.postDelayed({ stopSelf() }, 1500); return
        }
        if (Regex("what('s| is) the time|what time is it").containsMatchIn(cmd)) {
            say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())); return
        }
        if (Regex("today's date|what('s| is) the date|what day is it").containsMatchIn(cmd)) {
            say("Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())); return
        }
        if (cmd.contains("joke")) { say(jokes[(System.currentTimeMillis() % jokes.size).toInt()]); return }

        val timer = Regex("timer (?:for )?(\\d+(?:\\.\\d+)?)\\s*(second|sec|minute|min|hour)s?").find(cmd)
            ?: Regex("(\\d+(?:\\.\\d+)?)\\s*(second|sec|minute|min|hour)s?\\s+timer").find(cmd)
        if (timer != null) {
            val n = timer.groupValues[1].toDouble()
            val unit = timer.groupValues[2]
            val ms = (n * when { unit.startsWith("h") -> 3600000 ; unit.startsWith("m") -> 60000 ; else -> 1000 }).toLong()
            val word = when { unit.startsWith("h") -> "hour"; unit.startsWith("m") -> "minute"; else -> "second" }
            val label = (if (n == n.toInt().toDouble()) n.toInt().toString() else n.toString()) + " " + word + (if (n == 1.0) "" else "s")
            handler.postDelayed({
                try { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500) } catch (e: Exception) {}
                say("Your $label timer is done.")
            }, ms)
            say("Timer set for $label.")
            return
        }

        Regex("^(?:play|put on|start)\\s+(.+?)\\s+(?:on|in|using)\\s+spotify$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }
        Regex("^spotify\\s+(?:play\\s+)?(.+)$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }
        Regex("^play\\s+(.+)$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }

        Regex("^(?:call|phone|dial|ring)\\s+(.+)$").find(cmd)?.let { callPerson(it.groupValues[1].removePrefix("to ").trim()); return }
        Regex("^(?:open|launch|start|go to)\\s+(.+)$").find(cmd)?.let { openApp(it.groupValues[1]); return }

        val q = Regex("^(?:search(?: for)?|google|look up)\\s+(.+)$").find(cmd)?.groupValues?.get(1) ?: cmd
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        say("Searching for $q.")
    }

    private fun playSpotify(query: String) {
        say("Playing $query on Spotify.")
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
        }
        if (!launch(play)) launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))))
    }

    private fun callPerson(who: String) {
        val digits = who.replace(" ", "")
        val number = if (Regex("^\\+?\\d{3,}$").matches(digits)) digits else findNumber(who)
        if (number == null) { say("I could not find $who in your contacts."); return }
        say("Calling $who.")
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val canCall = checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        launch(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
    }

    private fun findNumber(name: String): String? {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun openApp(spoken: String) {
        val name = spoken.removePrefix("the ").removeSuffix(" app").trim()
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString().lowercase() }
        val match = apps.firstOrNull { it.second == name }
            ?: apps.firstOrNull { it.second.startsWith(name) }
            ?: apps.firstOrNull { it.second.contains(name) }
        val intent = match?.let { pm.getLaunchIntentForPackage(it.first) }
        if (intent != null) {
            say("Opening $name.")
            launch(intent)
        } else {
            say("I could not find an app called $name.")
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun say(text: String) {
        if (!ttsReady) return
        speaking = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova")
    }
}
