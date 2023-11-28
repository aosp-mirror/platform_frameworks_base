/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.window;

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Surface.FRAME_RATE_CATEGORY_DEFAULT;
import static android.view.Surface.FRAME_RATE_CATEGORY_HIGH;
import static android.view.SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN;
import static android.view.SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_PROPAGATE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.PerformanceHintManager;
import android.os.Trace;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * A helper class to manage performance related hints for a process.  This helper is used for both
 * long-lived and transient hints.
 *
 * @hide
 */
public class SystemPerformanceHinter {
    private static final String TAG = "SystemPerformanceHinter";

    private static final int HINT_NO_OP = 0;
    // Change app and SF wakeup times to allow sf more time to composite a frame
    public static final int HINT_SF_EARLY_WAKEUP = 1 << 0;
    // Force max refresh rate
    public static final int HINT_SF_FRAME_RATE = 1 << 1;
    // Boost CPU & GPU clocks
    public static final int HINT_ADPF = 1 << 2;
    // Convenience constant for SF only flags
    public static final int HINT_SF = HINT_SF_EARLY_WAKEUP | HINT_SF_FRAME_RATE;
    // Convenience constant for all the flags
    public static final int HINT_ALL = HINT_SF_EARLY_WAKEUP | HINT_SF_FRAME_RATE | HINT_ADPF;

    // Hints that are applied per-display and require a display root surface
    private static final int HINT_PER_DISPLAY = HINT_SF_FRAME_RATE;
    // Hints that are global (not per-display)
    private static final int HINT_GLOBAL = HINT_SF_EARLY_WAKEUP | HINT_ADPF;

    @IntDef(prefix = {"HINT_"}, value = {
            HINT_SF_EARLY_WAKEUP,
            HINT_SF_FRAME_RATE,
            HINT_ADPF,
    })
    private @interface HintFlags {}

    /**
     * A provider for the root to apply SurfaceControl hints which will be inherited by all children
     * of that root.
     * @hide
     */
    public interface DisplayRootProvider {
        /**
         * @return the SurfaceControl to apply hints for the given displayId.
         */
        @Nullable SurfaceControl getRootForDisplay(int displayId);
    }

    /**
     * A session where high performance is needed.
     * @hide
     */
    public class HighPerfSession implements AutoCloseable {
        private final @HintFlags int hintFlags;
        private final String reason;
        private final int displayId;
        private String mTraceName;

        protected HighPerfSession(@HintFlags int hintFlags, int displayId, @NonNull String reason) {
            this.hintFlags = hintFlags;
            this.reason = reason;
            this.displayId = displayId;
        }

        /** Makes this session active. It is no-op if this session is already active. */
        public void start() {
            if (!mActiveSessions.contains(this)) {
                startSession(this);
            }
        }

        /**
         * Closes this session.
         */
        @Override
        public void close() {
            endSession(this);
        }

        @Override
        public void finalize() {
            close();
        }

        boolean asyncTraceBegin() {
            if (!Trace.isTagEnabled(mTraceTag)) {
                mTraceName = null;
                return false;
            }
            if (mTraceName == null) {
                mTraceName = "PerfSession-d" + displayId + "-" + reason;
            }
            Trace.asyncTraceForTrackBegin(mTraceTag, TAG, mTraceName,
                    System.identityHashCode(this));
            return true;
        }

        boolean asyncTraceEnd() {
            if (mTraceName == null) {
                return false;
            }
            Trace.asyncTraceForTrackEnd(mTraceTag, TAG, System.identityHashCode(this));
            return true;
        }
    }

    /**
     * A no-op implementation of a session.
     */
    private class NoOpHighPerfSession extends HighPerfSession {
        public NoOpHighPerfSession() {
            super(HINT_NO_OP, Display.INVALID_DISPLAY, "");
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
            // Do nothing
        }
    }

    /** The tag category of trace. */
    public long mTraceTag = Trace.TRACE_TAG_APP;

    // The active sessions
    private final ArrayList<HighPerfSession> mActiveSessions = new ArrayList<>();
    private final SurfaceControl.Transaction mTransaction;
    private final PerformanceHintManager mPerfHintManager;
    private @Nullable PerformanceHintManager.Session mAdpfSession;
    private @Nullable DisplayRootProvider mDisplayRootProvider;

    /**
     * Constructor for the hinter.
     * @hide
     */
    public SystemPerformanceHinter(@NonNull Context context,
            @Nullable DisplayRootProvider displayRootProvider) {
        this(context, displayRootProvider, null /* transactionSupplier */);
    }

    /**
     * Constructor for the hinter.
     * @hide
     */
    public SystemPerformanceHinter(@NonNull Context context,
            @Nullable DisplayRootProvider displayRootProvider,
            @Nullable Supplier<SurfaceControl.Transaction> transactionSupplier) {
        mDisplayRootProvider = displayRootProvider;
        mPerfHintManager = context.getSystemService(PerformanceHintManager.class);
        mTransaction = transactionSupplier != null
                ? transactionSupplier.get()
                : new SurfaceControl.Transaction();
    }

