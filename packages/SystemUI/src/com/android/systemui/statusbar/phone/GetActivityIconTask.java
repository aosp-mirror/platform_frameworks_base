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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.util.Slog;
import android.widget.ImageView;

/**
 * Retrieves the icon for an activity and sets it as the Drawable on an ImageView. The ImageView
 * is hidden if the activity isn't recognized or if there is no icon.
 */
class GetActivityIconTask extends AsyncTask<AppButtonData, Void, Drawable> {
    private final static String TAG = "GetActivityIconTask";

    private final PackageManager mPackageManager;

    // The ImageView that will receive the icon.
    private final ImageView mImageView;

    public GetActivityIconTask(PackageManager packageManager, ImageView imageView) {
        mPackageManager = packageManager;
        mImageView = imageView;
    }

    @Override
    protected Drawable doInBackground(AppButtonData... params) {
        if (params.length != 1) {
            throw new IllegalArgumentException("Expected one parameter");
        }
        AppButtonData buttonData = params[0];
        AppInfo appInfo = buttonData.appInfo;
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
            Drawable badgedIcon =
                    mPackageManager.getUserBadgedIcon(unbadgedIcon, appInfo.getUser());

            if (NavigationBarApps.DEBUG) {
                // Draw pinned indicator and number of running tasks.
                Bitmap bitmap = Bitmap.createBitmap(
                        badgedIcon.getIntrinsicWidth(),
                        badgedIcon.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                badgedIcon.setBounds(
                        0, 0, badgedIcon.getIntrinsicWidth(), badgedIcon.getIntrinsicHeight());
                badgedIcon.draw(canvas);
                Paint paint = new Paint();
                paint.setStyle(Paint.Style.FILL);
                if (buttonData.pinned) {
                    paint.setColor(Color.WHITE);
                    canvas.drawCircle(10, 10, 10, paint);
                }
                if (buttonData.tasks != null && buttonData.tasks.size() > 0) {
                    paint.setColor(Color.BLACK);
                    canvas.drawCircle(60, 30, 30, paint);
                    paint.setColor(Color.WHITE);
                    paint.setTextSize(50);
                    paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                    canvas.drawText(Integer.toString(buttonData.tasks.size()), 50, 50, paint);
                }
                badgedIcon = new BitmapDrawable(null, bitmap);
            }

            return  badgedIcon;
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
