/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.controls

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE
import android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.UserHandle
import android.service.controls.ControlsProviderService
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.android.settingslib.applications.DefaultAppInfo
import com.android.systemui.R
import java.util.Objects

open class ControlsServiceInfo(
    private val context: Context,
    val serviceInfo: ServiceInfo
) : DefaultAppInfo(
    context,
    context.packageManager,
    context.userId,
    serviceInfo.componentName
) {
    private val _panelActivity: ComponentName?

    init {
        val metadata = serviceInfo.metaData
                ?.getString(ControlsProviderService.META_DATA_PANEL_ACTIVITY) ?: ""
        val unflatenned = ComponentName.unflattenFromString(metadata)
        if (unflatenned != null && unflatenned.packageName == componentName.packageName) {
            _panelActivity = unflatenned
        } else {
            _panelActivity = null
        }
    }

    /**
     * Component name of an activity that will be shown embedded in the device controls space
     * instead of using the controls rendered by SystemUI.
     *
     * The activity must be in the same package, exported, enabled and protected by the
     * [Manifest.permission.BIND_CONTROLS] permission. Additionally, only packages declared in
     * [R.array.config_controlsPreferredPackages] can declare activities for use as a panel.
     */
    var panelActivity: ComponentName? = null
        protected set

    private var resolved: Boolean = false

    @WorkerThread
    fun resolvePanelActivity(
            allowAllApps: Boolean = false
    ) {
        if (resolved) return
        resolved = true
        val validPackages = context.resources
                .getStringArray(R.array.config_controlsPreferredPackages)
        if (componentName.packageName !in validPackages && !allowAllApps) return
        panelActivity = _panelActivity?.let {
            val resolveInfos = mPm.queryIntentActivitiesAsUser(
                    Intent().setComponent(it),
                    PackageManager.ResolveInfoFlags.of(
                            MATCH_DIRECT_BOOT_AWARE.toLong() or
                                    MATCH_DIRECT_BOOT_UNAWARE.toLong()
                    ),
                    UserHandle.of(userId)
            )
            if (resolveInfos.isNotEmpty() && verifyResolveInfo(resolveInfos[0])) {
                it
            } else {
                null
            }
        }
    }

    /**
     * Verifies that the panel activity is enabled, exported and protected by the correct
     * permission. This last check is to prevent apps from forgetting to protect the activity, as
     * they won't be able to see the panel until they do.
     */
    @WorkerThread
    private fun verifyResolveInfo(resolveInfo: ResolveInfo): Boolean {
        return resolveInfo.activityInfo?.let {
            it.permission == Manifest.permission.BIND_CONTROLS &&
                    it.exported && isComponentActuallyEnabled(it)
        } ?: false
    }

    @WorkerThread
    private fun isComponentActuallyEnabled(activityInfo: ActivityInfo): Boolean {
        return when (mPm.getComponentEnabledSetting(activityInfo.componentName)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> activityInfo.enabled
            else -> false
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is ControlsServiceInfo &&
                userId == other.userId &&
                componentName == other.componentName &&
                panelActivity == other.panelActivity
    }

    override fun hashCode(): Int {
        return Objects.hash(userId, componentName, panelActivity)
    }

    fun copy(): ControlsServiceInfo {
        return ControlsServiceInfo(context, serviceInfo).also {
            it.panelActivity = this.panelActivity
        }
    }

    override fun toString(): String {
        return """
            ControlsServiceInfo(serviceInfo=$serviceInfo, panelActivity=$panelActivity, resolved=$resolved)
        """.trimIndent()
    }
}