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
package com.android.server.autofill.ui;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.IntentSender;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveInfo;
import android.text.TextUtils;
import android.util.Slog;
import android.view.autofill.AutoFillId;
import android.widget.Toast;

import com.android.server.UiThread;

import java.io.PrintWriter;

/**
 * Handles all auto-fill related UI tasks. The UI has two components:
 * fill UI that shows a popup style window anchored at the focused
 * input field for choosing a dataset to fill or trigger the response
 * authentication flow; save UI that shows a toast style window for
 * managing saving of user edits.
 */
public final class AutoFillUI {
    private static final String TAG = "AutoFillUI";

    private final Handler mHandler = UiThread.getHandler();
    private final @NonNull Context mContext;

    private @Nullable FillUi mFillUi;
    private @Nullable SaveUi mSaveUi;

    private @Nullable AutoFillUiCallback mCallback;
    private @Nullable IBinder mWindowToken;

    public interface AutoFillUiCallback {
        void authenticate(@NonNull IntentSender intent);
        void fill(@NonNull Dataset dataset);
        void save();
    }

    public AutoFillUI(@NonNull Context context) {
        mContext = context;
    }

    public void setCallback(@Nullable AutoFillUiCallback callback,
            @Nullable IBinder windowToken) {
        mHandler.post(() -> {
            if (mCallback != callback || mWindowToken != windowToken) {
                hideAllUiThread();
                mCallback = callback;
                mWindowToken = windowToken;
            }
        });
    }

    /**
     * Displays an error message to the user.
     */
    public void showError(@Nullable CharSequence message) {
        mHandler.post(() -> {
            if (!hasCallback()) {
                return;
            }
            hideAllUiThread();
            if (!TextUtils.isEmpty(message)) {
                Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Hides the fill UI.
     */
    public void hideFillUi() {
        mHandler.post(this::hideFillUiUiThread);
    }

    /**
     * Filters the options in the fill UI.
     *
     * @param filterText The filter prefix.
     */
    public void filterFillUi(@Nullable String filterText) {
        mHandler.post(() -> {
            if (!hasCallback()) {
                return;
            }
            hideSaveUiUiThread();
            if (mFillUi != null) {
                mFillUi.filter(filterText);
            }
        });
    }

    /**
     * Updates the position of the fill UI.
     *
     * @param anchoredBounds The bounds of the anchor view.
     */
    public void updateFillUi(@NonNull Rect anchoredBounds) {
        mHandler.post(() -> {
            if (!hasCallback()) {
                return;
            }
            hideSaveUiUiThread();
            if (mFillUi != null) {
                mFillUi.update(anchoredBounds);
            }
        });
    }

    /**
     * Shows the fill UI, removing the previous fill UI if the has changed.
     *
     * @param focusedId the currently focused field
     * @param response the current fill response
     * @param anchorBounds bounds of the focused view
     * @param filterText text of the view to be filled
     */
    public void showFillUi(@NonNull AutoFillId focusedId, @NonNull FillResponse response,
            @NonNull Rect anchorBounds, @Nullable String filterText) {
        mHandler.post(() -> {
            if (!hasCallback()) {
                return;
            }
            hideAllUiThread();
            mFillUi = new FillUi(mContext, response, focusedId,
                    mWindowToken, anchorBounds, filterText, new FillUi.Callback() {
                @Override
                public void onResponsePicked(FillResponse response) {
                    hideFillUiUiThread();
                    if (mCallback != null) {
                        mCallback.authenticate(response.getAuthentication());
                    }
                }

                @Override
                public void onDatasetPicked(Dataset dataset) {
                    hideFillUiUiThread();
                    if (mCallback != null) {
                        mCallback.fill(dataset);
                    }
                    // TODO(b/33197203): add MetricsLogger call
                }

                @Override
                public void onCanceled() {
                    hideFillUiUiThread();
                    // TODO(b/33197203): add MetricsLogger call
                }
            });
        });
    }

    /**
     * Shows the UI asking the user to save for auto-fill.
     */
    public void showSaveUi(@NonNull CharSequence providerLabel, @NonNull SaveInfo info) {
        mHandler.post(() -> {
            if (!hasCallback()) {
                return;
            }
            hideAllUiThread();
            mSaveUi = new SaveUi(mContext, providerLabel, info,
                    new SaveUi.OnSaveListener() {
                @Override
                public void onSave() {
                    hideSaveUiUiThread();
                    if (mCallback != null) {
                        mCallback.save();
                    }
                    // TODO(b/33197203): add MetricsLogger call
                }

                @Override
                public void onCancel(IntentSender listener) {
                    // TODO(b/33197203): add MetricsLogger call
                    hideSaveUiUiThread();
                    if (listener != null) {
                        try {
                            listener.sendIntent(mContext, 0, null, null, null);
                        } catch (IntentSender.SendIntentException e) {
                            Slog.e(TAG, "Error starting negative action listener: "
                                    + listener, e);
                        }
                    }
                }
            });
        });
    }

    /**
     * Hides all UI affordances.
     */
    public void hideAll() {
        mHandler.post(this::hideAllUiThread);
    }

    public void dump(PrintWriter pw) {
        pw.println("AufoFill UI");
        final String prefix = "  ";
        pw.print(prefix); pw.print("showsFillUi: "); pw.println(mFillUi != null);
        pw.print(prefix); pw.print("showsSaveUi: "); pw.println(mSaveUi != null);
    }

    @android.annotation.UiThread
    private void hideFillUiUiThread() {
        if (mFillUi != null) {
            mFillUi.destroy();
            mFillUi = null;
        }
    }

    @android.annotation.UiThread
    private void hideSaveUiUiThread() {
        if (mSaveUi != null) {
            mSaveUi.destroy();
            mSaveUi = null;
        }
    }

    @android.annotation.UiThread
    private void hideAllUiThread() {
        hideFillUiUiThread();
        hideSaveUiUiThread();
    }

    private boolean hasCallback() {
        return mCallback != null;
    }
}
