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
import android.os.IBinder;
import android.util.Slog;
import android.view.autofill.AutoFillId;
import android.view.autofill.Dataset;
import android.view.autofill.FillResponse;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Toast;

import com.android.internal.annotations.GuardedBy;
import com.android.server.UiThread;
import com.android.server.autofill.AutoFillManagerServiceImpl.Session;
import com.android.server.autofill.AutoFillManagerServiceImpl.ViewState;

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
    private final Session mSession;
    private final IBinder mAppToken;
    private final WindowManager mWm;

    // Fill UI variables
    private AnchoredWindow mFillWindow;
    private DatasetPicker mFillView;
    private ViewState mViewState;
    private Rect mBounds;
    private String mFilterText;

    /**
     * Custom snackbar UI used for saving autofill or other informational messages.
     */
    private View mSnackbar;

    AutoFillUI(Context context, Session session, IBinder appToken) {
        mContext = context;
        mSession = session;
        mAppToken = appToken;
        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
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
     * Hides the fill UI.
     * Shows the options from a {@link FillResponse} so the user can pick up the proper
     * {@link Dataset} (when the response has one) for a given view (identified by
     * {@code autoFillId}).
     */
    void hideFillUi() {
        UiThread.getHandler().runWithScissors(() -> {
            hideFillUiLocked();
        }, 0);
    }

    // Must be called in inside UI Thread
    private void hideFillUiLocked() {
        if (mFillWindow != null) {
            if (DEBUG) Slog.d(TAG, "hideFillUiLocked(): hide" + mFillWindow);

            mFillWindow.hide();
        }

        mViewState = null;
        mBounds = null;
        mFilterText = null;
        mFillView = null;
        mFillWindow = null;
    }


    /**
     * Shows the fill UI, removing the previous fill UI if the has changed.
     *
     * @param viewState the view state, compared by reference to know if new UI should be shown
     * @param datasets the datasets to show, not used if viewState is the same
     * @param bounds bounds of the view to be filled, used if changed
     * @param filterText text of the view to be filled, used if changed
     */
    void showFillUi(ViewState viewState, List<Dataset> datasets, Rect bounds,
            String filterText) {
        UiThread.getHandler().runWithScissors(() -> {
            if (mViewState != viewState) {
                // new
                hideFillUi();

                mViewState = viewState;

                mFillView = new DatasetPicker(mContext, datasets,
                        (dataset) -> {
                            mSession.autoFillApp(dataset);
                            hideFillUi();
                        });
                mFillWindow = new AnchoredWindow(
                        mWm, mFillView, 800, ViewGroup.LayoutParams.WRAP_CONTENT);

                if (DEBUG) Slog.d(TAG, "show FillUi");
            }

            if (!bounds.equals(mBounds)) {
                if (DEBUG) Slog.d(TAG, "update FillUi bounds: " + mBounds);
                mBounds = bounds;
                mFillWindow.show(mBounds);
            }

            if (!filterText.equals(mFilterText)) {
                if (DEBUG) Slog.d(TAG, "update FillUi filter text: " + mFilterText);
                mFilterText = filterText;
                mFillView.update(mFilterText);
            }
        }, 0);
    }

    /**
     * Shows an UI affordance indicating that user action is required before a {@link FillResponse}
     * can be used.
     *
     * <p>It typically replaces the auto-fill bar with a message saying "Press fingerprint or tap to
     * autofill" or "Tap to autofill", depending on the value of {@code usesFingerprint}.
     */
    void showFillResponseAuthenticationRequest(boolean usesFingerprint,
            Bundle extras, int flags) {
        // TODO(b/33197203): proper implementation
        showAuthNotification(usesFingerprint, extras, flags);
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
    void showSaveUi() {
        showSnackbar(new SavePrompt(mContext, new SavePrompt.OnSaveListener() {
            @Override
            public void onSaveClick() {
                hideSnackbar();

                // TODO(b/33197203): add MetricsLogger call
                mSession.requestSave();
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
    void dismissFingerprintRequest(boolean success) {
        if (DEBUG) Slog.d(TAG, "dismissFingerprintRequest(): ok=" + success);

        dismissAuthNotification();

        if (!success) {
            // TODO(b/33197203): proper implementation (snack bar / i18n string)
            UiThread.getHandler().runWithScissors(() -> {
                Toast.makeText(mContext, "AutoFill: fingerprint failed", Toast.LENGTH_LONG).show();
            }, 0);
        }
    }

    /**
     * Closes all UI affordances.
     */
    void closeAll() {
        if (DEBUG) Slog.d(TAG, "closeAll()");

        UiThread.getHandler().runWithScissors(() -> {
            hideSnackbarLocked();
            hideFillUiLocked();
        }, 0);
    }

    void dump(PrintWriter pw) {
        pw.println("AufoFill UI");
        final String prefix = "  ";
        pw.print(prefix); pw.print("sResultCode: "); pw.println(sResultCode);
        pw.print(prefix); pw.print("mSessionId: "); pw.println(mSession.mId);
        pw.print(prefix); pw.print("mSnackBar: "); pw.println(mSnackbar);
        pw.print(prefix); pw.print("mViewState: "); pw.println(mViewState);
        pw.print(prefix); pw.print("mBounds: "); pw.println(mBounds);
        pw.print(prefix); pw.print("mFilterText: "); pw.println(mFilterText);
    }

    //similar to a snackbar, but can be a bit custom since it is more than just text. This will
    //allow two buttons for saving or not saving the autofill for instance as well.
    private void showSnackbar(View snackBar) {
        final LayoutParams params = new LayoutParams();
        params.setTitle("AutoFill Save");
        params.type = LayoutParams.TYPE_PHONE; // TODO(b/33197203) use app window token
        params.flags =
                LayoutParams.FLAG_NOT_FOCUSABLE // don't receive input events,
                | LayoutParams.FLAG_ALT_FOCUSABLE_IM // resize for soft input
                | LayoutParams.FLAG_NOT_TOUCH_MODAL; // outside touches go to windows behind us
        params.softInputMode =
                LayoutParams.SOFT_INPUT_ADJUST_PAN; // pan with soft input
        params.gravity = Gravity.BOTTOM | Gravity.START;
        params.width = LayoutParams.MATCH_PARENT;
        params.height = LayoutParams.WRAP_CONTENT;

        UiThread.getHandler().runWithScissors(() -> {
            mSnackbar = snackBar;
            mWm.addView(mSnackbar, params);
        }, 0);
    }

    private void hideSnackbar() {
        UiThread.getHandler().runWithScissors(() -> {
            hideSnackbarLocked();
        }, 0);
    }

    // Must be called in inside UI Thread
    private void hideSnackbarLocked() {
        if (mSnackbar != null) {
            mWm.removeView(mSnackbar);
            mSnackbar = null;
        }
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
    private static final String TYPE_AUTH_RESPONSE = "auth_response";

    private BroadcastReceiver mNotificationReceiver;
    private final Object mLock = new Object();

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
            final String type = intent.getStringExtra(EXTRA_NOTIFICATION_TYPE);
            if (type == null) {
                Slog.wtf(TAG, "No extra " + EXTRA_NOTIFICATION_TYPE + " on intent " + intent);
                return;
            }
            final Dataset dataset = intent.getParcelableExtra(EXTRA_DATASET);
            final int flags = intent.getIntExtra(EXTRA_FLAGS, 0);

            if (DEBUG) Slog.d(TAG, "Notification received: type=" + type
                    + ", sessionId=" + mSession.mId);
            synchronized (mLock) {
                switch (type) {
                    case TYPE_AUTH_RESPONSE:
                        mSession.notifyResponseAuthenticationResult(
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

    private static Intent newNotificationIntent(String type) {
        final Intent intent = new Intent(NOTIFICATION_AUTO_FILL_INTENT);
        intent.putExtra(EXTRA_NOTIFICATION_TYPE, type);
        return intent;
    }

    private void showAuthNotification(boolean usesFingerprint,
            Bundle extras, int flags) {
        final long token = Binder.clearCallingIdentity();
        try {
            showAuthNotificationAsSystem(usesFingerprint, extras, flags);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void showAuthNotificationAsSystem(
            boolean usesFingerprint, Bundle extras, int flags) {
        final String title = "AutoFill Authentication";
        final StringBuilder subTitle = new StringBuilder("Provider require user authentication.\n");

        final Intent authIntent = newNotificationIntent(TYPE_AUTH_RESPONSE);
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
        NotificationManager.from(mContext).notify(mSession.mId, notification.build());
    }

    private void dismissAuthNotification() {
        NotificationManager.from(mContext).cancel(mSession.mId);
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
