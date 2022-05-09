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

import static android.service.autofill.FillEventHistory.Event.UI_TYPE_DIALOG;
import static android.service.autofill.FillEventHistory.Event.UI_TYPE_MENU;

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.autofill.Dataset;
import android.service.autofill.FillEventHistory;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveInfo;
import android.service.autofill.ValueFinder;
import android.text.TextUtils;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.IAutofillWindowPresenter;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.LocalServices;
import com.android.server.UiModeManagerInternal;
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
    private @Nullable DialogFillUi mFillDialog;

    private @Nullable AutoFillUiCallback mCallback;

    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    private final @NonNull OverlayControl mOverlayControl;
    private final @NonNull UiModeManagerInternal mUiModeMgr;

    private @Nullable Runnable mCreateFillUiRunnable;
    private @Nullable AutoFillUiCallback mSaveUiCallback;

    public interface AutoFillUiCallback {
        void authenticate(int requestId, int datasetIndex, @NonNull IntentSender intent,
                @Nullable Bundle extras, boolean authenticateInline);
        void fill(int requestId, int datasetIndex, @NonNull Dataset dataset,
                @FillEventHistory.Event.UiType int uiType);
        void save();
        void cancelSave();
        void requestShowFillUi(AutofillId id, int width, int height,
                IAutofillWindowPresenter presenter);
        void requestHideFillUi(AutofillId id);
        void startIntentSenderAndFinishSession(IntentSender intentSender);
        void startIntentSender(IntentSender intentSender, Intent intent);
        void dispatchUnhandledKey(AutofillId id, KeyEvent keyEvent);
        void cancelSession();
        void requestShowSoftInput(AutofillId id);
        void requestFallbackFromFillDialog();
    }

    public AutoFillUI(@NonNull Context context) {
        mContext = context;
        mOverlayControl = new OverlayControl(context);
        mUiModeMgr = LocalServices.getService(UiModeManagerInternal.class);
    }

    public void setCallback(@NonNull AutoFillUiCallback callback) {
        mHandler.post(() -> {
            if (mCallback != callback) {
                if (mCallback != null) {
                    if (isSaveUiShowing()) {
                        // keeps showing the save UI
                        hideFillUiUiThread(callback, true);
                    } else {
                        hideAllUiThread(mCallback);
                    }
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
        mHandler.post(() -> hideFillUiUiThread(callback, true));
    }

    /**
     * Hides the fill UI.
     */
    public void hideFillDialog(@NonNull AutoFillUiCallback callback) {
        mHandler.post(() -> hideFillDialogUiThread(callback));
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
     * @param componentName component name of the activity that is filled
     * @param serviceLabel label of autofill service
     * @param serviceIcon icon of autofill service
     * @param callback identifier for the caller
     * @param sessionId id of the autofill session
     * @param compatMode whether the app is being autofilled in compatibility mode.
     */
    public void showFillUi(@NonNull AutofillId focusedId, @NonNull FillResponse response,
            @Nullable String filterText, @Nullable String servicePackageName,
            @NonNull ComponentName componentName, @NonNull CharSequence serviceLabel,
            @NonNull Drawable serviceIcon, @NonNull AutoFillUiCallback callback, int sessionId,
            boolean compatMode) {
        if (sDebug) {
            final int size = filterText == null ? 0 : filterText.length();
            Slog.d(TAG, "showFillUi(): id=" + focusedId + ", filter=" + size + " chars");
        }
        final LogMaker log = Helper
                .newLogMaker(MetricsEvent.AUTOFILL_FILL_UI, componentName, servicePackageName,
                        sessionId, compatMode)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_FILTERTEXT_LEN,
                        filterText == null ? 0 : filterText.length())
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_DATASETS,
                        response.getDatasets() == null ? 0 : response.getDatasets().size());

        final Runnable createFillUiRunnable = () -> {
            if (callback != mCallback) {
                return;
            }
            hideAllUiThread(callback);
            mFillUi = new FillUi(mContext, response, focusedId,
                    filterText, mOverlayControl, serviceLabel, serviceIcon,
                    mUiModeMgr.isNightMode(),
                    new FillUi.Callback() {
                @Override
                public void onResponsePicked(FillResponse response) {
                    log.setType(MetricsEvent.TYPE_DETAIL);
                    hideFillUiUiThread(callback, true);
                    if (mCallback != null) {
                        mCallback.authenticate(response.getRequestId(),
                                AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED,
                                response.getAuthentication(), response.getClientState(),
                                /* authenticateInline= */ false);
                    }
                }

                @Override
                public void onDatasetPicked(Dataset dataset) {
                    log.setType(MetricsEvent.TYPE_ACTION);
                    hideFillUiUiThread(callback, true);
                    if (mCallback != null) {
                        final int datasetIndex = response.getDatasets().indexOf(dataset);
                        mCallback.fill(response.getRequestId(), datasetIndex,
                                dataset, UI_TYPE_MENU);
                    }
                }

                @Override
                public void onCanceled() {
                    log.setType(MetricsEvent.TYPE_DISMISS);
                    hideFillUiUiThread(callback, true);
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
                        mCallback.startIntentSenderAndFinishSession(intentSender);
                    }
                }

                @Override
                public void dispatchUnhandledKey(KeyEvent keyEvent) {
                    if (mCallback != null) {
                        mCallback.dispatchUnhandledKey(focusedId, keyEvent);
                    }
                }

                @Override
                public void cancelSession() {
                    if (mCallback != null) {
                        mCallback.cancelSession();
                    }
                }
            });
        };

        if (isSaveUiShowing()) {
            // postpone creating the fill UI for showing the save UI
            if (sDebug) Slog.d(TAG, "postpone fill UI request..");
            mCreateFillUiRunnable = createFillUiRunnable;
        } else {
            mHandler.post(createFillUiRunnable);
        }
    }

    /**
     * Shows the UI asking the user to save for autofill.
     */
    public void showSaveUi(@NonNull CharSequence serviceLabel, @NonNull Drawable serviceIcon,
            @Nullable String servicePackageName, @NonNull SaveInfo info,
            @NonNull ValueFinder valueFinder, @NonNull ComponentName componentName,
            @NonNull AutoFillUiCallback callback, @NonNull PendingUi pendingSaveUi,
            boolean isUpdate, boolean compatMode) {
        if (sVerbose) {
            Slog.v(TAG, "showSaveUi(update=" + isUpdate + ") for " + componentName.toShortString()
                    + ": " + info);
        }
        int numIds = 0;
        numIds += info.getRequiredIds() == null ? 0 : info.getRequiredIds().length;
        numIds += info.getOptionalIds() == null ? 0 : info.getOptionalIds().length;

        final LogMaker log = Helper
                .newLogMaker(MetricsEvent.AUTOFILL_SAVE_UI, componentName, servicePackageName,
                        pendingSaveUi.sessionId, compatMode)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_IDS, numIds);
        if (isUpdate) {
            log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_UPDATE, 1);
        }

        mHandler.post(() -> {
            if (callback != mCallback) {
                return;
            }
            hideAllUiThread(callback);
            mSaveUiCallback = callback;
            mSaveUi = new SaveUi(mContext, pendingSaveUi, serviceLabel, serviceIcon,
                    servicePackageName, componentName, info, valueFinder, mOverlayControl,
                    new SaveUi.OnSaveListener() {
                @Override
                public void onSave() {
                    log.setType(MetricsEvent.TYPE_ACTION);
                    hideSaveUiUiThread(callback);
                    callback.save();
                    destroySaveUiUiThread(pendingSaveUi, true);
                }

                @Override
                public void onCancel(IntentSender listener) {
                    log.setType(MetricsEvent.TYPE_DISMISS);
                    hideSaveUiUiThread(callback);
                    if (listener != null) {
                        try {
                            listener.sendIntent(mContext, 0, null, null, null);
                        } catch (IntentSender.SendIntentException e) {
                            Slog.e(TAG, "Error starting negative action listener: "
                                    + listener, e);
                        }
                    }
                    callback.cancelSave();
                    destroySaveUiUiThread(pendingSaveUi, true);
                }

                @Override
                public void onDestroy() {
                    if (log.getType() == MetricsEvent.TYPE_UNKNOWN) {
                        log.setType(MetricsEvent.TYPE_CLOSE);

                        callback.cancelSave();
                    }
                    mMetricsLogger.write(log);
                }

                @Override
                public void startIntentSender(IntentSender intentSender, Intent intent) {
                    callback.startIntentSender(intentSender, intent);
                }
            }, mUiModeMgr.isNightMode(), isUpdate, compatMode);
        });
    }

    /**
     * Shows the UI asking the user to choose for autofill.
     */
    public void showFillDialog(@NonNull AutofillId focusedId, @NonNull FillResponse response,
            @Nullable String filterText, @Nullable String servicePackageName,
            @NonNull ComponentName componentName, @Nullable Drawable serviceIcon,
            @NonNull AutoFillUiCallback callback, int sessionId, boolean compatMode) {
        if (sVerbose) {
            Slog.v(TAG, "showFillDialog for "
                    + componentName.toShortString() + ": " + response);
        }

        final LogMaker log = Helper
                .newLogMaker(MetricsEvent.AUTOFILL_FILL_UI, componentName, servicePackageName,
                        sessionId, compatMode)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_FILTERTEXT_LEN,
                        filterText == null ? 0 : filterText.length())
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_DATASETS,
                        response.getDatasets() == null ? 0 : response.getDatasets().size());

        mHandler.post(() -> {
            if (callback != mCallback) {
                return;
            }
            hideAllUiThread(callback);
            mFillDialog = new DialogFillUi(mContext, response, focusedId, filterText,
                    serviceIcon, servicePackageName, componentName, mOverlayControl,
                    mUiModeMgr.isNightMode(), new DialogFillUi.UiCallback() {
                        @Override
                        public void onResponsePicked(FillResponse response) {
                            log(MetricsEvent.TYPE_DETAIL);
                            hideFillDialogUiThread(callback);
                            if (mCallback != null) {
                                mCallback.authenticate(response.getRequestId(),
                                        AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED,
                                        response.getAuthentication(), response.getClientState(),
                                        /* authenticateInline= */ false);
                            }
                        }

                        @Override
                        public void onDatasetPicked(Dataset dataset) {
                            log(MetricsEvent.TYPE_ACTION);
                            hideFillDialogUiThread(callback);
                            if (mCallback != null) {
                                final int datasetIndex = response.getDatasets().indexOf(dataset);
                                mCallback.fill(response.getRequestId(), datasetIndex, dataset,
                                        UI_TYPE_DIALOG);
                            }
                        }

                        @Override
                        public void onDismissed() {
                            log(MetricsEvent.TYPE_DISMISS);
                            hideFillDialogUiThread(callback);
                            callback.requestShowSoftInput(focusedId);
                        }

                        @Override
                        public void onCanceled() {
                            log(MetricsEvent.TYPE_CLOSE);
                            hideFillDialogUiThread(callback);
                            callback.requestShowSoftInput(focusedId);
                            callback.requestFallbackFromFillDialog();
                        }

                        @Override
                        public void startIntentSender(IntentSender intentSender) {
                            mCallback.startIntentSenderAndFinishSession(intentSender);
                        }

                        private void log(int type) {
                            log.setType(type);
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
     * Hides all autofill UIs.
     */
    public void hideAll(@Nullable AutoFillUiCallback callback) {
        mHandler.post(() -> hideAllUiThread(callback));
    }

    /**
     * Destroy all autofill UIs.
     */
    public void destroyAll(@Nullable PendingUi pendingSaveUi,
            @Nullable AutoFillUiCallback callback, boolean notifyClient) {
        mHandler.post(() -> destroyAllUiThread(pendingSaveUi, callback, notifyClient));
    }

    public boolean isSaveUiShowing() {
        return mSaveUi == null ? false : mSaveUi.isShowing();
    }

    public boolean isFillDialogShowing() {
        return mFillDialog == null ? false : mFillDialog.isShowing();
    }

    public void dump(PrintWriter pw) {
        pw.println("Autofill UI");
        final String prefix = "  ";
        final String prefix2 = "    ";
        pw.print(prefix); pw.print("Night mode: "); pw.println(mUiModeMgr.isNightMode());
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
        if (mFillDialog != null) {
            pw.print(prefix); pw.println("showsFillDialog: true");
            mFillDialog.dump(pw, prefix2);
        } else {
            pw.print(prefix); pw.println("showsFillDialog: false");
        }
    }

    @android.annotation.UiThread
    private void hideFillUiUiThread(@Nullable AutoFillUiCallback callback, boolean notifyClient) {
        if (mFillUi != null && (callback == null || callback == mCallback)) {
            mFillUi.destroy(notifyClient);
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

        if (mSaveUi != null && mSaveUiCallback == callback) {
            return mSaveUi.hide();
        }
        return null;
    }

    @android.annotation.UiThread
    private void hideFillDialogUiThread(@Nullable AutoFillUiCallback callback) {
        if (mFillDialog != null && (callback == null || callback == mCallback)) {
            mFillDialog.destroy();
            mFillDialog = null;
        }
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
        mSaveUiCallback = null;
        if (pendingSaveUi != null && notifyClient) {
            try {
                if (sDebug) Slog.d(TAG, "destroySaveUiUiThread(): notifying client");
                pendingSaveUi.client.setSaveUiState(pendingSaveUi.sessionId, false);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client to set save UI state to hidden: " + e);
            }
        }

        if (mCreateFillUiRunnable != null) {
            if (sDebug) Slog.d(TAG, "start the pending fill UI request..");
            mHandler.post(mCreateFillUiRunnable);
            mCreateFillUiRunnable = null;
        }
    }

    @android.annotation.UiThread
    private void destroyAllUiThread(@Nullable PendingUi pendingSaveUi,
            @Nullable AutoFillUiCallback callback, boolean notifyClient) {
        hideFillUiUiThread(callback, notifyClient);
        hideFillDialogUiThread(callback);
        destroySaveUiUiThread(pendingSaveUi, notifyClient);
    }

    @android.annotation.UiThread
    private void hideAllUiThread(@Nullable AutoFillUiCallback callback) {
        hideFillUiUiThread(callback, true);
        hideFillDialogUiThread(callback);
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
