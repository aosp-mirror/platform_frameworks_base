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
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.util.ArraySet;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.Slog;
import android.view.autofill.Dataset;
import android.view.autofill.FillResponse;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Toast;

import com.android.internal.os.HandlerCaller;
import com.android.server.UiThread;
import com.android.server.autofill.AutoFillManagerServiceImpl.ViewState;

import java.io.PrintWriter;

/**
 * Handles all auto-fill related UI tasks.
 */
// TODO(b/33197203): document exactly what once the auto-fill bar is implemented
final class AutoFillUI {
    private static final String TAG = "AutoFillUI";
    private static final long SNACK_BAR_LIFETIME_MS = 30 * DateUtils.SECOND_IN_MILLIS;
    private static final int MSG_HIDE_SNACK_BAR = 1;

    private static final String EXTRA_AUTH_INTENT_SENDER =
            "com.android.server.autofill.extra.AUTH_INTENT_SENDER";
    private static final String EXTRA_AUTH_FILL_IN_INTENT =
            "com.android.server.autofill.extra.AUTH_FILL_IN_INTENT";

    private final Context mContext;
    private final WindowManager mWm;

    // TODO(b/33197203) Fix locking - some state requires lock and some not - requires refactoring

    // Fill UI variables
    private AnchoredWindow mFillWindow;
    private DatasetPicker mFillView;
    private ViewState mViewState;

    private AutoFillUiCallback mCallback;
    private IBinder mActivityToken;

    private final HandlerCaller.Callback mHandlerCallback = (msg) -> {
        switch (msg.what) {
            case MSG_HIDE_SNACK_BAR: {
                hideSnackbarUiThread();
                return;
            }
            default: {
                Slog.w(TAG, "Invalid message: " + msg);
            }
        }
    };
    private final HandlerCaller mHandlerCaller = new HandlerCaller(null, Looper.getMainLooper(),
            mHandlerCallback, true);

    /**
     * Custom snackbar UI used for saving autofill or other informational messages.
     */
    private View mSnackbar;

