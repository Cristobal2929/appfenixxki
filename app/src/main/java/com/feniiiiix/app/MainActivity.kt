package com.feniiiiix.app

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewbinding.ViewBinding
import com.feniiiiix.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // No need to handle result, we will check permission later
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener {
            requestAudioPermission()
        }
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
        } else {
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startFloatingService()
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestOverlayPermission()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onResume() {
        super.onResume()
        // If overlay permission was granted while we were in settings, start service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            startFloatingService()
        }
    }

    inner class FloatingService : Service() {

        private lateinit var windowManager: WindowManager
        private lateinit var bubbleView: ImageView
        private lateinit var expandedView: LinearLayout
        private var isExpanded = false
        private var speechRecognizer: SpeechRecognizer? = null
        private val client = OkHttpClient()
        private val handler = Handler()

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onCreate() {
            super.onCreate()
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            createBubble()
        }

        private fun createBubble() {
            bubbleView = ImageView(this).apply {
                val size = dpToPx(60)
                layoutParams = ViewGroup.LayoutParams(size, size)
                setImageResource(android.R.drawable.ic_dialog_info)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#8800AAFF"))
                }
                setOnTouchListener(BubbleTouchListener())
                setOnClickListener { togglePanel() }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 100
            }

            windowManager.addView(bubbleView, params)
        }

        private fun togglePanel() {
            if (isExpanded) {
                collapsePanel()
            } else {
                expandPanel()
            }
        }

        private fun expandPanel() {
            isExpanded = true
            bubbleView.visibility = View.GONE

            expandedView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#CCFFFFFF"))
                elevation = dpToPx(8).toFloat()
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                layoutParams = ViewGroup.LayoutParams(
                    dpToPx(250),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }

            // Close button
            val btnClose = Button(this).apply {
                text = "X"
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.BLACK)
                setOnClickListener { collapsePanel() }
            }
            expandedView.addView(btnClose)

            // TabLayout
            val tabLayout = TabLayout(this)
            tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.voice_tab)))
            tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.text_tab)))
            expandedView.addView(tabLayout)

            // Container for content
            val contentContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            expandedView.addView(contentContainer)

            // Voice UI
            val voiceLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.VISIBLE
            }
            val tvFrench = TextView(this).apply {
                text = getString(R.string.listening)
                setTextColor(Color.BLACK)
            }
            val tvSpanish = TextView(this).apply {
                text = ""
                setTextColor(Color.DKGRAY)
            }
            voiceLayout.addView(tvFrench)
            voiceLayout.addView(tvSpanish)

            // Text UI
            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            val etInput = EditText(this).apply {
                hint = getString(R.string.enter_french)
                setTextColor(Color.BLACK)
                setHintTextColor(Color.GRAY)
            }
            val btnTranslate = Button(this).apply {
                text = getString(R.string.translate)
            }
            val btnCopy = Button(this).apply {
                text = getString(R.string.copy)
            }
            val tvResult = TextView(this).apply {
                setTextColor(Color.DKGRAY)
            }
            textLayout.addView(etInput)
            textLayout.addView(btnTranslate)
            textLayout.addView(btnCopy)
            textLayout.addView(tvResult)

            contentContainer.addView(voiceLayout)
            contentContainer.addView(textLayout)

            // Tab selection handling
            tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (tab.position == 0) {
                        voiceLayout.visibility = View.VISIBLE
                        textLayout.visibility = View.GONE
                        startListening(tvFrench, tvSpanish)
                    } else {
                        voiceLayout.visibility = View.GONE
                        textLayout.visibility = View.VISIBLE
                        stopListening()
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })

            // Initial start with voice tab
            startListening(tvFrench, tvSpanish)

            // Translate button action
            btnTranslate.setOnClickListener {
                val text = etInput.text.toString()
                if (text.isNotBlank()) {
                    translateText(text, tvResult)
                }
            }

            // Copy button action
            btnCopy.setOnClickListener {
                val result = tvResult.text.toString()
                if (result.isNotBlank()) {
                    val clipboard =
                        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("translation", result)
                    clipboard.setPrimaryClip(clip)
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 100
            }

            windowManager.addView(expandedView, params)
        }

        private fun collapsePanel() {
            isExpanded = false
            if (::expandedView.isInitialized) {
                windowManager.removeView(expandedView)
            }
            bubbleView.visibility = View.VISIBLE
            stopListening()
        }

        private fun dpToPx(dp: Int): Int {
            val metrics = resources.displayMetrics
            return (dp * (metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT))
        }

        private inner class BubbleTouchListener : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val layoutParams = v.layoutParams as WindowManager.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(v, layoutParams)
                        return true
                    }
                }
                return false
            }
        }

        private fun startListening(tvFrench: TextView, tvSpanish: TextView) {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        // Restart listening on error
                        handler.postDelayed({ startListening(tvFrench, tvSpanish) }, 500)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        tvFrench.text = text
                        translateText(text, tvSpanish)
                        // Restart listening
                        handler.postDelayed({ startListening(tvFrench, tvSpanish) }, 500)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
        }

        private fun stopListening() {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }

        private fun translateText(text: String, targetView: TextView) {
            val url = "https://api.mymemory.translated.net/get?q=${Uri.encode(text)}&langpair=fr|es"
            val request = Request.Builder()
                .url(url)
                .build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    handler.post {
                        targetView.text = getString(R.string.translation_error)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.body?.string()?.let { bodyStr ->
                        try {
                            val json = JSONObject(bodyStr)
                            val responseData = json.getJSONObject("responseData")
                            val translated = responseData.getString("translatedText")
                            handler.post {
                                targetView.text = translated
                            }
                        } catch (e: Exception) {
                            handler.post {
                                targetView.text = getString(R.string.translation_error)
                            }
                        }
                    }
                }
            })
        }

        override fun onDestroy() {
            super.onDestroy()
            if (::bubbleView.isInitialized) {
                windowManager.removeView(bubbleView)
            }
            if (isExpanded && ::expandedView.isInitialized) {
                windowManager.removeView(expandedView)
            }
            stopListening()
        }
    }
}