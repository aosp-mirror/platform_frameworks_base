/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.camera

import android.content.Intent
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class CameraIntentsTest : SysuiTestCase() {
    companion object {
        val VALID_SECURE_INTENT = Intent(CameraIntents.DEFAULT_SECURE_CAMERA_INTENT_ACTION)
        val VALID_INSECURE_INTENT = Intent(CameraIntents.DEFAULT_INSECURE_CAMERA_INTENT_ACTION)
    }

    @Test
    fun testIsSecureCameraIntent() {
        assertTrue(CameraIntents.isSecureCameraIntent(VALID_SECURE_INTENT))
        // an intent with a specific package is still a valid secure camera intent
        assertTrue(CameraIntents.isSecureCameraIntent(
                Intent(VALID_SECURE_INTENT).setPackage("com.example")))
        assertFalse(CameraIntents.isSecureCameraIntent(null))
        assertFalse(CameraIntents.isSecureCameraIntent(Intent()))
        assertFalse(CameraIntents.isSecureCameraIntent(Intent(Intent.ACTION_MAIN)))
    }
    @Test
    fun testIsInsecureCameraIntent() {
        assertTrue(CameraIntents.isInsecureCameraIntent(VALID_INSECURE_INTENT))
        // an intent with a specific package is still a valid secure camera intent
        assertTrue(CameraIntents.isInsecureCameraIntent(
                Intent(VALID_INSECURE_INTENT).setPackage("com.example")))
        assertFalse(CameraIntents.isInsecureCameraIntent(null))
        assertFalse(CameraIntents.isInsecureCameraIntent(Intent()))
        assertFalse(CameraIntents.isInsecureCameraIntent(Intent(Intent.ACTION_MAIN)))
    }
}
