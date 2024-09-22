/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.saver.domain

import com.android.app.tracing.coroutines.createCoroutineTracingContext
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import com.android.internal.R
import com.android.systemui.qs.tiles.impl.saver.domain.interactor.DataSaverTileUserActionInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.DataSaverController
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DataSaverDialogDelegate(
    private val sysuiDialogFactory: SystemUIDialog.Factory,
    private val context: Context,
    private val backgroundContext: CoroutineContext,
    private val dataSaverController: DataSaverController,
    private val sharedPreferences: SharedPreferences,
) : SystemUIDialog.Delegate {
    override fun createDialog(): SystemUIDialog {
        return sysuiDialogFactory.create(this, context)
    }

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        with(dialog) {
            setTitle(R.string.data_saver_enable_title)
            setMessage(R.string.data_saver_description)
            setPositiveButton(R.string.data_saver_enable_button) { _: DialogInterface?, _ ->
                CoroutineScope(backgroundContext + createCoroutineTracingContext("DataSaverDialogScope")).launch {
                    dataSaverController.setDataSaverEnabled(true)
                }

                sharedPreferences
                    .edit()
                    .putBoolean(DataSaverTileUserActionInteractor.DIALOG_SHOWN, true)
                    .apply()
            }
            setNeutralButton(R.string.cancel, null)
            setShowForAllUsers(true)
        }
    }
}
