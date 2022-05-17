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

package com.android.systemui.util.wrapper

import android.content.Context
import android.provider.Settings.Secure.CAMERA_AUTOROTATE
import com.android.internal.view.RotationPolicy
import com.android.internal.view.RotationPolicy.RotationPolicyListener
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.traceSection
import javax.inject.Inject

/**
 * Testable wrapper interface around RotationPolicy {link com.android.internal.view.RotationPolicy}
 */
interface RotationPolicyWrapper {
    fun setRotationLock(enabled: Boolean)
    fun setRotationLockAtAngle(enabled: Boolean, rotation: Int)
    fun getRotationLockOrientation(): Int
    fun isRotationLockToggleVisible(): Boolean
    fun isRotationLocked(): Boolean
    fun isCameraRotationEnabled(): Boolean
    fun registerRotationPolicyListener(listener: RotationPolicyListener, userHandle: Int)
    fun unregisterRotationPolicyListener(listener: RotationPolicyListener)
}

class RotationPolicyWrapperImpl @Inject constructor(
    private val context: Context,
    private val secureSettings: SecureSettings
) :
        RotationPolicyWrapper {

    override fun setRotationLock(enabled: Boolean) {
        traceSection("RotationPolicyWrapperImpl#setRotationLock") {
            RotationPolicy.setRotationLock(context, enabled)
        }
    }

    override fun setRotationLockAtAngle(enabled: Boolean, rotation: Int) {
        RotationPolicy.setRotationLockAtAngle(context, enabled, rotation)
    }

    override fun getRotationLockOrientation(): Int =
        RotationPolicy.getRotationLockOrientation(context)

    override fun isRotationLockToggleVisible(): Boolean =
        RotationPolicy.isRotationLockToggleVisible(context)

    override fun isRotationLocked(): Boolean =
        RotationPolicy.isRotationLocked(context)

    override fun isCameraRotationEnabled(): Boolean =
            secureSettings.getInt(CAMERA_AUTOROTATE, 0) == 1

    override fun registerRotationPolicyListener(
        listener: RotationPolicyListener,
        userHandle: Int
    ) {
        RotationPolicy.registerRotationPolicyListener(context, listener, userHandle)
    }

    override fun unregisterRotationPolicyListener(listener: RotationPolicyListener) {
        RotationPolicy.unregisterRotationPolicyListener(context, listener)
    }
}
