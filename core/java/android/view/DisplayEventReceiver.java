/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.view;

import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.FrameInfo;
import android.os.Build;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import dalvik.annotation.optimization.FastNative;

import java.lang.ref.WeakReference;

/**
 * Provides a low-level mechanism for an application to receive display events
 * such as vertical sync.
 *
 * The display event receive is NOT thread safe.  Moreover, its methods must only
 * be called on the Looper thread to which it is attached.
 *
 * @hide
 */
public abstract class DisplayEventReceiver {

    /**
     * When retrieving vsync events, this specifies that the vsync event should happen at the normal
     * vsync-app tick.
     * <p>
     * Needs to be kept in sync with frameworks/native/include/gui/ISurfaceComposer.h
     */
    public static final int VSYNC_SOURCE_APP = 0;

    /**
     * When retrieving vsync events, this specifies that the vsync event should happen whenever
     * Surface Flinger is processing a frame.
     * <p>
     * Needs to be kept in sync with frameworks/native/include/gui/ISurfaceComposer.h
     */
    public static final int VSYNC_SOURCE_SURFACE_FLINGER = 1;

    /**
     * Specifies to generate mode changed events from Surface Flinger.
     * <p>
     * Needs to be kept in sync with frameworks/native/include/gui/ISurfaceComposer.h
     */
    public static final int EVENT_REGISTRATION_MODE_CHANGED_FLAG = 0x1;

    /**
     * Specifies to generate frame rate override events from Surface Flinger.
     * <p>
     * Needs to be kept in sync with frameworks/native/include/gui/ISurfaceComposer.h
     */
    public static final int EVENT_REGISTRATION_FRAME_RATE_OVERRIDE_FLAG = 0x2;

    private static final String TAG = "DisplayEventReceiver";

    @UnsupportedAppUsage
    private long mReceiverPtr;

    // We keep a reference message queue object here so that it is not
    // GC'd while the native peer of the receiver is using them.
    private MessageQueue mMessageQueue;

    private static native long nativeInit(WeakReference<DisplayEventReceiver> receiver,
            MessageQueue messageQueue, int vsyncSource, int eventRegistration);
    private static native void nativeDispose(long receiverPtr);
    @FastNative
    private static native void nativeScheduleVsync(long receiverPtr);
    private static native VsyncEventData nativeGetLatestVsyncEventData(long receiverPtr);

    /**
     * Creates a display event receiver.
     *
     * @param looper The looper to use when invoking callbacks.
     */
    @UnsupportedAppUsage
    public DisplayEventReceiver(Looper looper) {
        this(looper, VSYNC_SOURCE_APP, 0);
    }

