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
package android.view.contentcapture;

import static android.view.contentcapture.ContentCaptureHelper.sDebug;
import static android.view.contentcapture.ContentCaptureHelper.sVerbose;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UiThread;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.contentcapture.ContentCaptureSession.FlushReason;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.internal.util.SyncResultReceiver;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * TODO(b/123577059): add javadocs / mention it can be null
 */
@SystemService(Context.CONTENT_CAPTURE_MANAGER_SERVICE)
public final class ContentCaptureManager {

    private static final String TAG = ContentCaptureManager.class.getSimpleName();

    /** @hide */
    public static final int RESULT_CODE_TRUE = 1;
    /** @hide */
    public static final int RESULT_CODE_FALSE = 2;
    /** @hide */
    public static final int RESULT_CODE_NOT_SERVICE = -1;

    /**
     * Timeout for calls to system_server.
     */
    private static final int SYNC_CALLS_TIMEOUT_MS = 5000;

    /**
     * DeviceConfig property used by {@code com.android.server.SystemServer} on start to decide
     * whether the Content Capture service should be created or not
     *
     * <p>By default it should *NOT* be set (or set to {@code "default"}, so the decision is based
     * on whether the OEM provides an implementation for the service), but it can be overridden to:
     *
     * <ul>
     *   <li>Provide a "kill switch" so OEMs can disable it remotely in case of emergency (when
     *   it's set to {@code "false"}).
     *   <li>Enable the CTS tests to be run on AOSP builds (when it's set to {@code "true"}).
     * </ul>
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_SERVICE_EXPLICITLY_ENABLED =
            "service_explicitly_enabled";

    /**
     * Maximum number of events that are buffered before sent to the app.
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_MAX_BUFFER_SIZE = "max_buffer_size";

    /**
     * Frequency (in ms) of buffer flushes when no events are received.
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_IDLE_FLUSH_FREQUENCY = "idle_flush_frequency";

    /**
     * Frequency (in ms) of buffer flushes when no events are received and the last one was a
     * text change event.
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_TEXT_CHANGE_FLUSH_FREQUENCY =
            "text_change_flush_frequency";

    /**
     * Size of events that are logging on {@code dump}.
     *
     * <p>Set it to {@code 0} or less to disable history.
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_LOG_HISTORY_SIZE = "log_history_size";

    /**
     * Sets the logging level for {@code logcat} statements.
     *
     * <p>Valid values are: {@link #LOGGING_LEVEL_OFF}, {@value #LOGGING_LEVEL_DEBUG}, and
     * {@link #LOGGING_LEVEL_VERBOSE}.
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_LOGGING_LEVEL = "logging_level";


    /** @hide */
    @TestApi
    public static final int LOGGING_LEVEL_OFF = 0;

    /** @hide */
    @TestApi
    public static final int LOGGING_LEVEL_DEBUG = 1;

    /** @hide */
    @TestApi
    public static final int LOGGING_LEVEL_VERBOSE = 2;

