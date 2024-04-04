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

import static com.android.systemui.screenshot.LogConfig.DEBUG_CALLBACK;
import static com.android.systemui.screenshot.LogConfig.DEBUG_STORAGE;
import static com.android.systemui.screenshot.LogConfig.logTag;
import static com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider.ScreenshotSmartActionType;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.flags.FeatureFlags;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * An AsyncTask that saves an image to the media store in the background.
 */
class SaveImageInBackgroundTask extends AsyncTask<Void, Void, Void> {
    private static final String TAG = logTag(SaveImageInBackgroundTask.class);

    private static final String SCREENSHOT_ID_TEMPLATE = "Screenshot_%s";
    private static final String SCREENSHOT_SHARE_SUBJECT_TEMPLATE = "Screenshot (%s)";

    private final Context mContext;
    private FeatureFlags mFlags;
    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final ScreenshotController.SaveImageInBackgroundData mParams;
    private final ScreenshotController.SavedImageData mImageData;
    private final ScreenshotController.QuickShareData mQuickShareData;

    private final ScreenshotNotificationSmartActionsProvider mSmartActionsProvider;
    private String mScreenshotId;
    private final Random mRandom = new Random();
    private final ImageExporter mImageExporter;
    private long mImageTime;

    SaveImageInBackgroundTask(
            Context context,
            FeatureFlags flags,
            ImageExporter exporter,
            ScreenshotSmartActions screenshotSmartActions,
            ScreenshotController.SaveImageInBackgroundData data,
            ScreenshotNotificationSmartActionsProvider
                    screenshotNotificationSmartActionsProvider
    ) {
        mContext = context;
        mFlags = flags;
        mScreenshotSmartActions = screenshotSmartActions;
        mImageData = new ScreenshotController.SavedImageData();
        mQuickShareData = new ScreenshotController.QuickShareData();
        mImageExporter = exporter;

        // Prepare all the output metadata
        mParams = data;

        // Initialize screenshot notification smart actions provider.
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

        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

        Bitmap image = mParams.image;
        mScreenshotId = String.format(SCREENSHOT_ID_TEMPLATE, requestId);

        boolean savingToOtherUser = mParams.owner != Process.myUserHandle();
        // Smart actions don't yet work for cross-user saves.
        boolean smartActionsEnabled = !savingToOtherUser
                && DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ENABLE_SCREENSHOT_NOTIFICATION_SMART_ACTIONS,
                true);
        try {
            if (smartActionsEnabled && mParams.mQuickShareActionsReadyListener != null) {
                // Since Quick Share target recommendation does not rely on image URL, it is
                // queried and surfaced before image compress/export. Action intent would not be
                // used, because it does not contain image URL.
                Notification.Action quickShare =
                        queryQuickShareAction(mScreenshotId, image, mParams.owner, null);
                if (quickShare != null) {
                    mQuickShareData.quickShareAction = quickShare;
                    mParams.mQuickShareActionsReadyListener.onActionsReady(mQuickShareData);
                }
            }

            // Call synchronously here since already on a background thread.
            ListenableFuture<ImageExporter.Result> future =
                    mImageExporter.export(Runnable::run, requestId, image, mParams.owner,
                            mParams.displayId);
            ImageExporter.Result result = future.get();
            Log.d(TAG, "Saved screenshot: " + result);
            final Uri uri = result.uri;
            mImageTime = result.timestamp;

            CompletableFuture<List<Notification.Action>> smartActionsFuture =
                    mScreenshotSmartActions.getSmartActionsFuture(
                            mScreenshotId, uri, image, mSmartActionsProvider,
                            ScreenshotSmartActionType.REGULAR_SMART_ACTIONS,
                            smartActionsEnabled, mParams.owner);
            List<Notification.Action> smartActions = new ArrayList<>();
            if (smartActionsEnabled) {
                int timeoutMs = DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_SYSTEMUI,
                        SystemUiDeviceConfigFlags.SCREENSHOT_NOTIFICATION_SMART_ACTIONS_TIMEOUT_MS,
                        1000);
                smartActions.addAll(buildSmartActions(
                        mScreenshotSmartActions.getSmartActions(
                                mScreenshotId, smartActionsFuture, timeoutMs,
                                mSmartActionsProvider,
                                ScreenshotSmartActionType.REGULAR_SMART_ACTIONS),
                        mContext));
            }

