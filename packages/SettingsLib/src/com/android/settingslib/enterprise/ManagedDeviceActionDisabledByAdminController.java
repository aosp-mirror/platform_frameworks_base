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

package com.android.settingslib.enterprise;

import static java.util.Objects.requireNonNull;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.android.settingslib.enterprise.ActionDisabledLearnMoreButtonLauncher.ResolveActivityChecker;


/**
 * An {@link ActionDisabledByAdminController} to be used with managed devices.
 */
final class ManagedDeviceActionDisabledByAdminController
        extends BaseActionDisabledByAdminController {

    interface ForegroundUserChecker {
        boolean isUserForeground(Context context, UserHandle userHandle);
    }

    public final static ForegroundUserChecker DEFAULT_FOREGROUND_USER_CHECKER =
            ManagedDeviceActionDisabledByAdminController::isUserForeground;

    /**
     * The {@link UserHandle} which is preferred for launching the web help page in
     * <p>If not able to launch the web help page in this user, the current user will be used as
     * fallback instead. If the current user cannot open it either, the admin policies page will
     * be used instead.
     */
    private final UserHandle mPreferredUserHandle;

    private final ForegroundUserChecker mForegroundUserChecker;
    private final ResolveActivityChecker mResolveActivityChecker;

    /**
     * Constructs a {@link ManagedDeviceActionDisabledByAdminController}
     * @param preferredUserHandle - user on which to launch the help web page, if necessary
     */
    ManagedDeviceActionDisabledByAdminController(
            DeviceAdminStringProvider stringProvider,
            UserHandle preferredUserHandle,
            ForegroundUserChecker foregroundUserChecker,
            ResolveActivityChecker resolveActivityChecker) {
        super(stringProvider);
        mPreferredUserHandle = requireNonNull(preferredUserHandle);
        mForegroundUserChecker = requireNonNull(foregroundUserChecker);
        mResolveActivityChecker = requireNonNull(resolveActivityChecker);
    }

    @Override
    public void setupLearnMoreButton(Context context) {
        assertInitialized();

        String url = mStringProvider.getLearnMoreHelpPageUrl();

        if (!TextUtils.isEmpty(url)
                && canLaunchHelpPageInPreferredOrCurrentUser(context, url, mPreferredUserHandle)) {
            setupLearnMoreButtonToLaunchHelpPage(context, url, mPreferredUserHandle);
        } else {
            mLauncher.setupLearnMoreButtonToShowAdminPolicies(context, mEnforcementAdminUserId,
                    mEnforcedAdmin);
        }
    }

    private boolean canLaunchHelpPageInPreferredOrCurrentUser(
            Context context, String url, UserHandle preferredUserHandle) {
        PackageManager packageManager = context.getPackageManager();
        if (mLauncher.canLaunchHelpPage(
                packageManager, url, preferredUserHandle, mResolveActivityChecker)
                && mForegroundUserChecker.isUserForeground(context, preferredUserHandle)) {
            return true;
        }
        return mLauncher.canLaunchHelpPage(
                packageManager, url, context.getUser(), mResolveActivityChecker);
    }

    /**
     * Sets up the "Learn more" button to launch the web help page in the {@code
     * preferredUserHandle} user. If not possible to launch it there, it sets up the button to
     * launch it in the current user instead.
     */
    private void setupLearnMoreButtonToLaunchHelpPage(
            Context context, String url, UserHandle preferredUserHandle) {
        PackageManager packageManager = context.getPackageManager();
        if (mLauncher.canLaunchHelpPage(
                packageManager, url, preferredUserHandle, mResolveActivityChecker)
                && mForegroundUserChecker.isUserForeground(context, preferredUserHandle)) {
            mLauncher.setupLearnMoreButtonToLaunchHelpPage(context, url, preferredUserHandle);
        }
        if (mLauncher.canLaunchHelpPage(
                packageManager, url, context.getUser(), mResolveActivityChecker)) {
            mLauncher.setupLearnMoreButtonToLaunchHelpPage(context, url, context.getUser());
        }
    }

    private static boolean isUserForeground(Context context, UserHandle userHandle) {
        return context.createContextAsUser(userHandle, /* flags= */ 0)
                .getSystemService(UserManager.class).isUserForeground();
    }

    @Override
    public String getAdminSupportTitle(@Nullable String restriction) {
        if (restriction == null) {
            return mStringProvider.getDefaultDisabledByPolicyTitle();
        }
        switch (restriction) {
            case UserManager.DISALLOW_ADJUST_VOLUME:
                return mStringProvider.getDisallowAdjustVolumeTitle();
            case UserManager.DISALLOW_OUTGOING_CALLS:
                return mStringProvider.getDisallowOutgoingCallsTitle();
            case UserManager.DISALLOW_SMS:
                return mStringProvider.getDisallowSmsTitle();
            case DevicePolicyManager.POLICY_DISABLE_CAMERA:
                return mStringProvider.getDisableCameraTitle();
            case DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE:
                return mStringProvider.getDisableScreenCaptureTitle();
            case DevicePolicyManager.POLICY_SUSPEND_PACKAGES:
                return mStringProvider.getSuspendPackagesTitle();
            default:
                return mStringProvider.getDefaultDisabledByPolicyTitle();
        }
    }

    @Override
    public CharSequence getAdminSupportContentString(Context context, CharSequence supportMessage) {
        return supportMessage != null
                ? supportMessage
                : mStringProvider.getDefaultDisabledByPolicyContent();
    }
}
