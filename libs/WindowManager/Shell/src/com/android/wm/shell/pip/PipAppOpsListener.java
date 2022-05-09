/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.pip;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OP_PICTURE_IN_PICTURE;

import android.app.AppOpsManager;
import android.app.AppOpsManager.OnOpChangedListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Pair;

import com.android.wm.shell.common.ShellExecutor;

public class PipAppOpsListener {
    private static final String TAG = PipAppOpsListener.class.getSimpleName();

    private Context mContext;
    private ShellExecutor mMainExecutor;
    private AppOpsManager mAppOpsManager;
    private Callback mCallback;

    private AppOpsManager.OnOpChangedListener mAppOpsChangedListener = new OnOpChangedListener() {
        @Override
        public void onOpChanged(String op, String packageName) {
            try {
                // Dismiss the PiP once the user disables the app ops setting for that package
                final Pair<ComponentName, Integer> topPipActivityInfo =
                        PipUtils.getTopPipActivity(mContext);
                if (topPipActivityInfo.first != null) {
                    final ApplicationInfo appInfo = mContext.getPackageManager()
                            .getApplicationInfoAsUser(packageName, 0, topPipActivityInfo.second);
                    if (appInfo.packageName.equals(topPipActivityInfo.first.getPackageName()) &&
                            mAppOpsManager.checkOpNoThrow(OP_PICTURE_IN_PICTURE, appInfo.uid,
                                    packageName) != MODE_ALLOWED) {
                        mMainExecutor.execute(() -> mCallback.dismissPip());
                    }
                }
            } catch (NameNotFoundException e) {
                // Unregister the listener if the package can't be found
                unregisterAppOpsListener();
            }
        }
    };

    public PipAppOpsListener(Context context, Callback callback, ShellExecutor mainExecutor) {
        mContext = context;
        mMainExecutor = mainExecutor;
        mAppOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mCallback = callback;
    }

    public void onActivityPinned(String packageName) {
        // Register for changes to the app ops setting for this package while it is in PiP
        registerAppOpsListener(packageName);
    }

    public void onActivityUnpinned() {
        // Unregister for changes to the previously PiP'ed package
        unregisterAppOpsListener();
    }

    private void registerAppOpsListener(String packageName) {
        mAppOpsManager.startWatchingMode(OP_PICTURE_IN_PICTURE, packageName,
                mAppOpsChangedListener);
    }

    private void unregisterAppOpsListener() {
        mAppOpsManager.stopWatchingMode(mAppOpsChangedListener);
    }

    /** Callback for PipAppOpsListener to request changes to the PIP window. */
    public interface Callback {
        /** Dismisses the PIP window. */
        void dismissPip();
    }
}
