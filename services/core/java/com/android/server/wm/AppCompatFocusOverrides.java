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

package com.android.server.wm;

import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS;
import static android.view.WindowManager.PROPERTY_COMPAT_ENABLE_FAKE_FOCUS;

import static com.android.server.wm.AppCompatUtils.isChangeEnabled;

import android.annotation.NonNull;

import com.android.server.wm.utils.OptPropFactory;

/**
 * Encapsulates app compat focus policy.
 */
class AppCompatFocusOverrides {

    @NonNull
    final ActivityRecord mActivityRecord;
    @NonNull
    private final OptPropFactory.OptProp mFakeFocusOptProp;

    AppCompatFocusOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull AppCompatConfiguration appCompatConfiguration,
            @NonNull OptPropFactory optPropBuilder) {
        mActivityRecord = activityRecord;
        mFakeFocusOptProp = optPropBuilder.create(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS,
                appCompatConfiguration::isCompatFakeFocusEnabled);
    }

    /**
     * Whether sending compat fake focus for split screen resumed activities is enabled. Needed
     * because some game engines wait to get focus before drawing the content of the app which isn't
     * guaranteed by default in multi-window modes.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the treatment is enabled
     *     <li>Component property is NOT set to false
     *     <li>Component property is set to true or per-app override is enabled
     * </ul>
     */
    boolean shouldSendFakeFocus() {
        // TODO(b/263593361): Explore enabling compat fake focus for freeform.
        // TODO(b/263592337): Explore enabling compat fake focus for fullscreen, e.g. for when
        // covered with bubbles.
        return mFakeFocusOptProp.shouldEnableWithOverrideAndProperty(
                isChangeEnabled(mActivityRecord, OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS))
                && mActivityRecord.inMultiWindowMode() && !mActivityRecord.inPinnedWindowingMode()
                && !mActivityRecord.inFreeformWindowingMode();
    }

}