    /**
     * Sets the current ADPF session, required if you are using HINT_ADPF.  It is the responsibility
     * of the caller to manage up the ADPF session.
     * @hide
     */
    public void setAdpfSession(PerformanceHintManager.Session adpfSession) {
        mAdpfSession = adpfSession;
    }

    /** Creates a session that requires high performance. */
    public HighPerfSession createSession(@HintFlags int hintFlags, int displayId,
            @NonNull String reason) {
        if (hintFlags == HINT_NO_OP) {
            throw new IllegalArgumentException("Not allow empty hint flags");
        }
        if (mDisplayRootProvider == null && (hintFlags & HINT_SF_FRAME_RATE) != 0) {
            throw new IllegalArgumentException(
                    "Using SF frame rate hints requires a valid display root provider");
        }
        if (mAdpfSession == null && (hintFlags & HINT_ADPF) != 0) {
            throw new IllegalArgumentException("Using ADPF hints requires an ADPF session");
        }
        if ((hintFlags & HINT_PER_DISPLAY) != 0) {
            if (mDisplayRootProvider.getRootForDisplay(displayId) == null) {
                // Just log an error and return early if there is no root as there could be races
                // between when a display root is removed and when a hint session is requested
                Log.v(TAG, "No display root for displayId=" + displayId);
                Trace.instant(TRACE_TAG_WINDOW_MANAGER, "PerfHint-NoDisplayRoot: " + displayId);
                return new NoOpHighPerfSession();
            }
        }
        return new HighPerfSession(hintFlags, displayId, reason);
    }

    /**
     * Starts a new session that requires high performance.
     */
    public HighPerfSession startSession(@HintFlags int hintFlags, int displayId,
            @NonNull String reason) {
        final HighPerfSession session = createSession(hintFlags, displayId, reason);
        if (session.hintFlags != HINT_NO_OP) {
            startSession(session);
        }
        return session;
    }

    /** Starts the session that requires high performance. */
    private void startSession(HighPerfSession session) {
        final boolean isTraceEnabled = session.asyncTraceBegin();
        int oldGlobalFlags = calculateActiveHintFlags(HINT_GLOBAL);
        int oldPerDisplayFlags = calculateActiveHintFlagsForDisplay(HINT_PER_DISPLAY,
                session.displayId);
        mActiveSessions.add(session);
        int newGlobalFlags = calculateActiveHintFlags(HINT_GLOBAL);
        int newPerDisplayFlags = calculateActiveHintFlagsForDisplay(HINT_PER_DISPLAY,
                session.displayId);

        boolean transactionChanged = false;
        // Per-display flags
        if (nowEnabled(oldPerDisplayFlags, newPerDisplayFlags, HINT_SF_FRAME_RATE)) {
            SurfaceControl displaySurfaceControl = mDisplayRootProvider.getRootForDisplay(
                    session.displayId);
            mTransaction.setFrameRateSelectionStrategy(displaySurfaceControl,
                    FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN);
            // smoothSwitchOnly is false to request a higher framerate, even if it means switching
            // the display mode will cause would jank on non-VRR devices because keeping a lower
            // refresh rate would mean a poorer user experience.
            mTransaction.setFrameRateCategory(
                    displaySurfaceControl, FRAME_RATE_CATEGORY_HIGH, /* smoothSwitchOnly= */ false);
            transactionChanged = true;
            if (isTraceEnabled) {
                asyncTraceBegin(HINT_SF_FRAME_RATE, session.displayId);
            }
        }

        // Global flags
        if (nowEnabled(oldGlobalFlags, newGlobalFlags, HINT_SF_EARLY_WAKEUP)) {
            mTransaction.setEarlyWakeupStart();
            transactionChanged = true;
            if (isTraceEnabled) {
                asyncTraceBegin(HINT_SF_EARLY_WAKEUP, Display.INVALID_DISPLAY);
            }
        }
        if (nowEnabled(oldGlobalFlags, newGlobalFlags, HINT_ADPF)) {
            mAdpfSession.sendHint(PerformanceHintManager.Session.CPU_LOAD_UP);
            if (isTraceEnabled) {
                asyncTraceBegin(HINT_ADPF, Display.INVALID_DISPLAY);
            }
        }
        if (transactionChanged) {
            mTransaction.applyAsyncUnsafe();
        }
    }

