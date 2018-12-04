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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.contentcapture.ContentCaptureEvent;

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

    // TODO(b/111330312): STOPSHIP use dynamic value, or change to false
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

    private final IContentCaptureService mInterface = new IContentCaptureService.Stub() {

        @Override
        public void onSessionLifecycle(InteractionContext context, String sessionId)
                throws RemoteException {
            if (context != null) {
                mHandler.sendMessage(
                        obtainMessage(ContentCaptureService::handleOnCreateInteractionSession,
                                ContentCaptureService.this, context, sessionId));
            } else {
                mHandler.sendMessage(
                        obtainMessage(ContentCaptureService::handleOnDestroyInteractionSession,
                                ContentCaptureService.this, sessionId));
            }
        }

        @Override
        public void onContentCaptureEventsRequest(String sessionId,
                ContentCaptureEventsRequest request) {
            mHandler.sendMessage(
                    obtainMessage(ContentCaptureService::handleOnContentCaptureEventsRequest,
                            ContentCaptureService.this, sessionId, request));

        }

        @Override
        public void onActivitySnapshot(String sessionId, SnapshotData snapshotData) {
            mHandler.sendMessage(
                    obtainMessage(ContentCaptureService::handleOnActivitySnapshot,
                            ContentCaptureService.this, sessionId, snapshotData));
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
    public abstract void onContentCaptureEventsRequest(@NonNull InteractionSessionId sessionId,
            @NonNull ContentCaptureEventsRequest request);

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

    //TODO(b/111276913): consider caching the InteractionSessionId for the lifetime of the session,
    // so we don't need to create a temporary InteractionSessionId for each event.

    private void handleOnCreateInteractionSession(@NonNull InteractionContext context,
            @NonNull String sessionId) {
        onCreateInteractionSession(context, new InteractionSessionId(sessionId));
    }

    private void handleOnContentCaptureEventsRequest(@NonNull String sessionId,
            @NonNull ContentCaptureEventsRequest request) {
        onContentCaptureEventsRequest(new InteractionSessionId(sessionId), request);
    }

    private void handleOnActivitySnapshot(@NonNull String sessionId,
            @NonNull SnapshotData snapshotData) {
        onActivitySnapshot(new InteractionSessionId(sessionId), snapshotData);
    }

    private void handleOnDestroyInteractionSession(@NonNull String sessionId) {
        onDestroyInteractionSession(new InteractionSessionId(sessionId));
    }
}
