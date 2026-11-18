package com.carcast.mirror.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

class CarCastAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CarCast_A11yTouch"

        @Volatile
        var instance: CarCastAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        fun isServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(context.packageName) &&
                    enabledServices.contains(CarCastAccessibilityService::class.java.simpleName)
        }
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private val touchPathPoints = mutableListOf<Pair<Float, Float>>()
    private val gestureLock = Object()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "[TOUCH:SERVICE] CarCast Reverse Touch Accessibility Service CONNECTED and ready")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "[TOUCH:SERVICE] CarCast Reverse Touch Accessibility Service DESTROYED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used; only dispatchGesture is required
    }

    override fun onInterrupt() {
        Log.w(TAG, "[TOUCH:SERVICE] Accessibility service interrupted")
    }

    fun performTouchGesture(action: String, xRatio: Float, yRatio: Float, screenW: Int, screenH: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture requires Android 7.0+ (API 24)")
            return
        }

        val pxX = (xRatio * screenW).coerceIn(0f, screenW.toFloat())
        val pxY = (yRatio * screenH).coerceIn(0f, screenH.toFloat())

        synchronized(gestureLock) {
            when (action) {
                "down" -> {
                    touchDownX = pxX
                    touchDownY = pxY
                    touchDownTime = SystemClock.uptimeMillis()
                    touchPathPoints.clear()
                    touchPathPoints.add(Pair(pxX, pxY))
                }
                "move" -> {
                    touchPathPoints.add(Pair(pxX, pxY))
                }
                "up" -> {
                    val upTime = SystemClock.uptimeMillis()
                    val duration = (upTime - touchDownTime).coerceAtLeast(10L)
                    val dx = pxX - touchDownX
                    val dy = pxY - touchDownY
                    val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                    val path = Path()
                    path.moveTo(touchDownX, touchDownY)

                    val gestureDuration: Long
                    val gestureType: String

                    if (distance < 25f && duration < 400L) {
                        // 1. Single Tap
                        gestureType = "TAP"
                        gestureDuration = 50L
                    } else if (distance < 25f && duration >= 400L) {
                        // 2. Long Press
                        gestureType = "LONG_PRESS"
                        gestureDuration = 650L
                    } else {
                        // 3. Swipe / Scroll / Drag
                        gestureType = "SWIPE_OR_DRAG"
                        if (touchPathPoints.size > 2) {
                            val step = (touchPathPoints.size / 10).coerceAtLeast(1)
                            for (i in step until touchPathPoints.size step step) {
                                val pt = touchPathPoints[i]
                                path.lineTo(pt.first, pt.second)
                            }
                        }
                        path.lineTo(pxX, pxY)
                        gestureDuration = duration.coerceIn(120L, 750L)
                    }

                    try {
                        val stroke = GestureDescription.StrokeDescription(path, 0, gestureDuration)
                        val gesture = GestureDescription.Builder().addStroke(stroke).build()
                        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                Log.d(TAG, "[TOUCH:GESTURE] $gestureType completed successfully at (${pxX.toInt()}, ${pxY.toInt()})")
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                Log.w(TAG, "[TOUCH:GESTURE] $gestureType was cancelled by Android OS")
                            }
                        }, null)

                        Log.i(TAG, "[TOUCH:DISPATCH] Dispatched $gestureType to Android OS: start=(${touchDownX.toInt()}, ${touchDownY.toInt()}) end=(${pxX.toInt()}, ${pxY.toInt()}) duration=${gestureDuration}ms result=$dispatched")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to dispatch touch gesture", e)
                    } finally {
                        touchPathPoints.clear()
                    }
                }
                "cancel" -> {
                    touchPathPoints.clear()
                }
                else -> {
                    // Ignore unrecognized actions
                }
            }
        }
    }
}