    /**
     * Creates a display event receiver.
     *
     * @param looper The looper to use when invoking callbacks.
     * @param vsyncSource The source of the vsync tick. Must be on of the VSYNC_SOURCE_* values.
     * @param eventRegistration Which events to dispatch. Must be a bitfield consist of the
     * EVENT_REGISTRATION_*_FLAG values.
     */
    public DisplayEventReceiver(Looper looper, int vsyncSource, int eventRegistration) {
        if (looper == null) {
            throw new IllegalArgumentException("looper must not be null");
        }

        mMessageQueue = looper.getQueue();
        mReceiverPtr = nativeInit(new WeakReference<DisplayEventReceiver>(this), mMessageQueue,
                vsyncSource, eventRegistration);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            dispose(true);
        } finally {
            super.finalize();
        }
    }

    /**
     * Disposes the receiver.
     */
    public void dispose() {
        dispose(false);
    }

    private void dispose(boolean finalized) {
        if (mReceiverPtr != 0) {
            nativeDispose(mReceiverPtr);
            mReceiverPtr = 0;
        }
        mMessageQueue = null;
    }

    static final class VsyncEventData {

        static final FrameTimeline[] INVALID_FRAME_TIMELINES =
                {new FrameTimeline(FrameInfo.INVALID_VSYNC_ID, Long.MAX_VALUE, Long.MAX_VALUE)};

        public static class FrameTimeline {
            FrameTimeline(long vsyncId, long expectedPresentTime, long deadline) {
                this.vsyncId = vsyncId;
                this.expectedPresentTime = expectedPresentTime;
                this.deadline = deadline;
            }

            // The frame timeline vsync id, used to correlate a frame
            // produced by HWUI with the timeline data stored in Surface Flinger.
            public final long vsyncId;

            // The frame timestamp for when the frame is expected to be presented.
            public final long expectedPresentTime;

            // The frame deadline timestamp in {@link System#nanoTime()} timebase that it is
            // allotted for the frame to be completed.
            public final long deadline;
        }

        /**
         * The current interval between frames in ns. This will be used to align
         * {@link FrameInfo#VSYNC} to the current vsync in case Choreographer callback was heavily
         * delayed by the app.
         */
        public final long frameInterval;

        public final FrameTimeline[] frameTimelines;

        public final int preferredFrameTimelineIndex;

        // Called from native code.
        @SuppressWarnings("unused")
        VsyncEventData(FrameTimeline[] frameTimelines, int preferredFrameTimelineIndex,
                long frameInterval) {
            this.frameTimelines = frameTimelines;
            this.preferredFrameTimelineIndex = preferredFrameTimelineIndex;
            this.frameInterval = frameInterval;
        }

        VsyncEventData() {
            this.frameInterval = -1;
            this.frameTimelines = INVALID_FRAME_TIMELINES;
            this.preferredFrameTimelineIndex = 0;
        }

        public FrameTimeline preferredFrameTimeline() {
            return frameTimelines[preferredFrameTimelineIndex];
        }
    }

    /**
     * Called when a vertical sync pulse is received.
     * The recipient should render a frame and then call {@link #scheduleVsync}
     * to schedule the next vertical sync pulse.
     *
     * @param timestampNanos The timestamp of the pulse, in the {@link System#nanoTime()}
     * timebase.
     * @param physicalDisplayId Stable display ID that uniquely describes a (display, port) pair.
     * @param frame The frame number.  Increases by one for each vertical sync interval.
     * @param vsyncEventData The vsync event data.
     */
    public void onVsync(long timestampNanos, long physicalDisplayId, int frame,
            VsyncEventData vsyncEventData) {
    }

    /**
     * Called when a display hotplug event is received.
     *
     * @param timestampNanos The timestamp of the event, in the {@link System#nanoTime()}
     * timebase.
     * @param physicalDisplayId Stable display ID that uniquely describes a (display, port) pair.
     * @param connected True if the display is connected, false if it disconnected.
     */
    @UnsupportedAppUsage
    public void onHotplug(long timestampNanos, long physicalDisplayId, boolean connected) {
    }

    /**
     * Called when a display mode changed event is received.
     *
     * @param timestampNanos The timestamp of the event, in the {@link System#nanoTime()}
     * timebase.
     * @param physicalDisplayId Stable display ID that uniquely describes a (display, port) pair.
     * @param modeId The new mode Id
     */
    public void onModeChanged(long timestampNanos, long physicalDisplayId, int modeId) {
    }

    /**
     * Represents a mapping between a UID and an override frame rate
     */
    public static class FrameRateOverride {
        // The application uid
        public final int uid;

        // The frame rate that this application runs at
        public final float frameRateHz;


        @VisibleForTesting
        public FrameRateOverride(int uid, float frameRateHz) {
            this.uid = uid;
            this.frameRateHz = frameRateHz;
        }

        @Override
        public String toString() {
            return "{uid=" + uid + " frameRateHz=" + frameRateHz + "}";
        }
    }

    /**
     * Called when frame rate override event is received.
     *
     * @param timestampNanos The timestamp of the event, in the {@link System#nanoTime()}
     * timebase.
     * @param physicalDisplayId Stable display ID that uniquely describes a (display, port) pair.
     * @param overrides The mappings from uid to frame rates
     */
    public void onFrameRateOverridesChanged(long timestampNanos, long physicalDisplayId,
            FrameRateOverride[] overrides) {
    }

    /**
     * Schedules a single vertical sync pulse to be delivered when the next
     * display frame begins.
     */
    @UnsupportedAppUsage
    public void scheduleVsync() {
        if (mReceiverPtr == 0) {
            Log.w(TAG, "Attempted to schedule a vertical sync pulse but the display event "
                    + "receiver has already been disposed.");
        } else {
            nativeScheduleVsync(mReceiverPtr);
        }
    }

    /**
     * Gets the latest vsync event data from surface flinger.
     */
    VsyncEventData getLatestVsyncEventData() {
        return nativeGetLatestVsyncEventData(mReceiverPtr);
    }

    // Called from native code.
    @SuppressWarnings("unused")
    private void dispatchVsync(long timestampNanos, long physicalDisplayId, int frame,
            VsyncEventData vsyncEventData) {
        onVsync(timestampNanos, physicalDisplayId, frame, vsyncEventData);
    }

    // Called from native code.
    @SuppressWarnings("unused")
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void dispatchHotplug(long timestampNanos, long physicalDisplayId, boolean connected) {
        onHotplug(timestampNanos, physicalDisplayId, connected);
    }

    // Called from native code.
    @SuppressWarnings("unused")
    private void dispatchModeChanged(long timestampNanos, long physicalDisplayId, int modeId) {
        onModeChanged(timestampNanos, physicalDisplayId, modeId);
    }

    // Called from native code.
    @SuppressWarnings("unused")
    private void dispatchFrameRateOverrides(long timestampNanos, long physicalDisplayId,
            FrameRateOverride[] overrides) {
        onFrameRateOverridesChanged(timestampNanos, physicalDisplayId, overrides);
    }

}
