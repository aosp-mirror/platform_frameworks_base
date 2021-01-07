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

package com.android.internal.app;

import android.app.prediction.AppPredictionManager;
import android.provider.DeviceConfig;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;

/**
 * Common flags for {@link ChooserListAdapter} and {@link ChooserActivity}.
 */
public class ChooserFlags {

    /**
     * Whether to use the deprecated {@link android.service.chooser.ChooserTargetService} API for
     * direct share targets. If true, both CTS and Shortcuts will be used to find Direct Share
     * targets. If false, only Shortcuts will be used.
     */
    public static final boolean USE_SERVICE_TARGETS_FOR_DIRECT_TARGETS =
            DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.SHARE_USE_SERVICE_TARGETS, false);

    /**
     * Whether to use {@link AppPredictionManager} to query for direct share targets (as opposed to
     * talking directly to {@link android.content.pm.ShortcutManager}.
     */
    // TODO(b/123089490): Replace with system flag
    static final boolean USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS = true;
}

