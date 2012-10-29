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

import java.util.List;

import android.app.ActivityManagerNative;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;

public class CameraWidgetFrame extends KeyguardWidgetFrame {
    private static final String TAG = CameraWidgetFrame.class.getSimpleName();
    private static final boolean DEBUG = KeyguardHostView.DEBUG;

    interface Callbacks {
        void onLaunchingCamera();
        void onCameraLaunched();
    }

    private final Handler mHandler = new Handler();
    private final LockPatternUtils mLockPatternUtils;
    private final Callbacks mCallbacks;

    private boolean mCameraWidgetFound;
    private long mLaunchCameraStart;

    private final Runnable mLaunchCameraRunnable = new Runnable() {
        @Override
        public void run() {
            launchCamera();
        }};

    public CameraWidgetFrame(Context context, Callbacks callbacks) {
        super(context);

        mLockPatternUtils = new LockPatternUtils(context);
        mCallbacks = callbacks;

        View cameraView = createCameraView();
        addView(cameraView);
    }

    private View createCameraView() {
        View cameraView = null;
        Exception exception = null;
        try {
            String contextPackage = mContext.getString(R.string.kg_camera_widget_context_package);
            String layoutPackage = mContext.getString(R.string.kg_camera_widget_layout_package);
            String layoutName = mContext.getString(R.string.kg_camera_widget_layout_name);

            Context cameraContext = mContext.createPackageContext(
                    contextPackage, Context.CONTEXT_RESTRICTED);
            LayoutInflater cameraInflater = (LayoutInflater)
                    cameraContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            cameraInflater = cameraInflater.cloneInContext(cameraContext);

            int layoutId = cameraContext.getResources()
                    .getIdentifier(layoutName, "layout", layoutPackage);
            cameraView = cameraInflater.inflate(layoutId, null, false);
        } catch (NameNotFoundException e) {
            exception = e;
        } catch (RuntimeException e) {
            exception = e;
        }
        if (exception != null) {
            Log.w(TAG, "Error creating camera widget view", exception);
        }
        if (cameraView == null) {
            cameraView = createCameraErrorView();
        } else {
            mCameraWidgetFound = true;
        }
        return cameraView;
    }

    private View createCameraErrorView() {
        TextView errorView = new TextView(mContext);
        errorView.setGravity(Gravity.CENTER);
        errorView.setText(R.string.kg_camera_widget_not_found);
        errorView.setBackgroundColor(Color.argb(127, 0, 0, 0));
        return errorView;
    }

    private void transitionToCamera() {
        animate()
            .scaleX(1.22f)  // TODO compute this at runtime
            .scaleY(1.22f)
            .setDuration(250)
            .withEndAction(mLaunchCameraRunnable)
            .start();
        mCallbacks.onLaunchingCamera();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!mCameraWidgetFound) return;

        if (!hasWindowFocus) {
            if (mLaunchCameraStart > 0) {
                long launchTime = SystemClock.uptimeMillis() - mLaunchCameraStart;
                if (DEBUG) Log.d(TAG, String.format("Camera took %s to launch", launchTime));
                mLaunchCameraStart = 0;
            }
            onCameraLaunched();
        }
    }

    @Override
    public void onActive(boolean isActive) {
        if (!mCameraWidgetFound) return;

        if (isActive) {
            mHandler.post(new Runnable(){
                @Override
                public void run() {
                    transitionToCamera();
                }});
        } else {
            reset();
        }
    }

    private void onCameraLaunched() {
        reset();
        mCallbacks.onCameraLaunched();
    }

    private void reset() {
        animate().cancel();
        setScaleX(1);
        setScaleY(1);
    }

    // =========== from KeyguardSelectorView ===========
    protected void launchCamera() {
        mLaunchCameraStart = SystemClock.uptimeMillis();
        boolean isSecure = mLockPatternUtils.isSecure();
        if (DEBUG) Log.d(TAG, "launchCamera " + (isSecure?"(secure)":"(insecure)"));
        if (isSecure) {
            // Launch the secure version of the camera
            final Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

            if (wouldLaunchResolverActivity(intent)) {
                // TODO: Show disambiguation dialog instead.
                // For now, we'll treat this like launching any other app from secure keyguard.
                // When they do, user sees the system's ResolverActivity which lets them choose
                // which secure camera to use.
                launchActivity(intent, false);
            } else {
                launchActivity(intent, true);
            }
        } else {
            // Launch the normal camera
            launchActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), false);
        }
    }

    /**
     * Launches the said intent for the current foreground user.
     * @param intent
     * @param showsWhileLocked true if the activity can be run on top of keyguard.
     * See {@link WindowManager#FLAG_SHOW_WHEN_LOCKED}
     */
    private void launchActivity(final Intent intent, boolean showsWhileLocked) {
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        boolean isSecure = mLockPatternUtils.isSecure();
        if (!isSecure || showsWhileLocked) {
            if (!isSecure) try {
                ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
            } catch (RemoteException e) {
                Log.w(TAG, "can't dismiss keyguard on launch");
            }
            try {
                mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "Activity not found for intent + " + intent.getAction());
            }
        } else {
            Log.w(TAG, "TODO: handle this case");
            onCameraLaunched();
//            // Create a runnable to start the activity and ask the user to enter their
//            // credentials.
//            mCallback.setOnDismissRunnable(new Runnable() {
//                @Override
//                public void run() {
//                    mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
//                }
//            });
//            mCallback.dismiss(false);
        }
    }

    private boolean wouldLaunchResolverActivity(Intent intent) {
        PackageManager packageManager = mContext.getPackageManager();
        ResolveInfo resolved = packageManager.resolveActivityAsUser(intent,
                PackageManager.MATCH_DEFAULT_ONLY, mLockPatternUtils.getCurrentUser());
        final List<ResolveInfo> appList = packageManager.queryIntentActivitiesAsUser(
                intent, PackageManager.MATCH_DEFAULT_ONLY, mLockPatternUtils.getCurrentUser());
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
