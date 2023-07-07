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

import libcore.util.NativeAllocationRegistry;

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
     * Keep in sync with frameworks/native/libs/gui/aidl/android/gui/ISurfaceComposer.aidl
     */
    public static final int VSYNC_SOURCE_APP = 0;

    /**
     * When retrieving vsync events, this specifies that the vsync event should happen whenever
     * Surface Flinger is processing a frame.
     * <p>
     * Keep in sync with frameworks/native/libs/gui/aidl/android/gui/ISurfaceComposer.aidl
     */
    public static final int VSYNC_SOURCE_SURFACE_FLINGER = 1;

    /**
     * Specifies to generate mode changed events from Surface Flinger.
     * <p>
     * Keep in sync with frameworks/native/libs/gui/aidl/android/gui/ISurfaceComposer.aidl
     */
    public static final int EVENT_REGISTRATION_MODE_CHANGED_FLAG = 0x1;

    /**
     * Specifies to generate frame rate override events from Surface Flinger.
     * <p>
     * Keep in sync with frameworks/native/libs/gui/aidl/android/gui/ISurfaceComposer.aidl
     */
    public static final int EVENT_REGISTRATION_FRAME_RATE_OVERRIDE_FLAG = 0x2;

    private static final String TAG = "DisplayEventReceiver";

    @UnsupportedAppUsage
    private long mReceiverPtr;

    // We keep a reference message queue object here so that it is not
    // GC'd while the native peer of the receiver is using them.
    private MessageQueue mMessageQueue;

    private final VsyncEventData mVsyncEventData = new VsyncEventData();

    private static native long nativeInit(WeakReference<DisplayEventReceiver> receiver,
            WeakReference<VsyncEventData> vsyncEventData,
            MessageQueue messageQueue, int vsyncSource, int eventRegistration, long layerHandle);
    private static native long nativeGetDisplayEventReceiverFinalizer();
    @FastNative
    private static native void nativeScheduleVsync(long receiverPtr);
    private static native VsyncEventData nativeGetLatestVsyncEventData(long receiverPtr);

    private static final NativeAllocationRegistry sNativeAllocationRegistry =
            NativeAllocationRegistry.createMalloced(
                    DisplayEventReceiver.class.getClassLoader(),
                    nativeGetDisplayEventReceiverFinalizer());
    private Runnable mFreeNativeResources;

    /**
     * Creates a display event receiver.
     *
     * @param looper The looper to use when invoking callbacks.
     */
    @UnsupportedAppUsage
    public DisplayEventReceiver(Looper looper) {
        this(looper, VSYNC_SOURCE_APP, /* eventRegistration */ 0, /* layerHandle */ 0L);
    }

    public DisplayEventReceiver(Looper looper, int vsyncSource, int eventRegistration) {
        this(looper, vsyncSource, eventRegistration, /* layerHandle */ 0L);
    }

    /**
     * Creates a display event receiver.
     *
     * @param looper The looper to use when invoking callbacks.
     * @param vsyncSource The source of the vsync tick. Must be on of the VSYNC_SOURCE_* values.
     * @param eventRegistration Which events to dispatch. Must be a bitfield consist of the
     * EVENT_REGISTRATION_*_FLAG values.
     * @param layerHandle Layer to which the current instance is attached to
     */
    public DisplayEventReceiver(Looper looper, int vsyncSource, int eventRegistration,
            long layerHandle) {
        if (looper == null) {
            throw new IllegalArgumentException("looper must not be null");
        }

        mMessageQueue = looper.getQueue();
        mReceiverPtr = nativeInit(new WeakReference<DisplayEventReceiver>(this),
                new WeakReference<VsyncEventData>(mVsyncEventData),
                mMessageQueue,
                vsyncSource, eventRegistration, layerHandle);
        mFreeNativeResources = sNativeAllocationRegistry.registerNativeAllocation(this,
                mReceiverPtr);
    }

    /**
     * Disposes the receiver.
     */
    public void dispose() {
        if (mReceiverPtr != 0) {
            mFreeNativeResources.run();
            mReceiverPtr = 0;
        }
        mMessageQueue = null;
    }

    /**
     * Class to capture all inputs required for syncing events data.
     *
     * @hide
     */
    public static final class VsyncEventData {
        // The max capacity of frame timeline choices.
        // Must be in sync with VsyncEventData::kFrameTimelinesCapacity in
        // frameworks/native/libs/gui/include/gui/VsyncEventData.h
        static final int FRAME_TIMELINES_CAPACITY = 7;

        public static class FrameTimeline {
            FrameTimeline() {}

            // Called from native code.
            @SuppressWarnings("unused")
            FrameTimeline(long vsyncId, long expectedPresentationTime, long deadline) {
                this.vsyncId = vsyncId;
                this.expectedPresentationTime = expectedPresentationTime;
                this.deadline = deadline;
            }

            void copyFrom(FrameTimeline other) {
                vsyncId = other.vsyncId;
                expectedPresentationTime = other.expectedPresentationTime;
                deadline = other.deadline;
            }

            // The frame timeline vsync id, used to correlate a frame
            // produced by HWUI with the timeline data stored in Surface Flinger.
            public long vsyncId = FrameInfo.INVALID_VSYNC_ID;

            // The frame timestamp for when the frame is expected to be presented.
            public long expectedPresentationTime = Long.MAX_VALUE;

            // The frame deadline timestamp in {@link System#nanoTime()} timebase that it is
            // allotted for the frame to be completed.
            public long deadline = Long.MAX_VALUE;
        }

        /**
         * The current interval between frames in ns. This will be used to align
         * {@link FrameInfo#VSYNC} to the current vsync in case Choreographer callback was heavily
         * delayed by the app.
         */
        public long frameInterval = -1;

        public final FrameTimeline[] frameTimelines;

        public int preferredFrameTimelineIndex = 0;

        public int frameTimelinesLength = 0;

        VsyncEventData() {
            frameTimelines = new FrameTimeline[FRAME_TIMELINES_CAPACITY];
            for (int i = 0; i < frameTimelines.length; i++) {
                frameTimelines[i] = new FrameTimeline();
            }
        }

        // Called from native code.
        @SuppressWarnings("unused")
        VsyncEventData(FrameTimeline[] frameTimelines, int preferredFrameTimelineIndex,
                int frameTimelinesLength, long frameInterval) {
            this.frameTimelines = frameTimelines;
            this.preferredFrameTimelineIndex = preferredFrameTimelineIndex;
            this.frameTimelinesLength = frameTimelinesLength;
            this.frameInterval = frameInterval;
        }

        void copyFrom(VsyncEventData other) {
            preferredFrameTimelineIndex = other.preferredFrameTimelineIndex;
            frameTimelinesLength = other.frameTimelinesLength;
            frameInterval = other.frameInterval;
            for (int i = 0; i < frameTimelines.length; i++) {
                frameTimelines[i].copyFrom(other.frameTimelines[i]);
            }
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
     * @param renderPeriod The render frame period, which is a multiple of the mode's vsync period
     */
    public void onModeChanged(long timestampNanos, long physicalDisplayId, int modeId,
            long renderPeriod) {
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
    private void dispatchVsync(long timestampNanos, long physicalDisplayId, int frame) {
        onVsync(timestampNanos, physicalDisplayId, frame, mVsyncEventData);
    }

    // Called from native code.
    @SuppressWarnings("unused")
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void dispatchHotplug(long timestampNanos, long physicalDisplayId, boolean connected) {
        onHotplug(timestampNanos, physicalDisplayId, connected);
    }

    // Called from native code.
    @SuppressWarnings("unused")
    private void dispatchModeChanged(long timestampNanos, long physicalDisplayId, int modeId,
            long renderPeriod) {
        onModeChanged(timestampNanos, physicalDisplayId, modeId, renderPeriod);
    }

    // Called from native code.
    @SuppressWarnings("unused")
    private void dispatchFrameRateOverrides(long timestampNanos, long physicalDisplayId,
            FrameRateOverride[] overrides) {
        onFrameRateOverridesChanged(timestampNanos, physicalDisplayId, overrides);
    }

}
