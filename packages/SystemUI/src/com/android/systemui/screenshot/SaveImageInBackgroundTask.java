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

import android.app.ActivityTaskManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Picture;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.util.NotificationChannels;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * An AsyncTask that saves an image to the media store in the background.
 */
class SaveImageInBackgroundTask extends AsyncTask<Void, Void, Void> {
    private static final String TAG = "SaveImageInBackgroundTask";

    private static final String SCREENSHOT_FILE_NAME_TEMPLATE = "Screenshot_%s.png";
    private static final String SCREENSHOT_ID_TEMPLATE = "Screenshot_%s";
    private static final String SCREENSHOT_SHARE_SUBJECT_TEMPLATE = "Screenshot (%s)";

    private final GlobalScreenshot.SaveImageInBackgroundData mParams;
    private final NotificationManager mNotificationManager;
    private final Notification.Builder mNotificationBuilder, mPublicNotificationBuilder;
    private final String mImageFileName;
    private final long mImageTime;
    private final Notification.BigPictureStyle mNotificationStyle;
    private final int mImageWidth;
    private final int mImageHeight;
    private final ScreenshotNotificationSmartActionsProvider mSmartActionsProvider;
    private final String mScreenshotId;
    private final boolean mSmartActionsEnabled;
    private final Random mRandom = new Random();

    SaveImageInBackgroundTask(Context context, GlobalScreenshot.SaveImageInBackgroundData data,
            NotificationManager nManager) {
        Resources r = context.getResources();

        // Prepare all the output metadata
        mParams = data;
        mImageTime = System.currentTimeMillis();
        String imageDate = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(mImageTime));
        mImageFileName = String.format(SCREENSHOT_FILE_NAME_TEMPLATE, imageDate);
        mScreenshotId = String.format(SCREENSHOT_ID_TEMPLATE, UUID.randomUUID());