    AutoFillUI(Context context) {
        mContext = context;
        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    void setCallbackLocked(AutoFillUiCallback callback, IBinder activityToken) {
        hideAll();
        mCallback = callback;
        mActivityToken = activityToken;
    }

    /**
     * Displays an error message to the user.
     */
    void showError(CharSequence message) {
        if (!hasCallback()) {
            return;
        }
        hideAll();
        // TODO(b/33197203): proper implementation
        UiThread.getHandler().runWithScissors(() -> {
            Toast.makeText(mContext, "AutoFill error: " + message, Toast.LENGTH_LONG).show();
        }, 0);
    }

    /**
     * Hides the fill UI.
     */
    void hideFillUi() {
        UiThread.getHandler().runWithScissors(() -> {
            hideFillUiUiThread();
        }, 0);
    }

    @android.annotation.UiThread
    private void hideFillUiUiThread() {
        if (mFillWindow != null) {
            if (DEBUG) Slog.d(TAG, "hideFillUiUiThread(): hide" + mFillWindow);
            mFillWindow.hide();
        }

        mViewState = null;
        mFillView = null;
        mFillWindow = null;
    }

    /**
     * Shows the fill UI, removing the previous fill UI if the has changed.
     *
     * @param appToken the token of the app to be autofilled
     * @param viewState the view state, compared by reference to know if new UI should be shown
     * @param datasets the datasets to show, not used if viewState is the same
     * @param bounds bounds of the view to be filled, used if changed
     * @param filterText text of the view to be filled, used if changed
     */
    void showFillUi(IBinder appToken, ViewState viewState, @Nullable ArraySet<Dataset> datasets,
            Rect bounds, String filterText) {
        if (!hasCallback()) {
            return;
        }

        UiThread.getHandler().runWithScissors(() -> {
            hideSnackbarUiThread();
            hideFillResponseAuthUiUiThread();
        }, 0);

        if (datasets == null) {
            // TODO(b/33197203): shouldn't be called, but keeping the WTF for a while just to be
            // safe, otherwise it would crash system server...
            Slog.wtf(TAG, "showFillUI(): no dataset");
            return;
        }

        UiThread.getHandler().runWithScissors(() -> {
            if (mViewState == null || !mViewState.mId.equals(viewState.mId)) {
                hideFillUiUiThread();

                mViewState = viewState;

                mFillView = new DatasetPicker(mContext, datasets,
                        (dataset) -> {
                            final AutoFillUiCallback callback;
                            synchronized (mLock) {
                                callback = mCallback;
                            }
                            if (callback != null) {
                                callback.fill(dataset);
                            } else {
                                Slog.w(TAG, "null callback on showFillUi() for " + viewState.mId);
                            }
                            hideFillUi();
                        });

                mFillWindow = new AnchoredWindow(mWm, appToken, mFillView);

                if (DEBUG) Slog.d(TAG, "showFillUi(): view changed");
            }

            if (DEBUG) Slog.d(TAG, "showFillUi(): bounds=" + bounds + ", filterText=" + filterText);
            mFillView.update(filterText);
            mFillWindow.show(bounds);
        }, 0);
    }

    /**
     * Shows an UI affordance indicating that user action is required before a {@link FillResponse}
     * can be used.
     *
     * <p>It typically replaces the auto-fill bar with a message saying "Press fingerprint or tap to
     * autofill" or "Tap to autofill", depending on the value of {@code usesFingerprint}.
     */
    void showFillResponseAuthRequest(IntentSender intent, Intent fillInIntent) {
        if (!hasCallback()) {
            return;
        }
        hideAll();
        UiThread.getHandler().runWithScissors(() -> {
            // TODO(b/33197203): proper implementation
            showFillResponseAuthUiUiThread(intent, fillInIntent);
        }, 0);
    }

    /**
     * Shows the UI asking the user to save for auto-fill.
     */
    void showSaveUi() {
        if (!hasCallback()) {
            return;
        }
        hideAll();
        UiThread.getHandler().runWithScissors(() -> {
            showSnackbarUiThread(new SavePrompt(mContext,
                    new SavePrompt.OnSaveListener() {
                @Override
                public void onSaveClick() {
                    hideSnackbarUiThread();
                    // TODO(b/33197203): add MetricsLogger call
                    mCallback.save();
                }

                @Override
                public void onCancelClick() {
                    // TODO(b/33197203): add MetricsLogger call
                    hideSnackbarUiThread();
                }
            }));
        }, 0);
    }

    /**
     * Hides all UI affordances.
     */
    void hideAll() {
        UiThread.getHandler().runWithScissors(() -> {
            hideSnackbarUiThread();
            hideFillUiUiThread();
            hideFillResponseAuthUiUiThread();
        }, 0);
    }

    void dump(PrintWriter pw) {
        pw.println("AufoFill UI");
        final String prefix = "  ";
        pw.print(prefix); pw.print("sResultCode: "); pw.println(sResultCode);
        pw.print(prefix); pw.print("mActivityToken: "); pw.println(mActivityToken);
        pw.print(prefix); pw.print("mSnackBar: "); pw.println(mSnackbar);
        pw.print(prefix); pw.print("mViewState: "); pw.println(mViewState);
    }

    //similar to a snackbar, but can be a bit custom since it is more than just text. This will
    //allow two buttons for saving or not saving the autofill for instance as well.
    private void showSnackbarUiThread(View snackBar) {
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

        if (DEBUG) {
            Slog.d(TAG, "showSnackbar(): auto dismissing it in " + SNACK_BAR_LIFETIME_MS + " ms");
        }
        mHandlerCaller.sendMessageDelayed(mHandlerCaller.obtainMessage(MSG_HIDE_SNACK_BAR),
                SNACK_BAR_LIFETIME_MS);
    }

    private void hideSnackbarUiThread() {
        mHandlerCaller.getHandler().removeMessages(MSG_HIDE_SNACK_BAR);
        if (mSnackbar != null) {
            mWm.removeView(mSnackbar);
            mSnackbar = null;
        }
    }

    private boolean hasCallback() {
        synchronized (mLock) {
            return mCallback != null;
        }
    }

    interface AutoFillUiCallback {
        void authenticate(IntentSender intent, Intent fillInIntent);
        void fill(Dataset dataset);
        void save();
    }

    /////////////////////////////////////////////////////////////////////////////////
    // TODO(b/33197203): temporary code using a notification to request auto-fill. //
    // Will be removed once UX decide the right way to present it to the user.     //
    /////////////////////////////////////////////////////////////////////////////////

    // TODO(b/33197203): remove from frameworks/base/core/res/AndroidManifest.xml once not used
    private static final String NOTIFICATION_AUTO_FILL_INTENT =
            "com.android.internal.autofill.action.REQUEST_AUTOFILL";

    private BroadcastReceiver mNotificationReceiver;
    private final Object mLock = new Object();

    // Hack used to generate unique pending intents
    static int sResultCode = 0;

    private void ensureNotificationListener() {
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
            final AutoFillUiCallback callback;
            synchronized (mLock) {
                callback = mCallback;
            }
            if (callback != null) {
                IntentSender intentSender = intent.getParcelableExtra(EXTRA_AUTH_INTENT_SENDER);
                Intent fillInIntent = intent.getParcelableExtra(EXTRA_AUTH_FILL_IN_INTENT);
                callback.authenticate(intentSender, fillInIntent);
            }
            collapseStatusBar();
        }
    }

    @android.annotation.UiThread
    private void showFillResponseAuthUiUiThread(IntentSender intent, Intent fillInIntent) {
        final String title = "AutoFill Authentication";
        final StringBuilder subTitle = new StringBuilder("Provider require user authentication.\n");

        final Intent authIntent = new Intent(NOTIFICATION_AUTO_FILL_INTENT);
        authIntent.putExtra(EXTRA_AUTH_INTENT_SENDER, intent);
        authIntent.putExtra(EXTRA_AUTH_FILL_IN_INTENT, fillInIntent);

        final PendingIntent authPendingIntent = PendingIntent.getBroadcast(
                mContext, ++sResultCode, authIntent, PendingIntent.FLAG_ONE_SHOT);

        subTitle.append("Tap notification to launch its authentication UI.");

        final Notification.Builder notification = newNotificationBuilder()
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentTitle(title)
                .setStyle(new Notification.BigTextStyle().bigText(subTitle.toString()))
                .setContentIntent(authPendingIntent);

        ensureNotificationListener();

        final long identity = Binder.clearCallingIdentity();
        try {
            NotificationManager.from(mContext).notify(0, notification.build());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @android.annotation.UiThread
    private void hideFillResponseAuthUiUiThread() {
        final long identity = Binder.clearCallingIdentity();
        try {
            NotificationManager.from(mContext).cancel(0);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
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
