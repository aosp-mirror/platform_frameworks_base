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
package com.android.systemui.accessibility.extradim

import android.content.Intent
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.server.display.feature.flags.Flags
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** Tests for [ExtraDimDialogReceiver]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ExtraDimDialogReceiverTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    private lateinit var extraDimDialogReceiver: ExtraDimDialogReceiver

    @Mock private lateinit var extraDimDialogManager: ExtraDimDialogManager

    @Before
    fun setUp() {
        extraDimDialogReceiver = ExtraDimDialogReceiver(extraDimDialogManager)
        mContext
            .getOrCreateTestableResources()
            .addOverride(com.android.internal.R.bool.config_evenDimmerEnabled, true)
    }

    @Test
    @EnableFlags(Flags.FLAG_EVEN_DIMMER)
    fun receiveAction_flagEvenDimmerEnabled_showDialog() {
        extraDimDialogReceiver.onReceive(mContext, Intent(ExtraDimDialogReceiver.ACTION))
        verify(extraDimDialogManager).dismissKeyguardIfNeededAndShowDialog()
    }

    @Test
    @DisableFlags(Flags.FLAG_EVEN_DIMMER)
    fun receiveAction_flagEvenDimmerDisabled_neverShowDialog() {
        extraDimDialogReceiver.onReceive(mContext, Intent(ExtraDimDialogReceiver.ACTION))
        verify(extraDimDialogManager, never()).dismissKeyguardIfNeededAndShowDialog()
    }
}
