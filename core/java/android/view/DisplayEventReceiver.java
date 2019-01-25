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

import android.annotation.UnsupportedAppUsage;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;

import dalvik.annotation.optimization.FastNative;
import dalvik.system.CloseGuard;

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

    private static final String TAG = "DisplayEventReceiver";

    private final CloseGuard mCloseGuard = CloseGuard.get();

    @UnsupportedAppUsage
    private long mReceiverPtr;

    // We keep a reference message queue object here so that it is not
    // GC'd while the native peer of the receiver is using them.
    private MessageQueue mMessageQueue;

    private static native long nativeInit(WeakReference<DisplayEventReceiver> receiver,
            MessageQueue messageQueue, int vsyncSource);
    private static native void nativeDispose(long receiverPtr);
    @FastNative
    private static native void nativeScheduleVsync(long receiverPtr);

    /**
     * Creates a display event receiver.
     *
     * @param looper The looper to use when invoking callbacks.
     */
    @UnsupportedAppUsage
    public DisplayEventReceiver(Looper looper) {
        this(looper, VSYNC_SOURCE_APP);
    }

    /**
     * Creates a display event receiver.
     *
     * @param looper The looper to use when invoking callbacks.
     * @param vsyncSource The source of the vsync tick. Must be on of the VSYNC_SOURCE_* values.
     */
    public DisplayEventReceiver(Looper looper, int vsyncSource) {
        if (looper == null) {
            throw new IllegalArgumentException("looper must not be null");
        }

        mMessageQueue = looper.getQueue();
        mReceiverPtr = nativeInit(new WeakReference<DisplayEventReceiver>(this), mMessageQueue,
                vsyncSource);

        mCloseGuard.open("dispose");
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
        if (mCloseGuard != null) {
            if (finalized) {
                mCloseGuard.warnIfOpen();
            }
            mCloseGuard.close();
        }

        if (mReceiverPtr != 0) {
            nativeDispose(mReceiverPtr);
            mReceiverPtr = 0;
        }
        mMessageQueue = null;
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
     */
    @UnsupportedAppUsage
    public void onVsync(long timestampNanos, long physicalDisplayId, int frame) {
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

    // Called from native code.
    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    private void dispatchVsync(long timestampNanos, long physicalDisplayId, int frame) {
        onVsync(timestampNanos, physicalDisplayId, frame);
    }

    // Called from native code.
    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    private void dispatchHotplug(long timestampNanos, long physicalDisplayId, boolean connected) {
        onHotplug(timestampNanos, physicalDisplayId, connected);
    }
}
