/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static android.content.Context.NOTIFICATION_SERVICE;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Picture;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.android.internal.messages.nano.SystemMessageProto;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.util.NotificationChannels;

import javax.inject.Inject;

/**
 * Convenience class to handle showing and hiding notifications while taking a screenshot.
 */
public class ScreenshotNotificationsController {
    private static final String TAG = "ScreenshotNotificationManager";

    private final Context mContext;
    private final Resources mResources;
    private final NotificationManager mNotificationManager;
    private final Notification.BigPictureStyle mNotificationStyle;

    private int mIconSize;
    private int mPreviewWidth, mPreviewHeight;
    private Notification.Builder mNotificationBuilder, mPublicNotificationBuilder;

    @Inject
    ScreenshotNotificationsController(Context context, WindowManager windowManager) {
        mContext = context;
        mResources = context.getResources();

        mNotificationManager =
                (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);

        mIconSize = mResources.getDimensionPixelSize(
                android.R.dimen.notification_large_icon_height);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);


        // determine the optimal preview size
        int panelWidth = 0;
        try {
            panelWidth = mResources.getDimensionPixelSize(R.dimen.notification_panel_width);
        } catch (Resources.NotFoundException e) {
        }
        if (panelWidth <= 0) {
            // includes notification_panel_width==match_parent (-1)
            panelWidth = displayMetrics.widthPixels;
        }
        mPreviewWidth = panelWidth;
        mPreviewHeight = mResources.getDimensionPixelSize(R.dimen.notification_max_height);

