package com.libremobileos.freeform.server.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.provider.Settings
import android.util.Slog
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.DisplayInfo
import android.view.GestureDetector
import android.view.Gravity
import android.view.IRotationWatcher
import android.view.InputDevice
import android.view.MenuInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import com.android.server.LocalServices
import com.android.server.wm.WindowManagerInternal
import com.libremobileos.freeform.ILMOFreeformDisplayCallback
import com.libremobileos.freeform.server.Debug.dlog
import com.libremobileos.freeform.server.LMOFreeformServiceHolder
import com.libremobileos.freeform.server.SystemServiceHolder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FreeformWindow(
    val handler: Handler,
    val context: Context,
    private val appConfig: AppConfig,
    val freeformConfig: FreeformConfig
): TextureView.SurfaceTextureListener, ILMOFreeformDisplayCallback.Stub(), View.OnTouchListener,
    WindowManagerInternal.DisplaySecureContentListener {

    var freeformTaskStackListener: FreeformTaskStackListener? = null
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val windowManagerInt = LocalServices.getService(WindowManagerInternal::class.java)
    val windowParams = WindowManager.LayoutParams()
    private val resourceHolder = RemoteResourceHolder(context, FREEFORM_PACKAGE)
    var freeformLayout: ViewGroup? = null
    var freeformWindowView: ViewGroup? = null
    var freeformView: TextureView? = null
    private var moveHandle: View? = null
    private var moveHandleIconView: ImageView? = null
    private val moveHandleIcon by lazy { resourceHolder.getDrawable("ic_move_handle") }
    private var bottomBarView: View? = null
    private var actionsMenu: PopupMenu? = null
    var veilView: ViewGroup? = null
    private var displayId = Display.INVALID_DISPLAY
    var defaultDisplayWidth = context.resources.displayMetrics.widthPixels
    var defaultDisplayHeight = context.resources.displayMetrics.heightPixels
    var defaultDisplayRotation = context.display.rotation
    private val hangUpGestureListener = HangUpGestureListener(this)
    private val defaultDisplayInfo = DisplayInfo()
    private val destroyRunnable = Runnable { destroy("destroyRunnable", true) }
    
    private var isInitialized = false

    private val rotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            dlog(TAG, "onRotationChanged($rotation)")
            defaultDisplayWidth = context.resources.displayMetrics.widthPixels
            defaultDisplayHeight = context.resources.displayMetrics.heightPixels
            defaultDisplayRotation = context.display.rotation
            measureSize()
            handler.post {
                actionsMenu?.dismiss()
                if (freeformConfig.isHangUp) toHangUp()
                else changeOrientation()
            }
            measureScale()
            LMOFreeformServiceHolder.resizeFreeform(
                this@FreeformWindow,
                freeformConfig.freeformWidth,
                freeformConfig.freeformHeight,
                freeformConfig.densityDpi
            )
            freeformView?.surfaceTexture?.setDefaultBufferSize(
                freeformConfig.freeformWidth,
                freeformConfig.freeformHeight
            )
        }
    }

    companion object {
        private const val TAG = "LMOFreeform/FreeformWindow"
        private const val FREEFORM_PACKAGE = "com.libremobileos.freeform"
        private const val FREEFORM_LAYOUT = "view_freeform"
        private const val ACTIONS_MENU = "freeform_window_actions"
        private const val WINDOW_DESTROY_WAIT_MS = 10000L
        private const val CONTROL_MODE_SETTING = "freeform_control_mode"
        private const val CONTROL_MODE_BUTTON = "Button"
        private const val CONTROL_MODE_GESTURE = "Gesture"
        private const val LEGACY_BUTTON_MODE = "0"
        private const val LEGACY_GESTURE_MODE = "1"
    }

    init {
        if (LMOFreeformServiceHolder.ping()) {
            Slog.i(TAG, "FreeformWindow init")
            populateFreeformConfig()
            handler.post {
                if (addFreeformView()) {
                    isInitialized = true
                } else {
                    destroy("init:addFreeform failed")
                }
            }
        } else {
            Slog.e(TAG, "FreeformWindow init failed: service not running")
            destroy("init:service not running")
        }
    }

    override fun onDisplayPaused() {
        //NOT USED
    }

    override fun onDisplayResumed() {
        //NOT USED
    }

    override fun onDisplayStopped() {
        //NOT USED
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        dlog(TAG, "onSurfaceTextureAvailable width:$width height:$height")
        if (displayId < 0) {
            LMOFreeformServiceHolder.createDisplay(freeformConfig, appConfig, Surface(surfaceTexture), this)
        }
        surfaceTexture.setDefaultBufferSize(freeformConfig.freeformWidth, freeformConfig.freeformHeight)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(freeformConfig.freeformWidth, freeformConfig.freeformHeight)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        //NOT USED
    }

    override fun onDisplayAdd(displayId: Int) {
        Slog.i(TAG, "onDisplayAdd displayId=$displayId, $appConfig")
        handler.post {
            this.displayId = displayId
            freeformTaskStackListener = FreeformTaskStackListener(displayId, this)
            SystemServiceHolder.activityTaskManager.registerTaskStackListener(freeformTaskStackListener)
            if (appConfig.taskId != -1) {
                dlog(TAG, "moving taskId=${appConfig.taskId} to freeform display")
                freeformTaskStackListener!!.taskId = appConfig.taskId
                runCatching {
                    // TODO: find a new way for this since getTaskDescription was removed in fwb commit a7cae90a991e
                    // if (SystemServiceHolder.activityTaskManager.getTaskDescription(appConfig.taskId) == null) {
                    //     throw Exception("stale task")
                    // }
                    SystemServiceHolder.activityTaskManager.moveRootTaskToDisplay(appConfig.taskId, displayId)
                }
                .onFailure { e ->
                    Slog.e(TAG, "failed to move task ${appConfig.taskId}: $e, fallback to startApp")
                    startApp()
                }
            } else if (appConfig.userId == -100) {
                if (appConfig.pendingIntent == null) destroy("onDisplayAdd:userId=-100, but pendingIntent is null")
                else {
                    LMOFreeformServiceHolder.startPendingIntent(appConfig.pendingIntent, displayId)
                }
            } else {
                startApp()
            }
        }
    }

    private fun startApp() {
        if (displayId == Display.INVALID_DISPLAY) {
            Slog.e(TAG, "cannot startApp: displayId not yet set!")
            return
        }
        if (LMOFreeformServiceHolder.startApp(context, appConfig, displayId).not())
            destroy("startApp failed")
    }

    override fun onDisplayHasSecureWindowOnScreenChanged(displayId: Int, hasSecureWindowOnScreen: Boolean) {
        if (displayId != this.displayId) return;
        dlog(TAG, "onDisplayHasSecureWindowOnScreenChanged: $hasSecureWindowOnScreen")
        windowParams.apply {
            flags = if (hasSecureWindowOnScreen) {
                flags or WindowManager.LayoutParams.FLAG_SECURE
            } else {
                flags xor WindowManager.LayoutParams.FLAG_SECURE
            }
        }
        handler.post {
            val layout = freeformWindowView ?: freeformLayout ?: return@post
            runCatching { windowManager.updateViewLayout(layout, windowParams) }
                .onFailure { Slog.e(TAG, "updateViewLayout failed: $it") }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val newEvent = MotionEvent.obtain(event)
        val scaleMatrix = Matrix().apply {
            setScale(freeformConfig.scale, freeformConfig.scale)
        }
        newEvent.transform(scaleMatrix)
        newEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN)
        LMOFreeformServiceHolder.touch(newEvent, displayId)
        newEvent.recycle()
        return true
    }

    /**
     * get freeform screen dimen / freeform view dimen
     */
    private fun populateFreeformConfig() {
        measureSize()
        measureScale()
        context.display.getDisplayInfo(defaultDisplayInfo)
        freeformConfig.apply {
            refreshRate = defaultDisplayInfo.refreshRate
            presentationDeadlineNanos = defaultDisplayInfo.presentationDeadlineNanos
            dlog(TAG, "populateFreeformConfig: $this")
        }
    }

    fun measureSize() {
        val isPortrait = defaultDisplayRotation == Surface.ROTATION_0 ||
                defaultDisplayRotation == Surface.ROTATION_180
        freeformConfig.apply {
            height = (defaultDisplayHeight * (if (isPortrait) 0.4 else 0.7)).roundToInt()
            width = if (isPortrait) {
                (defaultDisplayWidth * 0.7).roundToInt()
            } else {
                // Landscape: increase width by 1.6x while preserving aspect ratio base
                val baseWidth = defaultDisplayHeight * defaultDisplayHeight / defaultDisplayWidth
                (baseWidth * 1.6).roundToInt()
            }
            dlog(TAG, "measureSize: isPortrait=$isPortrait width=$width height=$height")
        }
    }

    fun measureScale() {
        freeformConfig.apply {
            val widthScale = min(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / min(width, height)
            val heightScale = max(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / max(width, height)
            scale = min(widthScale, heightScale)
            freeformWidth = (width * scale).roundToInt()
            freeformHeight = (height * scale).roundToInt()
            dlog(TAG, "measureScale: $scale freeformWidth=$freeformWidth freeformHeight=$freeformHeight")
        }
    }

    /**
     * Called in system handler
     */
    @SuppressLint("WrongConstant")
    private fun addFreeformView(): Boolean {
        dlog(TAG, "addFreeformView")
        val layout = resourceHolder.getLayout(FREEFORM_LAYOUT) ?: return false
        val handle = resourceHolder.getLayoutChildViewByTag<View>(layout, "moveHandle") ?: return false
        moveHandle = handle
        moveHandleIconView = resourceHolder.getLayoutChildViewByTag(layout, "moveHandleIcon") ?: return false
        veilView = resourceHolder.getLayoutChildViewByTag(layout, "veilView") ?: return false
        bottomBarView = resourceHolder.getLayoutChildViewByTag(layout, "bottomBarView") ?: return false
        val leftScaleView = resourceHolder.getLayoutChildViewByTag<View>(layout, "leftScaleView") ?: return false
        val rightScaleView = resourceHolder.getLayoutChildViewByTag<View>(layout, "rightScaleView") ?: return false
        val gesturePillTouchView = resourceHolder.getLayoutChildViewByTag<View>(layout, "gesturePillTouchView") ?: return false
        val gesturePillView = resourceHolder.getLayoutChildViewByTag<View>(layout, "gesturePill") ?: return false
        val arrowBackView = resourceHolder.getLayoutChildViewByTag<View>(layout, "arrowBack") ?: return false
        val veilAppIconView = resourceHolder.getLayoutChildViewByTag<ImageView>(layout, "veilAppIcon") ?: return false

        runCatching { context.packageManager.getApplicationIcon(appConfig.packageName) }
            .onSuccess { veilAppIconView.setImageDrawable(it) }
            .onFailure { Slog.w(TAG, "Failed to load the resize veil icon", it) }
        handle.setOnClickListener { showWindowActions(it) }
        handle.setOnTouchListener(MoveTouchListener(this))
        if (isGestureMode) {
            gesturePillTouchView.setOnClickListener { goBack() }
            gesturePillTouchView.setOnTouchListener(PillGestureController(this, gesturePillView))
        } else {
            gesturePillTouchView.visibility = View.GONE
            arrowBackView.visibility = View.VISIBLE
            arrowBackView.setOnClickListener { goBack() }
        }
        leftScaleView.setOnTouchListener(ScaleTouchListener(this, false))
        rightScaleView.setOnTouchListener(ScaleTouchListener(this))

        val view = FreeformTextureView(context).apply {
            setOnTouchListener(this@FreeformWindow)
            surfaceTextureListener = this@FreeformWindow
        }
        freeformView = view
        layout.addView(
            view, 0, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        freeformLayout = layout
        val windowView = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            addView(
                layout,
                FrameLayout.LayoutParams(
                    freeformConfig.width,
                    freeformConfig.height,
                    Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                )
            )
        }
        freeformWindowView = windowView
        windowParams.apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
        }
        // Set initial positioning based on sidebar position
        setSidebarAwarePosition()
        
        runCatching {
            windowManager.addView(windowView, windowParams)
            SystemServiceHolder.windowManager.watchRotation(rotationWatcher, Display.DEFAULT_DISPLAY)
            windowManagerInt.registerDisplaySecureContentListener(this)
        }.onFailure {
            Slog.e(TAG, "addView failed: $it")
            return false
        }
        return true
    }

    private fun showWindowActions(anchor: View) {
        if (!anchor.isAttachedToWindow || actionsMenu != null) return

        val resources = anchor.resources
        val menuId = resources.getIdentifier(ACTIONS_MENU, "menu", FREEFORM_PACKAGE)
        if (menuId == 0) {
            Slog.w(TAG, "Missing window actions menu")
            return
        }
        val popupContext = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_DayNight)
        val popup = PopupMenu(popupContext, anchor, Gravity.END)
        MenuInflater(anchor.context).inflate(menuId, popup.menu)
        popup.setOnMenuItemClickListener {
            when (resources.getResourceEntryName(it.itemId)) {
                "freeform_pin" -> handleHangUp()
                "freeform_fullscreen" -> enterFullscreen()
                "freeform_close" -> close()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.setOnDismissListener { actionsMenu = null }
        runCatching { popup.show() }
            .onSuccess { actionsMenu = popup }
            .onFailure { Slog.w(TAG, "Failed to show window actions", it) }
    }

    /**
     * Called in system handler
     */
    @SuppressLint("ClickableViewAccessibility")
    fun handleHangUp() {
        val layout = freeformWindowView ?: return
        val handle = moveHandle ?: return
        val bottomBar = bottomBarView ?: return
        val view = freeformView ?: return
        
        if (freeformConfig.isHangUp) {
            windowParams.apply {
                x = freeformConfig.notInHangUpX
                y = freeformConfig.notInHangUpY
                flags = flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }
            restoreWindowSize()
            windowManager.updateViewLayout(layout, windowParams)
            handle.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            freeformConfig.isHangUp = false
            view.setOnTouchListener(this)
            makeSureFreeformInScreen()
        } else {
            freeformConfig.notInHangUpX = windowParams.x
            freeformConfig.notInHangUpY = windowParams.y
            toHangUp()
            handle.visibility = View.GONE
            bottomBar.visibility = View.GONE
            freeformConfig.isHangUp = true
            val gestureDetector = GestureDetector(context, hangUpGestureListener)
            view.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP) makeSureFreeformInScreen()
                true
            }
        }
    }

    /**
     * Called in system handler
     */
    private fun toHangUp() {
        val layout = freeformWindowView ?: return
        
        windowParams.apply {
            x = (defaultDisplayWidth / 2 - freeformConfig.hangUpWidth / 2)
            y = -(defaultDisplayHeight / 2 - freeformConfig.hangUpHeight / 2)
            flags = flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        }
        setWindowSize(freeformConfig.hangUpWidth, freeformConfig.hangUpHeight)
        runCatching { windowManager.updateViewLayout(layout, windowParams) }.onFailure { Slog.e(TAG, "$it") }
    }

    /**
     * Called in uiHandler
     */
    fun makeSureFreeformInScreen() {
        val size = freeformLayout?.layoutParams ?: return
        val maxX = ((defaultDisplayWidth - size.width) / 2).coerceAtLeast(0)
        val maxY = ((defaultDisplayHeight - size.height) / 2).coerceAtLeast(0)
        val x = windowParams.x.coerceIn(-maxX, maxX)
        val y = windowParams.y.coerceIn(-maxY, maxY)
        if (x != windowParams.x) FreeformAnimation.moveInScreenAnimator(windowParams.x, x, 300, true, this)
        if (y != windowParams.y) FreeformAnimation.moveInScreenAnimator(windowParams.y, y, 300, false, this)
    }

    fun setWindowSize(width: Int, height: Int) {
        val layout = freeformLayout ?: return
        layout.layoutParams = layout.layoutParams.apply {
            this.width = width
            this.height = height
        }
    }

    fun restoreWindowSize() {
        setWindowSize(freeformConfig.width, freeformConfig.height)
    }

    fun changeOrientation() {
        if (freeformConfig.isHangUp) {
            setWindowSize(freeformConfig.hangUpWidth, freeformConfig.hangUpHeight)
        } else {
            restoreWindowSize()
        }
        makeSureFreeformInScreen()
    }

    fun getFreeformId(): String {
        return "${appConfig.packageName},${appConfig.activityName},${appConfig.userId}"
    }

    fun goBack() {
        if (displayId == Display.INVALID_DISPLAY) return
        LMOFreeformServiceHolder.back(displayId)
    }

    enum class WindowAction(val icon: String) {
        Close("ic_minimize"),
        Fullscreen("ic_maximize"),
        Pin("ic_pin")
    }

    fun showActionIcon(action: WindowAction?) {
        moveHandleIconView?.setImageDrawable(
            action?.let { resourceHolder.getDrawable(it.icon) } ?: moveHandleIcon
        )
    }

    fun enterFullscreen() {
        val listener = freeformTaskStackListener
        if (listener == null || listener.taskId == -1) {
            Slog.e(TAG, "taskId is -1, can`t move")
            return
        }
        runCatching {
            SystemServiceHolder.activityTaskManager.moveRootTaskToDisplay(listener.taskId, Display.DEFAULT_DISPLAY)
        }.onFailure {
            Slog.e(TAG, "enterFullscreen failed", it)
        }
    }

    fun close() {
        dlog(TAG, "close()")
        runCatching {
            SystemServiceHolder.activityTaskManager.removeTask(freeformTaskStackListener!!.taskId)
            removeView()
        }.onFailure { exception ->
            Slog.e(TAG, "removeTask failed: ", exception)
            destroy("window.close() fallback")
        }
    }

    fun removeView(runDestroy: Boolean = true) {
        dlog(TAG, "removeView($runDestroy)")
        handler.removeCallbacks(destroyRunnable)
        
        (freeformWindowView ?: freeformLayout)?.let { layout ->
            handler.post {
                actionsMenu?.dismiss()
                runCatching {
                    windowManager.removeViewImmediate(layout)
                    dlog(TAG, "removeView success")
                }.onFailure { exception ->
                    Slog.e(TAG, "removeView failed $exception")
                }
            }
        }
        
        // wait for onTaskRemoved(), but take it into our own hands in case its never triggered.
        if (runDestroy)
            handler.postDelayed(destroyRunnable, WINDOW_DESTROY_WAIT_MS)
    }

    fun destroy(callReason: String, shouldRemoveTask: Boolean = false) {
        Slog.i(TAG, "destroy ${getFreeformId()}, displayId=$displayId callReason: $callReason")
        
        if (!isInitialized) {
            Slog.w(TAG, "destroy called on uninitialized window")
            return
        }
        
        removeView(false)
        handler.removeCallbacks(destroyRunnable)
        
        freeformTaskStackListener?.let {
            SystemServiceHolder.activityTaskManager.unregisterTaskStackListener(it)
        }
        
        runCatching {
            SystemServiceHolder.windowManager.removeRotationWatcher(rotationWatcher)
        }.onFailure {
            Slog.e(TAG, "Failed to remove rotation watcher", it)
        }
        
        LMOFreeformServiceHolder.releaseFreeform(this)
        FreeformWindowManager.removeWindow(getFreeformId())
        windowManagerInt.unregisterDisplaySecureContentListener(this)
        
        freeformTaskStackListener?.taskId?.let {
            if (it != -1 && shouldRemoveTask) {
                Slog.i(TAG, "destroy: remove taskId $it again")
                runCatching { SystemServiceHolder.activityTaskManager.removeTask(it) }
            }
        }
        
        isInitialized = false
    }

    private fun setSidebarAwarePosition() {
        val isLandscape = defaultDisplayWidth > defaultDisplayHeight
        val windowWidth = freeformConfig.width
        val margin = 80 // pixels from edge
        
        windowParams.apply {
            if (isLandscape) {
                // Landscape: position on left side of screen
                x = -(defaultDisplayWidth / 2) + (windowWidth / 2) + margin
                y = 0 // center vertically
            } else {
                // Portrait: center on screen
                x = 0 // center horizontally
                y = -(defaultDisplayHeight / 8) // slightly above center
            }
        }
        
        val mode = if (isLandscape) "landscape (left side)" else "portrait (center)"
        dlog(TAG, "Orientation-aware positioning: $mode, windowX=${windowParams.x}, windowY=${windowParams.y}, screenSize=${defaultDisplayWidth}x${defaultDisplayHeight}")
    }
    
    private fun setDefaultPosition() {
        // Use the same left side positioning as default
        setSidebarAwarePosition()
    }

    private val isGestureMode: Boolean
        get() {
            val mode = Settings.System.getString(context.contentResolver, CONTROL_MODE_SETTING)
            return when {
                mode == null -> true
                mode.equals(CONTROL_MODE_BUTTON, ignoreCase = true) ||
                        mode == LEGACY_BUTTON_MODE -> false
                mode.equals(CONTROL_MODE_GESTURE, ignoreCase = true) ||
                        mode == LEGACY_GESTURE_MODE -> true
                else -> true
            }
        }
}