    /**
     * Ends a session that requires high performance.
     */
    private void endSession(HighPerfSession session) {
        final boolean isTraceEnabled = session.asyncTraceEnd();
        int oldGlobalFlags = calculateActiveHintFlags(HINT_GLOBAL);
        int oldPerDisplayFlags = calculateActiveHintFlagsForDisplay(HINT_PER_DISPLAY,
                session.displayId);
        mActiveSessions.remove(session);
        int newGlobalFlags = calculateActiveHintFlags(HINT_GLOBAL);
        int newPerDisplayFlags = calculateActiveHintFlagsForDisplay(HINT_PER_DISPLAY,
                session.displayId);

        boolean transactionChanged = false;
        // Per-display flags
        if (nowDisabled(oldPerDisplayFlags, newPerDisplayFlags, HINT_SF_FRAME_RATE)) {
            SurfaceControl displaySurfaceControl = mDisplayRootProvider.getRootForDisplay(
                    session.displayId);
            mTransaction.setFrameRateSelectionStrategy(displaySurfaceControl,
                    FRAME_RATE_SELECTION_STRATEGY_PROPAGATE);
            // smoothSwitchOnly is false to request a higher framerate, even if it means switching
            // the display mode will cause would jank on non-VRR devices because keeping a lower
            // refresh rate would mean a poorer user experience.
            mTransaction.setFrameRateCategory(displaySurfaceControl, FRAME_RATE_CATEGORY_DEFAULT,
                    /* smoothSwitchOnly= */ false);
            transactionChanged = true;
            if (isTraceEnabled) {
                asyncTraceEnd(HINT_SF_FRAME_RATE);
            }
        }

        // Global flags
        if (nowDisabled(oldGlobalFlags, newGlobalFlags, HINT_SF_EARLY_WAKEUP)) {
            mTransaction.setEarlyWakeupEnd();
            transactionChanged = true;
            if (isTraceEnabled) {
                asyncTraceEnd(HINT_SF_EARLY_WAKEUP);
            }
        }
        if (nowDisabled(oldGlobalFlags, newGlobalFlags, HINT_ADPF)) {
            mAdpfSession.sendHint(PerformanceHintManager.Session.CPU_LOAD_RESET);
            if (isTraceEnabled) {
                asyncTraceEnd(HINT_ADPF);
            }
        }
        if (transactionChanged) {
            mTransaction.applyAsyncUnsafe();
        }
    }

    /**
     * Checks if checkFlags was previously not set and is now set.
     */
    private boolean nowEnabled(@HintFlags int oldFlags, @HintFlags int newFlags,
                               @HintFlags int checkFlags) {
        return (oldFlags & checkFlags) == 0 && (newFlags & checkFlags) != 0;
    }

    /**
     * Checks if checkFlags was previously set and is now not set.
     */
    private boolean nowDisabled(@HintFlags int oldFlags, @HintFlags int newFlags,
                                @HintFlags int checkFlags) {
        return (oldFlags & checkFlags) != 0 && (newFlags & checkFlags) == 0;
    }

    /**
     * @return the combined hint flags for all active sessions, filtered by {@param filterFlags}.
     */
    private @HintFlags int calculateActiveHintFlags(@HintFlags int filterFlags) {
        int flags = 0;
        for (int i = 0; i < mActiveSessions.size(); i++) {
            flags |= mActiveSessions.get(i).hintFlags & filterFlags;
        }
        return flags;
    }

    /**
     * @return the combined hint flags for all active sessions for a given display, filtered by
     *         {@param filterFlags}.
     */
    private @HintFlags int calculateActiveHintFlagsForDisplay(@HintFlags int filterFlags,
            int displayId) {
        int flags = 0;
        for (int i = 0; i < mActiveSessions.size(); i++) {
            final HighPerfSession session = mActiveSessions.get(i);
            if (session.displayId == displayId) {
                flags |= mActiveSessions.get(i).hintFlags & filterFlags;
            }
        }
        return flags;
    }

    private void asyncTraceBegin(@HintFlags int flag, int displayId) {
        final String prefix = switch (flag) {
            case HINT_SF_EARLY_WAKEUP -> "PerfHint-early_wakeup";
            case HINT_SF_FRAME_RATE -> "PerfHint-framerate";
            case HINT_ADPF -> "PerfHint-adpf";
            default -> "PerfHint-" + flag;
        };
        final String name = displayId != Display.INVALID_DISPLAY
                ? (prefix + "-d" + displayId) : prefix;
        Trace.asyncTraceForTrackBegin(mTraceTag, TAG, name,
                flag ^ System.identityHashCode(this));
    }

    private void asyncTraceEnd(@HintFlags int flag) {
        Trace.asyncTraceForTrackEnd(mTraceTag, TAG, flag ^ System.identityHashCode(this));
    }

    /**
     * Dumps the existing sessions.
     */
    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG + ":");
        pw.println(innerPrefix + "Active sessions (" + mActiveSessions.size() + "):");
        for (int i = 0; i < mActiveSessions.size(); i++) {
            final HighPerfSession s = mActiveSessions.get(i);
            pw.println(innerPrefix + "  reason=" + s.reason
                    + " flags=" + s.hintFlags
                    + " display=" + s.displayId);
        }
    }
}
