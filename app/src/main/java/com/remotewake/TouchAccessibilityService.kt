package com.remotewake

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Receives touch coordinates from Phone B via the relay and replays them on Phone A's screen.
 * This is how Phone B can tap, swipe and scroll on Phone A without root.
 *
 * The user must enable it once in Settings → Accessibility → Remote Wake. It does nothing
 * on its own; it only acts when a remote viewer sends touch events.
 */
class TouchAccessibilityService : AccessibilityService() {

    private val touchPoints = mutableListOf<PointF>()
    private var touchStartTime = 0L

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    fun onTouchDown(x: Float, y: Float) {
        touchPoints.clear()
        touchPoints.add(PointF(x, y))
        touchStartTime = SystemClock.uptimeMillis()
    }

    fun onTouchMove(x: Float, y: Float) {
        touchPoints.add(PointF(x, y))
    }

    fun onTouchUp(x: Float, y: Float) {
        touchPoints.add(PointF(x, y))
        dispatchAccumulatedGesture()
    }

    private fun dispatchAccumulatedGesture() {
        if (touchPoints.isEmpty()) return

        val path = Path()
        path.moveTo(touchPoints[0].x, touchPoints[0].y)
        for (i in 1 until touchPoints.size) {
            path.lineTo(touchPoints[i].x, touchPoints[i].y)
        }

        // A tap gets a very short duration so it registers as a tap, not a long press.
        // A swipe gets a duration proportional to how long the user actually swiped.
        val duration = if (touchPoints.size <= 2) {
            1L // tap
        } else {
            (SystemClock.uptimeMillis() - touchStartTime).coerceIn(50, 3000)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)

        touchPoints.clear()
    }

    // Not used — we only need dispatchGesture().
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val TAG = "TouchService"

        /** The live instance, or null if the service is not enabled. */
        @Volatile var instance: TouchAccessibilityService? = null
            private set

        /** Whether the user has turned on the accessibility service in Settings. */
        fun isEnabled(context: Context): Boolean {
            val name = "${context.packageName}/${TouchAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return TextUtils.SimpleStringSplitter(':').also { it.setString(enabledServices) }
                .any { it.equals(name, ignoreCase = true) }
        }
    }
}
