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

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.IntentSender;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.Handler;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveInfo;
import android.text.TextUtils;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.IAutofillWindowPresenter;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.server.UiThread;

import java.io.PrintWriter;

/**
 * Handles all autofill related UI tasks. The UI has two components:
 * fill UI that shows a popup style window anchored at the focused
 * input field for choosing a dataset to fill or trigger the response
 * authentication flow; save UI that shows a toast style window for
 * managing saving of user edits.
 */
public final class AutoFillUI {
    private static final String TAG = "AutofillUI";

    private final Handler mHandler = UiThread.getHandler();
    private final @NonNull Context mContext;

    private @Nullable FillUi mFillUi;
    private @Nullable SaveUi mSaveUi;

    private @Nullable AutoFillUiCallback mCallback;

    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    public interface AutoFillUiCallback {
        void authenticate(int requestId, int datasetIndex, @NonNull IntentSender intent,
                @Nullable Bundle extras);
        void fill(int requestId, int datasetIndex, @NonNull Dataset dataset);
        void save();
        void cancelSave();
        void requestShowFillUi(AutofillId id, int width, int height,
                IAutofillWindowPresenter presenter);
        void requestHideFillUi(AutofillId id);
        void startIntentSender(IntentSender intentSender);
    }

    public AutoFillUI(@NonNull Context context) {
        mContext = context;
    }

    public void setCallback(@NonNull AutoFillUiCallback callback) {
        mHandler.post(() -> {
            if (mCallback != callback) {
                if (mCallback != null) {
                    hideAllUiThread(mCallback);
                }

                mCallback = callback;
            }
        });
    }

    public void clearCallback(@NonNull AutoFillUiCallback callback) {
        mHandler.post(() -> {
            if (mCallback == callback) {
                hideAllUiThread(callback);
                mCallback = null;
            }
        });
    }

    /**
     * Displays an error message to the user.
     */
    public void showError(int resId, @NonNull AutoFillUiCallback callback) {
        showError(mContext.getString(resId), callback);
    }

