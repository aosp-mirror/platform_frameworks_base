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

package com.android.systemui.screenshot.appclips;

import static com.android.systemui.flags.Flags.SCREENSHOT_APP_CLIPS;

import android.app.Activity;
import android.app.Service;
import android.app.StatusBarManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.android.internal.statusbar.IAppClipsService;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.flags.FeatureFlags;
import com.android.wm.shell.bubbles.Bubbles;

import java.util.Optional;

import javax.inject.Inject;

/**
 * A service that communicates with {@link StatusBarManager} to support the
 * {@link StatusBarManager#canLaunchCaptureContentActivityForNote(Activity)} API.
 */
public class AppClipsService extends Service {

    @Application private final Context mContext;
    private final FeatureFlags mFeatureFlags;
    private final Optional<Bubbles> mOptionalBubbles;
    private final DevicePolicyManager mDevicePolicyManager;
    private final boolean mAreTaskAndTimeIndependentPrerequisitesMet;

    @Inject
    public AppClipsService(@Application Context context, FeatureFlags featureFlags,
            Optional<Bubbles> optionalBubbles, DevicePolicyManager devicePolicyManager) {
        mContext = context;
        mFeatureFlags = featureFlags;
        mOptionalBubbles = optionalBubbles;
        mDevicePolicyManager = devicePolicyManager;

        mAreTaskAndTimeIndependentPrerequisitesMet = checkIndependentVariables();
    }

    private boolean checkIndependentVariables() {
        if (!mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)) {
            return false;
        }

        if (mOptionalBubbles.isEmpty()) {
            return false;
        }

        return isComponentValid();
    }

    private boolean isComponentValid() {
        ComponentName componentName;
        try {
            componentName = ComponentName.unflattenFromString(
                    mContext.getString(R.string.config_screenshotAppClipsActivityComponent));
        } catch (Resources.NotFoundException e) {
            return false;
        }

        return componentName != null
                && !componentName.getPackageName().isEmpty()
                && !componentName.getClassName().isEmpty();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new IAppClipsService.Stub() {
            @Override
            public boolean canLaunchCaptureContentActivityForNote(int taskId) {
                if (!mAreTaskAndTimeIndependentPrerequisitesMet) {
                    return false;
                }

                if (!mOptionalBubbles.get().isAppBubbleTaskId(taskId)) {
                    return false;
                }

                return !mDevicePolicyManager.getScreenCaptureDisabled(null);
            }
        };
    }
}
