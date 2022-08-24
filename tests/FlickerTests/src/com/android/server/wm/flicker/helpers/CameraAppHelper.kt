/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm.flicker.helpers

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.MediaStore
import com.android.server.wm.traces.common.ComponentNameMatcher

class CameraAppHelper @JvmOverloads constructor(
    instrumentation: Instrumentation,
    pkgManager: PackageManager = instrumentation.context.packageManager
) : StandardAppHelper(instrumentation, getCameraLauncherName(pkgManager),
        getCameraComponent(pkgManager)){
    companion object{
        private fun getCameraIntent(): Intent {
            return Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        }

        private fun getResolveInfo(pkgManager: PackageManager): ResolveInfo {
            val intent = getCameraIntent()
            return pkgManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?: error("unable to resolve camera activity")
        }

        private fun getCameraComponent(pkgManager: PackageManager): ComponentNameMatcher {
            val resolveInfo = getResolveInfo(pkgManager)
            return ComponentNameMatcher(resolveInfo.activityInfo.packageName,
                    className = resolveInfo.activityInfo.name)
        }

        private fun getCameraLauncherName(pkgManager: PackageManager): String {
            val resolveInfo = getResolveInfo(pkgManager)
            return resolveInfo.activityInfo.name
        }
    }
}