    /**
     * Displays an error message to the user.
     */
    public void showError(@Nullable CharSequence message, @NonNull AutoFillUiCallback callback) {
        Slog.w(TAG, "showError(): " + message);

        mHandler.post(() -> {
            if (mCallback != callback) {
                return;
            }
            hideAllUiThread(callback);
            if (!TextUtils.isEmpty(message)) {
                Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Hides the fill UI.
     */
    public void hideFillUi(@NonNull AutoFillUiCallback callback) {
        mHandler.post(() -> hideFillUiUiThread(callback));
    }

    /**
     * Filters the options in the fill UI.
     *
     * @param filterText The filter prefix.
     */
    public void filterFillUi(@Nullable String filterText, @NonNull AutoFillUiCallback callback) {
        mHandler.post(() -> {
            if (callback != mCallback) {
                return;
            }
            hideSaveUiUiThread(callback);
            if (mFillUi != null) {
                mFillUi.setFilterText(filterText);
            }
        });
    }

    /**
     * Shows the fill UI, removing the previous fill UI if the has changed.
     *
     * @param focusedId the currently focused field
     * @param response the current fill response
     * @param filterText text of the view to be filled
     * @param packageName package name of the activity that is filled
     * @param callback Identifier for the caller
     */
    public void showFillUi(@NonNull AutofillId focusedId, @NonNull FillResponse response,
            @Nullable String filterText, @NonNull String packageName,
            @NonNull AutoFillUiCallback callback) {
        if (sDebug) {
            Slog.d(TAG, "showFillUi(): id=" + focusedId + ", filter=" + filterText);
        }
        final LogMaker log = (new LogMaker(MetricsProto.MetricsEvent.AUTOFILL_FILL_UI))
                .setPackageName(packageName)
                .addTaggedData(MetricsProto.MetricsEvent.FIELD_AUTOFILL_FILTERTEXT_LEN,
                        filterText == null ? 0 : filterText.length())
                .addTaggedData(MetricsProto.MetricsEvent.FIELD_AUTOFILL_NUM_DATASETS,
                        response.getDatasets() == null ? 0 : response.getDatasets().size());

        mHandler.post(() -> {
            if (callback != mCallback) {
                return;
            }
            hideAllUiThread(callback);
            mFillUi = new FillUi(mContext, response, focusedId,
                    filterText, new FillUi.Callback() {
                @Override
                public void onResponsePicked(FillResponse response) {
                    log.setType(MetricsProto.MetricsEvent.TYPE_DETAIL);
                    hideFillUiUiThread(callback);
                    if (mCallback != null) {
                        mCallback.authenticate(response.getRequestId(),
                                AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED,
                                response.getAuthentication(), response.getClientState());
                    }
                }

                @Override
                public void onDatasetPicked(Dataset dataset) {
                    log.setType(MetricsProto.MetricsEvent.TYPE_ACTION);
                    hideFillUiUiThread(callback);
                    if (mCallback != null) {
                        final int datasetIndex = response.getDatasets().indexOf(dataset);
                        mCallback.fill(response.getRequestId(), datasetIndex, dataset);
                    }
                }

                @Override
                public void onCanceled() {
                    log.setType(MetricsProto.MetricsEvent.TYPE_DISMISS);
                    hideFillUiUiThread(callback);
                }

                @Override
                public void onDestroy() {
                    if (log.getType() == MetricsProto.MetricsEvent.TYPE_UNKNOWN) {
                        log.setType(MetricsProto.MetricsEvent.TYPE_CLOSE);
                    }
                    mMetricsLogger.write(log);
                }

                @Override
                public void requestShowFillUi(int width, int height,
                        IAutofillWindowPresenter windowPresenter) {
                    if (mCallback != null) {
                        mCallback.requestShowFillUi(focusedId, width, height, windowPresenter);
                    }
                }

                @Override
                public void requestHideFillUi() {
                    if (mCallback != null) {
                        mCallback.requestHideFillUi(focusedId);
                    }
                }

                @Override
                public void startIntentSender(IntentSender intentSender) {
                    if (mCallback != null) {
                        mCallback.startIntentSender(intentSender);
                    }
                }
            });
        });
    }

    /**
     * Shows the UI asking the user to save for autofill.
     */
    public void showSaveUi(@NonNull CharSequence providerLabel, @NonNull SaveInfo info,
            @NonNull String packageName, @NonNull AutoFillUiCallback callback) {
        if (sVerbose) Slog.v(TAG, "showSaveUi() for " + packageName + ": " + info);
        int numIds = 0;
        numIds += info.getRequiredIds() == null ? 0 : info.getRequiredIds().length;
        numIds += info.getOptionalIds() == null ? 0 : info.getOptionalIds().length;

        LogMaker log = (new LogMaker(MetricsProto.MetricsEvent.AUTOFILL_SAVE_UI))
                .setPackageName(packageName).addTaggedData(
                        MetricsProto.MetricsEvent.FIELD_AUTOFILL_NUM_IDS, numIds);

        mHandler.post(() -> {
            if (callback != mCallback) {
                return;
            }
            hideAllUiThread(callback);
            mSaveUi = new SaveUi(mContext, providerLabel, info,
                    new SaveUi.OnSaveListener() {
                @Override
                public void onSave() {
                    log.setType(MetricsProto.MetricsEvent.TYPE_ACTION);
                    hideSaveUiUiThread(callback);
                    if (mCallback != null) {
                        mCallback.save();
                    }
                }

                @Override
                public void onCancel(IntentSender listener) {
                    log.setType(MetricsProto.MetricsEvent.TYPE_DISMISS);
                    hideSaveUiUiThread(callback);
                    if (listener != null) {
                        try {
                            listener.sendIntent(mContext, 0, null, null, null);
                        } catch (IntentSender.SendIntentException e) {
                            Slog.e(TAG, "Error starting negative action listener: "
                                    + listener, e);
                        }
                    }
                    if (mCallback != null) {
                        mCallback.cancelSave();
                    }
                }

                @Override
                public void onDestroy() {
                    if (log.getType() == MetricsProto.MetricsEvent.TYPE_UNKNOWN) {
                        log.setType(MetricsProto.MetricsEvent.TYPE_CLOSE);

                        if (mCallback != null) {
                            mCallback.cancelSave();
                        }
                    }
                    mMetricsLogger.write(log);
                }
            });
        });
    }

    /**
     * Hides all UI affordances.
     */
    public void hideAll(@Nullable AutoFillUiCallback callback) {
        mHandler.post(() -> hideAllUiThread(callback));
    }

    public void dump(PrintWriter pw) {
        pw.println("Autofill UI");
        final String prefix = "  ";
        final String prefix2 = "    ";
        if (mFillUi != null) {
            pw.print(prefix); pw.println("showsFillUi: true");
            mFillUi.dump(pw, prefix2);
        } else {
            pw.print(prefix); pw.println("showsFillUi: false");
        }
        if (mSaveUi != null) {
            pw.print(prefix); pw.println("showsSaveUi: true");
            mSaveUi.dump(pw, prefix2);
        } else {
            pw.print(prefix); pw.println("showsSaveUi: false");
        }
    }

    @android.annotation.UiThread
    private void hideFillUiUiThread(@Nullable AutoFillUiCallback callback) {
        if (mFillUi != null && (callback == null || callback == mCallback)) {
            mFillUi.destroy();
            mFillUi = null;
        }
    }

    @android.annotation.UiThread
    private void hideSaveUiUiThread(@Nullable AutoFillUiCallback callback) {
        if (sVerbose) {
            Slog.v(TAG, "hideSaveUiUiThread(): mSaveUi=" + mSaveUi + ", callback=" + callback
                    + ", mCallback=" + mCallback);
        }
        if (mSaveUi != null && (callback == null || callback == mCallback)) {
            mSaveUi.destroy();
            mSaveUi = null;
        }
    }

    @android.annotation.UiThread
    private void hideAllUiThread(@Nullable AutoFillUiCallback callback) {
        hideFillUiUiThread(callback);
        hideSaveUiUiThread(callback);
    }
}
