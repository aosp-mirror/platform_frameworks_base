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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

/**
 * Helper class meant to set up the "Learn more" button in the action disabled dialog.
 */
public abstract class ActionDisabledLearnMoreButtonLauncher {

    /**
     * Sets up a "learn more" button which shows a screen with device policy settings
     */
    public final void setupLearnMoreButtonToShowAdminPolicies(Context context,
            int enforcementAdminUserId, EnforcedAdmin enforcedAdmin) {
        requireNonNull(context, "context cannot be null");
        requireNonNull(enforcedAdmin, "enforcedAdmin cannot be null");

        // The "Learn more" button appears only if the restriction is enforced by an admin in the
        // same profile group or by the device owner. Otherwise the admin package and its policies
        // are not accessible to the current user.
        if (isSameProfileGroup(context, enforcementAdminUserId)
                || isEnforcedByDeviceOwnerOnSystemUserMode(context, enforcementAdminUserId)) {
            setLearnMoreButton(() -> showAdminPolicies(context, enforcedAdmin));
        }
    }

    /**
     * Sets up a "learn more" button which launches a help page
     */
    public final void setupLearnMoreButtonToLaunchHelpPage(Context context, String url) {
        requireNonNull(context, "context cannot be null");
        requireNonNull(url, "url cannot be null");

        setLearnMoreButton(() -> showHelpPage(context, url));
    }

    /**
     * Sets the "learning more" button.
     *
     * @param action action to be run when the button is tapped.
     */
    public abstract void setLearnMoreButton(Runnable action);

    /**
     * Launches the settings page with info about the given admin.
     */
    protected abstract void launchShowAdminPolicies(Context context, UserHandle user,
            ComponentName admin);

    /**
     * Launches the settings page that shows all admins.
     */
    protected abstract void launchShowAdminSettings(Context context);

    /**
     * Callback to finish the activity associated with the launcher.
     */
    protected void finishSelf() {
    }

    @VisibleForTesting
    protected boolean isSameProfileGroup(Context context, int enforcementAdminUserId) {
        UserManager um = context.getSystemService(UserManager.class);

        return um.isSameProfileGroup(enforcementAdminUserId, um.getUserHandle());
    }

    private boolean isEnforcedByDeviceOwnerOnSystemUserMode(
            Context context, int enforcementAdminUserId) {
        if (enforcementAdminUserId != UserHandle.USER_SYSTEM) {
            return false;
        }
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return enforcementAdminUserId == dpm.getDeviceOwnerUserId();
    }

    /**
     * Shows the help page using the given {@code url}.
     */
    @VisibleForTesting
    public void showHelpPage(Context context, String url) {
        context.startActivityAsUser(createLearnMoreIntent(url), UserHandle.of(context.getUserId()));
        finishSelf();
    }

    private void showAdminPolicies(Context context, EnforcedAdmin enforcedAdmin) {
        if (enforcedAdmin.component != null) {
            launchShowAdminPolicies(context, enforcedAdmin.user, enforcedAdmin.component);
        } else {
            launchShowAdminSettings(context);
        }
        finishSelf();
    }

    private static Intent createLearnMoreIntent(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }
}
