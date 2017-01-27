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


import static com.android.server.autofill.Helper.DEBUG;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Bundle;
import android.util.Slog;
import android.view.autofill.AutoFillId;
import android.view.autofill.Dataset;
import android.view.autofill.FillResponse;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.internal.annotations.GuardedBy;
import com.android.server.UiThread;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

/**
 * Handles all auto-fill related UI tasks.
 */
// TODO(b/33197203): document exactly what once the auto-fill bar is implemented
final class AutoFillUI {

    private static final String TAG = "AutoFillUI";

    private final Context mContext;

    private final WindowManager mWm;

    @Nullable
    private AnchoredWindow mFillWindow;

    /**
     * Custom snackbar UI used for saving autofill or other informational messages.
     */
    private View mSnackbar;

    AutoFillUI(Context context, AutoFillManagerService service, Object lock) {
        mContext = context;
        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mService = service;
        mLock = lock;

        setNotificationListener();
    }

    /**
     * Displays an error message to the user.
     */
    void showError(CharSequence message) {
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
     * {@link Dataset} (when the response has one) for a given view (identified by
     * {@code autoFillId}).
     */
    void showResponse(int userId, int sessionId, AutoFillId autoFillId, Rect bounds,
            FillResponse response) {
        if (DEBUG) Slog.d(TAG, "showResponse: id=" + autoFillId +  ", bounds=" + bounds);

        UiThread.getHandler().runWithScissors(() -> {
            if (mFillWindow != null) {
                mFillWindow.hide();
            }

            final DatasetPicker fillView = new DatasetPicker(mContext, response.getDatasets(),
                    (dataset) -> {
                        mFillWindow.hide();
                        onDatasetPicked(userId, dataset, sessionId);
                    });

            // TODO(b/33197203): request width/height properly.
            mFillWindow = new AnchoredWindow(mWm, fillView, 800,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            mFillWindow.show(bounds != null ? bounds : new Rect());
        }, 0);
    }

    /**
     * Shows an UI affordance indicating that user action is required before a {@link FillResponse}
     * can be used.
     *
     * <p>It typically replaces the auto-fill bar with a message saying "Press fingerprint or tap to
     * autofill" or "Tap to autofill", depending on the value of {@code usesFingerprint}.
     */
    void showFillResponseAuthenticationRequest(int userId, int sessionId, boolean usesFingerprint,
            Bundle extras, int flags) {
        // TODO(b/33197203): proper implementation
        showAuthNotification(userId, sessionId, usesFingerprint, extras, flags);
    }

    /**
     * Shows an UI affordance asking indicating that user action is required before a
     * {@link Dataset} can be used.
     *
     * <p>It typically replaces the auto-fill bar with a message saying "Press fingerprint to
     * autofill".
     */
    void showDatasetFingerprintAuthenticationRequest(Dataset dataset) {
        if (DEBUG) Slog.d(TAG, "showDatasetAuthenticationRequest(): dataset=" + dataset);

        // TODO(b/33197203): proper implementation (either pop up a fingerprint dialog or replace
        // the auto-fill bar with a new message.
        UiThread.getHandler().runWithScissors(() -> {
            Toast.makeText(mContext, "AutoFill: press fingerprint to unlock " + dataset.getName(),
                    Toast.LENGTH_LONG).show();
        }, 0);
    }

    /**
     * Shows the UI asking the user to save for auto-fill.
     */
    void showSaveUI(int userId, int sessionId) {
        showSnackbar(new SavePrompt(mContext, new SavePrompt.OnSaveListener() {
            @Override
            public void onSaveClick() {
                hideSnackbar();
                synchronized (mLock) {
                    final AutoFillManagerServiceImpl service = getServiceLocked(userId);
                    service.requestSaveLocked(sessionId);
                }
            }
            @Override
            public void onCancelClick() {
                hideSnackbar();
            }
        }));
    }

    /**
     * Called by service after the user user the fingerprint sensors to authenticate.
     */
    void dismissFingerprintRequest(int userId, boolean success) {
        if (DEBUG) Slog.d(TAG, "dismissFingerprintRequest(): ok=" + success);

        dismissAuthNotification(userId);

        if (!success) {
            // TODO(b/33197203): proper implementation (snack bar / i18n string)
            UiThread.getHandler().runWithScissors(() -> {
                Toast.makeText(mContext, "AutoFill: fingerprint failed", Toast.LENGTH_LONG).show();
            }, 0);
        }
    }

    void dump(PrintWriter pw) {
        pw.println("AufoFill UI");
        final String prefix = "  ";
        pw.print(prefix); pw.print("sResultCode: "); pw.println(sResultCode);
        pw.print(prefix); pw.print("mSnackBar: "); pw.println(mSnackbar);
        mFillWindow.dump(pw);
    }

    private AutoFillManagerServiceImpl getServiceLocked(int userId) {
        final AutoFillManagerServiceImpl service = mService.getServiceForUserLocked(userId);
        if (service == null) {
            Slog.w(TAG, "no auto-fill service for user " + userId);
        }
        return service;
    }

    private void onSaveRequested(int userId, int sessionId) {
        // TODO(b/33197203): displays the snack bar, until save notification is refactored
        showSaveUI(userId, sessionId);
    }

    private void onDatasetPicked(int userId, Dataset dataset, int sessionId) {
        synchronized (mLock) {
            final AutoFillManagerServiceImpl service = getServiceLocked(userId);
            if (service == null) return;

            service.autoFillApp(sessionId, dataset);
        }
    }

    private void onSessionDone(int userId, int sessionId) {
        synchronized (mLock) {
            final AutoFillManagerServiceImpl service = getServiceLocked(userId);
            if (service == null) return;

            service.removeSessionLocked(sessionId);
        }
    }

    private void onResponseAuthenticationRequested(int userId, Bundle extras, int flags) {
        synchronized (mLock) {
            final AutoFillManagerServiceImpl service = getServiceLocked(userId);
            if (service == null) return;

            service.notifyResponseAuthenticationResult(extras, flags);
        }
    }

    //similar to a snackbar, but can be a bit custom since it is more than just text. This will
    //allow two buttons for saving or not saving the autofill for instance as well.
    private void showSnackbar(View snackBar) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.FILL_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT, // TODO(b/33197203) use TYPE_AUTO_FILL
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN,
            PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.BOTTOM | Gravity.LEFT;

        UiThread.getHandler().runWithScissors(() -> {
            mSnackbar = snackBar;
            mWm.addView(mSnackbar, params);
        }, 0);
    }

    private void hideSnackbar() {
        UiThread.getHandler().runWithScissors(() -> {
            if (mSnackbar != null) {
                mWm.removeView(mSnackbar);
                mSnackbar = null;
            }
        }, 0);
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
    private static final String EXTRA_SESSION_ID = "session_id";
    private static final String EXTRA_FILL_RESPONSE = "fill_response";
    private static final String EXTRA_DATASET = "dataset";
    private static final String EXTRA_AUTH_REQUIRED_EXTRAS = "auth_required_extras";
    private static final String EXTRA_FLAGS = "flags";

    private static final String TYPE_OPTIONS = "options";
    private static final String TYPE_FINISH_SESSION = "finish_session";
    private static final String TYPE_PICK_DATASET = "pick_dataset";
    private static final String TYPE_SAVE = "save";
    private static final String TYPE_AUTH_RESPONSE = "auth_response";

    @GuardedBy("mServiceLock")
    private BroadcastReceiver mNotificationReceiver;
    @GuardedBy("mServiceLock")
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
            final int sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1);
            final String type = intent.getStringExtra(EXTRA_NOTIFICATION_TYPE);
            if (type == null) {
                Slog.wtf(TAG, "No extra " + EXTRA_NOTIFICATION_TYPE + " on intent " + intent);
                return;
            }
            final Dataset dataset = intent.getParcelableExtra(EXTRA_DATASET);
            final int flags = intent.getIntExtra(EXTRA_FLAGS, 0);

            if (DEBUG) Slog.d(TAG, "Notification received: type=" + type + ", userId=" + userId
                    + ", sessionId=" + sessionId);
            synchronized (mLock) {
                switch (type) {
                    case TYPE_SAVE:
                        onSaveRequested(userId, sessionId);
                        break;
                    case TYPE_FINISH_SESSION:
                        onSessionDone(userId, sessionId);
                        break;
                    case TYPE_PICK_DATASET:
                        onDatasetPicked(userId, dataset, sessionId);

                        // Must cancel notification because it might be comming from action
                        if (DEBUG) Slog.d(TAG, "Cancelling notification");
                        NotificationManager.from(mContext).cancel(TYPE_OPTIONS, userId);

                        break;
                    case TYPE_AUTH_RESPONSE:
                        onResponseAuthenticationRequested(userId,
                                intent.getBundleExtra(EXTRA_AUTH_REQUIRED_EXTRAS), flags);
                        break;
                    default: {
                        Slog.w(TAG, "Unknown notification type: " + type);
                    }
                }
            }
            collapseStatusBar();
        }
    }

    private static Intent newNotificationIntent(int userId, String type) {
        final Intent intent = new Intent(NOTIFICATION_AUTO_FILL_INTENT);
        intent.putExtra(EXTRA_USER_ID, userId);
        intent.putExtra(EXTRA_NOTIFICATION_TYPE, type);
        return intent;
    }

    private PendingIntent newPickDatasetPI(int userId, int sessionId, FillResponse response,
            Dataset dataset) {
        final int resultCode = ++ sResultCode;
        if (DEBUG) Slog.d(TAG, "newPickDatasetPI: userId=" + userId + ", sessionId=" + sessionId
                + ", resultCode=" + resultCode);

        final Intent intent = newNotificationIntent(userId, TYPE_PICK_DATASET);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        intent.putExtra(EXTRA_FILL_RESPONSE, response);
        intent.putExtra(EXTRA_DATASET, dataset);
        return PendingIntent.getBroadcast(mContext, resultCode, intent,
                PendingIntent.FLAG_ONE_SHOT);
    }

    /**
     * Shows a notification with the results of an auto-fill request, using notications actions
     * to emulate the auto-fill bar buttons displaying the dataset names.
     */
    private void showOptionsNotification(int userId, int callbackId, AutoFillId autoFillId,
            FillResponse response) {
        final long token = Binder.clearCallingIdentity();
        try {
            showOptionsNotificationAsSystem(userId, callbackId, autoFillId, response);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void showOptionsNotificationAsSystem(int userId, int sessionId,
            AutoFillId autoFillId, FillResponse response) {
        // Make sure server callback is removed from cache if user cancels the notification.
        final Intent deleteIntent = newNotificationIntent(userId, TYPE_FINISH_SESSION)
                .putExtra(EXTRA_SESSION_ID, sessionId);
        final PendingIntent deletePendingIntent = PendingIntent.getBroadcast(mContext,
                ++sResultCode, deleteIntent, PendingIntent.FLAG_ONE_SHOT);

        final String title = "AutoFill Options";

        final Notification.Builder notification = newNotificationBuilder()
                .setOngoing(false)
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
            subTitle = "No options to auto-fill " + autoFillId;
        } else if (datasets.isEmpty()) {
            if (savableIds.length == 0) {
                subTitle = "No options to auto-fill " + autoFillId;
            } else {
                subTitle = "No options to auto-fill " + autoFillId
                        + ", but provider can save ids:\n" + Arrays.toString(savableIds);
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
                subTitle = "There are " + size + " option(s) to fill " + autoFillId + ".\n"
                        + "Use the notification action(s) to select the proper one."
                        + "Actions with (F) require fingerprint unlock, and with (P) require"
                        + "provider authentication to unlock";
                for (Dataset dataset : datasets) {
                    final StringBuilder name = new StringBuilder(dataset.getName());
                    if (dataset.isAuthRequired()) {
                        if (dataset.hasCryptoObject()) {
                            name.append("(F)");
                        } else {
                            name.append("(P)");
                        }
                    }
                    final PendingIntent pi = newPickDatasetPI(userId, sessionId, response, dataset);
                    notification.addAction(new Action.Builder(null, name, pi).build());
                }
            }
        }

        notification.setAutoCancel(autoCancel);
        notification.setStyle(new Notification.BigTextStyle().bigText(subTitle));

        NotificationManager.from(mContext).notify(TYPE_OPTIONS, userId, notification.build());

        if (showSave) {
            showSaveNotification(userId, sessionId);
        }
    }

    void showSaveNotification(int userId, int sessionId) {
        final long token = Binder.clearCallingIdentity();
        try {
            showSaveNotificationAsSystem(userId, sessionId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void showSaveNotificationAsSystem(int userId, int sessionId) {
        final Intent saveIntent = newNotificationIntent(userId, TYPE_SAVE)
                .putExtra(EXTRA_SESSION_ID, sessionId);

        final PendingIntent savePendingIntent = PendingIntent.getBroadcast(mContext,
                ++sResultCode, saveIntent, PendingIntent.FLAG_ONE_SHOT);

        final String title = "AutoFill Save Emulation";
        final String subTitle = "Tap notification to launch the save snackbar.";

        final Notification notification = newNotificationBuilder()
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentTitle(title)
                .setContentIntent(savePendingIntent)
                .setStyle(new Notification.BigTextStyle().bigText(subTitle))
                .build();
        NotificationManager.from(mContext).notify(TYPE_SAVE, userId, notification);
    }

    private void showAuthNotification(int userId, int sessionId, boolean usesFingerprint,
            Bundle extras, int flags) {
        final long token = Binder.clearCallingIdentity();
        try {
            showAuthNotificationAsSystem(userId, sessionId, usesFingerprint, extras, flags);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void showAuthNotificationAsSystem(int userId, int sessionId,
            boolean usesFingerprint, Bundle extras, int flags) {
        final String title = "AutoFill Authentication";
        final StringBuilder subTitle = new StringBuilder("Provider require user authentication.\n");

        final Intent authIntent = newNotificationIntent(userId, TYPE_AUTH_RESPONSE)
                .putExtra(EXTRA_SESSION_ID, sessionId);
        if (extras != null) {
            authIntent.putExtra(EXTRA_AUTH_REQUIRED_EXTRAS, extras);
        }
        if (flags != 0) {
            authIntent.putExtra(EXTRA_FLAGS, flags);
        }
        final PendingIntent authPendingIntent = PendingIntent.getBroadcast(mContext, ++sResultCode,
                authIntent, PendingIntent.FLAG_ONE_SHOT);

        if (usesFingerprint) {
            subTitle.append("But kindly accepts your fingerprint instead"
                    + "\n(tap fingerprint sensor to trigger it)");

        } else {
            subTitle.append("Tap notification to launch its authentication UI.");
        }

        final Notification.Builder notification = newNotificationBuilder()
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentTitle(title)
                .setStyle(new Notification.BigTextStyle().bigText(subTitle.toString()));
        if (authPendingIntent != null) {
            notification.setContentIntent(authPendingIntent);
        }
        NotificationManager.from(mContext).notify(TYPE_AUTH_RESPONSE, userId, notification.build());
    }

    private void dismissAuthNotification(int userId) {
        NotificationManager.from(mContext).cancel(TYPE_AUTH_RESPONSE, userId);
    }

    private Notification.Builder newNotificationBuilder() {
        return new Notification.Builder(mContext)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setLocalOnly(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color));
    }

    private void collapseStatusBar() {
        final StatusBarManager sbm = (StatusBarManager) mContext.getSystemService("statusbar");
        sbm.collapsePanels();
    }
    /////////////////////////////////////////
    // End of temporary notification code. //
    /////////////////////////////////////////
}
