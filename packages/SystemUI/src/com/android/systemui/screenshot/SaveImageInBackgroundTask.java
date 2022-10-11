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

import static com.android.systemui.screenshot.LogConfig.DEBUG_ACTIONS;
import static com.android.systemui.screenshot.LogConfig.DEBUG_CALLBACK;
import static com.android.systemui.screenshot.LogConfig.DEBUG_STORAGE;
import static com.android.systemui.screenshot.LogConfig.logTag;
import static com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider.ScreenshotSmartActionType.QUICK_SHARE_ACTION;
import static com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider.ScreenshotSmartActionType.REGULAR_SMART_ACTIONS;

import android.app.ActivityTaskManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.R;
import com.android.systemui.screenshot.ScreenshotController.SavedImageData.ActionTransition;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * An AsyncTask that saves an image to the media store in the background.
 */
class SaveImageInBackgroundTask extends AsyncTask<Void, Void, Void> {
    private static final String TAG = logTag(SaveImageInBackgroundTask.class);

    private static final String SCREENSHOT_ID_TEMPLATE = "Screenshot_%s";
    private static final String SCREENSHOT_SHARE_SUBJECT_TEMPLATE = "Screenshot (%s)";

    private final Context mContext;
    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final ScreenshotController.SaveImageInBackgroundData mParams;
    private final ScreenshotController.SavedImageData mImageData;
    private final ScreenshotController.QuickShareData mQuickShareData;

    private final ScreenshotNotificationSmartActionsProvider mSmartActionsProvider;
    private String mScreenshotId;
    private final boolean mSmartActionsEnabled;
    private final Random mRandom = new Random();
    private final Supplier<ActionTransition> mSharedElementTransition;
    private final ImageExporter mImageExporter;
    private long mImageTime;

