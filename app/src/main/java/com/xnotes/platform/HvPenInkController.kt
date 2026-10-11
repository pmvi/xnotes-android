package com.xnotes.platform

import android.graphics.Rect
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Adapter for Hanvon's system-owned low-latency ink surface.
 *
 * The service draws the live preview while the pen is down and returns the completed screen-space
 * samples on lift. Those samples are replayed as a stylus MotionEvent stream through XNotes' normal
 * editor input path, so its stroke engine, document model, undo stack and existing wet-ink handover
 * remain responsible for the permanent stroke. On other Android devices this adapter stays idle.
 */
class HvPenInkController(
    private val view: View,
    private val selection: () -> Selection,
    private val dispatch: (MotionEvent) -> Boolean,
    private val publishAfterRender: (() -> Unit) -> Unit,
) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener {

    data class Selection(val tool: Tool, val config: ToolConfig, val zoom: Double = 1.0)

    private data class ScreenPoint(val x: Float, val y: Float, val pressure: Float)

    private val main = Handler(Looper.getMainLooper())
    private var manager: Any? = null
    private var callback: Any? = null
    private var areaId = 0L
    private var areaRect: Rect? = null
    private var areaRotation: Int? = null
    private var displayManager: DisplayManager? = null
    private var strokeRect: Rect? = null
    private var strokeSelection: Selection? = null
    private var strokeTouchStart: ScreenPoint? = null
    private var strokeTouchEnd: ScreenPoint? = null
    private var appliedSelection: Selection? = null
    @Volatile private var strokeGeneration = 0L
    private var nativeContactDown = false
    private var toolbarStylusContact = false
    private var clearPendingGeneration: Long? = null
    private var observedTree: ViewTreeObserver? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (view.display?.displayId == displayId) view.post(::startOrResize)
        }
    }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused) view.post(::startOrResize) else stop()
    }

    val isActive: Boolean get() = manager != null && areaId != 0L

    init {
        view.addOnAttachStateChangeListener(this)
        view.addOnLayoutChangeListener(this)
        if (view.isAttachedToWindow) {
            attachDisplayObserver()
            attachFocusObserver()
            view.post(::startOrResize)
        }
    }

    /** Call when XNotes changes the armed tool or its style. */
    fun updateSelection() {
        val next = selection()
        if (next == appliedSelection && manager != null) return
        appliedSelection = next
        configure(next)
    }

    /** Consume only contact events for an active native stroke tool. */
    fun consumeStylusEvent(event: MotionEvent): Boolean {
        if (!isActive || !selection().tool.isStroke || event.pointerCount == 0) return false
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokeGeneration++
                nativeContactDown = true
                updateSelection()
                strokeRect = screenRect()
                strokeSelection = selection()
                strokeTouchStart = ScreenPoint(event.rawX, event.rawY, event.pressure)
                strokeTouchEnd = null
            }
            MotionEvent.ACTION_MOVE -> strokeTouchEnd = ScreenPoint(event.rawX, event.rawY, event.pressure)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                strokeTouchEnd = ScreenPoint(event.rawX, event.rawY, event.pressure)
                nativeContactDown = false
                if (clearPendingGeneration == strokeGeneration) {
                    clearPendingGeneration = null
                    manager?.let(::clearTransientInk)
                }
                // Keep the down-time selection and rectangle until the service callback arrives.
            }
        }
        return true
    }

    /** Keep toolbar pen input available to Compose while preventing HvPen from drawing over it. */
    fun onToolbarStylusContact(active: Boolean) {
        if (active) {
            if (!toolbarStylusContact) {
                toolbarStylusContact = true
                val id = areaId
                if (id != 0L && manager != null) runCatching {
                    invoke("enablePen", id, false)
                    invoke("setAreaActive", id, false)
                    manager?.let(::clearTransientInk)
                }.onFailure { Log.w(TAG, "Could not suspend HvPen over toolbar", it) }
            }
            return
        }
        if (!toolbarStylusContact) return
        toolbarStylusContact = false
        val id = areaId
        if (id != 0L && manager != null) runCatching {
            clearTransientInk(manager ?: return@runCatching)
            configure(selection())
            invoke("enablePen", id, selection().tool.isStroke)
            invoke("setAreaActive", id, true)
        }.onFailure { Log.w(TAG, "Could not resume HvPen after toolbar input", it) }
    }

    override fun onViewAttachedToWindow(v: View) {
        attachDisplayObserver()
        attachFocusObserver()
        startOrResize()
    }

    override fun onViewDetachedFromWindow(v: View) {
        detachDisplayObserver()
        detachFocusObserver()
        stop()
    }

    override fun onLayoutChange(
        v: View,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int,
    ) {
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
            view.post(::startOrResize)
        }
    }

    private fun startOrResize() {
        if (!view.isAttachedToWindow || !view.hasWindowFocus() || view.width <= 0 || view.height <= 0) return
        val nextRect = screenRect()
        val nextRotation = view.display?.rotation ?: 0
        if (isActive && nextRect == areaRect && nextRotation == areaRotation) return
        if (isActive) stop()
        start(nextRect, nextRotation)
    }

    private fun start(rect: Rect, rotation: Int) {
        try {
            val serviceRect = toHvPenArea(rect, rotation)
            val service = view.context.getSystemService("hvpen")
                ?: error("Hanvon hvpen service is unavailable")
            val listenerType = Class.forName("android.os.HvPenDrawListener")
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerType.classLoader,
                arrayOf(listenerType),
            ) { proxy, method, args ->
                when (method.name) {
                    "onPenTouchUpStatus" -> {
                        val data = args?.getOrNull(1) as? FloatArray
                        val copy = data?.copyOf()
                        val capturedRect = strokeRect ?: areaRect ?: rect
                        val capturedSelection = strokeSelection ?: selection()
                        val capturedTouchStart = strokeTouchStart
                        val capturedTouchEnd = strokeTouchEnd
                        val capturedGeneration = strokeGeneration
                        Log.i(TAG, "hvpen returned ${copy?.size ?: 0} floats for ${capturedSelection.tool}")
                        main.post {
                            var committed = false
                            try {
                                if (copy != null && capturedTouchStart != null && capturedSelection.tool.isStroke) {
                                    committed = replay(copy, capturedRect, capturedTouchStart, capturedTouchEnd)
                                }
                            } catch (error: Throwable) {
                                Log.e(TAG, "Could not commit hvpen stroke into XNotes", error)
                            } finally {
                                strokeRect = null
                                strokeSelection = null
                                strokeTouchStart = null
                                strokeTouchEnd = null
                                if (committed) {
                                    // Wait for XNotes' committed frame, then leave a grace period
                                    // for the e-ink panel to settle the handover visibly.
                                    publishAfterRender {
                                        main.postDelayed(
                                            { clearWhenSafe(service, capturedGeneration) },
                                            HANDOFF_GRACE_MS,
                                        )
                                    }
                                } else {
                                    clearWhenSafe(service, capturedGeneration)
                                }
                            }
                        }
                        null
                    }
                    "toString" -> "XNotesHvPenListener"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            val result = service.javaClass.getMethod(
                "initService",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                listenerType,
            ).invoke(service, serviceRect.left, serviceRect.top, serviceRect.right, serviceRect.bottom, listener)
            val id = (result as Number).toLong()
            check(id != 0L) { "hvpen returned an empty drawing area" }

            manager = service
            callback = listener
            areaId = id
            areaRect = Rect(rect)
            areaRotation = rotation
            invoke("setDrawArea", id, serviceRect.left, serviceRect.top, serviceRect.right, serviceRect.bottom)
            invoke("setDrawStatus", id, 0)
            invoke("setOverlay", true)
            configure(selection())
            invoke("enablePen", id, selection().tool.isStroke)
            invoke("setAreaActive", id, true)
            Log.i(TAG, "Hanvon low-latency ink active: area=$id viewRect=$rect serviceRect=$serviceRect rotation=${view.display?.rotation} tool=${selection().tool}")
        } catch (error: Throwable) {
            Log.w(TAG, "Hanvon low-latency ink is unavailable; using XNotes touch rendering", error)
            stop()
        }
    }

    private fun configure(current: Selection) {
        val id = areaId
        if (id == 0L || manager == null) return
        try {
            val native = nativeStyle(current.tool, current.config.rgba)
            invoke("setPen", id, native.first)
            invoke("setPenColor", id, native.second)
            // HvPen's width is in screen pixels; XNotes stores width in page space when scale is
            // enabled. Apply the same zoom conversion before showing its live preview.
            val screenWidth = current.config.baseWidth *
                if (current.config.scale) current.zoom.coerceAtLeast(0.01) else 1.0
            invoke("setPenWidth", id, screenWidth.roundToInt().coerceIn(1, 20))
            invoke("enablePen", id, current.tool.isStroke)
            appliedSelection = current
            Log.d(TAG, "Applied hvpen style for ${current.tool}: native=${native.first}, colorCode=${native.second}")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not update Hanvon pen style", error)
        }
    }

    private fun nativeStyle(tool: Tool, color: Rgba): Pair<Int, Int> {
        val penId = when (tool) {
            // IDs verified against Hanvon Note's EnumPenStyle -> Pen.PEN_STYLE table:
            // crayon=1, pencil=15, graffiti=6, marker=15, fountain=20, calligraphy fountain=22.
            Tool.PEN, Tool.DASHED -> HV_GRAFFITI
            Tool.CALLIGRAPHY -> HV_CALLIGRAPHY_FOUNTAIN
            Tool.SPEED, Tool.HIGHLIGHTER -> HV_PENCIL
            Tool.TAPER -> HV_FOUNTAIN
            else -> HV_GRAFFITI
        }
        // libpaintworker's SetPenColor accepts codes 1..5 and selects fixed SkPaint colours:
        // black, light gray, blue, green, red. Arbitrary ARGB values are ignored by the native code.
        val nativeColor = listOf(
            HV_BLACK to Rgba(0, 0, 0),
            HV_LIGHT_GRAY to Rgba(224, 224, 224),
            HV_BLUE to Rgba(0, 0, 255),
            HV_GREEN to Rgba(0, 255, 0),
            HV_RED to Rgba(255, 0, 0),
        ).minBy { (_, candidate) ->
            val dr = color.r - candidate.r
            val dg = color.g - candidate.g
            val db = color.b - candidate.b
            dr * dr + dg * dg + db * db
        }.first
        return penId to nativeColor
    }

    private fun replay(
        data: FloatArray,
        rect: Rect,
        touchStart: ScreenPoint?,
        touchEnd: ScreenPoint?,
    ): Boolean {
        if (data.isEmpty()) return false
        if (!data[0].isFinite()) {
            Log.w(TAG, "Ignoring hvpen stroke with a non-finite point count")
            return false
        }
        val count = data[0].roundToInt()
        if (count < 1 || count > MAX_POINTS || data.size != count * 3 + 1) {
            Log.w(TAG, "Ignoring malformed hvpen stroke payload (${data.size} floats)")
            return false
        }
        val rawPoints = ArrayList<ScreenPoint>(count)
        for (index in 0 until count) {
            val offset = 1 + index * 3
            val x = data[offset]
            val y = data[offset + 1]
            val pressure = data[offset + 2]
            if (!x.isFinite() || !y.isFinite() || !pressure.isFinite()) return false
            rawPoints += ScreenPoint(x, y, pressure.coerceIn(0f, 1f))
        }
        val (transformName, points) = mapServiceCoordinates(rawPoints, rect, touchStart, touchEnd)
        Log.i(TAG, "Mapped hvpen stroke: transform=$transformName count=$count start=${rawPoints.first()} end=${rawPoints.last()}")

        // The HvPen payload contains x/y/pressure but no timestamps. Estimate timing from travel
        // distance so XNotes' speed-sensitive tools get a usable, if approximate, velocity signal.
        val times = LongArray(count)
        var elapsed = 0L
        for (index in 1 until count) {
            val previous = points[index - 1]
            val current = points[index]
            elapsed += (hypot((current.x - previous.x).toDouble(), (current.y - previous.y).toDouble()) / 1.5)
                .roundToInt().coerceIn(1, 12)
            times[index] = elapsed
        }
        val downTime = (SystemClock.uptimeMillis() - elapsed).coerceAtLeast(0L)
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        })

        fun coordinates(point: ScreenPoint) = arrayOf(MotionEvent.PointerCoords().apply {
            x = point.x
            y = point.y
            pressure = point.pressure
            size = 1f
        })

        fun send(action: Int, index: Int, eventTime: Long): MotionEvent = MotionEvent.obtain(
            downTime,
            eventTime.coerceAtLeast(downTime),
            action,
            1,
            properties,
            coordinates(points[index]),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )

        var handled = send(MotionEvent.ACTION_DOWN, 0, downTime).useEvent(dispatch)
        var index = 1
        while (index < count - 1) {
            val first = index
            val move = send(MotionEvent.ACTION_MOVE, first, downTime + times[first])
            index++
            var batched = 1
            while (index < count - 1 && batched < MOVE_BATCH_SIZE) {
                move.addBatch(downTime + times[index], coordinates(points[index]), 0)
                index++
                batched++
            }
            handled = move.useEvent(dispatch) && handled
        }
        val upTime = if (count == 1) downTime + 1 else downTime + times[count - 1]
        handled = send(MotionEvent.ACTION_UP, count - 1, upTime).useEvent(dispatch) && handled
        return handled
    }

    /**
     * HvPen's callback buffer has no documented coordinate-space contract. Compare its first and
     * last samples with Android's raw stylus DOWN/UP coordinates, then select the simple display
     * transform that best fits those observed endpoints. This corrects the committed path without
     * guessing the device orientation or changing the vendor preview path.
     */
    private fun mapServiceCoordinates(
        raw: List<ScreenPoint>,
        rect: Rect,
        touchStart: ScreenPoint?,
        touchEnd: ScreenPoint?,
    ): Pair<String, List<ScreenPoint>> {
        val legacy = raw.map { ScreenPoint(it.x - rect.left, it.y - rect.top, it.pressure) }
        if (touchStart == null || touchEnd == null || raw.size < 2) return "legacy-screen-minus-area" to legacy

        val displaySize = Point()
        view.display?.getRealSize(displaySize)
        val screenWidth = displaySize.x.takeIf { it > 0 }?.toFloat() ?: view.resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = displaySize.y.takeIf { it > 0 }?.toFloat() ?: view.resources.displayMetrics.heightPixels.toFloat()
        data class Candidate(val name: String, val map: (ScreenPoint) -> ScreenPoint)
        val candidates = listOf(
            Candidate("absolute", { p -> ScreenPoint(p.x - rect.left, p.y - rect.top, p.pressure) }),
            Candidate("area-local", { p -> ScreenPoint(p.x, p.y, p.pressure) }),
            Candidate("absolute-cw", { p -> ScreenPoint(screenWidth - p.y - rect.left, p.x - rect.top, p.pressure) }),
            Candidate("absolute-ccw", { p -> ScreenPoint(p.y - rect.left, screenHeight - p.x - rect.top, p.pressure) }),
            Candidate("absolute-180", { p -> ScreenPoint(screenWidth - p.x - rect.left, screenHeight - p.y - rect.top, p.pressure) }),
            Candidate("area-local-cw", { p -> ScreenPoint(rect.height() - p.y, p.x, p.pressure) }),
            Candidate("area-local-ccw", { p -> ScreenPoint(p.y, rect.width() - p.x, p.pressure) }),
            Candidate("area-local-180", { p -> ScreenPoint(rect.width() - p.x, rect.height() - p.y, p.pressure) }),
        )
        val start = raw.first()
        val end = raw.last()
        val selected = candidates.minByOrNull { candidate ->
            val mappedStart = candidate.map(start)
            val mappedEnd = candidate.map(end)
            val startError = hypot(
                (mappedStart.x + rect.left - touchStart.x).toDouble(),
                (mappedStart.y + rect.top - touchStart.y).toDouble(),
            )
            val endError = hypot(
                (mappedEnd.x + rect.left - touchEnd.x).toDouble(),
                (mappedEnd.y + rect.top - touchEnd.y).toDouble(),
            )
            startError + endError
        } ?: return "legacy-screen-minus-area" to legacy
        val mappedStart = selected.map(start)
        val mappedEnd = selected.map(end)
        val endpointError = hypot((mappedStart.x + rect.left - touchStart.x).toDouble(), (mappedStart.y + rect.top - touchStart.y).toDouble()) +
            hypot((mappedEnd.x + rect.left - touchEnd.x).toDouble(), (mappedEnd.y + rect.top - touchEnd.y).toDouble())
        Log.i(TAG, "HvPen coordinate calibration: ${selected.name}, endpointError=${endpointError.roundToInt()}px")
        return selected.name to raw.map(selected.map)
    }

    private inline fun MotionEvent.useEvent(block: (MotionEvent) -> Boolean): Boolean {
        try {
            return block(this)
        } finally {
            recycle()
        }
    }

    private fun screenRect(): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    /**
     * Hanvon's own FBReader rotates the view rectangle before passing it to HvPen. The service
     * uses the panel's native axes, which differ from Android's logical screen axes on this N10.
     * Keep this transformed rectangle confined to the vendor API; XNotes replay remains in the
     * logical screen space used by its CanvasView.
     */
    private fun toHvPenArea(rect: Rect, rotation: Int): Rect {
        val displaySize = Point()
        view.display?.getRealSize(displaySize)
        val screenWidth = displaySize.x.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
        val screenHeight = displaySize.y.takeIf { it > 0 } ?: view.resources.displayMetrics.heightPixels
        return when (rotation) {
            0 -> {
                val top = rect.left
                val right = screenHeight - rect.top
                Rect(right - rect.height(), top, right, top + rect.width())
            }
            1 -> {
                val left = screenWidth - rect.right
                val top = screenHeight - rect.bottom
                Rect(left, top, left + rect.width(), top + rect.height())
            }
            2 -> Rect(
                rect.top,
                screenWidth - rect.right,
                rect.bottom,
                screenWidth - rect.left,
            )
            else -> Rect(rect)
        }
    }

    private fun attachFocusObserver() {
        detachFocusObserver()
        val observer = view.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnWindowFocusChangeListener(focusListener)
            observedTree = observer
        }
    }

    private fun detachFocusObserver() {
        observedTree?.let { if (it.isAlive) it.removeOnWindowFocusChangeListener(focusListener) }
        observedTree = null
    }

    private fun clearTransientInk(service: Any) {
        runCatching { service.javaClass.getMethod("resetData").invoke(service) }
            .onFailure { Log.w(TAG, "Could not clear Hanvon transient ink", it) }
    }

    private fun clearWhenSafe(service: Any, generation: Long) {
        // A newer stroke now owns the shared vendor overlay; let its own canvas handover clear it.
        if (generation != strokeGeneration) return
        if (nativeContactDown) {
            // The grace period has already elapsed. Clear as soon as this same stroke lifts.
            clearPendingGeneration = generation
        } else {
            clearTransientInk(service)
        }
    }

    private fun invoke(name: String, vararg args: Any) {
        val target = manager ?: error("hvpen service is not initialized")
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == args.size
        } ?: error("hvpen method $name/${args.size} was not found")
        method.invoke(target, *args)
    }

    private fun stop() {
        val service = manager ?: return
        val id = areaId
        if (id == 0L) return
        try {
            invoke("setAreaActive", id, false)
            invoke("enablePen", id, false)
            invoke("resetData")
            val listenerType = Class.forName("android.os.HvPenDrawListener")
            service.javaClass.getMethod("endService", Long::class.javaPrimitiveType, listenerType)
                .invoke(service, id, callback)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not stop Hanvon low-latency ink", error)
        } finally {
            manager = null
            callback = null
            areaId = 0L
            areaRect = null
            areaRotation = null
            strokeRect = null
            strokeSelection = null
            strokeTouchStart = null
            strokeTouchEnd = null
            appliedSelection = null
            nativeContactDown = false
            toolbarStylusContact = false
            clearPendingGeneration = null
        }
    }

    private fun attachDisplayObserver() {
        if (displayManager != null) return
        val manager = view.context.getSystemService(DisplayManager::class.java) ?: return
        displayManager = manager
        manager.registerDisplayListener(displayListener, main)
    }

    private fun detachDisplayObserver() {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
    }

    companion object {
        private const val TAG = "XNotesHvPen"
        private const val MAX_POINTS = 20_000
        private const val MOVE_BATCH_SIZE = 128
        private const val HANDOFF_GRACE_MS = 700L
        private const val HV_BLACK = 1
        private const val HV_LIGHT_GRAY = 2
        private const val HV_BLUE = 3
        private const val HV_GREEN = 4
        private const val HV_RED = 5
        private const val HV_GRAFFITI = 6
        private const val HV_PENCIL = 15
        private const val HV_FOUNTAIN = 20
        private const val HV_CALLIGRAPHY_FOUNTAIN = 22
    }
}
