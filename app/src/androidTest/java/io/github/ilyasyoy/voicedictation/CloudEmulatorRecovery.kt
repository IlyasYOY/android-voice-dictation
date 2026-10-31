package io.github.ilyasyoy.voicedictation

import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo

/** One visible action on the cloud emulator's stale System UI ANR; never dismiss product failures. */
internal fun dismissStaleSystemUiAnr(instrumentation: Instrumentation): Boolean {
    val root=instrumentation.uiAutomation.rootInActiveWindow ?: return false
    if(root.findAccessibilityNodeInfosByText("System UI isn't responding").isEmpty())return false
    return root.findAccessibilityNodeInfosByViewId("android:id/aerr_close").firstOrNull()
        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
}
