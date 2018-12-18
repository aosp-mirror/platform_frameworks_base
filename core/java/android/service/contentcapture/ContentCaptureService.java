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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
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
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.view.contentcapture.ActivityContentCaptureSession;
import android.view.contentcapture.ContentCaptureContext;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.ContentCaptureSession;
import android.view.contentcapture.ContentCaptureSessionId;
import android.view.contentcapture.IContentCaptureDirectManager;

import com.android.internal.os.IResultReceiver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Set;

/**
 * A service used to capture the content of the screen to provide contextual data in other areas of
 * the system such as Autofill.
 *
 * @hide
 */
@SystemApi
public abstract class ContentCaptureService extends Service {

    private static final String TAG = ContentCaptureService.class.getSimpleName();

    // TODO(b/121044306): STOPSHIP use dynamic value, or change to false
    static final boolean DEBUG = true;
    static final boolean VERBOSE = false;

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

    /**
     * Binder that receives calls from the system server.
     */
    private final IContentCaptureService mServerInterface = new IContentCaptureService.Stub() {

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
    };

    /**
     * Binder that receives calls from the app.
     */
    private final IContentCaptureDirectManager mClientInterface =
            new IContentCaptureDirectManager.Stub() {

        @Override
        public void sendEvents(String sessionId,
                @SuppressWarnings("rawtypes") ParceledListSlice events) {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleSendEvents,
                            ContentCaptureService.this, sessionId, Binder.getCallingUid(), events));
        }
    };

    /**
     * List of sessions per UID.
     *
     * <p>This map is populated when an session is started, which is called by the system server
     * and can be trusted. Then subsequent calls made by the app are verified against this map.
     */
    private final ArrayMap<String, Integer> mSessionsByUid = new ArrayMap<>();

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
     * Creates a new content capture session.
     *
     * @param context content capture context
     * @param sessionId the session's Id
     */
    public void onCreateContentCaptureSession(@NonNull ContentCaptureContext context,
            @NonNull ContentCaptureSessionId sessionId) {
        if (VERBOSE) {
            Log.v(TAG, "onCreateContentCaptureSession(id=" + sessionId + ", ctx=" + context + ")");
        }
    }

    /**
     * Notifies the service of {@link ContentCaptureEvent events} associated with a content capture
     * session.
     *
     * @param sessionId the session's Id
     * @param request the events
     */
    public void onContentCaptureEventsRequest(@NonNull ContentCaptureSessionId sessionId,
            @NonNull ContentCaptureEventsRequest request) {
        if (VERBOSE) Log.v(TAG, "onContentCaptureEventsRequest(id=" + sessionId + ")");
    }

    /**
     * Notifies the service of {@link SnapshotData snapshot data} associated with a session.
     *
     * @param sessionId the session's Id
     * @param snapshotData the data
     */
    public void onActivitySnapshot(@NonNull ContentCaptureSessionId sessionId,
            @NonNull SnapshotData snapshotData) {}

    /**
     * Destroys the content capture session.
     *
     * @param sessionId the id of the session to destroy
     * */
    public void onDestroyContentCaptureSession(@NonNull ContentCaptureSessionId sessionId) {
        if (VERBOSE) Log.v(TAG, "onDestroyContentCaptureSession(id=" + sessionId + ")");
    }

    @Override
    @CallSuper
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final int size = mSessionsByUid.size();
        pw.print("Number sessions: "); pw.println(size);
        if (size > 0) {
            final String prefix = "  ";
            for (int i = 0; i < size; i++) {
                pw.print(prefix); pw.print(mSessionsByUid.keyAt(i));
                pw.print(": uid="); pw.println(mSessionsByUid.valueAt(i));
            }
        }
    }

    //TODO(b/111276913): consider caching the InteractionSessionId for the lifetime of the session,
    // so we don't need to create a temporary InteractionSessionId for each event.

    private void handleOnCreateSession(@NonNull ContentCaptureContext context,
            @NonNull String sessionId, int uid, IResultReceiver clientReceiver) {
        mSessionsByUid.put(sessionId, uid);
        onCreateContentCaptureSession(context, new ContentCaptureSessionId(sessionId));
        setClientState(clientReceiver, ContentCaptureSession.STATE_ACTIVE,
                mClientInterface.asBinder());
    }

    private void handleSendEvents(@NonNull String sessionId, int uid,
            @NonNull ParceledListSlice<ContentCaptureEvent> events) {
        if (handleIsRightCallerFor(sessionId, uid)) {
            onContentCaptureEventsRequest(new ContentCaptureSessionId(sessionId),
                    new ContentCaptureEventsRequest(events));
        }
    }

    private void handleOnActivitySnapshot(@NonNull String sessionId,
            @NonNull SnapshotData snapshotData) {
        onActivitySnapshot(new ContentCaptureSessionId(sessionId), snapshotData);
    }

    private void handleFinishSession(@NonNull String sessionId) {
        mSessionsByUid.remove(sessionId);
        onDestroyContentCaptureSession(new ContentCaptureSessionId(sessionId));
    }

    /**
     * Checks if the given {@code uid} owns the session.
     */
    private boolean handleIsRightCallerFor(@NonNull String sessionId, int uid) {
        final Integer rightUid = mSessionsByUid.get(sessionId);
        if (rightUid == null) {
            if (VERBOSE) Log.v(TAG, "No session for " + sessionId);
            // Just ignore, as the session could have finished
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
     * Sends the state of the {@link ContentCaptureManager} in the cleint app.
     *
     * @param clientReceiver receiver in the client app.
     * @param binder handle to the {@code IContentCaptureDirectManager} object that resides in the
     * service.
     * @hide
     */
    public static void setClientState(@NonNull IResultReceiver clientReceiver,
            int sessionStatus, @Nullable IBinder binder) {
        try {
            final Bundle extras;
            if (binder != null) {
                extras = new Bundle();
                extras.putBinder(ActivityContentCaptureSession.EXTRA_BINDER, binder);
            } else {
                extras = null;
            }
            clientReceiver.send(sessionStatus, extras);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error async reporting result to client: " + e);
        }
    }
}
