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
package android.service.contentcapture;

import static android.view.contentcapture.ContentCaptureHelper.sDebug;
import static android.view.contentcapture.ContentCaptureHelper.sVerbose;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.service.autofill.AutofillService;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.view.contentcapture.ContentCaptureContext;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.ContentCaptureSession;
import android.view.contentcapture.ContentCaptureSessionId;
import android.view.contentcapture.IContentCaptureDirectManager;
import android.view.contentcapture.MainContentCaptureSession;
import android.view.contentcapture.UserDataRemovalRequest;

import com.android.internal.os.IResultReceiver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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
@TestApi
public abstract class ContentCaptureService extends Service {

    private static final String TAG = ContentCaptureService.class.getSimpleName();

    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_CONTENT_CAPTURE_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.contentcapture.ContentCaptureService";

    private Handler mHandler;
    private IContentCaptureServiceCallback mCallback;

    /**
     * Binder that receives calls from the system server.
     */
    private final IContentCaptureService mServerInterface = new IContentCaptureService.Stub() {

        @Override
        public void onConnected(IBinder callback, boolean verbose, boolean debug) {
            sVerbose = verbose;
            sDebug = debug;
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleOnConnected,
                    ContentCaptureService.this, callback));
        }

        @Override
        public void onDisconnected() {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleOnDisconnected,
                    ContentCaptureService.this));
        }

        @Override
        public void onSessionStarted(ContentCaptureContext context, String sessionId, int uid,
                IResultReceiver clientReceiver) {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleOnCreateSession,
                    ContentCaptureService.this, context, sessionId, uid, clientReceiver));
        }

        @Override
        public void onActivitySnapshot(String sessionId, SnapshotData snapshotData) {
            mHandler.sendMessage(
                    obtainMessage(ContentCaptureService::handleOnActivitySnapshot,
                            ContentCaptureService.this, sessionId, snapshotData));
        }

        @Override
        public void onSessionFinished(String sessionId) {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleFinishSession,
                    ContentCaptureService.this, sessionId));
        }

        @Override
        public void onUserDataRemovalRequest(UserDataRemovalRequest request) {
            mHandler.sendMessage(
                    obtainMessage(ContentCaptureService::handleOnUserDataRemovalRequest,
                            ContentCaptureService.this, request));
        }

        @Override
        public void onActivityEvent(ActivityEvent event) {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleOnActivityEvent,
                    ContentCaptureService.this, event));

        }
    };

    /**
     * Binder that receives calls from the app.
     */
    private final IContentCaptureDirectManager mClientInterface =
            new IContentCaptureDirectManager.Stub() {

        @Override
        public void sendEvents(@SuppressWarnings("rawtypes") ParceledListSlice events) {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleSendEvents,
                            ContentCaptureService.this, Binder.getCallingUid(), events));
        }
    };

    /**
     * UIDs associated with each session.
     *
     * <p>This map is populated when an session is started, which is called by the system server
     * and can be trusted. Then subsequent calls made by the app are verified against this map.
     */
    private final ArrayMap<String, Integer> mSessionUids = new ArrayMap<>();

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
            return mServerInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    /**
     * Explicitly limits content capture to the given packages and activities.
     *
     * <p>To reset the whitelist, call it passing {@code null} to both arguments.
     *
     * <p>Useful when the service wants to restrict content capture to a category of apps, like
     * chat apps. For example, if the service wants to support view captures on all activities of
     * app {@code ChatApp1} and just activities {@code act1} and {@code act2} of {@code ChatApp2},
     * it would call: {@code setContentCaptureWhitelist(Sets.newArraySet("ChatApp1"),
     * Sets.newArraySet(new ComponentName("ChatApp2", "act1"),
     * new ComponentName("ChatApp2", "act2")));}
     */
    public final void setContentCaptureWhitelist(@Nullable Set<String> packages,
            @Nullable Set<ComponentName> activities) {
        final IContentCaptureServiceCallback callback = mCallback;
        if (callback == null) {
            Log.w(TAG, "setContentCaptureWhitelist(): no server callback");
            return;
        }

        try {
            callback.setContentCaptureWhitelist(toList(packages), toList(activities));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private <T> ArrayList<T> toList(@Nullable Set<T> set) {
        return set == null ? null : new ArrayList<T>(set);
    }

    /**
     * Called when the Android system connects to service.
     *
     * <p>You should generally do initialization here rather than in {@link #onCreate}.
     */
    public void onConnected() {
        Slog.i(TAG, "bound to " + getClass().getName());
    }

    /**
     * Creates a new content capture session.
     *
     * @param context content capture context
     * @param sessionId the session's Id
     */
    public void onCreateContentCaptureSession(@NonNull ContentCaptureContext context,
            @NonNull ContentCaptureSessionId sessionId) {
        if (sVerbose) {
            Log.v(TAG, "onCreateContentCaptureSession(id=" + sessionId + ", ctx=" + context + ")");
        }
    }

    /**
     * Notifies the service of {@link ContentCaptureEvent events} associated with a content capture
     * session.
     *
     * @param sessionId the session's Id
     * @param event the event
     */
    public void onContentCaptureEvent(@NonNull ContentCaptureSessionId sessionId,
            @NonNull ContentCaptureEvent event) {
        if (sVerbose) Log.v(TAG, "onContentCaptureEventsRequest(id=" + sessionId + ")");
    }

    /**
     * Notifies the service that the app requested to remove data associated with the user.
     *
     * @param request the user data requested to be removed
     */
    public void onUserDataRemovalRequest(@NonNull UserDataRemovalRequest request) {
        if (sVerbose) Log.v(TAG, "onUserDataRemovalRequest()");
    }

    /**
     * Notifies the service of {@link SnapshotData snapshot data} associated with a session.
     *
     * @param sessionId the session's Id
     * @param snapshotData the data
     */
    public void onActivitySnapshot(@NonNull ContentCaptureSessionId sessionId,
            @NonNull SnapshotData snapshotData) {
        if (sVerbose) Log.v(TAG, "onActivitySnapshot(id=" + sessionId + ")");
    }

    /**
     * Notifies the service of an activity-level event that is not associated with a session.
     *
     * <p>This method can be used to track some high-level events for all activities, even those
     * that are not whitelisted for Content Capture.
     *
     * @param event high-level activity event
     */
    public void onActivityEvent(@NonNull ActivityEvent event) {
        if (sVerbose) Log.v(TAG, "onActivityEvent(): " + event);
    }

    /**
     * Destroys the content capture session.
     *
     * @param sessionId the id of the session to destroy
     * */
    public void onDestroyContentCaptureSession(@NonNull ContentCaptureSessionId sessionId) {
        if (sVerbose) Log.v(TAG, "onDestroyContentCaptureSession(id=" + sessionId + ")");
    }

    /**
     * Disables the Content Capture service for the given user.
     */
    public final void disableContentCaptureServices() {
        if (sDebug) Log.d(TAG, "disableContentCaptureServices()");

        final IContentCaptureServiceCallback callback = mCallback;
        if (callback == null) {
            Log.w(TAG, "disableContentCaptureServices(): no server callback");
            return;
        }
        try {
            callback.disableSelf();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Called when the Android system disconnects from the service.
     *
     * <p> At this point this service may no longer be an active {@link AutofillService}.
     */
    public void onDisconnected() {
        Slog.i(TAG, "unbinding from " + getClass().getName());
    }

    @Override
    @CallSuper
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("Debug: "); pw.print(sDebug); pw.print(" Verbose: "); pw.println(sVerbose);
        final int size = mSessionUids.size();
        pw.print("Number sessions: "); pw.println(size);
        if (size > 0) {
            final String prefix = "  ";
            for (int i = 0; i < size; i++) {
                pw.print(prefix); pw.print(mSessionUids.keyAt(i));
                pw.print(": uid="); pw.println(mSessionUids.valueAt(i));
            }
        }
    }

    private void handleOnConnected(@NonNull IBinder callback) {
        mCallback = IContentCaptureServiceCallback.Stub.asInterface(callback);
        onConnected();
    }

    private void handleOnDisconnected() {
        onDisconnected();
        mCallback = null;
    }

    //TODO(b/111276913): consider caching the InteractionSessionId for the lifetime of the session,
    // so we don't need to create a temporary InteractionSessionId for each event.

    private void handleOnCreateSession(@NonNull ContentCaptureContext context,
            @NonNull String sessionId, int uid, IResultReceiver clientReceiver) {
        mSessionUids.put(sessionId, uid);
        onCreateContentCaptureSession(context, new ContentCaptureSessionId(sessionId));

        final int clientFlags = context.getFlags();
        int stateFlags = 0;
        if ((clientFlags & ContentCaptureContext.FLAG_DISABLED_BY_FLAG_SECURE) != 0) {
            stateFlags |= ContentCaptureSession.STATE_FLAG_SECURE;
        }
        if ((clientFlags & ContentCaptureContext.FLAG_DISABLED_BY_APP) != 0) {
            stateFlags |= ContentCaptureSession.STATE_BY_APP;
        }
        if (stateFlags == 0) {
            stateFlags = ContentCaptureSession.STATE_ACTIVE;
        } else {
            stateFlags |= ContentCaptureSession.STATE_DISABLED;

        }
        setClientState(clientReceiver, stateFlags, mClientInterface.asBinder());
    }

    private void handleSendEvents(int uid,
            @NonNull ParceledListSlice<ContentCaptureEvent> parceledEvents) {

        // Most events belong to the same session, so we can keep a reference to the last one
        // to avoid creating too many ContentCaptureSessionId objects
        String lastSessionId = null;
        ContentCaptureSessionId sessionId = null;

        final List<ContentCaptureEvent> events = parceledEvents.getList();
        for (int i = 0; i < events.size(); i++) {
            final ContentCaptureEvent event = events.get(i);
            if (!handleIsRightCallerFor(event, uid)) continue;
            String sessionIdString = event.getSessionId();
            if (!sessionIdString.equals(lastSessionId)) {
                sessionId = new ContentCaptureSessionId(sessionIdString);
                lastSessionId = sessionIdString;
            }
            switch (event.getType()) {
                case ContentCaptureEvent.TYPE_SESSION_STARTED:
                    final ContentCaptureContext clientContext = event.getContentCaptureContext();
                    clientContext.setParentSessionId(event.getParentSessionId());
                    mSessionUids.put(sessionIdString, uid);
                    onCreateContentCaptureSession(clientContext, sessionId);
                    break;
                case ContentCaptureEvent.TYPE_SESSION_FINISHED:
                    mSessionUids.remove(sessionIdString);
                    onDestroyContentCaptureSession(sessionId);
                    break;
                default:
                    onContentCaptureEvent(sessionId, event);
            }
        }
    }

    private void handleOnActivitySnapshot(@NonNull String sessionId,
            @NonNull SnapshotData snapshotData) {
        onActivitySnapshot(new ContentCaptureSessionId(sessionId), snapshotData);
    }

    private void handleFinishSession(@NonNull String sessionId) {
        mSessionUids.remove(sessionId);
        onDestroyContentCaptureSession(new ContentCaptureSessionId(sessionId));
    }

    private void handleOnUserDataRemovalRequest(@NonNull UserDataRemovalRequest request) {
        onUserDataRemovalRequest(request);
    }

    private void handleOnActivityEvent(@NonNull ActivityEvent event) {
        onActivityEvent(event);
    }

    /**
     * Checks if the given {@code uid} owns the session associated with the event.
     */
    private boolean handleIsRightCallerFor(@NonNull ContentCaptureEvent event, int uid) {
        final String sessionId;
        switch (event.getType()) {
            case ContentCaptureEvent.TYPE_SESSION_STARTED:
            case ContentCaptureEvent.TYPE_SESSION_FINISHED:
                sessionId = event.getParentSessionId();
                break;
            default:
                sessionId = event.getSessionId();
        }
        final Integer rightUid = mSessionUids.get(sessionId);
        if (rightUid == null) {
            if (sVerbose) {
                Log.v(TAG, "handleIsRightCallerFor(" + event + "): no session for " + sessionId
                        + ": " + mSessionUids);
            }
            // Just ignore, as the session could have been finished already
            return false;
        }
        if (rightUid != uid) {
            Log.e(TAG, "invalid call from UID " + uid + ": session " + sessionId + " belongs to "
                    + rightUid);
            //TODO(b/111276913): log metrics as this could be a malicious app forging a sessionId
            return false;
        }
        return true;

    }

    /**
     * Sends the state of the {@link ContentCaptureManager} in the client app.
     *
     * @param clientReceiver receiver in the client app.
     * @param sessionState state of the session
     * @param binder handle to the {@code IContentCaptureDirectManager} object that resides in the
     * service.
     * @hide
     */
    public static void setClientState(@NonNull IResultReceiver clientReceiver,
            int sessionState, @Nullable IBinder binder) {
        try {
            final Bundle extras;
            if (binder != null) {
                extras = new Bundle();
                extras.putBinder(MainContentCaptureSession.EXTRA_BINDER, binder);
            } else {
                extras = null;
            }
            clientReceiver.send(sessionState, extras);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error async reporting result to client: " + e);
        }
    }
}
