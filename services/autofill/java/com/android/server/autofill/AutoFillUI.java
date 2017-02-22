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
import android.content.IntentSender;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.text.format.DateUtils;
import android.util.Slog;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.autofill.AutoFillId;
import android.widget.Toast;

import com.android.server.UiThread;

import java.io.PrintWriter;

/**
 * Handles all auto-fill related UI tasks.
 */
// TODO(b/33197203): document exactly what once the auto-fill bar is implemented
final class AutoFillUI {
    private static final String TAG = "AutoFillUI";

    private static final long SNACK_BAR_LIFETIME_MS = 5 * DateUtils.SECOND_IN_MILLIS;

    private static final int MSG_HIDE_SNACK_BAR = 1;

    private final Handler mHandler = UiThread.getHandler();

    private final Context mContext;

    private final WindowManager mWm;

    private AnchoredWindow mFillWindow;

    private DatasetPicker mDatasetPicker;

    private AutoFillUiCallback mCallback;

    private IBinder mActivityToken;

    /**
     * Custom snackbar UI used for saving autofill or other informational messages.
     */
    private View mSnackbar;

    AutoFillUI(Context context) {
        mContext = context;
        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    void setCallbackLocked(AutoFillUiCallback callback, IBinder activityToken) {
        mHandler.post(() -> {
            if (callback != mCallback && activityToken != mActivityToken) {
                hideAllUiThread();
                mCallback = callback;
                mActivityToken = activityToken;
            }
        });
    }

    /**
     * Displays an error message to the user.
     */
    void showError(CharSequence message) {
        // TODO(b/33197203): proper implementation
        UiThread.getHandler().post(() -> {
            if (!hasCallback()) {
                return;
            }
            hideAllUiThread();
            Toast.makeText(mContext, "AutoFill error: " + message, Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Hides the fill UI.
     */
    void hideFillUi() {
        mHandler.post(() -> hideFillUiUiThread());
    }

    @android.annotation.UiThread
    private void hideFillUiUiThread() {
        if (mFillWindow != null) {
            if (DEBUG) Slog.d(TAG, "hideFillUiUiThread(): hide" + mFillWindow);
            mFillWindow.hide();
        }
        mFillWindow = null;
        mDatasetPicker = null;
    }

    void updateFillUi(@Nullable String filterText) {
        mHandler.post(() -> {
            if (!hasCallback()) {
                return;
            }
            hideSnackbarUiThread();
            if (mDatasetPicker != null) {
                mDatasetPicker.update(filterText);
            }
        });
    }

    /**
     * Shows the fill UI, removing the previous fill UI if the has changed.
     *
     * @param focusedId the currently focused field
     * @param response the current fill response
     * @param bounds bounds of the view to be filled, used if changed
     * @param filterText text of the view to be filled, used if changed
     */
    void showFillUi(AutoFillId focusedId, @Nullable FillResponse response, Rect bounds,
            String filterText) {
        mHandler.post(() -> {
            if (!hasCallback()) {
                return;
            }
            hideSnackbarUiThread();
            final View content;
            if (response.getPresentation() != null) {
                content = response.getPresentation().apply(mContext, null);
                content.setOnClickListener((view) -> {
                    if (mCallback != null) {
                        mCallback.authenticate(response.getAuthentication());
                    }
                    hideFillUiUiThread();
                });
            } else {
                mDatasetPicker = new DatasetPicker(mContext, response.getDatasets(),
                        focusedId, new DatasetPicker.Listener() {
                    @Override
                    public void onDatasetPicked(Dataset dataset) {
                        if (mCallback != null) {
                            mCallback.fill(dataset);
                        }
                        hideFillUiUiThread();
                    }

                    @Override
                    public void onCanceled() {
                        hideFillUiUiThread();
                    }
                });
                mDatasetPicker.update(filterText);
                content = mDatasetPicker;
            }

            mFillWindow = new AnchoredWindow(mWm, mActivityToken, content);
            mFillWindow.show(bounds);
        });
    }

    /**
     * Shows the UI asking the user to save for auto-fill.
     */
    void showSaveUi() {
        mHandler.post(() -> {
            if (!hasCallback()) {
                return;
            }
            hideAllUiThread();
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
        });
    }

    /**
     * Hides all UI affordances.
     */
    void hideAll() {
        mHandler.post(() -> hideAllUiThread());
    }

    @android.annotation.UiThread
    private void hideAllUiThread() {
        hideSnackbarUiThread();
        hideFillUiUiThread();
    }

    void dump(PrintWriter pw) {
        pw.println("AufoFill UI");
        final String prefix = "  ";
        pw.print(prefix); pw.print("mActivityToken: "); pw.println(mActivityToken);
        pw.print(prefix); pw.print("mSnackBar: "); pw.println(mSnackbar);
    }

    //similar to a snackbar, but can be a bit custom since it is more than just text. This will
    //allow two buttons for saving or not saving the autofill for instance as well.
    @android.annotation.UiThread
    private void showSnackbarUiThread(View snackBar) {
        final LayoutParams params = new LayoutParams();
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

        mHandler.post(() -> {
            mSnackbar = snackBar;
            mWm.addView(mSnackbar, params);
        });

        if (DEBUG) {
            Slog.d(TAG, "showSnackbar(): auto dismissing it in " + SNACK_BAR_LIFETIME_MS + " ms");
        }
        mHandler.sendMessageDelayed(mHandler
                        .obtainMessage(MSG_HIDE_SNACK_BAR), SNACK_BAR_LIFETIME_MS);
    }

    @android.annotation.UiThread
    private void hideSnackbarUiThread() {
        mHandler.removeMessages(MSG_HIDE_SNACK_BAR);
        if (mSnackbar != null) {
            mWm.removeView(mSnackbar);
            mSnackbar = null;
        }
    }

    private boolean hasCallback() {
        return mCallback != null;
    }

    interface AutoFillUiCallback {
        void authenticate(IntentSender intent);
        void fill(Dataset dataset);
        void save();
    }
}
