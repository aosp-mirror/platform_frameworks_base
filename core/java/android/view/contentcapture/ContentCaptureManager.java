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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.annotation.UiThread;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * NOTE: all methods in this class should return right away, or do the real work in a handler
 * thread.
 *
 * Hence, the only field that must be thread-safe is mEnabled, which is called at the beginning
 * of every method.
 */
/**
 * TODO(b/111276913): add javadocs / implement
 */
@SystemService(Context.CONTENT_CAPTURE_MANAGER_SERVICE)
public final class ContentCaptureManager {

    private static final String TAG = ContentCaptureManager.class.getSimpleName();

    private static final String BG_THREAD_NAME = "intel_svc_streamer_thread";

    // TODO(b/121044306): define a way to dynamically set them(for example, using settings?)
    static final boolean VERBOSE = false;
    static final boolean DEBUG = true; // STOPSHIP if not set to false

    @NonNull
    private final AtomicBoolean mDisabled = new AtomicBoolean();

    @NonNull
    private final Context mContext;

    @Nullable
    private final IContentCaptureManager mService;

    // TODO(b/119220549): use UI Thread directly (as calls are one-way) or a shared thread / handler
    // held at the Application level
    @NonNull
    private final Handler mHandler;

    private MainContentCaptureSession mMainSession;

    /** @hide */
    public ContentCaptureManager(@NonNull Context context,
            @Nullable IContentCaptureManager service) {
        mContext = Preconditions.checkNotNull(context, "context cannot be null");
        if (VERBOSE) {
            Log.v(TAG, "Constructor for " + context.getPackageName());
        }
        mService = service;
        // TODO(b/119220549): use an existing bg thread instead...
        final HandlerThread bgThread = new HandlerThread(BG_THREAD_NAME);
        bgThread.start();
        mHandler = Handler.createAsync(bgThread.getLooper());
    }

    @NonNull
    private static Handler newHandler() {
        // TODO(b/119220549): use an existing bg thread instead...
        // TODO(b/119220549): use UI Thread directly (as calls are one-way) or an existing bgThread
        // or a shared thread / handler held at the Application level
        final HandlerThread bgThread = new HandlerThread(BG_THREAD_NAME);
        bgThread.start();
        return Handler.createAsync(bgThread.getLooper());
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
        if (mMainSession == null) {
            mMainSession = new MainContentCaptureSession(mContext, mHandler, mService,
                    mDisabled);
            if (VERBOSE) {
                Log.v(TAG, "getDefaultContentCaptureSession(): created " + mMainSession);
            }
        }
        return mMainSession;
    }

    /** @hide */
    public void onActivityStarted(@NonNull IBinder applicationToken,
            @NonNull ComponentName activityComponent, int flags) {
        getMainContentCaptureSession().start(applicationToken, activityComponent, flags);
    }

    /** @hide */
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
    public void flush() {
        getMainContentCaptureSession().flush();
    }

    /**
     * Returns the component name of the system service that is consuming the captured events for
     * the current user.
     */
    @Nullable
    public ComponentName getServiceComponentName() {
        //TODO(b/121047489): implement
        return null;
    }

    /**
     * Checks whether content capture is enabled for this activity.
     */
    public boolean isContentCaptureEnabled() {
        return mService != null && !mDisabled.get();
    }

    /**
     * Called by apps to explicitly enable or disable content capture.
     *
     * <p><b>Note: </b> this call is not persisted accross reboots, so apps should typically call
     * it on {@link android.app.Activity#onCreate(android.os.Bundle, android.os.PersistableBundle)}.
     */
    public void setContentCaptureEnabled(boolean enabled) {
        //TODO(b/111276913): implement (need to finish / disable all sessions)
    }

    /**
     * Called by the ap to request the Content Capture service to remove user-data associated with
     * some context.
     *
     * @param request object specifying what user data should be removed.
     */
    public void removeUserData(@NonNull UserDataRemovalRequest request) {
        //TODO(b/111276913): implement
    }

    /** @hide */
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.println("ContentCaptureManager");

        pw.print(prefix); pw.print("Disabled: "); pw.println(mDisabled.get());
        pw.print(prefix); pw.print("Context: "); pw.println(mContext);
        pw.print(prefix); pw.print("User: "); pw.println(mContext.getUserId());
        if (mService != null) {
            pw.print(prefix); pw.print("Service: "); pw.println(mService);
        }
        if (mMainSession != null) {
            final String prefix2 = prefix + "  ";
            pw.print(prefix); pw.println("Main session:");
            mMainSession.dump(prefix2, pw);
        } else {
            pw.print(prefix); pw.println("No sessions");
        }
    }
}
