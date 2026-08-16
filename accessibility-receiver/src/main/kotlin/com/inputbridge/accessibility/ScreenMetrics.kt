package com.inputbridge.accessibility

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi

/**
 * BUG-137 FIX — true physical screen size for cursor coordinate space.
 *
 * `WindowManager.currentWindowMetrics.bounds` (used previously) returns the *current* window
 * bounds, which exclude system bars and may exclude the display cutout. That makes the virtual
 * cursor clamp to a sub-region of the real display. For a pointer that must travel edge-to-edge we
 * need the full physical screen:
 * - API 30+ (`R`): `maximumWindowMetrics.bounds` is the largest the window can be — it includes the
 *   system bars and the cutout area.
 * - Below R: `Display.getRealSize()` returns the actual screen pixels.
 *
 * Used by both [InputBridgeAccessibilityService] (on connect) and [CursorOverlayService] (on create)
 * so the cursor space matches the real screen even before accessibility connects.
 */
@RequiresApi(Build.VERSION_CODES.N)
fun realScreenSize(context: Context): Point {
    return try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.maximumWindowMetrics
            Point(metrics.bounds.width(), metrics.bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(size)
            size
        }
    } catch (e: Exception) {
        Point(1080, 2400)
    }
}
