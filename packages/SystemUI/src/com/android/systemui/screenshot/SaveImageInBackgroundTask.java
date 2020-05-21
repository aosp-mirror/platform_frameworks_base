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
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;

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

    private final Context mContext;
    private final GlobalScreenshot.SaveImageInBackgroundData mParams;
    private final GlobalScreenshot.SavedImageData mImageData;
    private final String mImageFileName;
    private final long mImageTime;
    private final ScreenshotNotificationSmartActionsProvider mSmartActionsProvider;
    private final String mScreenshotId;
    private final boolean mSmartActionsEnabled;
    private final boolean mCreateDeleteAction;
    private final Random mRandom = new Random();

    SaveImageInBackgroundTask(Context context, GlobalScreenshot.SaveImageInBackgroundData data) {
        mContext = context;
        mImageData = new GlobalScreenshot.SavedImageData();

        // Prepare all the output metadata
        mParams = data;
        mImageTime = System.currentTimeMillis();
        String imageDate = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(mImageTime));
        mImageFileName = String.format(SCREENSHOT_FILE_NAME_TEMPLATE, imageDate);
        mScreenshotId = String.format(SCREENSHOT_ID_TEMPLATE, UUID.randomUUID());

        mCreateDeleteAction = data.createDeleteAction;

        // Initialize screenshot notification smart actions provider.
        mSmartActionsEnabled = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ENABLE_SCREENSHOT_NOTIFICATION_SMART_ACTIONS, true);
        if (mSmartActionsEnabled) {
            mSmartActionsProvider =
                    SystemUIFactory.getInstance()
                            .createScreenshotNotificationSmartActionsProvider(
                                    context, THREAD_POOL_EXECUTOR, new Handler());
        } else {
            // If smart actions is not enabled use empty implementation.
            mSmartActionsProvider = new ScreenshotNotificationSmartActionsProvider();
        }
    }

    @Override
    protected Void doInBackground(Void... paramsUnused) {
        if (isCancelled()) {
            return null;
        }

        ContentResolver resolver = mContext.getContentResolver();
        Bitmap image = mParams.image;
        Resources r = mContext.getResources();

        try {
            // Save the screenshot to the MediaStore
            final ContentValues values = new ContentValues();
            values.put(MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES
                    + File.separator + Environment.DIRECTORY_SCREENSHOTS);
            values.put(MediaColumns.DISPLAY_NAME, mImageFileName);
            values.put(MediaColumns.MIME_TYPE, "image/png");
            values.put(MediaColumns.DATE_ADDED, mImageTime / 1000);
            values.put(MediaColumns.DATE_MODIFIED, mImageTime / 1000);
            values.put(MediaColumns.DATE_EXPIRES, (mImageTime + DateUtils.DAY_IN_MILLIS) / 1000);
            values.put(MediaColumns.IS_PENDING, 1);

            final Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            CompletableFuture<List<Notification.Action>> smartActionsFuture =
                    ScreenshotSmartActions.getSmartActionsFuture(
                            mScreenshotId, uri, image, mSmartActionsProvider,
                            mSmartActionsEnabled, isManagedProfile(mContext));

            try {
                // First, write the actual data for our screenshot
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw new IOException("Failed to compress");
                    }
                }

                // Next, write metadata to help index the screenshot
                try (ParcelFileDescriptor pfd = resolver.openFile(uri, "rw", null)) {
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

                // Everything went well above, publish it!
                values.clear();
                values.put(MediaColumns.IS_PENDING, 0);
                values.putNull(MediaColumns.DATE_EXPIRES);
                resolver.update(uri, values, null, null);
            } catch (Exception e) {
                resolver.delete(uri, null);
                throw e;
            }

            List<Notification.Action> smartActions = new ArrayList<>();
            if (mSmartActionsEnabled) {
                int timeoutMs = DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_SYSTEMUI,
                        SystemUiDeviceConfigFlags.SCREENSHOT_NOTIFICATION_SMART_ACTIONS_TIMEOUT_MS,
                        1000);
                smartActions.addAll(buildSmartActions(
                        ScreenshotSmartActions.getSmartActions(
                                mScreenshotId, smartActionsFuture, timeoutMs,
                                mSmartActionsProvider),
                        mContext));
            }

            mImageData.uri = uri;
            mImageData.smartActions = smartActions;
            mImageData.shareAction = createShareAction(mContext, mContext.getResources(), uri);
            mImageData.editAction = createEditAction(mContext, mContext.getResources(), uri);
            mImageData.deleteAction = createDeleteAction(mContext, mContext.getResources(), uri);

            mParams.mActionsReadyListener.onActionsReady(mImageData);
            mParams.finisher.accept(mImageData.uri);
            mParams.image = null;
            mParams.errorMsgResId = 0;
        } catch (Exception e) {
            // IOException/UnsupportedOperationException may be thrown if external storage is
            // not mounted
            Slog.e(TAG, "unable to save screenshot", e);
            mParams.clearImage();
            mParams.errorMsgResId = R.string.screenshot_failed_to_save_text;
            mImageData.reset();
            mParams.mActionsReadyListener.onActionsReady(mImageData);
            mParams.finisher.accept(null);
        }

        return null;
    }

    /**
     * Update the listener run when the saving task completes. Used to avoid showing UI for the
     * first screenshot when a second one is taken.
     */
    void setActionsReadyListener(GlobalScreenshot.ActionsReadyListener listener) {
        mParams.mActionsReadyListener = listener;
    }

    @Override
    protected void onCancelled(Void params) {
        // If we are cancelled while the task is running in the background, we may get null
        // params. The finisher is expected to always be called back, so just use the baked-in
        // params from the ctor in any case.
        mImageData.reset();
        mParams.mActionsReadyListener.onActionsReady(mImageData);
        mParams.finisher.accept(null);
        mParams.clearImage();
    }

    @VisibleForTesting
    Notification.Action createShareAction(Context context, Resources r, Uri uri) {
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
        Intent sharingChooserIntent =
                Intent.createChooser(sharingIntent, null, chooserAction.getIntentSender())
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
                        .setAction(Intent.ACTION_SEND)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_CANCEL_CURRENT, UserHandle.SYSTEM);

        Notification.Action.Builder shareActionBuilder = new Notification.Action.Builder(
                Icon.createWithResource(r, R.drawable.ic_screenshot_share),
                r.getString(com.android.internal.R.string.share), shareAction);

        return shareActionBuilder.build();
    }

    @VisibleForTesting
    Notification.Action createEditAction(Context context, Resources r, Uri uri) {
        // Note: Both the share and edit actions are proxied through ActionProxyReceiver in
        // order to do some common work like dismissing the keyguard and sending
        // closeSystemWindows

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
        editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        // Make sure pending intents for the system user are still unique across users
        // by setting the (otherwise unused) request code to the current user id.
        int requestCode = mContext.getUserId();

        // Create a edit action
        PendingIntent editAction = PendingIntent.getBroadcast(context, requestCode,
                new Intent(context, GlobalScreenshot.ActionProxyReceiver.class)
                        .putExtra(GlobalScreenshot.EXTRA_ACTION_INTENT, editIntent)
                        .putExtra(GlobalScreenshot.EXTRA_CANCEL_NOTIFICATION,
                                editIntent.getComponent() != null)
                        .putExtra(GlobalScreenshot.EXTRA_ID, mScreenshotId)
                        .putExtra(GlobalScreenshot.EXTRA_SMART_ACTIONS_ENABLED,
                                mSmartActionsEnabled)
                        .setAction(Intent.ACTION_EDIT)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_CANCEL_CURRENT);
        Notification.Action.Builder editActionBuilder = new Notification.Action.Builder(
                Icon.createWithResource(r, R.drawable.ic_screenshot_edit),
                r.getString(com.android.internal.R.string.screenshot_edit), editAction);

        return editActionBuilder.build();
    }

    @VisibleForTesting
    Notification.Action createDeleteAction(Context context, Resources r, Uri uri) {
        // Make sure pending intents for the system user are still unique across users
        // by setting the (otherwise unused) request code to the current user id.
        int requestCode = mContext.getUserId();

        // Create a delete action for the notification
        PendingIntent deleteAction = PendingIntent.getBroadcast(context, requestCode,
                new Intent(context, GlobalScreenshot.DeleteScreenshotReceiver.class)
                        .putExtra(GlobalScreenshot.SCREENSHOT_URI_ID, uri.toString())
                        .putExtra(GlobalScreenshot.EXTRA_ID, mScreenshotId)
                        .putExtra(GlobalScreenshot.EXTRA_SMART_ACTIONS_ENABLED,
                                mSmartActionsEnabled)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);
        Notification.Action.Builder deleteActionBuilder = new Notification.Action.Builder(
                Icon.createWithResource(r, R.drawable.ic_screenshot_delete),
                r.getString(com.android.internal.R.string.delete), deleteAction);

        return deleteActionBuilder.build();
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
            Intent intent = new Intent(context, GlobalScreenshot.SmartActionsReceiver.class)
                    .putExtra(GlobalScreenshot.EXTRA_ACTION_INTENT, action.actionIntent)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
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


}
