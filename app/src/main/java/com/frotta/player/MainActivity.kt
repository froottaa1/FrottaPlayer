package com.frotta.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val duration: Long,
    val size: Long,
    val folderName: String
)

data class FolderItem(
    val name: String,
    val videos: List<VideoItem>
)

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    // عناصر الواجهة الرئيسية (المعرض)
    private lateinit var rootFrame: FrameLayout
    private lateinit var browserContainer: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var tabAllVideosBtn: Button
    private lateinit var tabFoldersBtn: Button
    private lateinit var layoutToggleBtn: Button
    private lateinit var subHeaderBar: LinearLayout
    private lateinit var subHeaderTitle: TextView
    private lateinit var emptyMsgText: TextView

    // بيانات الفيديوهات والمجلدات
    private val allVideos = mutableListOf<VideoItem>()
    private val foldersList = mutableListOf<FolderItem>()
    private var isFolderTab = false
    private var currentOpenedFolder: FolderItem? = null
    private var isGridView = false

    // تحميل الصور المصغرة في الخلفية
    private val thumbExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbCache = object : LruCache<Long, Bitmap>(40) {}

    // عناصر مشغل الفيديو
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var controlsOverlay: FrameLayout
    private lateinit var playerTitleText: TextView
    private lateinit var gestureIndicator: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var playPauseBtn: Button

    private var exoPlayer: ExoPlayer? = null
    private var isPlayerVisible = false
    private var isControlsVisible = true
    private var isUserSeeking = false
    private var isLandscape = false
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    // متغيرات التحكم باللمس والسحب (الصوت، السطوع، التقديم)
    private lateinit var audioManager: AudioManager
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isSwiping = false
    private var swipeMode = 0 // 0=None, 1=Brightness, 2=Volume, 3=Seek
    private var initialBrightness = 0.5f
    private var initialVolume = 0
    private var maxVolume = 15
    private var initialSeekPosition = 0L
    private var targetSeekPosition = 0L

    private val progressRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (isPlayerVisible && !isUserSeeking && player.isPlaying) {
                    val pos = player.currentPosition
                    val dur = player.duration.coerceAtLeast(1L)
                    seekBar.max = dur.toInt()
                    seekBar.progress = pos.toInt()
                    currentTimeText.text = formatTime(pos)
                    totalTimeText.text = formatTime(dur)
                }
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private val hideControlsRunnable = Runnable {
        if (isPlayerVisible && exoPlayer?.isPlaying == true) {
            controlsOverlay.visibility = View.GONE
            isControlsVisible = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadVideosFromDevice()
        } else {
            emptyMsgText.visibility = View.VISIBLE
            emptyMsgText.text = "يرجى منح صلاحية الوصول للفيديوهات لعرض مكتبتك تلقائياً"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        setupCompleteUi()
        initializeExoPlayer()
        setupBackNavigation()

        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            openVideoPlayer(intent.data!!, "فيديو خارجي")
        } else {
            checkAndRequestPermission()
        }
    }

    private fun checkAndRequestPermission() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadVideosFromDevice()
        } else {
            permissionLauncher.launch(perm)
        }
    }

    private fun setupCompleteUi() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        setupBrowserUi()
        setupPlayerUi()

        rootFrame.addView(browserContainer)
        rootFrame.addView(playerContainer)
        setContentView(rootFrame)
    }

    private fun setupBrowserUi() {
        browserContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // الشريط العلوي للمعرض
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(36, 36, 36, 28)
        }

        val title = TextView(this).apply {
            text = "🎬 Frotta Player"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        layoutToggleBtn = createPillButton("⊞ شبكة", "#334155").apply {
            setOnClickListener {
                isGridView = !isGridView
                text = if (isGridView) "☰ قائمة" else "⊞ شبكة"
                refreshBrowserList()
            }
        }

        header.addView(title)
        header.addView(layoutToggleBtn)

        // شريط التبويبات (جميع الفيديوهات | المجلدات)
        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(36, 0, 36, 28)
        }

        tabAllVideosBtn = createPillButton("جميع الفيديوهات", "#2563EB").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
            setOnClickListener {
                isFolderTab = false
                currentOpenedFolder = null
                updateTabButtonsStyle()
                refreshBrowserList()
            }
        }

        tabFoldersBtn = createPillButton("📁 المجلدات", "#334155").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12
            }
            setOnClickListener {
                isFolderTab = true
                currentOpenedFolder = null
                updateTabButtonsStyle()
                refreshBrowserList()
            }
        }

        tabsRow.addView(tabAllVideosBtn)
        tabsRow.addView(tabFoldersBtn)

        // شريط عنوان المجلد المفتوح
        subHeaderBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(36, 20, 36, 12)
            visibility = View.GONE
        }

        val backFolderBtn = createPillButton("⬅ رجوع للمجلدات", "#334155").apply {
            setOnClickListener {
                currentOpenedFolder = null
                refreshBrowserList()
            }
        }

        subHeaderTitle = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, Typeface.BOLD)
            setPadding(24, 0, 24, 0)
        }

        subHeaderBar.addView(backFolderBtn)
        subHeaderBar.addView(subHeaderTitle)

        emptyMsgText = TextView(this).apply {
            text = "جاري فحص الفيديوهات على الهاتف..."
            textSize = 16f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(48, 120, 48, 48)
            visibility = View.GONE
        }

        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(16, 16, 16, 16)
            clipToPadding = false
        }

        browserContainer.addView(header)
        browserContainer.addView(tabsRow)
        browserContainer.addView(subHeaderBar)
        browserContainer.addView(emptyMsgText)
        browserContainer.addView(recyclerView)
    }

    private fun setupPlayerUi() {
        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        playerView = PlayerView(this).apply {
            useController = false // نستخدم واجهة التحكم المخصصة بأسفل اليسار
            resizeMode = currentResizeMode
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // طبقة استقبال اللمس والسحب (سطوع، صوت، تقديم/تأخير)
        val gestureSurface = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, event -> handlePlayerTouch(event) }
        }

        // مؤشر السحب في منتصف الشاشة
        gestureIndicator = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(48, 28, 48, 28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = 32f
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        // واجهة أزرار المشغل المخصصة
        controlsOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // الشريط العلوي للمشغل
        val topPlayerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(28, 24, 28, 24)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val closePlayerBtn = createPillButton("⬅ رجوع", "#1E293B").apply {
            setOnClickListener { closeVideoPlayer() }
        }

        playerTitleText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(24, 0, 24, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val ratioBtn = createPillButton("📐 الأبعاد", "#1E293B").apply {
            setOnClickListener {
                toggleResizeMode()
                scheduleHideControls()
            }
        }

        val rotateBtn = createPillButton("🔄 تدوير", "#1E293B").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 12 }
            setOnClickListener {
                toggleOrientation()
                scheduleHideControls()
            }
        }

        topPlayerBar.addView(closePlayerBtn)
        topPlayerBar.addView(playerTitleText)
        topPlayerBar.addView(ratioBtn)
        topPlayerBar.addView(rotateBtn)

        // الشريط السفلي (شريط الوقت + أزرار التشغيل في الأسفل على اليسار)
        val bottomPlayerBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(32, 16, 32, 28)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            // نفرض اتجاه LTR في شريط المشغل السفلي لتكون الأزرار دائماً في أقصى اليسار
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // صف شريط التقدم والوقت
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        currentTimeText = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            textSize = 13f
        }

        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 16
                marginEnd = 16
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) currentTimeText.text = formatTime(progress.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = true
                    mainHandler.removeCallbacks(hideControlsRunnable)
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = false
                    exoPlayer?.seekTo((sb?.progress ?: 0).toLong())
                    scheduleHideControls()
                }
            })
        }

        totalTimeText = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
        }

        seekRow.addView(currentTimeText)
        seekRow.addView(seekBar)
        seekRow.addView(totalTimeText)

        // صف أزرار التحكم الموضوعة في الأسفل جهة اليسار حتى لا تغطي الفيديو
        val bottomLeftControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(8, 12, 8, 0)
        }

        val rewindBtn = createPillButton("⏪ -10s", "#1E293B").apply {
            setOnClickListener {
                exoPlayer?.let { p -> p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L)) }
                scheduleHideControls()
            }
        }

        playPauseBtn = createPillButton("⏸ إيقاف", "#2563EB").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 16
                marginEnd = 16
            }
            setOnClickListener {
                exoPlayer?.let { p ->
                    if (p.isPlaying) p.pause() else p.play()
                    updatePlayPauseButtonText()
                }
                scheduleHideControls()
            }
        }

        val forwardBtn = createPillButton("⏩ +10s", "#1E293B").apply {
            setOnClickListener {
                exoPlayer?.let { p ->
                    val dur = p.duration.coerceAtLeast(0L)
                    p.seekTo((p.currentPosition + 10_000L).coerceAtMost(dur))
                }
                scheduleHideControls()
            }
        }

        bottomLeftControlsRow.addView(rewindBtn)
        bottomLeftControlsRow.addView(playPauseBtn)
        bottomLeftControlsRow.addView(forwardBtn)

        bottomPlayerBar.addView(seekRow)
        bottomPlayerBar.addView(bottomLeftControlsRow)

        controlsOverlay.addView(topPlayerBar)
        controlsOverlay.addView(bottomPlayerBar)

        playerContainer.addView(playerView)
        playerContainer.addView(gestureSurface)
        playerContainer.addView(controlsOverlay)
        playerContainer.addView(gestureIndicator)
    }

    // معالجة إيماءات اللمس والسحب (يسار: سطوع، يمين: صوت، أفقي: تقديم وتأخير)
    private fun handlePlayerTouch(event: MotionEvent): Boolean {
        val screenW = playerContainer.width.coerceAtLeast(1)
        val screenH = playerContainer.height.coerceAtLeast(1)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                isSwiping = false
                swipeMode = 0

                val winAttr = window.attributes
                initialBrightness = if (winAttr.screenBrightness >= 0f) winAttr.screenBrightness else 0.5f
                initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                initialSeekPosition = exoPlayer?.currentPosition ?: 0L
                targetSeekPosition = initialSeekPosition
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchStartX
                val dy = touchStartY - event.y // للأعلى موجب، للأسفل سالب

                if (!isSwiping && (abs(dx) > 35f || abs(dy) > 35f)) {
                    isSwiping = true
                    swipeMode = if (abs(dx) > abs(dy)) {
                        3 // سحب أفقي للتقديم والتأخير
                    } else {
                        if (touchStartX < screenW / 2f) 1 else 2 // 1: يسار (سطوع)، 2: يمين (صوت)
                    }
                    gestureIndicator.visibility = View.VISIBLE
                    mainHandler.removeCallbacks(hideControlsRunnable)
                }

                if (isSwiping) {
                    when (swipeMode) {
                        1 -> { // التحكم في السطوع (النصف الأيسر)
                            val delta = dy / (screenH * 0.65f)
                            val newBri = (initialBrightness + delta).coerceIn(0.02f, 1.0f)
                            val lp = window.attributes
                            lp.screenBrightness = newBri
                            window.attributes = lp
                            val pct = (newBri * 100).toInt()
                            gestureIndicator.text = "☀️ السطوع: $pct%"
                        }
                        2 -> { // التحكم في الصوت (النصف الأيمن)
                            val delta = dy / (screenH * 0.65f)
                            val newVol = (initialVolume + (delta * maxVolume)).toInt().coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            val pct = ((newVol.toFloat() / maxVolume) * 100).toInt()
                            gestureIndicator.text = "🔊 الصوت: $pct%"
                        }
                        3 -> { // التقديم والتأخير بالسحب يميناً ويساراً
                            val duration = (exoPlayer?.duration ?: 0L).coerceAtLeast(1L)
                            // السحب بعرض الشاشة يساوي تقديم/تأخير 90 ثانية
                            val seekDelta = ((dx / screenW) * 90_000L).toLong()
                            targetSeekPosition = (initialSeekPosition + seekDelta).coerceIn(0L, duration)
                            val diffSec = (targetSeekPosition - initialSeekPosition) / 1000
                            val sign = if (diffSec >= 0) "+" else ""
                            gestureIndicator.text = "⏩ ${formatTime(targetSeekPosition)} ($sign${diffSec}s)"
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSwiping) {
                    if (swipeMode == 3) {
                        exoPlayer?.seekTo(targetSeekPosition)
                    }
                    gestureIndicator.visibility = View.GONE
                    scheduleHideControls()
                } else {
                    // ضغطة عادية لإظهار أو إخفاء أزرار التحكم
                    toggleControlsVisibility()
                }
                isSwiping = false
                swipeMode = 0
                return true
            }
        }
        return false
    }

    private fun toggleControlsVisibility() {
        if (isControlsVisible) {
            controlsOverlay.visibility = View.GONE
            isControlsVisible = false
            mainHandler.removeCallbacks(hideControlsRunnable)
        } else {
            controlsOverlay.visibility = View.VISIBLE
            isControlsVisible = true
            scheduleHideControls()
        }
    }

    private fun scheduleHideControls() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 3500)
    }

    private fun loadVideosFromDevice() {
        emptyMsgText.visibility = View.VISIBLE
        emptyMsgText.text = "جاري جمع الفيديوهات من الهاتف..."

        thumbExecutor.execute {
            val loaded = mutableListOf<VideoItem>()
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DATA
            )
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(nameCol) ?: "Video_$id"
                    val duration = cursor.getLong(durCol)
                    val size = cursor.getLong(sizeCol)
                    var folder = if (bucketCol != -1) cursor.getString(bucketCol) else null
                    if (folder.isNullOrBlank() && dataCol != -1) {
                        val path = cursor.getString(dataCol)
                        folder = path?.let { File(it).parentFile?.name }
                    }
                    val finalFolder = if (folder.isNullOrBlank()) "مجلد عام" else folder
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    loaded.add(VideoItem(id, uri, title, duration, size, finalFolder))
                }
            }

            val grouped = loaded.groupBy { it.folderName }
                .map { FolderItem(it.key, it.value) }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }

            mainHandler.post {
                allVideos.clear()
                allVideos.addAll(loaded)
                foldersList.clear()
                foldersList.addAll(grouped)
                refreshBrowserList()
            }
        }
    }

    private fun refreshBrowserList() {
        recyclerView.layoutManager = if (isGridView) {
            GridLayoutManager(this, 2)
        } else {
            LinearLayoutManager(this)
        }

        if (isFolderTab && currentOpenedFolder == null) {
            subHeaderBar.visibility = View.GONE
            if (foldersList.isEmpty()) {
                emptyMsgText.visibility = View.VISIBLE
                emptyMsgText.text = "لا توجد مجلدات فيديو على الهاتف"
            } else {
                emptyMsgText.visibility = View.GONE
            }
            recyclerView.adapter = FolderAdapter(foldersList) { folder ->
                currentOpenedFolder = folder
                refreshBrowserList()
            }
        } else {
            val videosToShow = if (isFolderTab && currentOpenedFolder != null) {
                subHeaderBar.visibility = View.VISIBLE
                subHeaderTitle.text = "📁 ${currentOpenedFolder!!.name} (${currentOpenedFolder!!.videos.size})"
                currentOpenedFolder!!.videos
            } else {
                subHeaderBar.visibility = View.GONE
                allVideos
            }

            if (videosToShow.isEmpty()) {
                emptyMsgText.visibility = View.VISIBLE
                emptyMsgText.text = "لا توجد فيديوهات متاحة للعرض"
            } else {
                emptyMsgText.visibility = View.GONE
            }

            recyclerView.adapter = VideoAdapter(videosToShow) { video ->
                openVideoPlayer(video.uri, video.title)
            }
        }
    }

    private fun updateTabButtonsStyle() {
        val activeBg = GradientDrawable().apply {
            setColor(Color.parseColor("#2563EB"))
            cornerRadius = 24f
        }
        val inactiveBg = GradientDrawable().apply {
            setColor(Color.parseColor("#334155"))
            cornerRadius = 24f
        }
        tabAllVideosBtn.background = if (!isFolderTab) activeBg else inactiveBg
        tabFoldersBtn.background = if (isFolderTab) activeBg else inactiveBg
    }

    private fun initializeExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            playerView.player = player
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseButtonText()
                }
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(
                        this@MainActivity,
                        "خطأ في تشغيل الفيديو: ${error.errorCodeName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
    }

    private fun openVideoPlayer(uri: Uri, title: String) {
        isPlayerVisible = true
        browserContainer.visibility = View.GONE
        playerContainer.visibility = View.VISIBLE
        controlsOverlay.visibility = View.VISIBLE
        isControlsVisible = true
        playerTitleText.text = title

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        exoPlayer?.let { player ->
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
        }
        mainHandler.post(progressRunnable)
        scheduleHideControls()
    }

    private fun closeVideoPlayer() {
        isPlayerVisible = false
        exoPlayer?.stop()
        mainHandler.removeCallbacks(progressRunnable)
        mainHandler.removeCallbacks(hideControlsRunnable)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        isLandscape = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showSystemBars()

        playerContainer.visibility = View.GONE
        browserContainer.visibility = View.VISIBLE
    }

    private fun updatePlayPauseButtonText() {
        val playing = exoPlayer?.isPlaying == true
        playPauseBtn.text = if (playing) "⏸ إيقاف" else "▶ تشغيل"
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

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isPlayerVisible -> closeVideoPlayer()
                    isFolderTab && currentOpenedFolder != null -> {
                        currentOpenedFolder = null
                        refreshBrowserList()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // محول عرض الفيديوهات مع الصور المصغرة (يدعم القائمة والشبكة)
    inner class VideoAdapter(
        private val items: List<VideoItem>,
        private val onClick: (VideoItem) -> Unit
    ) : RecyclerView.Adapter<VideoAdapter.VideoVH>() {

        inner class VideoVH(
            val root: View,
            val thumbView: ImageView,
            val durationBadge: TextView,
            val titleView: TextView,
            val metaView: TextView
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoVH {
            val cardBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 24f
            }

            val thumbFrame = FrameLayout(parent.context)
            val imageView = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#0F172A"))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val badge = TextView(parent.context).apply {
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(14, 4, 14, 4)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#CC000000"))
                    cornerRadius = 12f
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).apply { setMargins(8, 8, 8, 8) }
            }
            thumbFrame.addView(imageView)
            thumbFrame.addView(badge)

            val titleTv = TextView(parent.context).apply {
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }

            val metaTv = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 8, 0, 0)
            }

            val container = LinearLayout(parent.context).apply {
                background = cardBg
                val lp = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(12, 12, 12, 12) }
                layoutParams = lp
            }

            if (isGridView) {
                container.orientation = LinearLayout.VERTICAL
                container.setPadding(20, 20, 20, 20)
                thumbFrame.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    260
                )
                titleTv.setPadding(0, 16, 0, 0)
                container.addView(thumbFrame)
                container.addView(titleTv)
                container.addView(metaTv)
            } else {
                container.orientation = LinearLayout.HORIZONTAL
                container.gravity = Gravity.CENTER_VERTICAL
                container.setPadding(20, 20, 20, 20)
                thumbFrame.layoutParams = LinearLayout.LayoutParams(280, 165)

                val textCol = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 0, 12, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(titleTv)
                textCol.addView(metaTv)

                container.addView(thumbFrame)
                container.addView(textCol)
            }

            return VideoVH(container, imageView, badge, titleTv, metaTv)
        }

        override fun onBindViewHolder(holder: VideoVH, position: Int) {
            val item = items[position]
            holder.titleView.text = item.title
            holder.durationBadge.text = formatTime(item.duration)
            val sizeMb = String.format(Locale.US, "%.1f MB", item.size / (1024f * 1024f))
            holder.metaView.text = "📁 ${item.folderName}  •  $sizeMb"

            holder.thumbView.tag = item.id
            val cached = thumbCache.get(item.id)
            if (cached != null) {
                holder.thumbView.setImageBitmap(cached)
            } else {
                holder.thumbView.setImageResource(android.R.drawable.ic_media_play)
                thumbExecutor.execute {
                    val bmp = loadVideoThumbnail(item)
                    if (bmp != null) {
                        thumbCache.put(item.id, bmp)
                        mainHandler.post {
                            if (holder.thumbView.tag == item.id) {
                                holder.thumbView.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }

            holder.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    // محول عرض المجلدات (يدعم القائمة والشبكة)
    inner class FolderAdapter(
        private val folders: List<FolderItem>,
        private val onClick: (FolderItem) -> Unit
    ) : RecyclerView.Adapter<FolderAdapter.FolderVH>() {

        inner class FolderVH(
            val root: View,
            val folderNameTv: TextView,
            val countTv: TextView
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderVH {
            val cardBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 24f
            }

            val iconTv = TextView(parent.context).apply {
                text = "📁"
                textSize = if (isGridView) 42f else 32f
                gravity = Gravity.CENTER
            }

            val nameTv = TextView(parent.context).apply {
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val countTv = TextView(parent.context).apply {
                textSize = 13f
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, 6, 0, 0)
            }

            val container = LinearLayout(parent.context).apply {
                background = cardBg
                setPadding(32, 28, 32, 28)
                val lp = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(12, 12, 12, 12) }
                layoutParams = lp
            }

            if (isGridView) {
                container.orientation = LinearLayout.VERTICAL
                container.gravity = Gravity.CENTER
                nameTv.gravity = Gravity.CENTER
                nameTv.setPadding(0, 12, 0, 0)
                countTv.gravity = Gravity.CENTER
                container.addView(iconTv)
                container.addView(nameTv)
                container.addView(countTv)
            } else {
                container.orientation = LinearLayout.HORIZONTAL
                container.gravity = Gravity.CENTER_VERTICAL
                val textCol = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(28, 0, 12, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(nameTv)
                textCol.addView(countTv)
                container.addView(iconTv)
                container.addView(textCol)
            }

            return FolderVH(container, nameTv, countTv)
        }

        override fun onBindViewHolder(holder: FolderVH, position: Int) {
            val folder = folders[position]
            holder.folderNameTv.text = folder.name
            holder.countTv.text = "${folder.videos.size} فيديو"
            holder.root.setOnClickListener { onClick(folder) }
        }

        override fun getItemCount(): Int = folders.size
    }

    @Suppress("DEPRECATION")
    private fun loadVideoThumbnail(item: VideoItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(item.uri, Size(320, 180), null)
            } else {
                MediaStore.Video.Thumbnails.getThumbnail(
                    contentResolver,
                    item.id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createPillButton(label: String, hexBg: String): Button {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor(hexBg))
            cornerRadius = 24f
        }
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = false
            background = bg
            setPadding(28, 14, 28, 14)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
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

    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        thumbExecutor.shutdownNow()
        exoPlayer?.release()
        exoPlayer = null
    }
}