        // Initialize screenshot notification smart actions provider.
        mSmartActionsEnabled = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ENABLE_SCREENSHOT_NOTIFICATION_SMART_ACTIONS, false);
        if (mSmartActionsEnabled) {
            mSmartActionsProvider =
                    SystemUIFactory.getInstance()
                            .createScreenshotNotificationSmartActionsProvider(
                                    context, THREAD_POOL_EXECUTOR, new Handler());
        } else {
            // If smart actions is not enabled use empty implementation.
            mSmartActionsProvider = new ScreenshotNotificationSmartActionsProvider();
        }

        // Create the large notification icon
        mImageWidth = data.image.getWidth();
        mImageHeight = data.image.getHeight();
        int iconSize = data.iconSize;
        int previewWidth = data.previewWidth;
        int previewHeight = data.previewheight;

        Paint paint = new Paint();
        ColorMatrix desat = new ColorMatrix();
        desat.setSaturation(0.25f);
        paint.setColorFilter(new ColorMatrixColorFilter(desat));
        Matrix matrix = new Matrix();
        int overlayColor = 0x40FFFFFF;

        matrix.setTranslate((previewWidth - mImageWidth) / 2,
                (previewHeight - mImageHeight) / 2);
        Bitmap picture = generateAdjustedHwBitmap(data.image, previewWidth, previewHeight,
                matrix, paint, overlayColor);

        // Note, we can't use the preview for the small icon, since it is non-square
        float scale = (float) iconSize / Math.min(mImageWidth, mImageHeight);
        matrix.setScale(scale, scale);
        matrix.postTranslate((iconSize - (scale * mImageWidth)) / 2,
                (iconSize - (scale * mImageHeight)) / 2);
        Bitmap icon = generateAdjustedHwBitmap(data.image, iconSize, iconSize, matrix, paint,
                overlayColor);

        mNotificationManager = nManager;
        final long now = System.currentTimeMillis();

        // Setup the notification
        mNotificationStyle = new Notification.BigPictureStyle()
                .bigPicture(picture.createAshmemBitmap());

        // The public notification will show similar info but with the actual screenshot omitted
        mPublicNotificationBuilder =
                new Notification.Builder(context, NotificationChannels.SCREENSHOTS_HEADSUP)
                        .setContentTitle(r.getString(R.string.screenshot_saving_title))
                        .setSmallIcon(R.drawable.stat_notify_image)
                        .setCategory(Notification.CATEGORY_PROGRESS)
                        .setWhen(now)
                        .setShowWhen(true)
                        .setColor(r.getColor(
                                com.android.internal.R.color.system_notification_accent_color));
        SystemUI.overrideNotificationAppName(context, mPublicNotificationBuilder, true);

        mNotificationBuilder = new Notification.Builder(context,
                NotificationChannels.SCREENSHOTS_HEADSUP)
                .setContentTitle(r.getString(R.string.screenshot_saving_title))
                .setSmallIcon(R.drawable.stat_notify_image)
                .setWhen(now)
                .setShowWhen(true)
                .setColor(r.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setStyle(mNotificationStyle)
                .setPublicVersion(mPublicNotificationBuilder.build());
        mNotificationBuilder.setFlag(Notification.FLAG_NO_CLEAR, true);
        SystemUI.overrideNotificationAppName(context, mNotificationBuilder, true);

        mNotificationManager.notify(SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT,
                mNotificationBuilder.build());

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

    private int getUserHandleOfForegroundApplication(Context context) {
        // This logic matches
        // com.android.systemui.statusbar.phone.PhoneStatusBarPolicy#updateManagedProfile
        try {
            return ActivityTaskManager.getService().getLastResumedActivityUserId();
        } catch (RemoteException e) {
            Slog.w(TAG, "getUserHandleOfForegroundApplication: ", e);
            return context.getUserId();
        }
    }

    private boolean isManagedProfile(Context context) {
        UserManager manager = UserManager.get(context);
        UserInfo info = manager.getUserInfo(getUserHandleOfForegroundApplication(context));
        return info.isManagedProfile();
    }

    private List<Notification.Action> buildSmartActions(
            List<Notification.Action> actions, Context context) {
        List<Notification.Action> broadcastActions = new ArrayList<>();
        for (Notification.Action action : actions) {
            // Proxy smart actions through {@link GlobalScreenshot.SmartActionsReceiver}
            // for logging smart actions.
            Bundle extras = action.getExtras();
            String actionType = extras.getString(
                    ScreenshotNotificationSmartActionsProvider.ACTION_TYPE,
                    ScreenshotNotificationSmartActionsProvider.DEFAULT_ACTION_TYPE);
            Intent intent = new Intent(context,
                    GlobalScreenshot.SmartActionsReceiver.class).putExtra(
                    GlobalScreenshot.EXTRA_ACTION_INTENT, action.actionIntent);
            addIntentExtras(mScreenshotId, intent, actionType, mSmartActionsEnabled);
            PendingIntent broadcastIntent = PendingIntent.getBroadcast(context,
                    mRandom.nextInt(),
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT);
            broadcastActions.add(new Notification.Action.Builder(action.getIcon(), action.title,
                    broadcastIntent).setContextual(true).addExtras(extras).build());
        }
        return broadcastActions;
    }

    private static void addIntentExtras(String screenshotId, Intent intent, String actionType,
            boolean smartActionsEnabled) {
        intent
                .putExtra(GlobalScreenshot.EXTRA_ACTION_TYPE, actionType)
                .putExtra(GlobalScreenshot.EXTRA_ID, screenshotId)
                .putExtra(GlobalScreenshot.EXTRA_SMART_ACTIONS_ENABLED, smartActionsEnabled);
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

    @Override
    protected Void doInBackground(Void... paramsUnused) {
        if (isCancelled()) {
            return null;
        }

        // By default, AsyncTask sets the worker thread to have background thread priority,
        // so bump it back up so that we save a little quicker.
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

        Context context = mParams.context;
        Bitmap image = mParams.image;
        Resources r = context.getResources();

        try {
            CompletableFuture<List<Notification.Action>> smartActionsFuture =
                    GlobalScreenshot.getSmartActionsFuture(mScreenshotId, image,
                            mSmartActionsProvider, mSmartActionsEnabled, isManagedProfile(context));

            // Save the screenshot to the MediaStore
            final MediaStore.PendingParams params = new MediaStore.PendingParams(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mImageFileName, "image/png");
            params.setRelativePath(Environment.DIRECTORY_PICTURES + File.separator
                    + Environment.DIRECTORY_SCREENSHOTS);

            final Uri uri = MediaStore.createPending(context, params);
            final MediaStore.PendingSession session = MediaStore.openPending(context, uri);
            try {
                // First, write the actual data for our screenshot
                try (OutputStream out = session.openOutputStream()) {
                    if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw new IOException("Failed to compress");
                    }
                }

                // Next, write metadata to help index the screenshot
                try (ParcelFileDescriptor pfd = session.open()) {
                    final ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());

                    exif.setAttribute(ExifInterface.TAG_SOFTWARE,
                            "Android " + Build.DISPLAY);

                    exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH,
                            Integer.toString(image.getWidth()));
                    exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH,
                            Integer.toString(image.getHeight()));

                    final ZonedDateTime time = ZonedDateTime.ofInstant(
                            Instant.ofEpochMilli(mImageTime), ZoneId.systemDefault());
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").format(time));
                    exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                            DateTimeFormatter.ofPattern("SSS").format(time));

                    if (Objects.equals(time.getOffset(), ZoneOffset.UTC)) {
                        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00");
                    } else {
                        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                                DateTimeFormatter.ofPattern("XXX").format(time));
                    }

                    exif.saveAttributes();
                }
                session.publish();
            } catch (Exception e) {
                session.abandon();
                throw e;
            } finally {
                IoUtils.closeQuietly(session);
            }

            populateNotificationActions(context, r, uri, smartActionsFuture, mNotificationBuilder);

            mParams.imageUri = uri;
            mParams.image = null;
            mParams.errorMsgResId = 0;
        } catch (Exception e) {
            // IOException/UnsupportedOperationException may be thrown if external storage is
            // not mounted
            Slog.e(TAG, "unable to save screenshot", e);
            mParams.clearImage();
            mParams.errorMsgResId = R.string.screenshot_failed_to_save_text;
        }

        // Recycle the bitmap data
        if (image != null) {
            image.recycle();
        }

        return null;
    }

    @VisibleForTesting
    void populateNotificationActions(Context context, Resources r, Uri uri,
            CompletableFuture<List<Notification.Action>> smartActionsFuture,
            Notification.Builder notificationBuilder) {
        // Note: Both the share and edit actions are proxied through ActionProxyReceiver in
        // order to do some common work like dismissing the keyguard and sending
        // closeSystemWindows

        // Create a share intent, this will always go through the chooser activity first
        // which should not trigger auto-enter PiP
        String subjectDate = DateFormat.getDateTimeInstance().format(new Date(mImageTime));
        String subject = String.format(SCREENSHOT_SHARE_SUBJECT_TEMPLATE, subjectDate);
        Intent sharingIntent = new Intent(Intent.ACTION_SEND);
        sharingIntent.setType("image/png");
        sharingIntent.putExtra(Intent.EXTRA_STREAM, uri);
        // Include URI in ClipData also, so that grantPermission picks it up.
        // We don't use setData here because some apps interpret this as "to:".
        ClipData clipdata = new ClipData(new ClipDescription("content",
                new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN}),
                new ClipData.Item(uri));
        sharingIntent.setClipData(clipdata);
        sharingIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
        sharingIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Make sure pending intents for the system user are still unique across users
        // by setting the (otherwise unused) request code to the current user id.
        int requestCode = context.getUserId();

        PendingIntent chooserAction = PendingIntent.getBroadcast(context, requestCode,
                new Intent(context, GlobalScreenshot.TargetChosenReceiver.class),
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);
        Intent sharingChooserIntent = Intent.createChooser(sharingIntent, null,
                chooserAction.getIntentSender())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Create a share action for the notification
        PendingIntent shareAction = PendingIntent.getBroadcastAsUser(context, requestCode,
                new Intent(context, GlobalScreenshot.ActionProxyReceiver.class)
                        .putExtra(GlobalScreenshot.EXTRA_ACTION_INTENT, sharingChooserIntent)
                        .putExtra(GlobalScreenshot.EXTRA_DISALLOW_ENTER_PIP, true)
                        .putExtra(GlobalScreenshot.EXTRA_ID, mScreenshotId)
                        .putExtra(GlobalScreenshot.EXTRA_SMART_ACTIONS_ENABLED,
                                mSmartActionsEnabled)
                        .setAction(Intent.ACTION_SEND),
                PendingIntent.FLAG_CANCEL_CURRENT, UserHandle.SYSTEM);
        Notification.Action.Builder shareActionBuilder = new Notification.Action.Builder(
                R.drawable.ic_screenshot_share,
                r.getString(com.android.internal.R.string.share), shareAction);
        notificationBuilder.addAction(shareActionBuilder.build());

        // Create an edit intent, if a specific package is provided as the editor, then
        // launch that directly
        String editorPackage = context.getString(R.string.config_screenshotEditor);
        Intent editIntent = new Intent(Intent.ACTION_EDIT);
        if (!TextUtils.isEmpty(editorPackage)) {
            editIntent.setComponent(ComponentName.unflattenFromString(editorPackage));
        }
        editIntent.setType("image/png");
        editIntent.setData(uri);
        editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        editIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        // Create a edit action
        PendingIntent editAction = PendingIntent.getBroadcastAsUser(context, requestCode,
                new Intent(context, GlobalScreenshot.ActionProxyReceiver.class)
                        .putExtra(GlobalScreenshot.EXTRA_ACTION_INTENT, editIntent)
                        .putExtra(GlobalScreenshot.EXTRA_CANCEL_NOTIFICATION,
                                editIntent.getComponent() != null)
                        .putExtra(GlobalScreenshot.EXTRA_ID, mScreenshotId)
                        .putExtra(GlobalScreenshot.EXTRA_SMART_ACTIONS_ENABLED,
                                mSmartActionsEnabled)
                        .setAction(Intent.ACTION_EDIT),
                PendingIntent.FLAG_CANCEL_CURRENT, UserHandle.SYSTEM);
        Notification.Action.Builder editActionBuilder = new Notification.Action.Builder(
                R.drawable.ic_screenshot_edit,
                r.getString(com.android.internal.R.string.screenshot_edit), editAction);
        notificationBuilder.addAction(editActionBuilder.build());
        if (mParams.mActionsReadyListener != null) {
            mParams.mActionsReadyListener.onActionsReady(shareAction, editAction);
        }

        // Create a delete action for the notification
        PendingIntent deleteAction = PendingIntent.getBroadcast(context, requestCode,
                new Intent(context, GlobalScreenshot.DeleteScreenshotReceiver.class)
                        .putExtra(GlobalScreenshot.SCREENSHOT_URI_ID, uri.toString())
                        .putExtra(GlobalScreenshot.EXTRA_ID, mScreenshotId)
                        .putExtra(GlobalScreenshot.EXTRA_SMART_ACTIONS_ENABLED,
                                mSmartActionsEnabled),
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);
        Notification.Action.Builder deleteActionBuilder = new Notification.Action.Builder(
                R.drawable.ic_screenshot_delete,
                r.getString(com.android.internal.R.string.delete), deleteAction);
        notificationBuilder.addAction(deleteActionBuilder.build());

        if (mSmartActionsEnabled) {
            int timeoutMs = DeviceConfig.getInt(DeviceConfig.NAMESPACE_SYSTEMUI,
                    SystemUiDeviceConfigFlags
                            .SCREENSHOT_NOTIFICATION_SMART_ACTIONS_TIMEOUT_MS,
                    1000);
            List<Notification.Action> smartActions = GlobalScreenshot.getSmartActions(mScreenshotId,
                    smartActionsFuture, timeoutMs, mSmartActionsProvider);
            smartActions = buildSmartActions(smartActions, context);
            for (Notification.Action action : smartActions) {
                notificationBuilder.addAction(action);
            }
        }
    }

    @Override
    protected void onPostExecute(Void params) {
        if (mParams.errorMsgResId != 0) {
            // Show a message that we've failed to save the image to disk
            GlobalScreenshot.notifyScreenshotError(mParams.context, mNotificationManager,
                    mParams.errorMsgResId);
        } else {
            if (mParams.mActionsReadyListener != null) {
                // Cancel the "saving screenshot" notification
                mNotificationManager.cancel(
                        SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT);
            } else {
                // Show the final notification to indicate screenshot saved
                Context context = mParams.context;
                Resources r = context.getResources();

                // Create the intent to show the screenshot in gallery
                Intent launchIntent = new Intent(Intent.ACTION_VIEW);
                launchIntent.setDataAndType(mParams.imageUri, "image/png");
                launchIntent.setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

                final long now = System.currentTimeMillis();

                // Update the text and the icon for the existing notification
                mPublicNotificationBuilder
                        .setContentTitle(r.getString(R.string.screenshot_saved_title))
                        .setContentText(r.getString(R.string.screenshot_saved_text))
                        .setContentIntent(
                                PendingIntent.getActivity(mParams.context, 0, launchIntent, 0))
                        .setWhen(now)
                        .setAutoCancel(true)
                        .setColor(context.getColor(
                                com.android.internal.R.color.system_notification_accent_color));
                mNotificationBuilder
                        .setContentTitle(r.getString(R.string.screenshot_saved_title))
                        .setContentText(r.getString(R.string.screenshot_saved_text))
                        .setContentIntent(PendingIntent.getActivity(mParams.context, 0,
                                launchIntent, 0))
                        .setWhen(now)
                        .setAutoCancel(true)
                        .setColor(context.getColor(
                                com.android.internal.R.color.system_notification_accent_color))
                        .setPublicVersion(mPublicNotificationBuilder.build())
                        .setFlag(Notification.FLAG_NO_CLEAR, false);

                mNotificationManager.notify(SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT,
                        mNotificationBuilder.build());
            }
        }
        mParams.finisher.accept(mParams.imageUri);
        mParams.clearContext();
    }

    @Override
    protected void onCancelled(Void params) {
        // If we are cancelled while the task is running in the background, we may get null
        // params. The finisher is expected to always be called back, so just use the baked-in
        // params from the ctor in any case.
        mParams.finisher.accept(null);
        mParams.clearImage();
        mParams.clearContext();

        // Cancel the posted notification
        mNotificationManager.cancel(SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT);
    }
}
