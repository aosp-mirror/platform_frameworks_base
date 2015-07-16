/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.widget.Toast;

import java.util.List;

/**
 * This is used in conjunction with
 * {@link DevicePolicyManager#addCrossProfileIntentFilter} to enable intents to
 * be passed in and out of a managed profile.
 */
public class IntentForwarderActivity extends Activity  {

    public static String TAG = "IntentForwarderActivity";

    public static String FORWARD_INTENT_TO_USER_OWNER
            = "com.android.internal.app.ForwardIntentToUserOwner";

    public static String FORWARD_INTENT_TO_MANAGED_PROFILE
            = "com.android.internal.app.ForwardIntentToManagedProfile";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intentReceived = getIntent();

        String className = intentReceived.getComponent().getClassName();
        final int targetUserId;
        final int userMessageId;

        if (className.equals(FORWARD_INTENT_TO_USER_OWNER)) {
            userMessageId = com.android.internal.R.string.forward_intent_to_owner;
            targetUserId = UserHandle.USER_OWNER;
        } else if (className.equals(FORWARD_INTENT_TO_MANAGED_PROFILE)) {
            userMessageId = com.android.internal.R.string.forward_intent_to_work;
            targetUserId = getManagedProfile();
        } else {
            Slog.wtf(TAG, IntentForwarderActivity.class.getName() + " cannot be called directly");
            userMessageId = -1;
            targetUserId = UserHandle.USER_NULL;
        }
        if (targetUserId == UserHandle.USER_NULL) {
            // This covers the case where there is no managed profile.
            finish();
            return;
        }
        Intent newIntent = new Intent(intentReceived);
        newIntent.setComponent(null);
        // Apps should not be allowed to target a specific package in the target user.
        newIntent.setPackage(null);
        newIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT
                |Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
        int callingUserId = getUserId();

        if (canForward(newIntent, targetUserId)) {
            if (newIntent.getAction().equals(Intent.ACTION_CHOOSER)) {
                Intent innerIntent = (Intent) newIntent.getParcelableExtra(Intent.EXTRA_INTENT);
                innerIntent.prepareToLeaveUser(callingUserId);
            } else {
                newIntent.prepareToLeaveUser(callingUserId);
            }

            final android.content.pm.ResolveInfo ri = getPackageManager().resolveActivityAsUser(
                        newIntent, MATCH_DEFAULT_ONLY, targetUserId);

            // Don't show the disclosure if next activity is ResolverActivity or ChooserActivity
            // as those will already have shown work / personal as neccesary etc.
            final boolean shouldShowDisclosure = ri == null || ri.activityInfo == null ||
                    !"android".equals(ri.activityInfo.packageName) ||
                    !(ResolverActivity.class.getName().equals(ri.activityInfo.name)
                    || ChooserActivity.class.getName().equals(ri.activityInfo.name));

            try {
                startActivityAsCaller(newIntent, null, false, targetUserId);
            } catch (RuntimeException e) {
                int launchedFromUid = -1;
                String launchedFromPackage = "?";
                try {
                    launchedFromUid = ActivityManagerNative.getDefault().getLaunchedFromUid(
                            getActivityToken());
                    launchedFromPackage = ActivityManagerNative.getDefault().getLaunchedFromPackage(
                            getActivityToken());
                } catch (RemoteException ignored) {
                }

                Slog.wtf(TAG, "Unable to launch as UID " + launchedFromUid + " package "
                        + launchedFromPackage + ", while running in "
                        + ActivityThread.currentProcessName(), e);
            }

            if (shouldShowDisclosure) {
                Toast.makeText(this, getString(userMessageId), Toast.LENGTH_LONG).show();
            }
        } else {
            Slog.wtf(TAG, "the intent: " + newIntent + "cannot be forwarded from user "
                    + callingUserId + " to user " + targetUserId);
        }
        finish();
    }

    boolean canForward(Intent intent, int targetUserId)  {
        IPackageManager ipm = AppGlobals.getPackageManager();
        if (intent.getAction().equals(Intent.ACTION_CHOOSER)) {
            // The EXTRA_INITIAL_INTENTS may not be allowed to be forwarded.
            if (intent.hasExtra(Intent.EXTRA_INITIAL_INTENTS)) {
                Slog.wtf(TAG, "An chooser intent with extra initial intents cannot be forwarded to"
                        + " a different user");
                return false;
            }
            if (intent.hasExtra(Intent.EXTRA_REPLACEMENT_EXTRAS)) {
                Slog.wtf(TAG, "A chooser intent with replacement extras cannot be forwarded to a"
                        + " different user");
                return false;
            }
            intent = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
        }
        String resolvedType = intent.resolveTypeIfNeeded(getContentResolver());
        if (intent.getSelector() != null) {
            intent = intent.getSelector();
        }
        try {
            return ipm.canForwardTo(intent, resolvedType, getUserId(),
                    targetUserId);
        } catch (RemoteException e) {
            Slog.e(TAG, "PackageManagerService is dead?");
            return false;
        }
    }

    /**
     * Returns the userId of the managed profile for this device or UserHandle.USER_NULL if there is
     * no managed profile.
     *
     * TODO: Remove the assumption that there is only one managed profile
     * on the device.
     */
    private int getManagedProfile() {
        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        List<UserInfo> relatedUsers = userManager.getProfiles(UserHandle.USER_OWNER);
        for (UserInfo userInfo : relatedUsers) {
            if (userInfo.isManagedProfile()) return userInfo.id;
        }
        Slog.wtf(TAG, FORWARD_INTENT_TO_MANAGED_PROFILE
                + " has been called, but there is no managed profile");
        return UserHandle.USER_NULL;
    }
}
