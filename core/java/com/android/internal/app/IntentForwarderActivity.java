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
import android.app.AppGlobals;
import android.os.Bundle;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.UserInfo;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.widget.Toast;
import java.util.List;
import java.util.Set;

/*
 * This is used in conjunction with the {@link setCrossProfileIntentFilter} method of
 * {@link DevicePolicyManager} to enable intents to be passed in and out of a managed profile.
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
        final UserHandle userDest;
        final int userMessageId;

        if (className.equals(FORWARD_INTENT_TO_USER_OWNER)) {
            userMessageId = com.android.internal.R.string.forward_intent_to_owner;
            userDest = UserHandle.OWNER;
        } else if (className.equals(FORWARD_INTENT_TO_MANAGED_PROFILE)) {
            userMessageId = com.android.internal.R.string.forward_intent_to_work;
            userDest = getManagedProfile();
        } else {
            Slog.wtf(TAG, IntentForwarderActivity.class.getName() + " cannot be called directly");
            userMessageId = -1;
            userDest = null;
        }
        if (userDest == null) { // This covers the case where there is no managed profile.
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
        IPackageManager ipm = AppGlobals.getPackageManager();
        String resolvedType = newIntent.resolveTypeIfNeeded(getContentResolver());
        boolean canForward = false;
        Intent selector = newIntent.getSelector();
        if (selector == null) {
            selector = newIntent;
        }
        try {
            canForward = ipm.canForwardTo(selector, resolvedType, callingUserId,
                    userDest.getIdentifier());
        } catch (RemoteException e) {
            Slog.e(TAG, "PackageManagerService is dead?");
        }
        if (canForward) {
            newIntent.setContentUserHint(callingUserId);

            final android.content.pm.ResolveInfo ri = getPackageManager().resolveActivityAsUser(
                        newIntent, MATCH_DEFAULT_ONLY, userDest.getIdentifier());

            // Only show a disclosure if this is a normal (non-OS) app
            final boolean shouldShowDisclosure =
                    !UserHandle.isSameApp(ri.activityInfo.applicationInfo.uid, Process.SYSTEM_UID);

            startActivityAsUser(newIntent, userDest);

            if (shouldShowDisclosure) {
                Toast.makeText(this, getString(userMessageId), Toast.LENGTH_LONG).show();
            }
        } else {
            Slog.wtf(TAG, "the intent: " + newIntent + "cannot be forwarded from user "
                    + callingUserId + " to user " + userDest.getIdentifier());
        }
        finish();
    }

    /**
     * Returns the managed profile for this device or null if there is no managed
     * profile.
     *
     * TODO: Remove the assumption that there is only one managed profile
     * on the device.
     */
    private UserHandle getManagedProfile() {
        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        List<UserInfo> relatedUsers = userManager.getProfiles(UserHandle.USER_OWNER);
        for (UserInfo userInfo : relatedUsers) {
            if (userInfo.isManagedProfile()) return new UserHandle(userInfo.id);
        }
        Slog.wtf(TAG, FORWARD_INTENT_TO_MANAGED_PROFILE
                + " has been called, but there is no managed profile");
        return null;
    }
}
