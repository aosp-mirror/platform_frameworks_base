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

import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_PAUSED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_RESUMED;
import static android.view.contentcapture.ContentCaptureHelper.sDebug;
import static android.view.contentcapture.ContentCaptureHelper.sVerbose;
import static android.view.contentcapture.ContentCaptureHelper.toList;
import static android.view.contentcapture.ContentCaptureManager.NO_SESSION_ID;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.contentcapture.ContentCaptureCondition;
import android.view.contentcapture.ContentCaptureContext;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.ContentCaptureSession;
import android.view.contentcapture.ContentCaptureSessionId;
import android.view.contentcapture.DataRemovalRequest;
import android.view.contentcapture.DataShareRequest;
import android.view.contentcapture.IContentCaptureDirectManager;
import android.view.contentcapture.MainContentCaptureSession;

import com.android.internal.os.IResultReceiver;
import com.android.internal.util.FrameworkStatsLog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A service used to capture the content of the screen to provide contextual data in other areas of
 * the system such as Autofill.
 *
 * @hide
 */
@SystemApi
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

    /**
     * The {@link Intent} that must be declared as handled by the protection service.
     *
     * <p>To be supported, the service must also require the {@link
     * android.Manifest.permission#BIND_CONTENT_CAPTURE_SERVICE} permission so that other
     * applications can not abuse it.
     *
     * @hide
     */
    public static final String PROTECTION_SERVICE_INTERFACE =
            "android.service.contentcapture.ContentProtectionService";

    /**
     * Name under which a ContentCaptureService component publishes information about itself.
     *
     * <p>This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#ContentCaptureService content-capture-service}&gt;</code> tag.
     *
     * <p>Here's an example of how to use it on {@code AndroidManifest.xml}:
     *
     * <pre>
     * &lt;service android:name=".MyContentCaptureService"
     *     android:permission="android.permission.BIND_CONTENT_CAPTURE_SERVICE"&gt;
     *   &lt;intent-filter&gt;
     *     &lt;action android:name="android.service.contentcapture.ContentCaptureService" /&gt;
     *   &lt;/intent-filter&gt;
     *
     *   &lt;meta-data
     *       android:name="android.content_capture"
     *       android:resource="@xml/my_content_capture_service"/&gt;
     * &lt;/service&gt;
     * </pre>
     *
     * <p>And then on {@code res/xml/my_content_capture_service.xml}:
     *
     * <pre>
     *   &lt;content-capture-service xmlns:android="http://schemas.android.com/apk/res/android"
     *       android:settingsActivity="my.package.MySettingsActivity"&gt;
     *   &lt;/content-capture-service&gt;
     * </pre>
     */
    public static final String SERVICE_META_DATA = "android.content_capture";

    private final LocalDataShareAdapterResourceManager mDataShareAdapterResourceManager =
            new LocalDataShareAdapterResourceManager();

    private Handler mHandler;
    @Nullable private IContentCaptureServiceCallback mContentCaptureServiceCallback;
    @Nullable private IContentProtectionAllowlistCallback mContentProtectionAllowlistCallback;

    private long mCallerMismatchTimeout = 1000;
    private long mLastCallerMismatchLog;

    /** Binder that receives calls from the system server in the content capture flow. */
    private final IContentCaptureService mContentCaptureServerInterface =
            new IContentCaptureService.Stub() {

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
        public void onSessionStarted(ContentCaptureContext context, int sessionId, int uid,
                IResultReceiver clientReceiver, int initialState) {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleOnCreateSession,
                    ContentCaptureService.this, context, sessionId, uid, clientReceiver,
                    initialState));
        }

        @Override
        public void onActivitySnapshot(int sessionId, SnapshotData snapshotData) {
            mHandler.sendMessage(
                    obtainMessage(ContentCaptureService::handleOnActivitySnapshot,
                            ContentCaptureService.this, sessionId, snapshotData));
        }

        @Override
        public void onSessionFinished(int sessionId) {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleFinishSession,
                    ContentCaptureService.this, sessionId));
        }

        @Override
        public void onDataRemovalRequest(DataRemovalRequest request) {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleOnDataRemovalRequest,
                    ContentCaptureService.this, request));
        }

        @Override
        public void onDataShared(DataShareRequest request, IDataShareCallback callback) {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleOnDataShared,
                    ContentCaptureService.this, request, callback));
        }

        @Override
        public void onActivityEvent(ActivityEvent event) {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleOnActivityEvent,
                    ContentCaptureService.this, event));
        }
    };

    /** Binder that receives calls from the system server in the content protection flow. */
    private final IContentProtectionService mContentProtectionServerInterface =
            new IContentProtectionService.Stub() {

                @Override
                public void onLoginDetected(
                        @SuppressWarnings("rawtypes") ParceledListSlice events) {
                    mHandler.sendMessage(
                            obtainMessage(
                                    ContentCaptureService::handleOnLoginDetected,
                                    ContentCaptureService.this,
                                    Binder.getCallingUid(),
                                    events));
                }

                @Override
                public void onUpdateAllowlistRequest(IBinder callback) {
                    mHandler.sendMessage(
                            obtainMessage(
                                    ContentCaptureService::handleOnUpdateAllowlistRequest,
                                    ContentCaptureService.this,
                                    Binder.getCallingUid(),
                                    callback));
                }
            };

    /** Binder that receives calls from the app in the content capture flow. */
    private final IContentCaptureDirectManager mContentCaptureClientInterface =
            new IContentCaptureDirectManager.Stub() {

        @Override
        public void sendEvents(@SuppressWarnings("rawtypes") ParceledListSlice events, int reason,
                ContentCaptureOptions options) {
            mHandler.sendMessage(obtainMessage(ContentCaptureService::handleSendEvents,
                    ContentCaptureService.this, Binder.getCallingUid(), events, reason, options));
        }
    };

    /**
     * UIDs associated with each session.
     *
     * <p>This map is populated when an session is started, which is called by the system server
     * and can be trusted. Then subsequent calls made by the app are verified against this map.
     */
    private final SparseIntArray mSessionUids = new SparseIntArray();

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
            return mContentCaptureServerInterface.asBinder();
        }
        if (PROTECTION_SERVICE_INTERFACE.equals(intent.getAction())) {
            return mContentProtectionServerInterface.asBinder();
        }
        Log.w(
                TAG,
                "Tried to bind to wrong intent (should be "
                        + SERVICE_INTERFACE
                        + " or "
                        + PROTECTION_SERVICE_INTERFACE
                        + "): "
                        + intent);
        return null;
    }

    /**
     * Explicitly limits content capture to the given packages and activities.
     *
     * <p>To reset the allowlist, call it passing {@code null} to both arguments.
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

        IContentCaptureServiceCallback contentCaptureCallback = mContentCaptureServiceCallback;
        IContentProtectionAllowlistCallback contentProtectionAllowlistCallback =
                mContentProtectionAllowlistCallback;

        if (contentCaptureCallback == null && contentProtectionAllowlistCallback == null) {
            Log.w(TAG, "setContentCaptureWhitelist(): missing both server callbacks");
            return;
        }

        if (contentCaptureCallback != null) {
            if (contentProtectionAllowlistCallback != null) {
                throw new IllegalStateException("Have both server callbacks");
            }
            try {
                contentCaptureCallback.setContentCaptureWhitelist(
                        toList(packages), toList(activities));
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            return;
        }

        try {
            contentProtectionAllowlistCallback.setAllowlist(toList(packages));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Explicitly sets the conditions for which content capture should be available by an app.
     *
     * <p>Typically used to restrict content capture to a few websites on browser apps. Example:
     *
     * <code>
     *   ArraySet<ContentCaptureCondition> conditions = new ArraySet<>(1);
     *   conditions.add(new ContentCaptureCondition(new LocusId("^https://.*\\.example\\.com$"),
     *       ContentCaptureCondition.FLAG_IS_REGEX));
     *   service.setContentCaptureConditions("com.example.browser_app", conditions);
     *
     * </code>
     *
     * <p>NOTE: </p> this method doesn't automatically disable content capture for the given
     * conditions; it's up to the {@code packageName} implementation to call
     * {@link ContentCaptureManager#getContentCaptureConditions()} and disable it accordingly.
     *
     * @param packageName name of the packages where the restrictions are set.
     * @param conditions list of conditions, or {@code null} to reset the conditions for the
     * package.
     */
    public final void setContentCaptureConditions(@NonNull String packageName,
            @Nullable Set<ContentCaptureCondition> conditions) {
        final IContentCaptureServiceCallback callback = mContentCaptureServiceCallback;
        if (callback == null) {
            Log.w(TAG, "setContentCaptureConditions(): no server callback");
            return;
        }

        try {
            callback.setContentCaptureConditions(packageName, toList(conditions));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
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
     * Notifies the service that the app requested to remove content capture data.
     *
     * @param request the content capture data requested to be removed
     */
    public void onDataRemovalRequest(@NonNull DataRemovalRequest request) {
        if (sVerbose) Log.v(TAG, "onDataRemovalRequest()");
    }

    /**
     * Notifies the service that data has been shared via a readable file.
     *
     * @param request request object containing information about data being shared
     * @param callback callback to be fired with response on whether the request is "needed" and can
     *                 be handled by the Content Capture service.
     *
     * @hide
     */
    @SystemApi
    public void onDataShareRequest(@NonNull DataShareRequest request,
            @NonNull DataShareCallback callback) {
        if (sVerbose) Log.v(TAG, "onDataShareRequest()");
    }

    /**
     * Notifies the service of {@link SnapshotData snapshot data} associated with an activity.
     *
     * @param sessionId the session's Id. This may also be
     *                  {@link ContentCaptureSession#NO_SESSION_ID} if no content capture session
     *                  exists for the activity being snapshotted
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
     * that are not allowlisted for Content Capture.
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
    public final void disableSelf() {
        if (sDebug) Log.d(TAG, "disableSelf()");

        final IContentCaptureServiceCallback callback = mContentCaptureServiceCallback;
        if (callback == null) {
            Log.w(TAG, "disableSelf(): no server callback");
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
     * <p> At this point this service may no longer be an active {@link ContentCaptureService}.
     * It should not make calls on {@link ContentCaptureManager} that requires the caller to be
     * the current service.
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
        mContentCaptureServiceCallback = IContentCaptureServiceCallback.Stub.asInterface(callback);
        onConnected();
    }

    private void handleOnDisconnected() {
        onDisconnected();
        mContentCaptureServiceCallback = null;
        mContentProtectionAllowlistCallback = null;
    }

    //TODO(b/111276913): consider caching the InteractionSessionId for the lifetime of the session,
    // so we don't need to create a temporary InteractionSessionId for each event.

    private void handleOnCreateSession(@NonNull ContentCaptureContext context,
            int sessionId, int uid, IResultReceiver clientReceiver, int initialState) {
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
            stateFlags = initialState;
        } else {
            stateFlags |= ContentCaptureSession.STATE_DISABLED;
        }
        setClientState(clientReceiver, stateFlags, mContentCaptureClientInterface.asBinder());
    }

    private void handleSendEvents(int uid,
            @NonNull ParceledListSlice<ContentCaptureEvent> parceledEvents, int reason,
            @Nullable ContentCaptureOptions options) {
        final List<ContentCaptureEvent> events = parceledEvents.getList();
        if (events.isEmpty()) {
            Log.w(TAG, "handleSendEvents() received empty list of events");
            return;
        }

        // Metrics.
        final FlushMetrics metrics = new FlushMetrics();
        ComponentName activityComponent = null;

        // Most events belong to the same session, so we can keep a reference to the last one
        // to avoid creating too many ContentCaptureSessionId objects
        int lastSessionId = NO_SESSION_ID;
        ContentCaptureSessionId sessionId = null;

        for (int i = 0; i < events.size(); i++) {
            final ContentCaptureEvent event = events.get(i);
            if (!handleIsRightCallerFor(event, uid)) continue;
            int sessionIdInt = event.getSessionId();
            if (sessionIdInt != lastSessionId) {
                sessionId = new ContentCaptureSessionId(sessionIdInt);
                lastSessionId = sessionIdInt;
                if (i != 0) {
                    writeFlushMetrics(lastSessionId, activityComponent, metrics, options, reason);
                    metrics.reset();
                }
            }
            final ContentCaptureContext clientContext = event.getContentCaptureContext();
            if (activityComponent == null && clientContext != null) {
                activityComponent = clientContext.getActivityComponent();
            }
            switch (event.getType()) {
                case ContentCaptureEvent.TYPE_SESSION_STARTED:
                    clientContext.setParentSessionId(event.getParentSessionId());
                    mSessionUids.put(sessionIdInt, uid);
                    onCreateContentCaptureSession(clientContext, sessionId);
                    metrics.sessionStarted++;
                    break;
                case ContentCaptureEvent.TYPE_SESSION_FINISHED:
                    mSessionUids.delete(sessionIdInt);
                    onDestroyContentCaptureSession(sessionId);
                    metrics.sessionFinished++;
                    break;
                case ContentCaptureEvent.TYPE_VIEW_APPEARED:
                    onContentCaptureEvent(sessionId, event);
                    metrics.viewAppearedCount++;
                    break;
                case ContentCaptureEvent.TYPE_VIEW_DISAPPEARED:
                    onContentCaptureEvent(sessionId, event);
                    metrics.viewDisappearedCount++;
                    break;
                case ContentCaptureEvent.TYPE_VIEW_TEXT_CHANGED:
                    onContentCaptureEvent(sessionId, event);
                    metrics.viewTextChangedCount++;
                    break;
                default:
                    onContentCaptureEvent(sessionId, event);
            }
        }
        writeFlushMetrics(lastSessionId, activityComponent, metrics, options, reason);
    }

    private void handleOnLoginDetected(
            int uid, @NonNull ParceledListSlice<ContentCaptureEvent> parceledEvents) {
        if (uid != Process.SYSTEM_UID) {
            Log.e(TAG, "handleOnLoginDetected() not allowed for uid: " + uid);
            return;
        }
        List<ContentCaptureEvent> events = parceledEvents.getList();
        int sessionIdInt = events.isEmpty() ? NO_SESSION_ID : events.get(0).getSessionId();
        ContentCaptureSessionId sessionId = new ContentCaptureSessionId(sessionIdInt);

        ContentCaptureEvent startEvent =
                new ContentCaptureEvent(sessionIdInt, TYPE_SESSION_RESUMED);
        startEvent.setSelectionIndex(0, events.size());
        onContentCaptureEvent(sessionId, startEvent);

        events.forEach(event -> onContentCaptureEvent(sessionId, event));

        ContentCaptureEvent endEvent = new ContentCaptureEvent(sessionIdInt, TYPE_SESSION_PAUSED);
        onContentCaptureEvent(sessionId, endEvent);
    }

    private void handleOnUpdateAllowlistRequest(int uid, @NonNull IBinder callback) {
        if (uid != Process.SYSTEM_UID) {
            Log.e(TAG, "handleOnUpdateAllowlistRequest() not allowed for uid: " + uid);
            return;
        }
        mContentProtectionAllowlistCallback =
                IContentProtectionAllowlistCallback.Stub.asInterface(callback);
        onConnected();
    }

    private void handleOnActivitySnapshot(int sessionId, @NonNull SnapshotData snapshotData) {
        onActivitySnapshot(new ContentCaptureSessionId(sessionId), snapshotData);
    }

    private void handleFinishSession(int sessionId) {
        mSessionUids.delete(sessionId);
        onDestroyContentCaptureSession(new ContentCaptureSessionId(sessionId));
    }

    private void handleOnDataRemovalRequest(@NonNull DataRemovalRequest request) {
        onDataRemovalRequest(request);
    }

    private void handleOnDataShared(@NonNull DataShareRequest request,
            IDataShareCallback callback) {
        onDataShareRequest(request, new DataShareCallback() {

            @Override
            public void onAccept(@NonNull Executor executor,
                    @NonNull DataShareReadAdapter adapter) {
                Objects.requireNonNull(adapter);
                Objects.requireNonNull(executor);

                DataShareReadAdapterDelegate delegate =
                        new DataShareReadAdapterDelegate(executor, adapter,
                                mDataShareAdapterResourceManager);

                try {
                    callback.accept(delegate);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to accept data sharing", e);
                }
            }

            @Override
            public void onReject() {
                try {
                    callback.reject();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to reject data sharing", e);
                }
            }
        });
    }

    private void handleOnActivityEvent(@NonNull ActivityEvent event) {
        onActivityEvent(event);
    }

    /**
     * Checks if the given {@code uid} owns the session associated with the event.
     */
    private boolean handleIsRightCallerFor(@NonNull ContentCaptureEvent event, int uid) {
        final int sessionId;
        switch (event.getType()) {
            case ContentCaptureEvent.TYPE_SESSION_STARTED:
            case ContentCaptureEvent.TYPE_SESSION_FINISHED:
                sessionId = event.getParentSessionId();
                break;
            default:
                sessionId = event.getSessionId();
        }
        if (mSessionUids.indexOfKey(sessionId) < 0) {
            if (sVerbose) {
                Log.v(TAG, "handleIsRightCallerFor(" + event + "): no session for " + sessionId
                        + ": " + mSessionUids);
            }
            // Just ignore, as the session could have been finished already
            return false;
        }
        final int rightUid = mSessionUids.get(sessionId);
        if (rightUid != uid) {
            Log.e(TAG, "invalid call from UID " + uid + ": session " + sessionId + " belongs to "
                    + rightUid);
            long now = System.currentTimeMillis();
            if (now - mLastCallerMismatchLog > mCallerMismatchTimeout) {
                FrameworkStatsLog.write(FrameworkStatsLog.CONTENT_CAPTURE_CALLER_MISMATCH_REPORTED,
                        getPackageManager().getNameForUid(rightUid),
                        getPackageManager().getNameForUid(uid));
                mLastCallerMismatchLog = now;
            }
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

    /**
     * Logs the metrics for content capture events flushing.
     */
    private void writeFlushMetrics(int sessionId, @Nullable ComponentName app,
            @NonNull FlushMetrics flushMetrics, @Nullable ContentCaptureOptions options,
            int flushReason) {
        if (mContentCaptureServiceCallback == null) {
            Log.w(TAG, "writeSessionFlush(): no server callback");
            return;
        }

        try {
            mContentCaptureServiceCallback.writeSessionFlush(
                    sessionId, app, flushMetrics, options, flushReason);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to write flush metrics: " + e);
        }
    }

    private static class DataShareReadAdapterDelegate extends IDataShareReadAdapter.Stub {

        private final WeakReference<LocalDataShareAdapterResourceManager> mResourceManagerReference;
        private final Object mLock = new Object();

        DataShareReadAdapterDelegate(Executor executor, DataShareReadAdapter adapter,
                LocalDataShareAdapterResourceManager resourceManager) {
            Objects.requireNonNull(executor);
            Objects.requireNonNull(adapter);
            Objects.requireNonNull(resourceManager);

            resourceManager.initializeForDelegate(this, adapter, executor);
            mResourceManagerReference = new WeakReference<>(resourceManager);
        }

        @Override
        public void start(ParcelFileDescriptor fd)
                throws RemoteException {
            synchronized (mLock) {
                executeAdapterMethodLocked(adapter -> adapter.onStart(fd), "onStart");
            }
        }

        @Override
        public void error(int errorCode) throws RemoteException {
            synchronized (mLock) {
                executeAdapterMethodLocked(
                        adapter -> adapter.onError(errorCode), "onError");
                clearHardReferences();
            }
        }

        @Override
        public void finish() throws RemoteException {
            synchronized (mLock) {
                clearHardReferences();
            }
        }

        private void executeAdapterMethodLocked(Consumer<DataShareReadAdapter> adapterFn,
                String methodName) {
            LocalDataShareAdapterResourceManager resourceManager = mResourceManagerReference.get();
            if (resourceManager == null) {
                Slog.w(TAG, "Can't execute " + methodName + "(), resource manager has been GC'ed");
                return;
            }

            DataShareReadAdapter adapter = resourceManager.getAdapter(this);
            Executor executor = resourceManager.getExecutor(this);

            if (adapter == null || executor == null) {
                Slog.w(TAG, "Can't execute " + methodName + "(), references are null");
                return;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                executor.execute(() -> adapterFn.accept(adapter));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void clearHardReferences() {
            LocalDataShareAdapterResourceManager resourceManager = mResourceManagerReference.get();
            if (resourceManager == null) {
                Slog.w(TAG, "Can't clear references, resource manager has been GC'ed");
                return;
            }

            resourceManager.clearHardReferences(this);
        }
    }

    /**
     * Wrapper class making sure dependencies on the current application stay in the application
     * context.
     */
    private static class LocalDataShareAdapterResourceManager {

        // Keeping hard references to the remote objects in the current process (static context)
        // to prevent them to be gc'ed during the lifetime of the application. This is an
        // artifact of only operating with weak references remotely: there has to be at least 1
        // hard reference in order for this to not be killed.
        private Map<DataShareReadAdapterDelegate, DataShareReadAdapter>
                mDataShareReadAdapterHardReferences = new HashMap<>();
        private Map<DataShareReadAdapterDelegate, Executor> mExecutorHardReferences =
                new HashMap<>();


        void initializeForDelegate(DataShareReadAdapterDelegate delegate,
                DataShareReadAdapter adapter, Executor executor) {
            mDataShareReadAdapterHardReferences.put(delegate, adapter);
            mExecutorHardReferences.put(delegate, executor);
        }

        Executor getExecutor(DataShareReadAdapterDelegate delegate) {
            return mExecutorHardReferences.get(delegate);
        }

        DataShareReadAdapter getAdapter(DataShareReadAdapterDelegate delegate) {
            return mDataShareReadAdapterHardReferences.get(delegate);
        }

        void clearHardReferences(DataShareReadAdapterDelegate delegate) {
            mDataShareReadAdapterHardReferences.remove(delegate);
            mExecutorHardReferences.remove(delegate);
        }
    }
}
