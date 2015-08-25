/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.app.AppGlobals;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.util.Slog;
import android.widget.ImageView;

/**
 * Retrieves the icon for an activity and sets it as the Drawable on an ImageView. The ImageView
 * is hidden if the activity isn't recognized or if there is no icon.
 */
class GetActivityIconTask extends AsyncTask<AppInfo, Void, Drawable> {
    private final static String TAG = "GetActivityIconTask";

    private final PackageManager mPackageManager;

    // The ImageView that will receive the icon.
    private final ImageView mImageView;

    public GetActivityIconTask(PackageManager packageManager, ImageView imageView) {
        mPackageManager = packageManager;
        mImageView = imageView;
    }

    @Override
    protected Drawable doInBackground(AppInfo... params) {
        if (params.length != 1) {
            throw new IllegalArgumentException("Expected one parameter");
        }
        AppInfo appInfo = params[0];
        try {
            IPackageManager mPM = AppGlobals.getPackageManager();
            ActivityInfo ai = mPM.getActivityInfo(
                    appInfo.getComponentName(),
                    0,
                    appInfo.getUser().getIdentifier());

            if (ai == null) {
                Slog.w(TAG, "Icon not found for " + appInfo);
                return null;
            }

            Drawable unbadgedIcon = ai.loadIcon(mPackageManager);
            return mPackageManager.getUserBadgedIcon(unbadgedIcon, appInfo.getUser());
        } catch (RemoteException e) {
            Slog.w(TAG, "Icon not found for " + appInfo, e);
            return null;
        }
    }

    @Override
    protected void onPostExecute(Drawable icon) {
        mImageView.setImageDrawable(icon);
    }
}