    /** @hide */
    @IntDef(flag = false, value = {
            LOGGING_LEVEL_OFF,
            LOGGING_LEVEL_DEBUG,
            LOGGING_LEVEL_VERBOSE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LoggingLevel {}

    private final Object mLock = new Object();

    @NonNull
    private final Context mContext;

    @NonNull
    private final IContentCaptureManager mService;

    // Flags used for starting session.
    @GuardedBy("mLock")
    private int mFlags;

    // TODO(b/119220549): use UI Thread directly (as calls are one-way) or a shared thread / handler
    // held at the Application level
    @NonNull
    private final Handler mHandler;

    @GuardedBy("mLock")
    private MainContentCaptureSession mMainSession;

    /** @hide */
    public ContentCaptureManager(@NonNull Context context,
            @NonNull IContentCaptureManager service) {
        mContext = Preconditions.checkNotNull(context, "context cannot be null");
        mService = Preconditions.checkNotNull(service, "service cannot be null");

        // TODO(b/123096662): right now we're reading the device config values here, but ideally
        // it should be read on ContentCaptureManagerService and passed back when the activity
        // started.
        ContentCaptureHelper.setLoggingLevel();

        if (sVerbose) Log.v(TAG, "Constructor for " + context.getPackageName());

        // TODO(b/119220549): we might not even need a handler, as the IPCs are oneway. But if we
        // do, then we should optimize it to run the tests after the Choreographer finishes the most
        // important steps of the frame.
        mHandler = Handler.createAsync(Looper.getMainLooper());
    }

    /**
     * Gets the main session associated with the context.
     *
     * <p>By default there's just one (associated with the activity lifecycle), but apps could
     * explicitly add more using
     * {@link ContentCaptureSession#createContentCaptureSession(ContentCaptureContext)}.
     *
     * @hide
     */
    @NonNull
    @UiThread
    public MainContentCaptureSession getMainContentCaptureSession() {
        synchronized (mLock) {
            if (mMainSession == null) {
                mMainSession = new MainContentCaptureSession(mContext, this, mHandler, mService);
                if (sVerbose) Log.v(TAG, "getMainContentCaptureSession(): created " + mMainSession);
            }
            return mMainSession;
        }
    }

    /** @hide */
    @UiThread
    public void onActivityStarted(@NonNull IBinder applicationToken,
            @NonNull ComponentName activityComponent, int flags) {
        synchronized (mLock) {
            mFlags |= flags;
            getMainContentCaptureSession().start(applicationToken, activityComponent, mFlags);
        }
    }

    /** @hide */
    @UiThread
    public void onActivityStopped() {
        getMainContentCaptureSession().destroy();
    }

    /**
     * Flushes the content of all sessions.
     *
     * <p>Typically called by {@code Activity} when it's paused / resumed.
     *
     * @hide
     */
    @UiThread
    public void flush(@FlushReason int reason) {
        getMainContentCaptureSession().flush(reason);
    }

    /**
     * Returns the component name of the system service that is consuming the captured events for
     * the current user.
     */
    @Nullable
    public ComponentName getServiceComponentName() {
        if (!isContentCaptureEnabled()) return null;

        final SyncResultReceiver resultReceiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        try {
            mService.getServiceComponentName(resultReceiver);
            return resultReceiver.getParcelableResult();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether content capture is enabled for this activity.
     *
     * <p>There are many reasons it could be disabled, such as:
     * <ul>
     *   <li>App itself disabled content capture through {@link #setContentCaptureEnabled(boolean)}.
     *   <li>Service disabled content capture for this specific activity.
     *   <li>Service disabled content capture for all activities of this package.
     *   <li>Service disabled content capture globally.
     *   <li>User disabled content capture globally (through Settings).
     *   <li>OEM disabled content capture globally.
     *   <li>Transient errors.
     * </ul>
     */
    public boolean isContentCaptureEnabled() {
        final MainContentCaptureSession mainSession;
        synchronized (mLock) {
            mainSession = mMainSession;
        }
        // The main session is only set when the activity starts, so we need to return true until
        // then.
        if (mainSession != null && mainSession.isDisabled()) return false;

        return true;
    }

    /**
     * Called by apps to explicitly enable or disable content capture.
     *
     * <p><b>Note: </b> this call is not persisted accross reboots, so apps should typically call
     * it on {@link android.app.Activity#onCreate(android.os.Bundle, android.os.PersistableBundle)}.
     */
    public void setContentCaptureEnabled(boolean enabled) {
        if (sDebug) {
            Log.d(TAG, "setContentCaptureEnabled(): setting to " + enabled + " for " + mContext);
        }

        synchronized (mLock) {
            mFlags |= enabled ? 0 : ContentCaptureContext.FLAG_DISABLED_BY_APP;
        }
    }

    /**
     * Gets whether Content Capture is enabled for the given user.
     *
     * <p>This method is typically used by the Content Capture Service settings page, so it can
     * provide a toggle to enable / disable it.
     *
     * @throws SecurityException if caller is not the app that owns the Content Capture service
     * associated with the user.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public boolean isContentCaptureFeatureEnabled() {
        final SyncResultReceiver resultReceiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        final int resultCode;
        try {
            mService.isContentCaptureFeatureEnabled(resultReceiver);
            resultCode = resultReceiver.getIntResult();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        switch (resultCode) {
            case RESULT_CODE_TRUE:
                return true;
            case RESULT_CODE_FALSE:
                return false;
            case RESULT_CODE_NOT_SERVICE:
                throw new SecurityException("caller is not user's ContentCapture service");
            default:
                Log.wtf(TAG, "received invalid result: " + resultCode);
                return false;
        }
    }

    /**
     * Called by the app to request the Content Capture service to remove user-data associated with
     * some context.
     *
     * @param request object specifying what user data should be removed.
     */
    public void removeUserData(@NonNull UserDataRemovalRequest request) {
        Preconditions.checkNotNull(request);

        try {
            mService.removeUserData(request);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.println("ContentCaptureManager");
        final String prefix2 = prefix + "  ";
        synchronized (mLock) {
            pw.print(prefix2); pw.print("isContentCaptureEnabled(): ");
            pw.println(isContentCaptureEnabled());
            pw.print(prefix); pw.print("Debug: "); pw.print(sDebug);
            pw.print(" Verbose: "); pw.println(sVerbose);
            pw.print(prefix); pw.print("Context: "); pw.println(mContext);
            pw.print(prefix); pw.print("User: "); pw.println(mContext.getUserId());
            pw.print(prefix); pw.print("Service: "); pw.println(mService);
            pw.print(prefix); pw.print("Flags: "); pw.println(mFlags);
            if (mMainSession != null) {
                final String prefix3 = prefix2 + "  ";
                pw.print(prefix2); pw.println("Main session:");
                mMainSession.dump(prefix3, pw);
            } else {
                pw.print(prefix2); pw.println("No sessions");
            }
        }
    }
}
