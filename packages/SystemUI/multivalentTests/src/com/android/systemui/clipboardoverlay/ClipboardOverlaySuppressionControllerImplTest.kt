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
package com.android.systemui.clipboardoverlay

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ClipboardOverlaySuppressionControllerImplTest : SysuiTestCase() {
    private lateinit var mSampleClipData: ClipData
    private lateinit var mSuppressableClipData: ClipData
    private lateinit var mClipboardOverlaySuppressionControllerImpl:
        ClipboardOverlaySuppressionControllerImpl

    @Before
    fun setup() {
        mSampleClipData = ClipData("Test", arrayOf("text/plain"), ClipData.Item("Test Item"))

        val desc = ClipDescription("Test", arrayOf("text/plain"))
        val bundle = PersistableBundle()
        bundle.putBoolean(ClipboardOverlaySuppressionControllerImpl.EXTRA_SUPPRESS_OVERLAY, true)
        desc.extras = bundle
        mSuppressableClipData = ClipData(desc, ClipData.Item("Test Item"))

        mClipboardOverlaySuppressionControllerImpl = ClipboardOverlaySuppressionControllerImpl()
    }

    @Test
    fun shouldSuppressOverlay_notEmulatorOrShellPackage_returnFalse() {
        Assert.assertFalse(
            mClipboardOverlaySuppressionControllerImpl.shouldSuppressOverlay(
                mSuppressableClipData,
                EXAMPLE_PACKAGE,
                false,
            )
        )
    }

    @Test
    fun shouldSuppressOverlay_nullClipData_returnFalse() {
        Assert.assertFalse(
            mClipboardOverlaySuppressionControllerImpl.shouldSuppressOverlay(
                null,
                ClipboardOverlaySuppressionControllerImpl.SHELL_PACKAGE,
                true,
            )
        )
    }

    @Test
    fun shouldSuppressOverlay_noSuppressOverlayExtra_returnFalse() {
        // Regardless of the package or emulator, nothing should be suppressed without the flag.
        Assert.assertFalse(
            mClipboardOverlaySuppressionControllerImpl.shouldSuppressOverlay(
                mSampleClipData,
                EXAMPLE_PACKAGE,
                true,
            )
        )
        Assert.assertFalse(
            mClipboardOverlaySuppressionControllerImpl.shouldSuppressOverlay(
                mSampleClipData,
                ClipboardOverlaySuppressionControllerImpl.SHELL_PACKAGE,
                false,
            )
        )
    }

    @Test
    fun shouldSuppressOverlay_hasSuppressOverlayExtra_returnTrue() {
        // Clip data with the suppression extra is only honored in the emulator or with the shell
        // package.
        Assert.assertTrue(
            mClipboardOverlaySuppressionControllerImpl.shouldSuppressOverlay(
                mSuppressableClipData,
                EXAMPLE_PACKAGE,
                true,
            )
        )
        Assert.assertTrue(
            mClipboardOverlaySuppressionControllerImpl.shouldSuppressOverlay(
                mSuppressableClipData,
                ClipboardOverlaySuppressionControllerImpl.SHELL_PACKAGE,
                false,
            )
        )
    }

    companion object {
        const val EXAMPLE_PACKAGE = "com.example"
    }
}
