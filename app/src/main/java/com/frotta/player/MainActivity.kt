package com.frotta.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var emptyStateLayout: LinearLayout

    private var isLandscape = false
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            playVideo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setupUi()
        initializePlayer()

        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            playVideo(intent.data!!)
        }
    }

    private fun setupUi() {
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // شاشة عرض الفيديو
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            useController = true
            resizeMode = currentResizeMode
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }

        // شاشة البداية قبل اختيار فيديو
        emptyStateLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val welcomeTitle = TextView(this).apply {
            text = "Frotta Player"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val welcomeSub = TextView(this).apply {
            text = "مشغل فيديو فائق السرعة بجميع الصيغ"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 12, 0, 48)
            gravity = Gravity.CENTER
        }

        val bigOpenBtn = createStyledButton("🎬 اختيار فيديو من الهاتف", "#2563EB").apply {
            textSize = 16f
            setPadding(64, 32, 64, 32)
            setOnClickListener { pickVideoLauncher.launch("video/*") }
        }

        emptyStateLayout.addView(welcomeTitle)
        emptyStateLayout.addView(welcomeSub)
        emptyStateLayout.addView(bigOpenBtn)

        // الشريط العلوي للتحكم السريع
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(32, 24, 32, 24)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val appTitle = TextView(this).apply {
            text = "Frotta Player"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val openBtn = createStyledButton("📂 فتح", "#1E293B").apply {
            setOnClickListener { pickVideoLauncher.launch("video/*") }
        }

        val ratioBtn = createStyledButton("📐 الأبعاد", "#1E293B").apply {
            setOnClickListener { toggleResizeMode() }
        }

        val rotateBtn = createStyledButton("🔄 تدوير", "#1E293B").apply {
            setOnClickListener { toggleOrientation() }
        }

        val btnMargin = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 16 }

        topBar.addView(appTitle)
        topBar.addView(openBtn)
        topBar.addView(ratioBtn, btnMargin)
        topBar.addView(rotateBtn, btnMargin)

        // إخفاء وإظهار الشريط العلوي مع شريط التحكم السفلي
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = visibility
            }
        )

        rootLayout.addView(playerView)
        rootLayout.addView(emptyStateLayout)
        rootLayout.addView(topBar)

        setContentView(rootLayout)
    }

    private fun createStyledButton(textStr: String, hexColor: String): Button {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = 24f
            setStroke(2, Color.parseColor("#33FFFFFF"))
        }
        return Button(this).apply {
            text = textStr
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = false
            background = bg
            setPadding(32, 16, 32, 16)
        }
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            playerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(
                        this@MainActivity,
                        "تعذر تشغيل الفيديو: ${error.errorCodeName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
    }

    private fun playVideo(uri: Uri) {
        emptyStateLayout.visibility = View.GONE
        exoPlayer?.let { player ->
            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun toggleResizeMode() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
                Toast.makeText(this, "الأبعاد: ملء الشاشة", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                Toast.makeText(this, "الأبعاد: تكبير (Zoom)", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            else -> {
                Toast.makeText(this, "الأبعاد: الحجم الأصلي", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
        playerView.resizeMode = currentResizeMode
    }

    private fun toggleOrientation() {
        isLandscape = !isLandscape
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}
