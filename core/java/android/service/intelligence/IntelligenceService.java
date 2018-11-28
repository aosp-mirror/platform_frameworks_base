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
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.service.intelligence.PresentationParams.SystemPopupPresentationParams;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAugmentedAutofillManagerClient;
import android.view.intelligence.ContentCaptureEvent;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * A service used to capture the content of the screen.
 *
 * <p>The data collected by this service can be analyzed and combined with other sources to provide
 * contextual data in other areas of the system such as Autofill.
 *
 * @hide
 */
@SystemApi
public abstract class IntelligenceService extends Service {

    private static final String TAG = "IntelligenceService";

    // TODO(b/111330312): STOPSHIP use dynamic value, or change to false
    static final boolean DEBUG = true;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_INTELLIGENCE_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.intelligence.IntelligenceService";

    private Handler mHandler;

    private ArrayMap<InteractionSessionId, AutofillProxy> mAutofillProxies;

    private final IIntelligenceService mInterface = new IIntelligenceService.Stub() {

        @Override
        public void onSessionLifecycle(InteractionContext context, InteractionSessionId sessionId)
                throws RemoteException {
            if (context != null) {
                mHandler.sendMessage(
                        obtainMessage(IntelligenceService::onCreateInteractionSession,
                                IntelligenceService.this, context, sessionId));
            } else {
                mHandler.sendMessage(
                        obtainMessage(IntelligenceService::onDestroyInteractionSession,
                                IntelligenceService.this, sessionId));
            }
        }

        @Override
        public void onContentCaptureEvents(InteractionSessionId sessionId,
                List<ContentCaptureEvent> events) {
            mHandler.sendMessage(
                    obtainMessage(IntelligenceService::onContentCaptureEvent,
                            IntelligenceService.this, sessionId, events));

        }

        @Override
        public void onActivitySnapshot(InteractionSessionId sessionId,
                SnapshotData snapshotData) {
            mHandler.sendMessage(
                    obtainMessage(IntelligenceService::onActivitySnapshot,
                            IntelligenceService.this, sessionId, snapshotData));
        }

        @Override
        public void onAutofillRequest(InteractionSessionId sessionId, IBinder client,
                int autofilSessionId, AutofillId focusedId) {
            mHandler.sendMessage(obtainMessage(IntelligenceService::handleOnAutofillRequest,
                    IntelligenceService.this, sessionId, client, autofilSessionId, focusedId));
        }

        @Override
        public void onDestroyAutofillWindowsRequest(InteractionSessionId sessionId) {
            mHandler.sendMessage(
                    obtainMessage(IntelligenceService::handleOnDestroyAutofillWindowsRequest,
                            IntelligenceService.this, sessionId));
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
     * Creates a new interaction session.
     *
     * @param context interaction context
     * @param sessionId the session's Id
     */
    public void onCreateInteractionSession(@NonNull InteractionContext context,
            @NonNull InteractionSessionId sessionId) {}

    /**
     * Notifies the service of {@link ContentCaptureEvent events} associated with a content capture
     * session.
     *
     * @param sessionId the session's Id
     * @param events the events
     */
    // TODO(b/111276913): rename to onContentCaptureEvents or something like that; also, pass a
    // Request object so it can be extended
    public abstract void onContentCaptureEvent(@NonNull InteractionSessionId sessionId,
            @NonNull List<ContentCaptureEvent> events);

    private void handleOnAutofillRequest(@NonNull InteractionSessionId sessionId,
            @NonNull IBinder client, int autofillSessionId, @NonNull AutofillId focusedId) {
        if (mAutofillProxies == null) {
            mAutofillProxies = new ArrayMap<>();
        }
        AutofillProxy proxy = mAutofillProxies.get(sessionId);
        if (proxy == null) {
            proxy = new AutofillProxy(sessionId, client, autofillSessionId, focusedId);
            mAutofillProxies.put(sessionId,  proxy);
        } else {
            // TODO(b/111330312): figure out if it's ok to reuse the proxy; add logging
            if (DEBUG) Log.d(TAG, "Reusing proxy for session " + sessionId);
        }
        // TODO(b/111330312): set cancellation signal
        final CancellationSignal cancellationSignal = null;
        onFillRequest(sessionId, new FillRequest(proxy), cancellationSignal,
                new FillController(proxy), new FillCallback());
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
     * Notifies the service of {@link IntelligenceSnapshotData snapshot data} associated with a
     * session.
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
    public void onDestroyInteractionSession(@NonNull InteractionSessionId sessionId) {}

    /** @hide */
    static final class AutofillProxy {
        private final Object mLock = new Object();
        private final IAugmentedAutofillManagerClient mClient;
        private final int mAutofillSessionId;
        public final InteractionSessionId sessionId;
        public final AutofillId focusedId;

        @GuardedBy("mLock")
        private SystemPopupPresentationParams mSmartSuggestion;

        @GuardedBy("mLock")
        private FillWindow mFillWindow;

        private AutofillProxy(@NonNull InteractionSessionId sessionId, @NonNull IBinder client,
                int autofillSessionId, @NonNull AutofillId focusedId) {
            this.sessionId = sessionId;
            mClient = IAugmentedAutofillManagerClient.Stub.asInterface(client);
            mAutofillSessionId = autofillSessionId;
            this.focusedId = focusedId;
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

        public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
            pw.print(prefix); pw.print("afSessionId: "); pw.println(mAutofillSessionId);
            pw.print(prefix); pw.print("focusedId: "); pw.println(focusedId);
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
