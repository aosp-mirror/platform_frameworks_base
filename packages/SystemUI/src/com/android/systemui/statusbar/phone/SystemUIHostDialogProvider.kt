package com.android.systemui.statusbar.phone

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import com.android.systemui.animation.HostDialogProvider

/** An implementation of [HostDialogProvider] to be used when animating SysUI dialogs. */
class SystemUIHostDialogProvider : HostDialogProvider {
    override fun createHostDialog(
        context: Context,
        onCreateCallback: () -> Unit,
        dismissOverride: (() -> Unit) -> Unit
    ): Dialog {
        return SystemUIHostDialog(context, onCreateCallback, dismissOverride)
    }

    private class SystemUIHostDialog(
        context: Context,
        private val onCreateCallback: () -> Unit,
        private val dismissOverride: (() -> Unit) -> Unit
    ) : SystemUIDialog(context) {
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