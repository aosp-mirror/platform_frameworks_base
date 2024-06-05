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

import android.content.SharedPreferences
import android.platform.test.annotations.EnabledOnRavenwood
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.utils.leaks.FakeDataSaverController
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

/** Test [DataSaverDialogDelegate]. */
@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class DataSaverDialogDelegateTest : SysuiTestCase() {

    private val dataSaverController = FakeDataSaverController(LeakCheck())

    private lateinit var sysuiDialogFactory: SystemUIDialog.Factory
    private lateinit var sysuiDialog: SystemUIDialog
    private lateinit var dataSaverDialogDelegate: DataSaverDialogDelegate

    @Before
    fun setup() {
        sysuiDialog = mock<SystemUIDialog>()
        sysuiDialogFactory = mock<SystemUIDialog.Factory>()

        dataSaverDialogDelegate =
            DataSaverDialogDelegate(
                sysuiDialogFactory,
                context,
                EmptyCoroutineContext,
                dataSaverController,
                mock<SharedPreferences>()
            )

        whenever(sysuiDialogFactory.create(eq(dataSaverDialogDelegate), eq(context)))
            .thenReturn(sysuiDialog)
    }
    @Test
    fun delegateSetsDialogTitleCorrectly() {
        val expectedResId = R.string.data_saver_enable_title

        dataSaverDialogDelegate.beforeCreate(sysuiDialog, null)

        verify(sysuiDialog).setTitle(eq(expectedResId))
    }

    @Test
    fun delegateSetsDialogMessageCorrectly() {
        val expectedResId = R.string.data_saver_description

        dataSaverDialogDelegate.beforeCreate(sysuiDialog, null)

        verify(sysuiDialog).setMessage(expectedResId)
    }

    @Test
    fun delegateSetsDialogPositiveButtonCorrectly() {
        val expectedResId = R.string.data_saver_enable_button

        dataSaverDialogDelegate.beforeCreate(sysuiDialog, null)

        verify(sysuiDialog).setPositiveButton(eq(expectedResId), any())
    }

    @Test
    fun delegateSetsDialogCancelButtonCorrectly() {
        val expectedResId = R.string.cancel

        dataSaverDialogDelegate.beforeCreate(sysuiDialog, null)

        verify(sysuiDialog).setNeutralButton(eq(expectedResId), eq(null))
    }
}
