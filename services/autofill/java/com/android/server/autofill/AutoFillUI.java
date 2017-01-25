/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.autofill;

import static android.view.View.AUTO_FILL_FLAG_TYPE_SAVE;

import static com.android.server.autofill.AutoFillManagerService.DEBUG;

import android.app.Activity;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.service.autofill.AutoFillService;
import android.util.Log;
import android.util.Slog;
import android.view.autofill.AutoFillId;
import android.view.autofill.Dataset;
import android.view.autofill.FillResponse;
import android.widget.Toast;

import com.android.internal.annotations.GuardedBy;
import com.android.server.UiThread;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Handles all auto-fill related UI tasks.
 */
// TODO(b/33197203): document exactly what once the auto-fill bar is implemented
final class AutoFillUI {

    private static final String TAG = "AutoFillUI";

    private final Context mContext;

    AutoFillUI(Context context, AutoFillManagerService service, Object lock) {
        mContext = context;
        mService = service;
        mLock = lock;

        setNotificationListener();
    }

    /**
     * Displays an error message to the user.
     */
    void showError(String message) {
        // TODO(b/33197203): proper implementation
        UiThread.getHandler().runWithScissors(() -> {
            Toast.makeText(mContext, "AutoFill error: " + message, Toast.LENGTH_LONG).show();
        }, 0);
    }

    /**
     * Highlights in the {@link Activity} the fields saved by the service.
     */
    void highlightSavedFields(AutoFillId[] ids) {
        // TODO(b/33197203): proper implementation (must be handled by activity)
        UiThread.getHandler().runWithScissors(() -> {
            Toast.makeText(mContext, "AutoFill: service saved ids " + Arrays.toString(ids),
                    Toast.LENGTH_LONG).show();
        }, 0);
    }

    /**
     * Shows the options from a {@link FillResponse} so the user can pick up the proper
     * {@link Dataset} (when the response has one).
     */
    void showOptions(int userId, int callbackId, FillResponse response) {
        // TODO(b/33197203): proper implementation
        // TODO(b/33197203): make sure if removes the callback from cache
        showOptionsNotification(userId, callbackId, response);
    }

    /////////////////////////////////////////////////////////////////////////////////
    // TODO(b/33197203): temporary code using a notification to request auto-fill. //
    // Will be removed once UX decide the right way to present it to the user.     //
    /////////////////////////////////////////////////////////////////////////////////

    // TODO(b/33197203): remove from frameworks/base/core/res/AndroidManifest.xml once not used
    private static final String NOTIFICATION_AUTO_FILL_INTENT =
            "com.android.internal.autofill.action.REQUEST_AUTOFILL";

    // Extras used in the notification intents
    private static final String EXTRA_USER_ID = "user_id";
    private static final String EXTRA_NOTIFICATION_TYPE = "notification_type";
    private static final String EXTRA_CALLBACK_ID = "callback_id";
    private static final String EXTRA_FILL_RESPONSE = "fill_response";
    private static final String EXTRA_DATASET = "dataset";

    private static final String TYPE_OPTIONS = "options";
    private static final String TYPE_DELETE_CALLBACK = "delete_callback";
    private static final String TYPE_PICK_DATASET = "pick_dataset";
    private static final String TYPE_SAVE = "save";

    @GuardedBy("mLock")
    private BroadcastReceiver mNotificationReceiver;
    @GuardedBy("mLock")
    private final AutoFillManagerService mService;
    private final Object mLock;

    // Hack used to generate unique pending intents
    static int sResultCode = 0;

    private void setNotificationListener() {
        synchronized (mLock) {
            if (mNotificationReceiver == null) {
                mNotificationReceiver = new NotificationReceiver();
                mContext.registerReceiver(mNotificationReceiver,
                        new IntentFilter(NOTIFICATION_AUTO_FILL_INTENT));
            }
        }
    }

    final class NotificationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(EXTRA_USER_ID, -1);