            mImageData.uri = uri;
            mImageData.owner = mParams.owner;
            mImageData.smartActions = smartActions;
            mImageData.quickShareAction = createQuickShareAction(
                    mQuickShareData.quickShareAction, mScreenshotId, uri, mImageTime, image,
                    mParams.owner);
            mImageData.subject = getSubjectString(mImageTime);
            mImageData.imageTime = mImageTime;

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
            Log.d(TAG, "Failed to store screenshot", e);
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
            addIntentExtras(mScreenshotId, intent, actionType, true /* smartActionsEnabled */);
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
     * Wrap the quickshare intent and populate the fillin intent with the URI
     */
    @VisibleForTesting
    Notification.Action createQuickShareAction(
            Notification.Action quickShare, String screenshotId, Uri uri, long imageTime,
            Bitmap image, UserHandle user) {
        if (quickShare == null) {
            return null;
        } else if (quickShare.actionIntent.isImmutable()) {
            Notification.Action quickShareWithUri =
                    queryQuickShareAction(screenshotId, image, user, uri);
            if (quickShareWithUri == null
                    || !quickShareWithUri.title.toString().contentEquals(quickShare.title)) {
                return null;
            }
            quickShare = quickShareWithUri;
        }

        Intent wrappedIntent = new Intent(mContext, SmartActionsReceiver.class)
                .putExtra(ScreenshotController.EXTRA_ACTION_INTENT, quickShare.actionIntent)
                .putExtra(ScreenshotController.EXTRA_ACTION_INTENT_FILLIN,
                        createFillInIntent(uri, imageTime))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        Bundle extras = quickShare.getExtras();
        String actionType = extras.getString(
                ScreenshotNotificationSmartActionsProvider.ACTION_TYPE,
                ScreenshotNotificationSmartActionsProvider.DEFAULT_ACTION_TYPE);
        // We only query for quick share actions when smart actions are enabled, so we can assert
        // that it's true here.
        addIntentExtras(screenshotId, wrappedIntent, actionType, true /* smartActionsEnabled */);
        PendingIntent broadcastIntent =
                PendingIntent.getBroadcast(mContext, mRandom.nextInt(), wrappedIntent,
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(quickShare.getIcon(), quickShare.title,
                broadcastIntent)
                .setContextual(true)
                .addExtras(extras)
                .build();
    }

    private Intent createFillInIntent(Uri uri, long imageTime) {
        Intent fillIn = new Intent();
        fillIn.setType("image/png");
        fillIn.putExtra(Intent.EXTRA_STREAM, uri);
        fillIn.putExtra(Intent.EXTRA_SUBJECT, getSubjectString(imageTime));
        // Include URI in ClipData also, so that grantPermission picks it up.
        // We don't use setData here because some apps interpret this as "to:".
        ClipData clipData = new ClipData(
                new ClipDescription("content", new String[]{"image/png"}),
                new ClipData.Item(uri));
        fillIn.setClipData(clipData);
        fillIn.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return fillIn;
    }

    /**
     * Query and surface Quick Share chip if it is available. Action intent would not be used,
     * because it does not contain image URL which would be populated in {@link
     * #createQuickShareAction(Notification.Action, String, Uri, long, Bitmap, UserHandle)}
     */

    @VisibleForTesting
    Notification.Action queryQuickShareAction(
            String screenshotId, Bitmap image, UserHandle user, Uri uri) {
        CompletableFuture<List<Notification.Action>> quickShareActionsFuture =
                mScreenshotSmartActions.getSmartActionsFuture(
                        screenshotId, uri, image, mSmartActionsProvider,
                        ScreenshotSmartActionType.QUICK_SHARE_ACTION,
                        true /* smartActionsEnabled */, user);
        int timeoutMs = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.SCREENSHOT_NOTIFICATION_QUICK_SHARE_ACTIONS_TIMEOUT_MS,
                500);
        List<Notification.Action> quickShareActions =
                mScreenshotSmartActions.getSmartActions(
                        screenshotId, quickShareActionsFuture, timeoutMs,
                        mSmartActionsProvider,
                        ScreenshotSmartActionType.QUICK_SHARE_ACTION);
        if (!quickShareActions.isEmpty()) {
            return quickShareActions.get(0);
        }
        return null;
    }

    private static String getSubjectString(long imageTime) {
        String subjectDate = DateFormat.getDateTimeInstance().format(new Date(imageTime));
        return String.format(SCREENSHOT_SHARE_SUBJECT_TEMPLATE, subjectDate);
    }
}
