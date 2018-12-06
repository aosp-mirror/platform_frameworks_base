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
package android.service.intelligence;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.intelligence.PresentationParams.SystemPopupPresentationParams;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAugmentedAutofillManagerClient;
import android.view.intelligence.ContentCaptureEvent;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A service used to capture the content of the screen to provide contextual data in other areas of
 * the system such as Autofill.
 *
 * @hide
 */
@SystemApi
public abstract class SmartSuggestionsService extends Service {

    private static final String TAG = "SmartSuggestionsService";

    // TODO(b/111330312): STOPSHIP use dynamic value, or change to false
    static final boolean DEBUG = true;
    static final boolean VERBOSE = false;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_SMART_SUGGESTIONS_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.intelligence.SmartSuggestionsService";

    private Handler mHandler;

    private ArrayMap<InteractionSessionId, AutofillProxy> mAutofillProxies;

    private final IIntelligenceService mInterface = new IIntelligenceService.Stub() {

        @Override
        public void onSessionLifecycle(InteractionContext context, InteractionSessionId sessionId)
                throws RemoteException {
            if (context != null) {
                mHandler.sendMessage(
                        obtainMessage(SmartSuggestionsService::onCreateInteractionSession,
                                SmartSuggestionsService.this, context, sessionId));
            } else {
                mHandler.sendMessage(
                        obtainMessage(SmartSuggestionsService::onDestroyInteractionSession,
                                SmartSuggestionsService.this, sessionId));
            }
        }

        @Override
        public void onContentCaptureEventsRequest(InteractionSessionId sessionId,
                ContentCaptureEventsRequest request) {
            mHandler.sendMessage(
                    obtainMessage(SmartSuggestionsService::onContentCaptureEventsRequest,
                            SmartSuggestionsService.this, sessionId, request));

        }

        @Override
        public void onActivitySnapshot(InteractionSessionId sessionId,
                SnapshotData snapshotData) {
            mHandler.sendMessage(
                    obtainMessage(SmartSuggestionsService::onActivitySnapshot,
                            SmartSuggestionsService.this, sessionId, snapshotData));
        }

        @Override
        public void onAutofillRequest(InteractionSessionId sessionId, IBinder client,
                int autofilSessionId, AutofillId focusedId, AutofillValue focusedValue,
                long requestTime) {
            mHandler.sendMessage(obtainMessage(SmartSuggestionsService::handleOnAutofillRequest,
                    SmartSuggestionsService.this, sessionId, client, autofilSessionId, focusedId,
                    focusedValue, requestTime));
        }

        @Override
        public void onDestroyAutofillWindowsRequest(InteractionSessionId sessionId) {
            mHandler.sendMessage(
                    obtainMessage(SmartSuggestionsService::handleOnDestroyAutofillWindowsRequest,
                            SmartSuggestionsService.this, sessionId));
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
        Log.w(TAG, "Tried to bind to wrong intent: " + intent);
        return null;
    }

    /**
     * Explicitly limits content capture to the given packages and activities.
     *
     * <p>When the whitelist is set, it overrides the values passed to
     * {@link #setActivityContentCaptureEnabled(ComponentName, boolean)}
     * and {@link #setPackageContentCaptureEnabled(String, boolean)}.
     *
     * <p>To reset the whitelist, call it passing {@code null} to both arguments.
     *
     * <p>Useful when the service wants to restrict content capture to a category of apps, like
     * chat apps. For example, if the service wants to support view captures on all activities of
     * app {@code ChatApp1} and just activities {@code act1} and {@code act2} of {@code ChatApp2},
     * it would call: {@code setContentCaptureWhitelist(Arrays.asList("ChatApp1"),
     * Arrays.asList(new ComponentName("ChatApp2", "act1"),
     * new ComponentName("ChatApp2", "act2")));}
     */
    public final void setContentCaptureWhitelist(@Nullable List<String> packages,
            @Nullable List<ComponentName> activities) {
        //TODO(b/111276913): implement
    }

    /**
     * Defines whether content capture should be enabled for activities with such
     * {@link android.content.ComponentName}.
     *
     * <p>Useful to blacklist a particular activity.
     */
    public final void setActivityContentCaptureEnabled(@NonNull ComponentName activity,
            boolean enabled) {
        //TODO(b/111276913): implement
    }

    /**
     * Defines whether content capture should be enabled for activities of the app with such
     * {@code packageName}.
     *
     * <p>Useful to blacklist any activity from a particular app.
     */
    public final void setPackageContentCaptureEnabled(@NonNull String packageName,
            boolean enabled) {
        //TODO(b/111276913): implement
    }

    /**
     * Gets the activities where content capture was disabled by
     * {@link #setActivityContentCaptureEnabled(ComponentName, boolean)}.
     */
    @NonNull
    public final Set<ComponentName> getContentCaptureDisabledActivities() {
        //TODO(b/111276913): implement
        return null;
    }

    /**
     * Gets the apps where content capture was disabled by
     * {@link #setPackageContentCaptureEnabled(String, boolean)}.
     */
    @NonNull
    public final Set<String> getContentCaptureDisabledPackages() {
        //TODO(b/111276913): implement
        return null;
    }

    /**
     * Creates a new interaction session.
     *
     * @param context interaction context
     * @param sessionId the session's Id
     */
    public void onCreateInteractionSession(@NonNull InteractionContext context,
            @NonNull InteractionSessionId sessionId) {
        if (VERBOSE) {
            Log.v(TAG, "onCreateInteractionSession(id=" + sessionId + ", ctx=" + context + ")");
        }
    }

    /**
     * Notifies the service of {@link ContentCaptureEvent events} associated with a content capture
     * session.
     *
     * @param sessionId the session's Id
     * @param request the events
     */
    // TODO(b/111276913): rename to onContentCaptureEvents or something like that; also, pass a
    // Request object so it can be extended
    public abstract void onContentCaptureEventsRequest(@NonNull InteractionSessionId sessionId,
            @NonNull ContentCaptureEventsRequest request);

    private void handleOnAutofillRequest(@NonNull InteractionSessionId sessionId,
            @NonNull IBinder client, int autofillSessionId, @NonNull AutofillId focusedId,
            @Nullable AutofillValue focusedValue, long requestTime) {
        if (mAutofillProxies == null) {
            mAutofillProxies = new ArrayMap<>();
        }
        AutofillProxy proxy = mAutofillProxies.get(sessionId);
        if (proxy == null) {
            proxy = new AutofillProxy(sessionId, client, autofillSessionId, focusedId, focusedValue,
                    requestTime);
            mAutofillProxies.put(sessionId,  proxy);
        } else {
            // TODO(b/111330312): figure out if it's ok to reuse the proxy; add logging
            if (DEBUG) Log.d(TAG, "Reusing proxy for session " + sessionId);
        }
        // TODO(b/111330312): set cancellation signal
        final CancellationSignal cancellationSignal = null;
        onFillRequest(sessionId, new FillRequest(proxy), cancellationSignal,
                new FillController(proxy), new FillCallback(proxy));
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
     * FillCallback#onSuccess(android.service.intelligence.FillResponse)} as soon as possible,
     * passing {@code null} when it cannot fulfill the request.
     *
     * @param sessionId the session's id
     * @param request the request to handle.
     * @param cancellationSignal signal for observing cancellation requests. The system will use
     *     this to notify you that the fill result is no longer needed and you should stop
     *     handling this fill request in order to save resources.
     * @param controller object used to interact with the autofill system.
     * @param callback object used to notify the result of the request. Service <b>must</b> call
     * {@link FillCallback#onSuccess(android.service.intelligence.FillResponse)}.
     */
    public void onFillRequest(@NonNull InteractionSessionId sessionId, @NonNull FillRequest request,
            @NonNull CancellationSignal cancellationSignal, @NonNull FillController controller,
            @NonNull FillCallback callback) {
    }

    private void handleOnDestroyAutofillWindowsRequest(@NonNull InteractionSessionId sessionId) {
        AutofillProxy proxy = null;
        if (mAutofillProxies != null) {
            proxy = mAutofillProxies.get(sessionId);
        }
        if (proxy == null) {
            // TODO(b/111330312): this might be fine, in which case we should logv it
            Log.w(TAG, "No proxy for session " + sessionId);
            return;
        }
        proxy.destroy();
        mAutofillProxies.remove(sessionId);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mAutofillProxies != null) {
            final int size = mAutofillProxies.size();
            pw.print("Number proxies: "); pw.println(size);
            for (int i = 0; i < size; i++) {
                final InteractionSessionId sessionId = mAutofillProxies.keyAt(i);
                final AutofillProxy proxy = mAutofillProxies.valueAt(i);
                pw.print(i); pw.print(") SessionId="); pw.print(sessionId); pw.println(":");
                proxy.dump("  ", pw);
            }
        }
    }

    /**
     * Notifies the service of {@link SnapshotData snapshot data} associated with a session.
     *
     * @param sessionId the session's Id
     * @param snapshotData the data
     */
    public void onActivitySnapshot(@NonNull InteractionSessionId sessionId,
            @NonNull SnapshotData snapshotData) {}

    /**
     * Destroys the interaction session.
     *
     * @param sessionId the id of the session to destroy
     */
    public void onDestroyInteractionSession(@NonNull InteractionSessionId sessionId) {
        if (VERBOSE) {
            Log.v(TAG, "onDestroyInteractionSession(id=" + sessionId + ")");
        }
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
        private final int mAutofillSessionId;
        public final InteractionSessionId sessionId;
        public final AutofillId focusedId;
        public final AutofillValue focusedValue;

        // Objects used to log metrics
        private final long mRequestTime;
        private long mOnSuccessTime;
        private long mUiFirstShownTime;
        private long mUiFirstDestroyedTime;

        @GuardedBy("mLock")
        private SystemPopupPresentationParams mSmartSuggestion;

        @GuardedBy("mLock")
        private FillWindow mFillWindow;

        private AutofillProxy(@NonNull InteractionSessionId sessionId, @NonNull IBinder client,
                int autofillSessionId, @NonNull AutofillId focusedId,
                @Nullable AutofillValue focusedValue, long requestTime) {
            this.sessionId = sessionId;
            mClient = IAugmentedAutofillManagerClient.Stub.asInterface(client);
            mAutofillSessionId = autofillSessionId;
            this.focusedId = focusedId;
            this.focusedValue = focusedValue;
            this.mRequestTime = requestTime;
            // TODO(b/111330312): linkToDeath
        }

        @NonNull
        public SystemPopupPresentationParams getSmartSuggestionParams() {
            synchronized (mLock) {
                if (mSmartSuggestion != null) {
                    return mSmartSuggestion;
                }
                Rect rect;
                try {
                    rect = mClient.getViewCoordinates(focusedId);
                } catch (RemoteException e) {
                    Log.w(TAG, "Could not get coordinates for " + focusedId);
                    return null;
                }
                if (rect == null) {
                    if (DEBUG) Log.d(TAG, "getViewCoordinates(" + focusedId + ") returned null");
                    return null;
                }
                mSmartSuggestion = new SystemPopupPresentationParams(this, rect);
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
            mClient.autofill(mAutofillSessionId, ids, values);
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

        // Used for metrics.
        public void report(@ReportEvent int event) {
            switch (event) {
                case REPORT_EVENT_ON_SUCCESS:
                    if (mOnSuccessTime == 0) {
                        mOnSuccessTime = SystemClock.elapsedRealtime();
                        if (DEBUG) {
                            Slog.d(TAG, "Service responsed in "
                                    + TimeUtils.formatDuration(mOnSuccessTime - mRequestTime));
                        }
                    }
                    break;
                case REPORT_EVENT_UI_SHOWN:
                    if (mUiFirstShownTime == 0) {
                        mUiFirstShownTime = SystemClock.elapsedRealtime();
                        if (DEBUG) {
                            Slog.d(TAG, "UI shown in "
                                    + TimeUtils.formatDuration(mUiFirstShownTime - mRequestTime));
                        }
                    }
                    break;
                case REPORT_EVENT_UI_DESTROYED:
                    if (mUiFirstDestroyedTime == 0) {
                        mUiFirstDestroyedTime = SystemClock.elapsedRealtime();
                        if (DEBUG) {
                            Slog.d(TAG, "UI destroyed in "
                                    + TimeUtils.formatDuration(
                                            mUiFirstDestroyedTime - mRequestTime));
                        }
                    }
                    break;
                default:
                    Slog.w(TAG, "invalid event reported: " + event);
            }
            // TODO(b/111330312): log metrics as well
        }


        public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
            pw.print(prefix); pw.print("afSessionId: "); pw.println(mAutofillSessionId);
            pw.print(prefix); pw.print("focusedId: "); pw.println(focusedId);
            if (focusedValue != null) {
                pw.print(prefix); pw.print("focusedValue: "); pw.println(focusedValue);
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
            if (mOnSuccessTime > 0) {
                final long responseTime = mOnSuccessTime - mRequestTime;
                pw.print(prefix); pw.print("response time: ");
                TimeUtils.formatDuration(responseTime, pw); pw.println();
            }

            if (mUiFirstShownTime > 0) {
                final long uiRenderingTime = mUiFirstShownTime - mRequestTime;
                pw.print(prefix); pw.print("UI rendering time: ");
                TimeUtils.formatDuration(uiRenderingTime, pw); pw.println();
            }

            if (mUiFirstDestroyedTime > 0) {
                final long uiTotalTime = mUiFirstDestroyedTime - mRequestTime;
                pw.print(prefix); pw.print("UI life time: ");
                TimeUtils.formatDuration(uiTotalTime, pw); pw.println();
            }
        }

        private void destroy() {
            synchronized (mLock) {
                if (mFillWindow != null) {
                    if (DEBUG) Log.d(TAG, "destroying window");
                    mFillWindow.destroy();
                }
            }
        }
    }
}
