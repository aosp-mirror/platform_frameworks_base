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

package com.android.systemui.statusbar.connectivity;

import android.content.Context;
import android.util.FeatureFlagUtils;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.flags.FeatureFlags;

import javax.inject.Inject;

/**
 * Class for providing StatusBar specific logic around {@link FeatureFlags}.
 */
@SysUISingleton
public class StatusBarFlags {
    private final Context mContext;

    @Inject
    public StatusBarFlags(Context context) {
        mContext = context;
    }

    /** System setting for provider model behavior */
    public boolean isProviderModelSettingEnabled() {
        return FeatureFlagUtils.isEnabled(mContext, FeatureFlagUtils.SETTINGS_PROVIDER_MODEL);
    }
}
