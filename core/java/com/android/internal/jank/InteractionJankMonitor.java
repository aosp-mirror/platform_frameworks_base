/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.jank;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.HandlerThread;
import android.view.ThreadedRenderer;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;

/**
 * This class let users to begin and end the always on tracing mechanism.
 * @hide
 */
public class InteractionJankMonitor {
    private static final String TAG = InteractionJankMonitor.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final Object LOCK = new Object();
    public static final int CUJ_NOTIFICATION_SHADE_MOTION = 0;
    public static final int CUJ_NOTIFICATION_SHADE_GESTURE = 1;

    private static ThreadedRenderer sRenderer;
    private static Map<String, FrameTracker> sRunningTracker;
    private static HandlerThread sWorker;
    private static boolean sInitialized;

    /** @hide */
    @IntDef({
            CUJ_NOTIFICATION_SHADE_MOTION,
            CUJ_NOTIFICATION_SHADE_GESTURE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CujType {}

    /**
     * @param view Any view in the view tree to get context and ThreadedRenderer.
     */
    public static void init(@NonNull View view) {
        init(view, null, null, null);
    }

    /**
     * Should be only invoked internally or from unit tests.
     */
    @VisibleForTesting
    public static void init(@NonNull View view, @NonNull ThreadedRenderer renderer,
            @NonNull Map<String, FrameTracker> map, @NonNull HandlerThread worker) {
        //TODO (163505250): This should be no-op if not in droid food rom.
        synchronized (LOCK) {
            if (!sInitialized) {
                if (!view.isAttachedToWindow()) {
                    throw new IllegalStateException("View is not attached!");
                }
                sRenderer = renderer == null ? view.getThreadedRenderer() : renderer;
                sRunningTracker = map == null ? new HashMap<>() : map;
                sWorker = worker == null ? new HandlerThread("Aot-Worker") : worker;
                sWorker.start();
                sInitialized = true;
            }
        }
    }

    /**
     * Must invoke init() before invoking this method.
     */
    public static void begin(@NonNull @CujType int cujType) {
        begin(cujType, null);
    }

    /**
     * Should be only invoked internally or from unit tests.
     */
    @VisibleForTesting
    public static void begin(@NonNull @CujType int cujType, FrameTracker tracker) {
        //TODO (163505250): This should be no-op if not in droid food rom.
        //TODO (163510843): Remove synchronized, add @UiThread if only invoked from ui threads.
        synchronized (LOCK) {
            checkInitStateLocked();
            Session session = new Session(cujType);
            FrameTracker currentTracker = getTracker(session.getName());
            if (currentTracker != null) return;
            if (tracker == null) {
                tracker = new FrameTracker(session, sWorker.getThreadHandler(), sRenderer);
            }
            sRunningTracker.put(session.getName(), tracker);
            tracker.begin();
        }
    }

    /**
     * Must invoke init() before invoking this method.
     */
    public static void end(@NonNull @CujType int cujType) {
        //TODO (163505250): This should be no-op if not in droid food rom.
        //TODO (163510843): Remove synchronized, add @UiThread if only invoked from ui threads.
        synchronized (LOCK) {
            checkInitStateLocked();
            Session session = new Session(cujType);
            FrameTracker tracker = getTracker(session.getName());
            if (tracker != null) {
                tracker.end();
                sRunningTracker.remove(session.getName());
            }
        }
    }

    private static void checkInitStateLocked() {
        if (!sInitialized) {
            throw new IllegalStateException("InteractionJankMonitor not initialized!");
        }
    }

    /**
     * Should be only invoked from unit tests.
     */
    @VisibleForTesting
    public static void reset() {
        sInitialized = false;
        sRenderer = null;
        sRunningTracker = null;
        if (sWorker != null) {
            sWorker.quit();
            sWorker = null;
        }
    }

    private static FrameTracker getTracker(String sessionName) {
        synchronized (LOCK) {
            return sRunningTracker.get(sessionName);
        }
    }

    /**
     * Trigger the perfetto daemon to collect and upload data.
     */
    public static void trigger() {
        sWorker.getThreadHandler().post(
                () -> PerfettoTrigger.trigger(PerfettoTrigger.TRIGGER_TYPE_JANK));
    }

    /**
     * A class to represent a session.
     */
    public static class Session {
        private @CujType int mId;

        public Session(@CujType int session) {
            mId = session;
        }

        public int getId() {
            return mId;
        }

        public String getName() {
            return "CujType<" + mId + ">";
        }
    }

}
