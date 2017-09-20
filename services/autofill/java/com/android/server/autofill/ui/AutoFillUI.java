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
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveInfo;
import android.service.autofill.ValueFinder;
import android.text.TextUtils;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.IAutofillWindowPresenter;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.UiThread;
import com.android.server.autofill.Helper;

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

    private final @NonNull OverlayControl mOverlayControl;

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
        mOverlayControl = new OverlayControl(context);
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
     * @param servicePackageName package name of the autofill service filling the activity
     * @param packageName package name of the activity that is filled
     * @param callback Identifier for the caller
     */
    public void showFillUi(@NonNull AutofillId focusedId, @NonNull FillResponse response,
            @Nullable String filterText, @Nullable String servicePackageName,
            @NonNull String packageName, @NonNull AutoFillUiCallback callback) {
        if (sDebug) {
            final int size = filterText == null ? 0 : filterText.length();
            Slog.d(TAG, "showFillUi(): id=" + focusedId + ", filter=" + size + " chars");
        }
        final LogMaker log =
                Helper.newLogMaker(MetricsEvent.AUTOFILL_FILL_UI, packageName, servicePackageName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_FILTERTEXT_LEN,
                        filterText == null ? 0 : filterText.length())
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_DATASETS,
                        response.getDatasets() == null ? 0 : response.getDatasets().size());

        mHandler.post(() -> {
            if (callback != mCallback) {
                return;
            }
            hideAllUiThread(callback);
            mFillUi = new FillUi(mContext, response, focusedId,
                    filterText, mOverlayControl, new FillUi.Callback() {
                @Override
                public void onResponsePicked(FillResponse response) {
                    log.setType(MetricsEvent.TYPE_DETAIL);
                    hideFillUiUiThread(callback);
                    if (mCallback != null) {
                        mCallback.authenticate(response.getRequestId(),
                                AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED,
                                response.getAuthentication(), response.getClientState());
                    }
                }

                @Override
                public void onDatasetPicked(Dataset dataset) {
                    log.setType(MetricsEvent.TYPE_ACTION);
                    hideFillUiUiThread(callback);
                    if (mCallback != null) {
                        final int datasetIndex = response.getDatasets().indexOf(dataset);
                        mCallback.fill(response.getRequestId(), datasetIndex, dataset);
                    }
                }

                @Override
                public void onCanceled() {
                    log.setType(MetricsEvent.TYPE_DISMISS);
                    hideFillUiUiThread(callback);
                }

                @Override
                public void onDestroy() {
                    if (log.getType() == MetricsEvent.TYPE_UNKNOWN) {
                        log.setType(MetricsEvent.TYPE_CLOSE);
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
    public void showSaveUi(@NonNull CharSequence serviceLabel, @NonNull Drawable serviceIcon,
            @Nullable String servicePackageName, @NonNull SaveInfo info,
            @NonNull ValueFinder valueFinder, @NonNull String packageName,
            @NonNull AutoFillUiCallback callback, @NonNull PendingUi pendingSaveUi) {
        if (sVerbose) Slog.v(TAG, "showSaveUi() for " + packageName + ": " + info);
        int numIds = 0;
        numIds += info.getRequiredIds() == null ? 0 : info.getRequiredIds().length;
        numIds += info.getOptionalIds() == null ? 0 : info.getOptionalIds().length;

        final LogMaker log =
                Helper.newLogMaker(MetricsEvent.AUTOFILL_SAVE_UI, packageName, servicePackageName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_IDS, numIds);

        mHandler.post(() -> {
            if (callback != mCallback) {
                return;
            }
            hideAllUiThread(callback);
            mSaveUi = new SaveUi(mContext, pendingSaveUi, serviceLabel, serviceIcon,
                    servicePackageName, packageName, info, valueFinder, mOverlayControl,
                    new SaveUi.OnSaveListener() {
                @Override
                public void onSave() {
                    log.setType(MetricsEvent.TYPE_ACTION);
                    hideSaveUiUiThread(mCallback);
                    if (mCallback != null) {
                        mCallback.save();
                    }
                    destroySaveUiUiThread(pendingSaveUi, true);
                }

                @Override
                public void onCancel(IntentSender listener) {
                    log.setType(MetricsEvent.TYPE_DISMISS);
                    hideSaveUiUiThread(mCallback);
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
                    destroySaveUiUiThread(pendingSaveUi, true);
                }

                @Override
                public void onDestroy() {
                    if (log.getType() == MetricsEvent.TYPE_UNKNOWN) {
                        log.setType(MetricsEvent.TYPE_CLOSE);

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
     * Executes an operation in the pending save UI, if any.
     */
    public void onPendingSaveUi(int operation, @NonNull IBinder token) {
        mHandler.post(() -> {
            if (mSaveUi != null) {
                mSaveUi.onPendingUi(operation, token);
            } else {
                Slog.w(TAG, "onPendingSaveUi(" + operation + "): no save ui");
            }
        });
    }

    /**
     * Hides all UI affordances.
     */
    public void hideAll(@Nullable AutoFillUiCallback callback) {
        mHandler.post(() -> hideAllUiThread(callback));
    }

    /**
     * Destroy all UI affordances.
     */
    public void destroyAll(@Nullable PendingUi pendingSaveUi,
            @Nullable AutoFillUiCallback callback, boolean notifyClient) {
        mHandler.post(() -> destroyAllUiThread(pendingSaveUi, callback, notifyClient));
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
    @Nullable
    private PendingUi hideSaveUiUiThread(@Nullable AutoFillUiCallback callback) {
        if (sVerbose) {
            Slog.v(TAG, "hideSaveUiUiThread(): mSaveUi=" + mSaveUi + ", callback=" + callback
                    + ", mCallback=" + mCallback);
        }
        if (mSaveUi != null && (callback == null || callback == mCallback)) {
            return mSaveUi.hide();
        }
        return null;
    }

    @android.annotation.UiThread
    private void destroySaveUiUiThread(@Nullable PendingUi pendingSaveUi, boolean notifyClient) {
        if (mSaveUi == null) {
            // Calling destroySaveUiUiThread() twice is normal - it usually happens when the
            // first call is made after the SaveUI is hidden and the second when the session is
            // finished.
            if (sDebug) Slog.d(TAG, "destroySaveUiUiThread(): already destroyed");
            return;
        }

        if (sDebug) Slog.d(TAG, "destroySaveUiUiThread(): " + pendingSaveUi);
        mSaveUi.destroy();
        mSaveUi = null;
        if (pendingSaveUi != null && notifyClient) {
            try {
                if (sDebug) Slog.d(TAG, "destroySaveUiUiThread(): notifying client");
                pendingSaveUi.client.setSaveUiState(pendingSaveUi.id, false);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client to set save UI state to hidden: " + e);
            }
        }
    }

    @android.annotation.UiThread
    private void destroyAllUiThread(@Nullable PendingUi pendingSaveUi,
            @Nullable AutoFillUiCallback callback, boolean notifyClient) {
        hideFillUiUiThread(callback);
        destroySaveUiUiThread(pendingSaveUi, notifyClient);
    }

    @android.annotation.UiThread
    private void hideAllUiThread(@Nullable AutoFillUiCallback callback) {
        hideFillUiUiThread(callback);
        final PendingUi pendingSaveUi = hideSaveUiUiThread(callback);
        if (pendingSaveUi != null && pendingSaveUi.getState() == PendingUi.STATE_FINISHED) {
            if (sDebug) {
                Slog.d(TAG, "hideAllUiThread(): "
                        + "destroying Save UI because pending restoration is finished");
            }
            destroySaveUiUiThread(pendingSaveUi, true);
        }
    }
}