        // Setup the notification
        mNotificationStyle = new Notification.BigPictureStyle();
    }

    /**
     * Resets the notification builders.
     */
    public void reset() {
        // The public notification will show similar info but with the actual screenshot omitted
        mPublicNotificationBuilder =
                new Notification.Builder(mContext, NotificationChannels.SCREENSHOTS_HEADSUP);
        mNotificationBuilder =
                new Notification.Builder(mContext, NotificationChannels.SCREENSHOTS_HEADSUP);
    }

    /**
     * Sets the current screenshot bitmap.
     *
     * @param image the bitmap of the current screenshot (used for preview)
     */
    public void setImage(Bitmap image) {
        // Create the large notification icon
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();

        Paint paint = new Paint();
        ColorMatrix desat = new ColorMatrix();
        desat.setSaturation(0.25f);
        paint.setColorFilter(new ColorMatrixColorFilter(desat));
        Matrix matrix = new Matrix();
        int overlayColor = 0x40FFFFFF;

        matrix.setTranslate((mPreviewWidth - imageWidth) / 2f, (mPreviewHeight - imageHeight) / 2f);

        Bitmap picture = generateAdjustedHwBitmap(
                image, mPreviewWidth, mPreviewHeight, matrix, paint, overlayColor);

        mNotificationStyle.bigPicture(picture.createAshmemBitmap());

        // Note, we can't use the preview for the small icon, since it is non-square
        float scale = (float) mIconSize / Math.min(imageWidth, imageHeight);
        matrix.setScale(scale, scale);
        matrix.postTranslate(
                (mIconSize - (scale * imageWidth)) / 2,
                (mIconSize - (scale * imageHeight)) / 2);
        Bitmap icon =
                generateAdjustedHwBitmap(image, mIconSize, mIconSize, matrix, paint, overlayColor);

        /**
         * NOTE: The following code prepares the notification builder for updating the
         * notification after the screenshot has been written to disk.
         */

        // On the tablet, the large icon makes the notification appear as if it is clickable
        // (and on small devices, the large icon is not shown) so defer showing the large icon
        // until we compose the final post-save notification below.
        mNotificationBuilder.setLargeIcon(icon.createAshmemBitmap());
        // But we still don't set it for the expanded view, allowing the smallIcon to show here.
        mNotificationStyle.bigLargeIcon((Bitmap) null);
    }

    /**
     * Shows a notification to inform the user that a screenshot is currently being saved.
     */
    public void showSavingScreenshotNotification() {
        final long now = System.currentTimeMillis();

        mPublicNotificationBuilder
                .setContentTitle(mResources.getString(R.string.screenshot_saving_title))
                .setSmallIcon(R.drawable.stat_notify_image)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setWhen(now)
                .setShowWhen(true)
                .setColor(mResources.getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        SystemUI.overrideNotificationAppName(mContext, mPublicNotificationBuilder, true);

        mNotificationBuilder
                .setContentTitle(mResources.getString(R.string.screenshot_saving_title))
                .setSmallIcon(R.drawable.stat_notify_image)
                .setWhen(now)
                .setShowWhen(true)
                .setColor(mResources.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setStyle(mNotificationStyle)
                .setPublicVersion(mPublicNotificationBuilder.build());
        mNotificationBuilder.setFlag(Notification.FLAG_NO_CLEAR, true);
        SystemUI.overrideNotificationAppName(mContext, mNotificationBuilder, true);

        mNotificationManager.notify(SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT,
                mNotificationBuilder.build());
    }

    /**
     * Shows a notification with the saved screenshot and actions that can be taken with it.
     *
     * @param actionData SavedImageData struct with image URI and actions
     */
    public void showScreenshotActionsNotification(
            GlobalScreenshot.SavedImageData actionData) {
        mNotificationBuilder.addAction(actionData.shareAction);
        mNotificationBuilder.addAction(actionData.editAction);
        mNotificationBuilder.addAction(actionData.deleteAction);
        for (Notification.Action smartAction : actionData.smartActions) {
            mNotificationBuilder.addAction(smartAction);
        }

        // Create the intent to show the screenshot in gallery
        Intent launchIntent = new Intent(Intent.ACTION_VIEW);
        launchIntent.setDataAndType(actionData.uri, "image/png");
        launchIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        final long now = System.currentTimeMillis();

        // Update the text and the icon for the existing notification
        mPublicNotificationBuilder
                .setContentTitle(mResources.getString(R.string.screenshot_saved_title))
                .setContentText(mResources.getString(R.string.screenshot_saved_text))
                .setContentIntent(PendingIntent
                        .getActivity(mContext, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE))
                .setWhen(now)
                .setAutoCancel(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        mNotificationBuilder
                .setContentTitle(mResources.getString(R.string.screenshot_saved_title))
                .setContentText(mResources.getString(R.string.screenshot_saved_text))
                .setContentIntent(PendingIntent
                        .getActivity(mContext, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE))
                .setWhen(now)
                .setAutoCancel(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setPublicVersion(mPublicNotificationBuilder.build())
                .setFlag(Notification.FLAG_NO_CLEAR, false);

        mNotificationManager.notify(SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT,
                mNotificationBuilder.build());
    }

    /**
     * Sends a notification that the screenshot capture has failed.
     */
    public void notifyScreenshotError(int msgResId) {
        Resources res = mContext.getResources();
        String errorMsg = res.getString(msgResId);

        // Repurpose the existing notification to notify the user of the error
        Notification.Builder b = new Notification.Builder(mContext, NotificationChannels.ALERTS)
                .setTicker(res.getString(R.string.screenshot_failed_title))
                .setContentTitle(res.getString(R.string.screenshot_failed_title))
                .setContentText(errorMsg)
                .setSmallIcon(R.drawable.stat_notify_image_error)
                .setWhen(System.currentTimeMillis())
                .setVisibility(Notification.VISIBILITY_PUBLIC) // ok to show outside lockscreen
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        final DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        final Intent intent =
                dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);
        if (intent != null) {
            final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                    mContext, 0, intent, PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);
            b.setContentIntent(pendingIntent);
        }

        SystemUI.overrideNotificationAppName(mContext, b, true);

        Notification n = new Notification.BigTextStyle(b)
                .bigText(errorMsg)
                .build();
        mNotificationManager.notify(SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT, n);
    }

    /**
     * Cancels the current screenshot notification.
     */
    public void cancelNotification() {
        mNotificationManager.cancel(SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT);
    }

    /**
     * Generates a new hardware bitmap with specified values, copying the content from the
     * passed in bitmap.
     */
    private Bitmap generateAdjustedHwBitmap(Bitmap bitmap, int width, int height, Matrix matrix,
            Paint paint, int color) {
        Picture picture = new Picture();
        Canvas canvas = picture.beginRecording(width, height);
        canvas.drawColor(color);
        canvas.drawBitmap(bitmap, matrix, paint);
        picture.endRecording();
        return Bitmap.createBitmap(picture);
    }

    static void cancelScreenshotNotification(Context context) {
        final NotificationManager nm =
                (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT);
    }
}
