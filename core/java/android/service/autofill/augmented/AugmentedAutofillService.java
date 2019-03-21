/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.service.autofill.augmented;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.autofill.augmented.PresentationParams.SystemPopupPresentationParams;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAugmentedAutofillManagerClient;
import android.view.autofill.IAutofillWindowPresenter;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * A service used to augment the Autofill subsystem by potentially providing autofill data when the
 * "standard" workflow failed (for example, because the standard AutofillService didn't have data).
 *
 * @hide
 */
@SystemApi
@TestApi
public abstract class AugmentedAutofillService extends Service {

    private static final String TAG = AugmentedAutofillService.class.getSimpleName();

    // TODO(b/123100811): STOPSHIP use dynamic value, or change to false
    static final boolean DEBUG = true;
    static final boolean VERBOSE = false;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_AUGMENTED_AUTOFILL_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.autofill.augmented.AugmentedAutofillService";

    private Handler mHandler;

    private SparseArray<AutofillProxy> mAutofillProxies;

    private final IAugmentedAutofillService mInterface = new IAugmentedAutofillService.Stub() {

        @Override
        public void onConnected() {
            mHandler.sendMessage(obtainMessage(AugmentedAutofillService::handleOnConnected,
                    AugmentedAutofillService.this));
        }

        @Override
        public void onDisconnected() {
            mHandler.sendMessage(obtainMessage(AugmentedAutofillService::handleOnDisconnected,
                    AugmentedAutofillService.this));
        }

        @Override
        public void onFillRequest(int sessionId, IBinder client, int taskId,
                ComponentName componentName, AutofillId focusedId, AutofillValue focusedValue,
                long requestTime, IFillCallback callback) {
            mHandler.sendMessage(obtainMessage(AugmentedAutofillService::handleOnFillRequest,
                    AugmentedAutofillService.this, sessionId, client, taskId, componentName,
                    focusedId, focusedValue, requestTime, callback));
        }

        @Override
        public void onDestroyAllFillWindowsRequest() {
            mHandler.sendMessage(
                    obtainMessage(AugmentedAutofillService::handleOnDestroyAllFillWindowsRequest,
                            AugmentedAutofillService.this));
        }
    };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
    }

    /** @hide */
    @Override
    public final IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mHandler.sendMessage(obtainMessage(AugmentedAutofillService::handleOnUnbind,
                AugmentedAutofillService.this));
        return false;
    }

    /**
     * Called when the Android system connects to service.
     *
     * <p>You should generally do initialization here rather than in {@link #onCreate}.
     */
    public void onConnected() {
    }

    /**
     * Asks the service to handle an "augmented" autofill request.
     *
     * <p>This method is called when the "stantard" autofill service cannot handle a request, which
     * typically occurs when:
     * <ul>
     *   <li>Service does not recognize what should be autofilled.
     *   <li>Service does not have data to fill the request.
     *   <li>Service blacklisted that app (or activity) for autofill.
     *   <li>App disabled itself for autofill.
     * </ul>
     *
     * <p>Differently from the standard autofill workflow, on augmented autofill the service is
     * responsible to generate the autofill UI and request the Android system to autofill the
     * activity when the user taps an action in that UI (through the
     * {@link FillController#autofill(List)} method).
     *
     * <p>The service <b>MUST</b> call {@link
     * FillCallback#onSuccess(android.service.autofill.augmented.FillResponse)} as soon as possible,
     * passing {@code null} when it cannot fulfill the request.
     * @param request the request to handle.
     * @param cancellationSignal signal for observing cancellation requests. The system will use
     *     this to notify you that the fill result is no longer needed and you should stop
     *     handling this fill request in order to save resources.
     * @param controller object used to interact with the autofill system.
     * @param callback object used to notify the result of the request. Service <b>must</b> call
     * {@link FillCallback#onSuccess(android.service.autofill.augmented.FillResponse)}.
     */
    public void onFillRequest(@NonNull FillRequest request,
            @NonNull CancellationSignal cancellationSignal, @NonNull FillController controller,
            @NonNull FillCallback callback) {
    }

    /**
     * Called when the Android system disconnects from the service.
     *
     * <p> At this point this service may no longer be an active {@link AugmentedAutofillService}.
     */
    public void onDisconnected() {
    }

    private void handleOnConnected() {
        onConnected();
    }

    private void handleOnDisconnected() {
        onDisconnected();
    }

    private void handleOnFillRequest(int sessionId, @NonNull IBinder client, int taskId,
            @NonNull ComponentName componentName, @NonNull AutofillId focusedId,
            @Nullable AutofillValue focusedValue, long requestTime,
            @NonNull IFillCallback callback) {
        if (mAutofillProxies == null) {
            mAutofillProxies = new SparseArray<>();
        }

        final ICancellationSignal transport = CancellationSignal.createTransport();
        final CancellationSignal cancellationSignal = CancellationSignal.fromTransport(transport);
        AutofillProxy proxy = mAutofillProxies.get(sessionId);
        if (proxy == null) {
            proxy = new AutofillProxy(sessionId, client, taskId, componentName, focusedId,
                    focusedValue, requestTime, callback, cancellationSignal);
            mAutofillProxies.put(sessionId,  proxy);
        } else {
            // TODO(b/123099468): figure out if it's ok to reuse the proxy; add logging
            if (DEBUG) Log.d(TAG, "Reusing proxy for session " + sessionId);
            proxy.update(focusedId, focusedValue, callback);
        }

        try {
            callback.onCancellable(transport);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        onFillRequest(new FillRequest(proxy), cancellationSignal, new FillController(proxy),
                new FillCallback(proxy));
    }

    private void handleOnDestroyAllFillWindowsRequest() {
        if (mAutofillProxies != null) {
            final int size = mAutofillProxies.size();
            for (int i = 0; i < size; i++) {
                final int sessionId = mAutofillProxies.keyAt(i);
                final AutofillProxy proxy = mAutofillProxies.valueAt(i);
                if (proxy == null) {
                    // TODO(b/123100811): this might be fine, in which case we should logv it
                    Log.w(TAG, "No proxy for session " + sessionId);
                    return;
                }
                proxy.destroy();
            }
            mAutofillProxies.clear();
        }
    }

    private void handleOnUnbind() {
        if (mAutofillProxies == null) {
            if (DEBUG) Log.d(TAG, "onUnbind(): no proxy to destroy");
            return;
        }
        final int size = mAutofillProxies.size();
        if (DEBUG) Log.d(TAG, "onUnbind(): destroying " + size + " proxies");
        for (int i = 0; i < size; i++) {
            final AutofillProxy proxy = mAutofillProxies.valueAt(i);
            try {
                proxy.destroy();
            } catch (Exception e) {
                Log.w(TAG, "error destroying " + proxy);
            }
        }
        mAutofillProxies = null;
    }

    @Override
    /** @hide */
    protected final void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mAutofillProxies != null) {
            final int size = mAutofillProxies.size();
            pw.print("Number proxies: "); pw.println(size);
            for (int i = 0; i < size; i++) {
                final int sessionId = mAutofillProxies.keyAt(i);
                final AutofillProxy proxy = mAutofillProxies.valueAt(i);
                pw.print(i); pw.print(") SessionId="); pw.print(sessionId); pw.println(":");
                proxy.dump("  ", pw);
            }
        }
        dump(pw, args);
    }

    /**
     * Implementation specific {@code dump}. The child class can override the method to provide
     * additional information about the Service's state into the dumpsys output.
     *
     * @param pw The PrintWriter to which you should dump your state.  This will be closed for
     * you after you return.
     * @param args additional arguments to the dump request.
     */
    protected void dump(@NonNull PrintWriter pw,
            @SuppressWarnings("unused") @NonNull String[] args) {
        pw.print(getClass().getName()); pw.println(": nothing to dump");
    }

    /** @hide */
    static final class AutofillProxy {

        static final int REPORT_EVENT_ON_SUCCESS = 1;
        static final int REPORT_EVENT_UI_SHOWN = 2;
        static final int REPORT_EVENT_UI_DESTROYED = 3;

        @IntDef(prefix = { "REPORT_EVENT_" }, value = {
                REPORT_EVENT_ON_SUCCESS,
                REPORT_EVENT_UI_SHOWN,
                REPORT_EVENT_UI_DESTROYED
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface ReportEvent{}


        private final Object mLock = new Object();
        private final IAugmentedAutofillManagerClient mClient;
        private final int mSessionId;
        public final int taskId;
        public final ComponentName componentName;
        @GuardedBy("mLock")
        private AutofillId mFocusedId;
        @GuardedBy("mLock")
        private AutofillValue mFocusedValue;
        @GuardedBy("mLock")
        private IFillCallback mCallback;

        /**
         * Id of the last field that cause the Autofill UI to be shown.
         *
         * <p>Used to make sure the SmartSuggestionsParams is updated when a new fields is focused.
         */
        @GuardedBy("mLock")
        private AutofillId mLastShownId;

        // Objects used to log metrics
        private final long mFirstRequestTime;
        private long mFirstOnSuccessTime;
        private long mUiFirstShownTime;
        private long mUiFirstDestroyedTime;

        @GuardedBy("mLock")
        private SystemPopupPresentationParams mSmartSuggestion;

        @GuardedBy("mLock")
        private FillWindow mFillWindow;

        private CancellationSignal mCancellationSignal;

        private AutofillProxy(int sessionId, @NonNull IBinder client, int taskId,
                @NonNull ComponentName componentName, @NonNull AutofillId focusedId,
                @Nullable AutofillValue focusedValue, long requestTime,
                @NonNull IFillCallback callback, @NonNull CancellationSignal cancellationSignal) {
            mSessionId = sessionId;
            mClient = IAugmentedAutofillManagerClient.Stub.asInterface(client);
            mCallback = callback;
            this.taskId = taskId;
            this.componentName = componentName;
            mFocusedId = focusedId;
            mFocusedValue = focusedValue;
            mFirstRequestTime = requestTime;
            mCancellationSignal = cancellationSignal;
            // TODO(b/123099468): linkToDeath
        }

        @NonNull
        public SystemPopupPresentationParams getSmartSuggestionParams() {
            synchronized (mLock) {
                if (mSmartSuggestion != null && mFocusedId.equals(mLastShownId)) {
                    return mSmartSuggestion;
                }
                Rect rect;
                try {
                    rect = mClient.getViewCoordinates(mFocusedId);
                } catch (RemoteException e) {
                    Log.w(TAG, "Could not get coordinates for " + mFocusedId);
                    return null;
                }
                if (rect == null) {
                    if (DEBUG) Log.d(TAG, "getViewCoordinates(" + mFocusedId + ") returned null");
                    return null;
                }
                mSmartSuggestion = new SystemPopupPresentationParams(this, rect);
                mLastShownId = mFocusedId;
                return mSmartSuggestion;
            }
        }

        public void autofill(@NonNull List<Pair<AutofillId, AutofillValue>> pairs)
                throws RemoteException {
            final int size = pairs.size();
            final List<AutofillId> ids = new ArrayList<>(size);
            final List<AutofillValue> values = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                final Pair<AutofillId, AutofillValue> pair = pairs.get(i);
                ids.add(pair.first);
                values.add(pair.second);
            }
            mClient.autofill(mSessionId, ids, values);
        }

        public void setFillWindow(@NonNull FillWindow fillWindow) {
            synchronized (mLock) {
                mFillWindow = fillWindow;
            }
        }

        public FillWindow getFillWindow() {
            synchronized (mLock) {
                return mFillWindow;
            }
        }

        public void requestShowFillUi(int width, int height, Rect anchorBounds,
                IAutofillWindowPresenter presenter) throws RemoteException {
            if (mCancellationSignal.isCanceled()) {
                if (VERBOSE) {
                    Log.v(TAG, "requestShowFillUi() not showing because request is cancelled");
                }
                return;
            }
            mClient.requestShowFillUi(mSessionId, mFocusedId, width, height, anchorBounds,
                    presenter);
        }

        public void requestHideFillUi() throws RemoteException {
            mClient.requestHideFillUi(mSessionId, mFocusedId);
        }

        private void update(@NonNull AutofillId focusedId, @NonNull AutofillValue focusedValue,
                @NonNull IFillCallback callback) {
            synchronized (mLock) {
                mFocusedId = focusedId;
                mFocusedValue = focusedValue;
                if (mCallback != null) {
                    try {
                        if (!mCallback.isCompleted()) {
                            mCallback.cancel();
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "failed to check current pending request status", e);
                    }
                    Slog.d(TAG, "mCallback is updated.");
                }
                mCallback = callback;
            }
        }

        @NonNull
        public AutofillId getFocusedId() {
            synchronized (mLock) {
                return mFocusedId;
            }
        }

        @NonNull
        public AutofillValue getFocusedValue() {
            synchronized (mLock) {
                return mFocusedValue;
            }
        }

        // Used (mostly) for metrics.
        public void report(@ReportEvent int event) {
            switch (event) {
                case REPORT_EVENT_ON_SUCCESS:
                    if (mFirstOnSuccessTime == 0) {
                        mFirstOnSuccessTime = SystemClock.elapsedRealtime();
                        if (DEBUG) {
                            Slog.d(TAG, "Service responded in " + TimeUtils.formatDuration(
                                    mFirstOnSuccessTime - mFirstRequestTime));
                        }
                    }
                    try {
                        mCallback.onSuccess();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error reporting success: " + e);
                    }
                    break;
                case REPORT_EVENT_UI_SHOWN:
                    if (mUiFirstShownTime == 0) {
                        mUiFirstShownTime = SystemClock.elapsedRealtime();
                        if (DEBUG) {
                            Slog.d(TAG, "UI shown in " + TimeUtils.formatDuration(
                                    mUiFirstShownTime - mFirstRequestTime));
                        }
                    }
                    break;
                case REPORT_EVENT_UI_DESTROYED:
                    if (mUiFirstDestroyedTime == 0) {
                        mUiFirstDestroyedTime = SystemClock.elapsedRealtime();
                        if (DEBUG) {
                            Slog.d(TAG, "UI destroyed in " + TimeUtils.formatDuration(
                                    mUiFirstDestroyedTime - mFirstRequestTime));
                        }
                    }
                    break;
                default:
                    Slog.w(TAG, "invalid event reported: " + event);
            }
            // TODO(b/122858578): log metrics as well
        }

        public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
            pw.print(prefix); pw.print("sessionId: "); pw.println(mSessionId);
            pw.print(prefix); pw.print("taskId: "); pw.println(taskId);
            pw.print(prefix); pw.print("component: ");
            pw.println(componentName.flattenToShortString());
            pw.print(prefix); pw.print("focusedId: "); pw.println(mFocusedId);
            if (mFocusedValue != null) {
                pw.print(prefix); pw.print("focusedValue: "); pw.println(mFocusedValue);
            }
            if (mLastShownId != null) {
                pw.print(prefix); pw.print("lastShownId: "); pw.println(mLastShownId);
            }
            pw.print(prefix); pw.print("client: "); pw.println(mClient);
            final String prefix2 = prefix + "  ";
            if (mFillWindow != null) {
                pw.print(prefix); pw.println("window:");
                mFillWindow.dump(prefix2, pw);
            }
            if (mSmartSuggestion != null) {
                pw.print(prefix); pw.println("smartSuggestion:");
                mSmartSuggestion.dump(prefix2, pw);
            }
            if (mFirstOnSuccessTime > 0) {
                final long responseTime = mFirstOnSuccessTime - mFirstRequestTime;
                pw.print(prefix); pw.print("response time: ");
                TimeUtils.formatDuration(responseTime, pw); pw.println();
            }

            if (mUiFirstShownTime > 0) {
                final long uiRenderingTime = mUiFirstShownTime - mFirstRequestTime;
                pw.print(prefix); pw.print("UI rendering time: ");
                TimeUtils.formatDuration(uiRenderingTime, pw); pw.println();
            }

            if (mUiFirstDestroyedTime > 0) {
                final long uiTotalTime = mUiFirstDestroyedTime - mFirstRequestTime;
                pw.print(prefix); pw.print("UI life time: ");
                TimeUtils.formatDuration(uiTotalTime, pw); pw.println();
            }
        }

        private void destroy() {
            synchronized (mLock) {
                if (mFillWindow != null) {
                    if (DEBUG) Log.d(TAG, "destroying window");
                    mFillWindow.destroy();
                    mFillWindow = null;
                }
            }
        }
    }
}
