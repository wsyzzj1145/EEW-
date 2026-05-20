package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.*
import com.example.data.db.EarthquakeDatabase
import com.example.data.model.EarlyWarningEvent
import com.example.data.repository.EarthquakeRepository
import com.example.ui.EarthquakeApp
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.EarthquakeViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: EarthquakeViewModel
    private var systemFloatingView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize Room Database and Repository safely
        val database = EarthquakeDatabase.getInstance(applicationContext)
        val repository = EarthquakeRepository(database.dao())

        // 2. Initialize ViewModel with Custom Factory to inject dependencies
        val factory = EarthquakeViewModelFactory(application, repository)
        viewModel = ViewModelProvider(this, factory)[EarthquakeViewModel::class.java]

        // 3. Set up the Jetpack Compose User Interface
        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EarthquakeApp(viewModel = viewModel)
                }
            }
        }

        // 4. Reactive Listening loops to trigger SYSTEM floating window overlay if permitted (Fully disabled per user request: "只要全屏倒计时预警不要什么小窗")
        dismissSystemFloatingOverlay()
    }

    /**
     * Programmatically constructs and overlay adds a highly stable system windows view on top of apps
     */
    private fun showSystemFloatingOverlay(event: EarlyWarningEvent, countdown: Int) {
        if (!Settings.canDrawOverlays(this)) {
            // Log warning internally
            return
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (systemFloatingView != null) {
            updateSystemFloatingOverlay(countdown)
            return
        }

        // Setup Window Layout Params
        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP
            y = 120 // Positioned clear of notches
        }

        val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            colors = intArrayOf(
                android.graphics.Color.parseColor("#1C1C1E"), // Jet black night
                android.graphics.Color.parseColor("#2C1010")  // Deep muted wine
            )
            gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
            orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            cornerRadius = 64f // Big rounded corners
            setStroke(3, android.graphics.Color.parseColor("#FF453A"))
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = backgroundDrawable
            setPadding(48, 48, 48, 48)
            elevation = 20f
            gravity = Gravity.CENTER
        }

        val textHeader = TextView(this).apply {
            text = "【地震预警】 横波紧急避险警告"
            setTextColor(android.graphics.Color.parseColor("#FF453A"))
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }

        val textDetail = TextView(this).apply {
            text = "震源: ${event.placeName} (M${String.format("%.1f", event.magnitude)})"
            setTextColor(android.graphics.Color.parseColor("#AEAEB2"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        val textCountdown = TextView(this).apply {
            text = if (countdown > 0) "横波预计将在 ${countdown} 秒内到达" else "横波已到达！请就地躲避！"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        val btnMute = Button(this).apply {
            text = "我已就地避护安全"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#FF453A"))
            setOnClickListener {
                dismissSystemFloatingOverlay()
                viewModel.dismissWarning()
            }
        }

        mainLayout.addView(textHeader)
        mainLayout.addView(textDetail)
        mainLayout.addView(textCountdown)
        mainLayout.addView(btnMute)

        // Cache the reference to countdown view directly inside container tag attributes
        mainLayout.tag = textCountdown
        systemFloatingView = mainLayout

        try {
            windowManager.addView(mainLayout, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateSystemFloatingOverlay(countdown: Int) {
        val view = systemFloatingView ?: return
        val textCountdown = view.tag as? TextView ?: return
        textCountdown.text = if (countdown > 0) {
            "横波预计将在 ${countdown} 秒内到达"
        } else {
            "横波已到达！请就地避护！"
        }
    }

    private fun dismissSystemFloatingOverlay() {
        val view = systemFloatingView ?: return
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            if (view.isAttachedToWindow) {
                windowManager.removeViewImmediate(view)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            systemFloatingView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissSystemFloatingOverlay()
    }
}

/**
 * Custom Factory class to allow manual coordinate repository injection into ViewModel
 */
class EarthquakeViewModelFactory(
    private val application: Application,
    private val repository: EarthquakeRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EarthquakeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EarthquakeViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

