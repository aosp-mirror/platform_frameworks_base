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

import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_FAILED;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_SUCCESS;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED;

import static com.android.systemui.flags.Flags.SCREENSHOT_APP_CLIPS;

import android.app.Activity;
import android.app.Service;
import android.app.StatusBarManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.Intent.CaptureContentForNoteStatusCodes;
import android.content.res.Resources;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.statusbar.IAppClipsService;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.res.R;
import com.android.wm.shell.bubbles.Bubbles;

import java.util.Optional;

import javax.inject.Inject;

/**
 * A service that communicates with {@link StatusBarManager} to support the
 * {@link StatusBarManager#canLaunchCaptureContentActivityForNote(Activity)} API. Also used by
 * {@link AppClipsTrampolineActivity} to query if an app should be allowed to user App Clips.
 *
 * <p>Note: This service always runs in the SysUI process running on the system user irrespective of
 * which user started the service. This is required so that the correct instance of {@link Bubbles}
 * instance is injected. This is set via attribute {@code android:singleUser=”true”} in
 * AndroidManifest.
 */
public class AppClipsService extends Service {

  private static final String TAG = AppClipsService.class.getSimpleName();

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
            Log.d(TAG, "Feature flag disabled");
            return false;
        }

        if (mOptionalBubbles.isEmpty()) {
            Log.d(TAG, "Bubbles not available");
            return false;
        }

        if (isComponentValid()) {
            Log.d(TAG, "checkIndependentVariables returned true");
            return true;
        }

        Log.d(TAG, "checkIndependentVariables returned false");
        return false;
    }

    private boolean isComponentValid() {
        ComponentName componentName;
        try {
            componentName = ComponentName.unflattenFromString(
                    mContext.getString(R.string.config_screenshotAppClipsActivityComponent));
        } catch (Resources.NotFoundException e) {
            Log.d(TAG, "AppClips activity component resource not defined");
            return false;
        }

        if (componentName == null) {
            Log.d(TAG, "AppClips component name not defined");
            return false;
        }

        if (componentName.getPackageName().isEmpty()) {
            Log.d(TAG, "AppClips component package name is empty");
            return false;
        }

        if (componentName.getClassName().isEmpty()) {
            Log.d(TAG, "AppClips component class name is empty");
            return false;
        }

        Log.d(TAG, "isComponentValid returned true");
        return true;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new IAppClipsService.Stub() {
            @Override
            public boolean canLaunchCaptureContentActivityForNote(int taskId) {
                if (canLaunchCaptureContentActivityForNoteInternal(taskId)
                        == CAPTURE_CONTENT_FOR_NOTE_SUCCESS) {
                    Log.d(TAG, String.format("Can launch AppClips returned true for %d", taskId));
                    return true;
                }

                Log.d(TAG, String.format("Can launch AppClips returned false for %d", taskId));
                return false;
            }

            @Override
            @CaptureContentForNoteStatusCodes
            public int canLaunchCaptureContentActivityForNoteInternal(int taskId) {
                if (!mAreTaskAndTimeIndependentPrerequisitesMet) {
                    Log.d(TAG,
                        String.format("Task (%d) and time independent prereqs not met", taskId));
                    return CAPTURE_CONTENT_FOR_NOTE_FAILED;
                }

                if (!mOptionalBubbles.get().isAppBubbleTaskId(taskId)) {
                    Log.d(TAG, String.format("Taskid %d is not app bubble task", taskId));
                    return CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED;
                }

                if (mDevicePolicyManager.getScreenCaptureDisabled(null)) {
                    Log.d(TAG,
                        String.format("Screen capture disabled by admin, taskId %d", taskId));
                    return CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN;
                }

                Log.d(TAG,
                    String.format("Can launch AppClips (internal) successful for %d", taskId));
                return CAPTURE_CONTENT_FOR_NOTE_SUCCESS;
            }
        };
    }
}
