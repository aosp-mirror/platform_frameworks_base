/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.widget.LockPatternUtils;

import java.util.List;

public abstract class KeyguardActivityLauncher {
    private static final String TAG = KeyguardActivityLauncher.class.getSimpleName();
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final String META_DATA_KEYGUARD_LAYOUT = "com.android.keyguard.layout";
    private static final Intent SECURE_CAMERA_INTENT =
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
                    .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    private static final Intent INSECURE_CAMERA_INTENT =
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);

    abstract Context getContext();

    abstract KeyguardSecurityCallback getCallback();

    abstract LockPatternUtils getLockPatternUtils();

    public static class CameraWidgetInfo {
        public String contextPackage;
        public int layoutId;
    }

    public CameraWidgetInfo getCameraWidgetInfo() {
        CameraWidgetInfo info = new CameraWidgetInfo();
        Intent intent = getCameraIntent();
        PackageManager packageManager = getContext().getPackageManager();
        final List<ResolveInfo> appList = packageManager.queryIntentActivitiesAsUser(
                intent, PackageManager.MATCH_DEFAULT_ONLY, getLockPatternUtils().getCurrentUser());
        if (appList.size() == 0) {
            if (DEBUG) Log.d(TAG, "getCameraWidgetInfo(): Nothing found");
            return null;
        }
        ResolveInfo resolved = packageManager.resolveActivityAsUser(intent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_META_DATA,
                getLockPatternUtils().getCurrentUser());
        if (DEBUG) Log.d(TAG, "getCameraWidgetInfo(): resolved: " + resolved);
        if (wouldLaunchResolverActivity(resolved, appList)) {
            if (DEBUG) Log.d(TAG, "getCameraWidgetInfo(): Would launch resolver");
            return info;
        }
        if (resolved == null || resolved.activityInfo == null) {
            return null;
        }
        if (resolved.activityInfo.metaData == null || resolved.activityInfo.metaData.isEmpty()) {
            if (DEBUG) Log.d(TAG, "getCameraWidgetInfo(): no metadata found");
            return info;
        }
        int layoutId = resolved.activityInfo.metaData.getInt(META_DATA_KEYGUARD_LAYOUT);
        if (layoutId == 0) {
            if (DEBUG) Log.d(TAG, "getCameraWidgetInfo(): no layout specified");
            return info;
        }
        info.contextPackage = resolved.activityInfo.packageName;
        info.layoutId = layoutId;
        return info;
    }

    public void launchCamera() {
        LockPatternUtils lockPatternUtils = getLockPatternUtils();
        if (lockPatternUtils.isSecure()) {
            // Launch the secure version of the camera
            if (wouldLaunchResolverActivity(SECURE_CAMERA_INTENT)) {
                // TODO: Show disambiguation dialog instead.
                // For now, we'll treat this like launching any other app from secure keyguard.
                // When they do, user sees the system's ResolverActivity which lets them choose
                // which secure camera to use.
                launchActivity(SECURE_CAMERA_INTENT, false, false);
            } else {
                launchActivity(SECURE_CAMERA_INTENT, true, false);
            }
        } else {
            // Launch the normal camera
            launchActivity(INSECURE_CAMERA_INTENT, false, false);
        }
    }

    /**
     * Launches the said intent for the current foreground user.
     * @param intent
     * @param showsWhileLocked true if the activity can be run on top of keyguard.
     * See {@link WindowManager#FLAG_SHOW_WHEN_LOCKED}
     */
    public void launchActivity(final Intent intent, boolean showsWhileLocked, boolean animate) {
        final Context context = getContext();
        final Bundle animation = animate ? null :
                ActivityOptions.makeCustomAnimation(context, com.android.internal.R.anim.fade_in,
                        com.android.internal.R.anim.fade_out).toBundle();
        LockPatternUtils lockPatternUtils = getLockPatternUtils();
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        boolean isSecure = lockPatternUtils.isSecure();
        if (!isSecure || showsWhileLocked) {
            if (!isSecure) try {
                ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
            } catch (RemoteException e) {
                Log.w(TAG, "can't dismiss keyguard on launch");
            }
            try {
                context.startActivityAsUser(intent, animation,
                        new UserHandle(UserHandle.USER_CURRENT));
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "Activity not found for intent + " + intent.getAction());
            }
        } else {
            // Create a runnable to start the activity and ask the user to enter their
            // credentials.
            KeyguardSecurityCallback callback = getCallback();
            callback.setOnDismissRunnable(new Runnable() {
                @Override
                public void run() {
                    context.startActivityAsUser(intent, animation,
                            new UserHandle(UserHandle.USER_CURRENT));
                }
            });
            callback.dismiss(false);
        }
    }

    private Intent getCameraIntent() {
        return getLockPatternUtils().isSecure() ? SECURE_CAMERA_INTENT : INSECURE_CAMERA_INTENT;
    }

    private boolean wouldLaunchResolverActivity(Intent intent) {
        PackageManager packageManager = getContext().getPackageManager();
        ResolveInfo resolved = packageManager.resolveActivityAsUser(intent,
                PackageManager.MATCH_DEFAULT_ONLY, getLockPatternUtils().getCurrentUser());
        List<ResolveInfo> appList = packageManager.queryIntentActivitiesAsUser(
                intent, PackageManager.MATCH_DEFAULT_ONLY, getLockPatternUtils().getCurrentUser());
        return wouldLaunchResolverActivity(resolved, appList);
    }

    private boolean wouldLaunchResolverActivity(ResolveInfo resolved, List<ResolveInfo> appList) {
        // If the list contains the above resolved activity, then it can't be
        // ResolverActivity itself.
        for (int i = 0; i < appList.size(); i++) {
            ResolveInfo tmp = appList.get(i);
            if (tmp.activityInfo.name.equals(resolved.activityInfo.name)
                    && tmp.activityInfo.packageName.equals(resolved.activityInfo.packageName)) {
                return false;
            }
        }
        return true;
    }
}