    SaveImageInBackgroundTask(Context context, ImageExporter exporter,
            ScreenshotSmartActions screenshotSmartActions,
            ScreenshotController.SaveImageInBackgroundData data,
            Supplier<ActionTransition> sharedElementTransition,
            ScreenshotNotificationSmartActionsProvider
                    screenshotNotificationSmartActionsProvider
    ) {
        mContext = context;
        mScreenshotSmartActions = screenshotSmartActions;
        mImageData = new ScreenshotController.SavedImageData();
        mQuickShareData = new ScreenshotController.QuickShareData();
        mSharedElementTransition = sharedElementTransition;
        mImageExporter = exporter;

        // Prepare all the output metadata
        mParams = data;

        // Initialize screenshot notification smart actions provider.
        mSmartActionsEnabled = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ENABLE_SCREENSHOT_NOTIFICATION_SMART_ACTIONS, true);
        mSmartActionsProvider = screenshotNotificationSmartActionsProvider;
    }

    @Override
    protected Void doInBackground(Void... paramsUnused) {
        if (isCancelled()) {
            if (DEBUG_STORAGE) {
                Log.d(TAG, "cancelled! returning null");
            }
            return null;
        }
        // TODO: move to constructor / from ScreenshotRequest
        final UUID requestId = UUID.randomUUID();
        final UserHandle user = getUserHandleOfForegroundApplication(mContext);

        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

        Bitmap image = mParams.image;
        mScreenshotId = String.format(SCREENSHOT_ID_TEMPLATE, requestId);
        try {
            if (mSmartActionsEnabled && mParams.mQuickShareActionsReadyListener != null) {
                // Since Quick Share target recommendation does not rely on image URL, it is
                // queried and surfaced before image compress/export. Action intent would not be
                // used, because it does not contain image URL.
                queryQuickShareAction(image, user);
            }

            // Call synchronously here since already on a background thread.
            ListenableFuture<ImageExporter.Result> future =
                    mImageExporter.export(Runnable::run, requestId, image);
            ImageExporter.Result result = future.get();
            final Uri uri = result.uri;
            mImageTime = result.timestamp;

            CompletableFuture<List<Notification.Action>> smartActionsFuture =
                    mScreenshotSmartActions.getSmartActionsFuture(
                            mScreenshotId, uri, image, mSmartActionsProvider, REGULAR_SMART_ACTIONS,
                            mSmartActionsEnabled, user);

            List<Notification.Action> smartActions = new ArrayList<>();
            if (mSmartActionsEnabled) {
                int timeoutMs = DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_SYSTEMUI,
                        SystemUiDeviceConfigFlags.SCREENSHOT_NOTIFICATION_SMART_ACTIONS_TIMEOUT_MS,
                        1000);
                smartActions.addAll(buildSmartActions(
                        mScreenshotSmartActions.getSmartActions(
                                mScreenshotId, smartActionsFuture, timeoutMs,
                                mSmartActionsProvider, REGULAR_SMART_ACTIONS),
                        mContext));
            }

            mImageData.uri = uri;
            mImageData.smartActions = smartActions;
            mImageData.shareTransition = createShareAction(mContext, mContext.getResources(), uri);
            mImageData.editTransition = createEditAction(mContext, mContext.getResources(), uri);
            mImageData.deleteAction = createDeleteAction(mContext, mContext.getResources(), uri);
            mImageData.quickShareAction = createQuickShareAction(mContext,
                    mQuickShareData.quickShareAction, uri);

            mParams.mActionsReadyListener.onActionsReady(mImageData);
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "finished background processing, Calling (Consumer<Uri>) "
                        + "finisher.accept(\"" + mImageData.uri + "\"");
            }
            mParams.finisher.accept(mImageData.uri);
            mParams.image = null;
        } catch (Exception e) {
            // IOException/UnsupportedOperationException may be thrown if external storage is
            // not mounted
            if (DEBUG_STORAGE) {
                Log.d(TAG, "Failed to store screenshot", e);
            }
            mParams.clearImage();
            mImageData.reset();
            mQuickShareData.reset();
            mParams.mActionsReadyListener.onActionsReady(mImageData);
            if (DEBUG_CALLBACK) {
                Log.d(TAG, "Calling (Consumer<Uri>) finisher.accept(null)");
            }
            mParams.finisher.accept(null);
        }

        return null;
    }

    /**
     * Update the listener run when the saving task completes. Used to avoid showing UI for the
     * first screenshot when a second one is taken.
     */
    void setActionsReadyListener(ScreenshotController.ActionsReadyListener listener) {
        mParams.mActionsReadyListener = listener;
    }

    @Override
    protected void onCancelled(Void params) {
        // If we are cancelled while the task is running in the background, we may get null
        // params. The finisher is expected to always be called back, so just use the baked-in
        // params from the ctor in any case.
        mImageData.reset();
        mQuickShareData.reset();
        mParams.mActionsReadyListener.onActionsReady(mImageData);
        if (DEBUG_CALLBACK) {
            Log.d(TAG, "onCancelled, calling (Consumer<Uri>) finisher.accept(null)");
        }
        mParams.finisher.accept(null);
        mParams.clearImage();
    }

    /**
     * Assumes that the action intent is sent immediately after being supplied.
     */
    @VisibleForTesting
    Supplier<ActionTransition> createShareAction(Context context, Resources r, Uri uri) {
        return () -> {
            ActionTransition transition = mSharedElementTransition.get();

            // Note: Both the share and edit actions are proxied through ActionProxyReceiver in
            // order to do some common work like dismissing the keyguard and sending
            // closeSystemWindows

            // Create a share intent, this will always go through the chooser activity first
            // which should not trigger auto-enter PiP
            String subjectDate = DateFormat.getDateTimeInstance().format(new Date(mImageTime));
            String subject = String.format(SCREENSHOT_SHARE_SUBJECT_TEMPLATE, subjectDate);
            Intent sharingIntent = new Intent(Intent.ACTION_SEND);
            sharingIntent.setDataAndType(uri, "image/png");
            sharingIntent.putExtra(Intent.EXTRA_STREAM, uri);
            // Include URI in ClipData also, so that grantPermission picks it up.
            // We don't use setData here because some apps interpret this as "to:".
            ClipData clipdata = new ClipData(new ClipDescription("content",
                    new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN}),
                    new ClipData.Item(uri));
            sharingIntent.setClipData(clipdata);
            sharingIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
            sharingIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);


            // Make sure pending intents for the system user are still unique across users
            // by setting the (otherwise unused) request code to the current user id.
            int requestCode = context.getUserId();

            Intent sharingChooserIntent = Intent.createChooser(sharingIntent, null)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);


            // cancel current pending intent (if any) since clipData isn't used for matching
            PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                    context, 0, sharingChooserIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    transition.bundle, UserHandle.CURRENT);

            // Create a share action for the notification
            PendingIntent shareAction = PendingIntent.getBroadcastAsUser(context, requestCode,
                    new Intent(context, ActionProxyReceiver.class)
                            .putExtra(ScreenshotController.EXTRA_ACTION_INTENT, pendingIntent)
                            .putExtra(ScreenshotController.EXTRA_DISALLOW_ENTER_PIP, true)
                            .putExtra(ScreenshotController.EXTRA_ID, mScreenshotId)
                            .putExtra(ScreenshotController.EXTRA_SMART_ACTIONS_ENABLED,
                                    mSmartActionsEnabled)
                            .setAction(Intent.ACTION_SEND)
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    UserHandle.SYSTEM);

            Notification.Action.Builder shareActionBuilder = new Notification.Action.Builder(
                    Icon.createWithResource(r, R.drawable.ic_screenshot_share),
                    r.getString(com.android.internal.R.string.share), shareAction);

            transition.action = shareActionBuilder.build();
            return transition;
        };
    }

    @VisibleForTesting
    Supplier<ActionTransition> createEditAction(Context context, Resources r, Uri uri) {
        return () -> {
            ActionTransition transition = mSharedElementTransition.get();
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
            editIntent.setDataAndType(uri, "image/png");
            editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            editIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                    context, 0, editIntent, PendingIntent.FLAG_IMMUTABLE,
                    transition.bundle, UserHandle.CURRENT);

            // Make sure pending intents for the system user are still unique across users
            // by setting the (otherwise unused) request code to the current user id.
            int requestCode = mContext.getUserId();

            // Create a edit action
            PendingIntent editAction = PendingIntent.getBroadcastAsUser(context, requestCode,
                    new Intent(context, ActionProxyReceiver.class)
                            .putExtra(ScreenshotController.EXTRA_ACTION_INTENT, pendingIntent)
                            .putExtra(ScreenshotController.EXTRA_ID, mScreenshotId)
                            .putExtra(ScreenshotController.EXTRA_SMART_ACTIONS_ENABLED,
                                    mSmartActionsEnabled)
                            .putExtra(ScreenshotController.EXTRA_OVERRIDE_TRANSITION, true)
                            .setAction(Intent.ACTION_EDIT)
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    UserHandle.SYSTEM);
            Notification.Action.Builder editActionBuilder = new Notification.Action.Builder(
                    Icon.createWithResource(r, R.drawable.ic_screenshot_edit),
                    r.getString(com.android.internal.R.string.screenshot_edit), editAction);

            transition.action = editActionBuilder.build();
            return transition;
        };
    }

    @VisibleForTesting
    Notification.Action createDeleteAction(Context context, Resources r, Uri uri) {
        // Make sure pending intents for the system user are still unique across users
        // by setting the (otherwise unused) request code to the current user id.
        int requestCode = mContext.getUserId();

        // Create a delete action for the notification
        PendingIntent deleteAction = PendingIntent.getBroadcast(context, requestCode,
                new Intent(context, DeleteScreenshotReceiver.class)
                        .putExtra(ScreenshotController.SCREENSHOT_URI_ID, uri.toString())
                        .putExtra(ScreenshotController.EXTRA_ID, mScreenshotId)
                        .putExtra(ScreenshotController.EXTRA_SMART_ACTIONS_ENABLED,
                                mSmartActionsEnabled)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_CANCEL_CURRENT
                        | PendingIntent.FLAG_ONE_SHOT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action.Builder deleteActionBuilder = new Notification.Action.Builder(
                Icon.createWithResource(r, R.drawable.ic_screenshot_delete),
                r.getString(com.android.internal.R.string.delete), deleteAction);

        return deleteActionBuilder.build();
    }

    private UserHandle getUserHandleOfForegroundApplication(Context context) {
        UserManager manager = UserManager.get(context);
        int result;
        // This logic matches
        // com.android.systemui.statusbar.phone.PhoneStatusBarPolicy#updateManagedProfile
        try {
            result = ActivityTaskManager.getService().getLastResumedActivityUserId();
        } catch (RemoteException e) {
            if (DEBUG_ACTIONS) {
                Log.d(TAG, "Failed to get UserHandle of foreground app: ", e);
            }
            result = context.getUserId();
        }
        UserInfo userInfo = manager.getUserInfo(result);
        return userInfo.getUserHandle();
    }

    private List<Notification.Action> buildSmartActions(
            List<Notification.Action> actions, Context context) {
        List<Notification.Action> broadcastActions = new ArrayList<>();
        for (Notification.Action action : actions) {
            // Proxy smart actions through {@link SmartActionsReceiver} for logging smart actions.
            Bundle extras = action.getExtras();
            String actionType = extras.getString(
                    ScreenshotNotificationSmartActionsProvider.ACTION_TYPE,
                    ScreenshotNotificationSmartActionsProvider.DEFAULT_ACTION_TYPE);
            Intent intent = new Intent(context, SmartActionsReceiver.class)
                    .putExtra(ScreenshotController.EXTRA_ACTION_INTENT, action.actionIntent)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            addIntentExtras(mScreenshotId, intent, actionType, mSmartActionsEnabled);
            PendingIntent broadcastIntent = PendingIntent.getBroadcast(context,
                    mRandom.nextInt(),
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            broadcastActions.add(new Notification.Action.Builder(action.getIcon(), action.title,
                    broadcastIntent).setContextual(true).addExtras(extras).build());
        }
        return broadcastActions;
    }

    private static void addIntentExtras(String screenshotId, Intent intent, String actionType,
            boolean smartActionsEnabled) {
        intent
                .putExtra(ScreenshotController.EXTRA_ACTION_TYPE, actionType)
                .putExtra(ScreenshotController.EXTRA_ID, screenshotId)
                .putExtra(ScreenshotController.EXTRA_SMART_ACTIONS_ENABLED, smartActionsEnabled);
    }

    /**
     * Populate image uri into intent of Quick Share action.
     */
    @VisibleForTesting
    private Notification.Action createQuickShareAction(Context context, Notification.Action action,
            Uri uri) {
        if (action == null) {
            return null;
        }
        // Populate image URI into Quick Share chip intent
        Intent sharingIntent = action.actionIntent.getIntent();
        sharingIntent.setType("image/png");
        sharingIntent.putExtra(Intent.EXTRA_STREAM, uri);
        String subjectDate = DateFormat.getDateTimeInstance().format(new Date(mImageTime));
        String subject = String.format(SCREENSHOT_SHARE_SUBJECT_TEMPLATE, subjectDate);
        sharingIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
        // Include URI in ClipData also, so that grantPermission picks it up.
        // We don't use setData here because some apps interpret this as "to:".
        ClipData clipdata = new ClipData(new ClipDescription("content",
                new String[]{"image/png"}),
                new ClipData.Item(uri));
        sharingIntent.setClipData(clipdata);
        sharingIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent updatedPendingIntent = PendingIntent.getActivity(
                context, 0, sharingIntent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Proxy smart actions through {@link SmartActionsReceiver} for logging smart actions.
        Bundle extras = action.getExtras();
        String actionType = extras.getString(
                ScreenshotNotificationSmartActionsProvider.ACTION_TYPE,
                ScreenshotNotificationSmartActionsProvider.DEFAULT_ACTION_TYPE);
        Intent intent = new Intent(context, SmartActionsReceiver.class)
                .putExtra(ScreenshotController.EXTRA_ACTION_INTENT, updatedPendingIntent)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        addIntentExtras(mScreenshotId, intent, actionType, mSmartActionsEnabled);
        PendingIntent broadcastIntent = PendingIntent.getBroadcast(context,
                mRandom.nextInt(),
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(action.getIcon(), action.title,
                broadcastIntent).setContextual(true).addExtras(extras).build();
    }

    /**
     * Query and surface Quick Share chip if it is available. Action intent would not be used,
     * because it does not contain image URL which would be populated in {@link
     * #createQuickShareAction(Context, Notification.Action, Uri)}
     */
    private void queryQuickShareAction(Bitmap image, UserHandle user) {
        CompletableFuture<List<Notification.Action>> quickShareActionsFuture =
                mScreenshotSmartActions.getSmartActionsFuture(
                        mScreenshotId, null, image, mSmartActionsProvider,
                        QUICK_SHARE_ACTION,
                        mSmartActionsEnabled, user);
        int timeoutMs = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.SCREENSHOT_NOTIFICATION_QUICK_SHARE_ACTIONS_TIMEOUT_MS,
                500);
        List<Notification.Action> quickShareActions =
                mScreenshotSmartActions.getSmartActions(
                        mScreenshotId, quickShareActionsFuture, timeoutMs,
                        mSmartActionsProvider, QUICK_SHARE_ACTION);
        if (!quickShareActions.isEmpty()) {
            mQuickShareData.quickShareAction = quickShareActions.get(0);
            mParams.mQuickShareActionsReadyListener.onActionsReady(mQuickShareData);
        }
    }
}
