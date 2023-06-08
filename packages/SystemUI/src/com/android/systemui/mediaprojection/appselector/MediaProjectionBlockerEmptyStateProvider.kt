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
package com.android.systemui.mediaprojection.appselector

import android.content.Context
import android.os.UserHandle
import com.android.internal.R as AndroidR
import com.android.internal.app.AbstractMultiProfilePagerAdapter.EmptyState
import com.android.internal.app.AbstractMultiProfilePagerAdapter.EmptyStateProvider
import com.android.internal.app.ResolverListAdapter
import com.android.systemui.R
import com.android.systemui.mediaprojection.devicepolicy.PersonalProfile
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDevicePolicyResolver
import javax.inject.Inject

@MediaProjectionAppSelectorScope
class MediaProjectionBlockerEmptyStateProvider
@Inject
constructor(
    @HostUserHandle private val hostAppHandle: UserHandle,
    @PersonalProfile private val personalProfileHandle: UserHandle,
    private val policyResolver: ScreenCaptureDevicePolicyResolver,
    private val context: Context
) : EmptyStateProvider {

    override fun getEmptyState(resolverListAdapter: ResolverListAdapter): EmptyState? {
        val screenCaptureAllowed =
            policyResolver.isScreenCaptureAllowed(
                targetAppUserHandle = resolverListAdapter.userHandle,
                hostAppUserHandle = hostAppHandle
            )

        val isHostAppInPersonalProfile = hostAppHandle == personalProfileHandle

        val subtitle =
            if (isHostAppInPersonalProfile) {
                AndroidR.string.resolver_cant_share_with_personal_apps_explanation
            } else {
                AndroidR.string.resolver_cant_share_with_work_apps_explanation
            }

        if (!screenCaptureAllowed) {
            return object : EmptyState {
                override fun getSubtitle(): String = context.resources.getString(subtitle)
                override fun getTitle(): String =
                    context.resources.getString(
                        R.string.screen_capturing_disabled_by_policy_dialog_title
                    )
                override fun onEmptyStateShown() {
                    // TODO(b/237397740) report analytics
                }
                override fun shouldSkipDataRebuild(): Boolean = true
            }
        }
        return null
    }
}