            synchronized (mLock) {
                final AutoFillManagerServiceImpl service = mService.getServiceForUserLocked(userId);
                if (service == null) {
                    Slog.w(TAG, "no auto-fill service for user " + userId);
                    return;
                }

                final int callbackId = intent.getIntExtra(EXTRA_CALLBACK_ID, -1);
                final String type = intent.getStringExtra(EXTRA_NOTIFICATION_TYPE);
                if (type == null) {
                    Slog.wtf(TAG, "No extra " + EXTRA_NOTIFICATION_TYPE + " on intent " + intent);
                    return;
                }
                final FillResponse fillData = intent.getParcelableExtra(EXTRA_FILL_RESPONSE);
                final Dataset dataset = intent.getParcelableExtra(EXTRA_DATASET);
                final Bundle datasetArgs = dataset == null ? null : dataset.getExtras();
                final Bundle fillDataArgs = fillData == null ? null : fillData.getExtras();

                // Bundle sent on AutoFillService methods - only set if service provided a bundle
                final Bundle extras = (datasetArgs == null && fillDataArgs == null)
                        ? null : new Bundle();

                if (DEBUG) Slog.d(TAG, "Notification received: type=" + type + ", userId=" + userId
                        + ", callbackId=" + callbackId);
                switch (type) {
                    case TYPE_SAVE:
                        if (datasetArgs != null) {
                            if (DEBUG) Log.d(TAG, "filldata args on save notificataion: " +
                                    bundleToString(fillDataArgs));
                            extras.putBundle(AutoFillService.EXTRA_RESPONSE_EXTRAS, fillDataArgs);
                        }
                        if (dataset != null) {
                            if (DEBUG) Log.d(TAG, "dataset args on save notificataion: " +
                                    bundleToString(datasetArgs));
                            extras.putBundle(AutoFillService.EXTRA_DATASET_EXTRAS, datasetArgs);
                        }
                        service.requestAutoFill(null, extras, AUTO_FILL_FLAG_TYPE_SAVE);
                        break;
                    case TYPE_DELETE_CALLBACK:
                        service.removeServerCallbackLocked(callbackId);
                        break;
                    case TYPE_PICK_DATASET:
                        service.autoFillApp(callbackId, dataset);
                        // Must cancel notification because it might be comming from action
                        if (DEBUG) Log.d(TAG, "Cancelling notification");
                        NotificationManager.from(mContext).cancel(TYPE_OPTIONS, userId);

                        if (datasetArgs != null) {
                            if (DEBUG) Log.d(TAG, "adding dataset's extra_data on save intent: "
                                    + bundleToString(datasetArgs));
                            extras.putBundle(AutoFillService.EXTRA_DATASET_EXTRAS, datasetArgs);
                        }

                        // Also show notification with option to save the data
                        showSaveNotification(userId, fillData, dataset);
                        break;
                    default: {
                        Slog.w(TAG, "Unknown notification type: " + type);
                    }
                }
            }
        }
    }

    private static Intent newNotificationIntent(int userId, String type) {
        final Intent intent = new Intent(NOTIFICATION_AUTO_FILL_INTENT);
        intent.putExtra(EXTRA_USER_ID, userId);
        intent.putExtra(EXTRA_NOTIFICATION_TYPE, type);
        return intent;
    }

    private PendingIntent newPickDatasetPI(int userId, int callbackId, FillResponse response,
            Dataset dataset) {
        final int resultCode = ++ sResultCode;
        if (DEBUG) Log.d(TAG, "newPickDatasetPI: userId=" + userId + ", callback=" + callbackId
                + ", resultCode=" + resultCode);

        final Intent intent = newNotificationIntent(userId, TYPE_PICK_DATASET);
        intent.putExtra(EXTRA_CALLBACK_ID, callbackId);
        intent.putExtra(EXTRA_FILL_RESPONSE, response);
        intent.putExtra(EXTRA_DATASET, dataset);
        return PendingIntent.getBroadcast(mContext, resultCode, intent,
                PendingIntent.FLAG_ONE_SHOT);
    }

    private static String bundleToString(Bundle bundle) {
        if (bundle == null) {
            return "null";
        }
        final Set<String> keySet = bundle.keySet();
        final StringBuilder builder = new StringBuilder("[Bundle with ").append(keySet.size())
                .append(" keys:");
        for (String key : keySet) {
            final Object value = bundle.get(key);
            builder.append(' ').append(key).append('=');
            builder.append((value instanceof Object[])
                    ? Arrays.toString((Objects[]) value) : value);
        }
        return builder.append(']').toString();
    }

    /**
     * Shows a notification with the results of an auto-fill request, using notications actions
     * to emulate the auto-fill bar buttons displaying the dataset names.
     */
    private void showOptionsNotification(int userId, int callbackId, FillResponse response) {
        final long token = Binder.clearCallingIdentity();
        try {
            showOptionsNotificationAsSystem(userId, callbackId, response);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void showOptionsNotificationAsSystem(int userId, int callbackId,
            FillResponse response) {
        // Make sure server callback is removed from cache if user cancels the notification.
        final Intent deleteIntent = newNotificationIntent(userId, TYPE_DELETE_CALLBACK);
        deleteIntent.putExtra(EXTRA_CALLBACK_ID, callbackId);
        final PendingIntent deletePendingIntent = PendingIntent.getBroadcast(mContext,
                ++sResultCode, deleteIntent, PendingIntent.FLAG_ONE_SHOT);

        final String title = "AutoFill Options";

        final Notification.Builder notification = new Notification.Builder(mContext)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setOngoing(false)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setLocalOnly(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setDeleteIntent(deletePendingIntent)
                .setContentTitle(title);

        boolean autoCancel = true;
        final String subTitle;
        final List<Dataset> datasets;
        final AutoFillId[] savableIds;
        if (response != null) {
            datasets = response.getDatasets();
            savableIds = response.getSavableIds();
        } else {
            datasets = null;
            savableIds = null;
        }
        boolean showSave = false;
        if (datasets == null ) {
            subTitle = "No options to auto-fill this activity.";
        } else if (datasets.isEmpty()) {
            if (savableIds.length == 0) {
                subTitle = "No options to auto-fill this activity.";
            } else {
                subTitle = "No options to auto-fill this activity, but provider can save ids:\n"
                        + Arrays.toString(savableIds);
                showSave = true;
            }
        } else {
            final AutoFillManagerServiceImpl service = mService.getServiceForUserLocked(userId);
            if (service == null) {
                subTitle = "No auto-fill service for user " + userId;
                Slog.w(TAG, subTitle);
            } else {
                autoCancel = false;
                final int size = datasets.size();
                subTitle = "There are " + size + " option(s).\n"
                        + "Use the notification action(s) to select the proper one.";
                for (Dataset dataset : datasets) {
                    final CharSequence name = dataset.getName();
                    final PendingIntent pi = newPickDatasetPI(userId, callbackId, response, dataset);
                    notification.addAction(new Action.Builder(null, name, pi).build());
                }
            }
        }

        notification.setAutoCancel(autoCancel);
        notification.setStyle(new Notification.BigTextStyle().bigText(subTitle));

        NotificationManager.from(mContext).notify(TYPE_OPTIONS, userId, notification.build());

        if (showSave) {
            showSaveNotification(userId, response, null);
        }
    }

    private void showSaveNotification(int userId, FillResponse response, Dataset dataset) {
        final Intent saveIntent = newNotificationIntent(userId, TYPE_SAVE);
        saveIntent.putExtra(EXTRA_FILL_RESPONSE, response);
        if (dataset != null) {
            saveIntent.putExtra(EXTRA_DATASET, dataset);
        }
        final PendingIntent savePendingIntent = PendingIntent.getBroadcast(mContext,
                ++sResultCode, saveIntent, PendingIntent.FLAG_ONE_SHOT);

        final String title = "AutoFill Save";
        final String subTitle = "Tap notification to ask provider to save fields: \n"
                + Arrays.toString(response.getSavableIds());

        final Notification notification = new Notification.Builder(mContext)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setAutoCancel(true)
                .setOngoing(false)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setLocalOnly(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentIntent(savePendingIntent)
                .setStyle(new Notification.BigTextStyle().bigText(subTitle))
                .build();
        NotificationManager.from(mContext).notify(TYPE_SAVE, userId, notification);
    }

    /////////////////////////////////////////
    // End of temporary notification code. //
    /////////////////////////////////////////
}
