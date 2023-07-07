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
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;
import com.android.internal.statusbar.IAppClipsService;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.flags.FeatureFlags;
import com.android.wm.shell.bubbles.Bubbles;

import java.util.Optional;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;

/**
 * A service that communicates with {@link StatusBarManager} to support the
 * {@link StatusBarManager#canLaunchCaptureContentActivityForNote(Activity)} API.
 */
public class AppClipsService extends Service {

    private static final String TAG = AppClipsService.class.getSimpleName();

    @Application private final Context mContext;
    private final FeatureFlags mFeatureFlags;
    private final Optional<Bubbles> mOptionalBubbles;
    private final DevicePolicyManager mDevicePolicyManager;
    private final UserManager mUserManager;

    private final boolean mAreTaskAndTimeIndependentPrerequisitesMet;

    @VisibleForTesting()
    @Nullable ServiceConnector<IAppClipsService> mProxyConnectorToMainProfile;

    @Inject
    public AppClipsService(@Application Context context, FeatureFlags featureFlags,
            Optional<Bubbles> optionalBubbles, DevicePolicyManager devicePolicyManager,
            UserManager userManager) {
        mContext = context;
        mFeatureFlags = featureFlags;
        mOptionalBubbles = optionalBubbles;
        mDevicePolicyManager = devicePolicyManager;
        mUserManager = userManager;

        // The consumer of this service are apps that call through StatusBarManager API to query if
        // it can use app clips API. Since these apps can be launched as work profile users, this
        // service will start as work profile user. SysUI doesn't share injected instances for
        // different users. This is why the bubbles instance injected will be incorrect. As the apps
        // don't generally have permission to connect to a service running as different user, we
        // start a proxy connection to communicate with the main user's version of this service.
        if (mUserManager.isManagedProfile()) {
            // No need to check for prerequisites in this case as those are incorrect for work
            // profile user instance of the service and the main user version of the service will
            // take care of this check.
            mAreTaskAndTimeIndependentPrerequisitesMet = false;

            // Get the main user so that we can connect to the main user's version of the service.
            UserHandle mainUser = mUserManager.getMainUser();
            if (mainUser == null) {
                // If main user is not available there isn't much we can do, no apps can use app
                // clips.
                return;
            }

            // Set up the connection to be used later during onBind callback.
            mProxyConnectorToMainProfile =
                    new ServiceConnector.Impl<>(
                            context,
                            new Intent(context, AppClipsService.class),
                            Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY
                                    | Context.BIND_NOT_VISIBLE,
                            mainUser.getIdentifier(),
                            IAppClipsService.Stub::asInterface);
            return;
        }

        mAreTaskAndTimeIndependentPrerequisitesMet = checkIndependentVariables();
        mProxyConnectorToMainProfile = null;
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
                // In case of managed profile, use the main user's instance of the service. Callers
                // cannot directly connect to the main user's instance as they may not have the
                // permission to interact across users.
                if (mUserManager.isManagedProfile()) {
                    return canLaunchCaptureContentActivityForNoteFromMainUser(taskId);
                }

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

    /** Returns whether the app clips API can be used by querying the service as the main user. */
    private boolean canLaunchCaptureContentActivityForNoteFromMainUser(int taskId) {
        if (mProxyConnectorToMainProfile == null) {
            return false;
        }

        try {
            AndroidFuture<Boolean> future = mProxyConnectorToMainProfile.postForResult(
                    service -> service.canLaunchCaptureContentActivityForNote(taskId));
            return future.get();
        } catch (ExecutionException | InterruptedException e) {
            Log.d(TAG, "Exception from service\n" + e);
        }

        return false;
    }
}
