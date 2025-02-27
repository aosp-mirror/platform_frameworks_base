/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.reardisplay

import android.content.Context
import android.os.Bundle
import android.view.View
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/**
 * A {@link com.android.systemui.statusbar.phone.SystemUIDialog.Delegate} providing a dialog which
 * lets the user know that the Rear Display Mode is active, and that the content has moved to the
 * outer display.
 */
class RearDisplayInnerDialogDelegate
@AssistedInject
internal constructor(
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    @Assisted private val rearDisplayContext: Context,
    @Assisted private val onCanceledRunnable: Runnable,
) : SystemUIDialog.Delegate {

    @AssistedFactory
    interface Factory {
        fun create(
            rearDisplayContext: Context,
            onCanceledRunnable: Runnable,
        ): RearDisplayInnerDialogDelegate
    }

    override fun createDialog(): SystemUIDialog {
        return systemUIDialogFactory.create(this, rearDisplayContext)
    }

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.apply {
            setContentView(R.layout.activity_rear_display_front_screen_on)
            setCanceledOnTouchOutside(false)
            requireViewById<View>(R.id.button_cancel).setOnClickListener {
                onCanceledRunnable.run()
            }
        }
    }
}
