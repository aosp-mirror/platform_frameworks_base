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

package com.android.keyguard;

import com.android.internal.widget.LockPatternUtils;

import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.IActivityManager.WaitResult;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.keyguard.KeyguardHostView.OnDismissAction;

import java.util.List;

public abstract class KeyguardActivityLauncher {
    private static final String TAG = KeyguardActivityLauncher.class.getSimpleName();
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String META_DATA_KEYGUARD_LAYOUT = "com.android.keyguard.layout";
    private static final Intent SECURE_CAMERA_INTENT =
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
                    .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    private static final Intent INSECURE_CAMERA_INTENT =
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);

    abstract Context getContext();

    abstract LockPatternUtils getLockPatternUtils();

    abstract void setOnDismissAction(OnDismissAction action);

    abstract void requestDismissKeyguard();

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

    public void launchCamera(Handler worker, Runnable onSecureCameraStarted) {
        LockPatternUtils lockPatternUtils = getLockPatternUtils();

        // Workaround to avoid camera release/acquisition race when resuming face unlock
        // after showing lockscreen camera (bug 11063890).
        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(getContext());
        updateMonitor.setAlternateUnlockEnabled(false);

        if (mustLaunchSecurely()) {
            // Launch the secure version of the camera
            if (wouldLaunchResolverActivity(SECURE_CAMERA_INTENT)) {
                // TODO: Show disambiguation dialog instead.
                // For now, we'll treat this like launching any other app from secure keyguard.
                // When they do, user sees the system's ResolverActivity which lets them choose
                // which secure camera to use.
                launchActivity(SECURE_CAMERA_INTENT, false, false, null, null);
            } else {
                launchActivity(SECURE_CAMERA_INTENT, true, false, worker, onSecureCameraStarted);
            }
        } else {
            // Launch the normal camera
            launchActivity(INSECURE_CAMERA_INTENT, false, false, null, null);
        }
    }

    private boolean mustLaunchSecurely() {
        LockPatternUtils lockPatternUtils = getLockPatternUtils();
        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(getContext());
        int currentUser = lockPatternUtils.getCurrentUser();
        return lockPatternUtils.isSecure() && !updateMonitor.getUserHasTrust(currentUser);
    }

    public void launchWidgetPicker(int appWidgetId) {
        Intent pickIntent = new Intent(AppWidgetManager.ACTION_KEYGUARD_APPWIDGET_PICK);

        pickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        pickIntent.putExtra(AppWidgetManager.EXTRA_CUSTOM_SORT, false);
        pickIntent.putExtra(AppWidgetManager.EXTRA_CATEGORY_FILTER,
                AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD);

        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD);
        pickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options);
        pickIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        launchActivity(pickIntent, false, false, null, null);
    }

    /**
     * Launches the said intent for the current foreground user.
     *
     * @param intent
     * @param showsWhileLocked true if the activity can be run on top of keyguard.
     *   See {@link WindowManager#FLAG_SHOW_WHEN_LOCKED}
     * @param useDefaultAnimations true if default transitions should be used, else suppressed.
     * @param worker if supplied along with onStarted, used to launch the blocking activity call.
     * @param onStarted if supplied along with worker, called after activity is started.
     */
    public void launchActivity(final Intent intent,
            boolean showsWhileLocked,
            boolean useDefaultAnimations,
            final Handler worker,
            final Runnable onStarted) {

        final Context context = getContext();
        final Bundle animation = useDefaultAnimations ? null
                : ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle();
        launchActivityWithAnimation(intent, showsWhileLocked, animation, worker, onStarted);
    }

    public void launchActivityWithAnimation(final Intent intent,
            boolean showsWhileLocked,
            final Bundle animation,
            final Handler worker,
            final Runnable onStarted) {

        LockPatternUtils lockPatternUtils = getLockPatternUtils();
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        boolean mustLaunchSecurely = mustLaunchSecurely();
        if (!mustLaunchSecurely || showsWhileLocked) {
            if (!mustLaunchSecurely) {
                dismissKeyguardOnNextActivity();
            }
            try {
                if (DEBUG) Log.d(TAG, String.format("Starting activity for intent %s at %s",
                        intent, SystemClock.uptimeMillis()));
                startActivityForCurrentUser(intent, animation, worker, onStarted);
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "Activity not found for intent + " + intent.getAction());
            }
        } else {
            // Create a runnable to start the activity and ask the user to enter their
            // credentials.
            setOnDismissAction(new OnDismissAction() {
                @Override
                public boolean onDismiss() {
                    dismissKeyguardOnNextActivity();
                    startActivityForCurrentUser(intent, animation, worker, onStarted);
                    return true;
                }
            });
            requestDismissKeyguard();
        }
    }

    private void dismissKeyguardOnNextActivity() {
        try {
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard();
        } catch (RemoteException e) {
            Log.w(TAG, "Error dismissing keyguard", e);
        }
    }

    private void startActivityForCurrentUser(final Intent intent, final Bundle options,
            Handler worker, final Runnable onStarted) {
        final UserHandle user = new UserHandle(UserHandle.USER_CURRENT);
        if (worker == null || onStarted == null) {
            getContext().startActivityAsUser(intent, options, user);
            return;
        }
        // if worker + onStarted are supplied, run blocking activity launch call in the background
        worker.post(new Runnable(){
            @Override
            public void run() {
                try {
                    WaitResult result = ActivityManagerNative.getDefault().startActivityAndWait(
                            null /*caller*/,
                            null /*caller pkg*/,
                            intent,
                            intent.resolveTypeIfNeeded(getContext().getContentResolver()),
                            null /*resultTo*/,
                            null /*resultWho*/,
                            0 /*requestCode*/,
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                            null /*profileFile*/,
                            null /*profileFd*/,
                            options,
                            user.getIdentifier());
                    if (DEBUG) Log.d(TAG, String.format("waitResult[%s,%s,%s,%s] at %s",
                            result.result, result.thisTime, result.totalTime, result.who,
                            SystemClock.uptimeMillis()));
                } catch (RemoteException e) {
                    Log.w(TAG, "Error starting activity", e);
                    return;
                }
                try {
                    onStarted.run();
                } catch (Throwable t) {
                    Log.w(TAG, "Error running onStarted callback", t);
                }
            }});
    }

    private Intent getCameraIntent() {
        return mustLaunchSecurely() ? SECURE_CAMERA_INTENT : INSECURE_CAMERA_INTENT;
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
