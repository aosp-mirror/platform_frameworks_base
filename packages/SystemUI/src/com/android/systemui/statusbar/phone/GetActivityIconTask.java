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

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.Slog;
import android.view.View;
import android.widget.ImageView;

/**
 * Retrieves the icon for an activity and sets it as the Drawable on an ImageView. The ImageView
 * is hidden if the activity isn't recognized or if there is no icon.
 */
class GetActivityIconTask extends AsyncTask<ComponentName, Void, Drawable> {
    private final static String TAG = "GetActivityIconTask";

    private final PackageManager mPackageManager;

    // The ImageView that will receive the icon.
    private final ImageView mImageView;

    public GetActivityIconTask(PackageManager packageManager, ImageView imageView) {
        mPackageManager = packageManager;
        mImageView = imageView;
    }

    @Override
    protected Drawable doInBackground(ComponentName... params) {
        if (params.length != 1) {
            throw new IllegalArgumentException("Expected one parameter");
        }
        ComponentName activityName = params[0];
        try {
            return mPackageManager.getActivityIcon(activityName);
        } catch (NameNotFoundException e) {
            Slog.w(TAG, "Icon not found for " + activityName);
            return null;
        }
    }

    @Override
    protected void onPostExecute(Drawable icon) {
        mImageView.setImageDrawable(icon);
        mImageView.setVisibility(icon != null ? View.VISIBLE : View.GONE);
    }
}
