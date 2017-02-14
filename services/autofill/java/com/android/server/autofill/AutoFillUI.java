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
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.autofill.Dataset;
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

    private final Context mContext;
    private final WindowManager mWm;

    // TODO(b/33197203) Fix locking - some state requires lock and some not - requires refactoring
    private final Object mLock = new Object();

    // Fill UI variables
    private AnchoredWindow mFillWindow;
    private View mFillView;
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
        }, 0);

        if (datasets == null && viewState.mAuthIntent == null) {
            // TODO(b/33197203): shouldn't be called, but keeping the WTF for a while just to be
            // safe, otherwise it would crash system server...
            Slog.wtf(TAG, "showFillUI(): no dataset");
            return;
        }

        // TODO(b/33197203): should not display UI after we launched an authentication intent, since
        // we have no warranty the provider will call onFailure() if the authentication failed or
        // user dismissed the auth window
        // because if the service does not handle calling the callback,

        UiThread.getHandler().runWithScissors(() -> {
            // The dataset picker is only shown when authentication is not required...
            DatasetPicker datasetPicker = null;

            if (mViewState == null || !mViewState.mId.equals(viewState.mId)) {
                hideFillUiUiThread();
                mViewState = viewState;

                if (viewState.mAuthIntent != null) {
                    final String packageName = viewState.mServiceComponent.getPackageName();
                    CharSequence serviceName = null;
                    try {
                        final PackageManager pm = mContext.getPackageManager();
                        final ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                        serviceName = pm.getApplicationLabel(info);
                    } catch (Exception e) {
                        Slog.w(TAG, "Could not get label for " + packageName + ": " + e);
                        serviceName = packageName;
                    }

                    mFillView = new SignInPrompt(mContext, serviceName, (e) -> {
                        final IntentSender intentSender = viewState.mResponse.getAuthentication();
                        final AutoFillUiCallback callback;
                        final Intent authIntent;
                        synchronized (mLock) {
                            callback = mCallback;
                            authIntent = viewState.mAuthIntent;
                            // Must reset the authentication intent so UI display the datasets after
                            // the user authenticated.
                            viewState.mAuthIntent = null;
                        }
                        if (callback != null) {
                            callback.authenticate(intentSender, authIntent);
                        } else {
                            // TODO(b/33197203): need to figure out why it's null sometimes
                            Slog.w(TAG, "no callback on showFillUi().auth for " + viewState.mId);
                        }
                    });

                } else {
                    mFillView = datasetPicker = new DatasetPicker(mContext, datasets,
                            (dataset) -> {
                                final AutoFillUiCallback callback;
                                synchronized (mLock) {
                                    callback = mCallback;
                                }
                                if (callback != null) {
                                    callback.fill(dataset);
                                } else {
                                    // TODO(b/33197203): need to figure out why it's null sometimes
                                    Slog.w(TAG, "no callback on showFillUi() for " + viewState.mId);
                                }
                                hideFillUiUiThread();
                            });
                }
                mFillWindow = new AnchoredWindow(mWm, appToken, mFillView);

                if (DEBUG) Slog.d(TAG, "showFillUi(): view changed for: " + viewState.mId);
            }

            if (datasetPicker != null) {
                datasetPicker.update(filterText);
            }
            mFillWindow.show(bounds);

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
        }, 0);
    }

    void dump(PrintWriter pw) {
        pw.println("AufoFill UI");
        final String prefix = "  ";
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
    }}
