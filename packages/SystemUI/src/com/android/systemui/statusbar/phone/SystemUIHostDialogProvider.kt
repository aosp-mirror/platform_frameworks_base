package com.android.systemui.statusbar.phone

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import com.android.systemui.animation.HostDialogProvider

/** An implementation of [HostDialogProvider] to be used when animating SysUI dialogs. */
class SystemUIHostDialogProvider : HostDialogProvider {
    override fun createHostDialog(
        context: Context,
        theme: Int,
        onCreateCallback: () -> Unit,
        dismissOverride: (() -> Unit) -> Unit
    ): Dialog {
        return SystemUIHostDialog(context, theme, onCreateCallback, dismissOverride)
    }

    /**
     * This host dialog is a SystemUIDialog so that it's displayed above all SystemUI windows. Note
     * that it is not automatically dismissed when the device is locked, but only when the hosted
     * (original) dialog is dismissed. That way, the behavior of the dialog (dismissed when locking
     * or not) is consistent with when the dialog is shown with or without the dialog animator.
     */
    private class SystemUIHostDialog(
        context: Context,
        theme: Int,
        private val onCreateCallback: () -> Unit,
        private val dismissOverride: (() -> Unit) -> Unit
    ) : SystemUIDialog(context, theme, false /* dismissOnDeviceLock */) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            onCreateCallback()
        }

        override fun dismiss() {
            dismissOverride {
                super.dismiss()
            }
        }
    }
}