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

package com.android.server.display;

import android.annotation.NonNull;
import android.os.Trace;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.Surface;

import com.android.internal.annotations.GuardedBy;
import com.android.server.display.DisplayManagerService.SyncRoot;
import com.android.server.display.utils.DebugUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Container for all the display devices present in the system.  If an object wants to get events
 * about all the DisplayDevices without needing to listen to all of the DisplayAdapters, they can
 * listen and interact with the instance of this class.
 * <p>
 * The collection of {@link DisplayDevice}s and their usage is protected by the provided
 * {@link DisplayManagerService.SyncRoot} lock object.
 */
class DisplayDeviceRepository implements DisplayAdapter.Listener {
    private static final String TAG = "DisplayDeviceRepository";

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.DisplayDeviceRepository DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    public static final int DISPLAY_DEVICE_EVENT_ADDED = 1;
    public static final int DISPLAY_DEVICE_EVENT_REMOVED = 3;

    /**
     * List of all currently connected display devices. Indexed by the displayId.
     * TODO: multi-display - break the notion that this is indexed by displayId.
     */
    @GuardedBy("mSyncRoot")
    private final List<DisplayDevice> mDisplayDevices = new ArrayList<>();

    /** Listeners for {link DisplayDevice} events. */
    @GuardedBy("mSyncRoot")
    private final List<Listener> mListeners = new ArrayList<>();

    /** Global lock object from {@link DisplayManagerService}. */
    private final SyncRoot mSyncRoot;

    private final PersistentDataStore mPersistentDataStore;

    /**
     * @param syncRoot The global lock for DisplayManager related objects.
     * @param persistentDataStore Settings data store from {@link DisplayManagerService}.
     */
    DisplayDeviceRepository(@NonNull SyncRoot syncRoot,
            @NonNull PersistentDataStore persistentDataStore) {
        mSyncRoot = syncRoot;
        mPersistentDataStore = persistentDataStore;
    }

    public void addListener(@NonNull Listener listener) {
        mListeners.add(listener);
    }

    @Override
    public void onDisplayDeviceEvent(DisplayDevice device, int event) {
        String tag = null;
        if (DEBUG) {
            tag = "DisplayDeviceRepository#onDisplayDeviceEvent (event=" + event + ")";
            Trace.beginAsyncSection(tag, 0);
        }
        switch (event) {
            case DisplayAdapter.DISPLAY_DEVICE_EVENT_ADDED:
                handleDisplayDeviceAdded(device);
                break;

            case DisplayAdapter.DISPLAY_DEVICE_EVENT_CHANGED:
                handleDisplayDeviceChanged(device);
                break;

            case DisplayAdapter.DISPLAY_DEVICE_EVENT_REMOVED:
                handleDisplayDeviceRemoved(device);
                break;
        }
        if (DEBUG) {
            Trace.endAsyncSection(tag, 0);
        }
    }

    @Override
    public void onTraversalRequested() {
        final int size = mListeners.size();
        for (int i = 0; i < size; i++) {
            mListeners.get(i).onTraversalRequested();
        }
    }

    public boolean containsLocked(DisplayDevice d) {
        return mDisplayDevices.contains(d);
    }

    public int sizeLocked() {
        return mDisplayDevices.size();
    }

    public void forEachLocked(Consumer<DisplayDevice> consumer) {
        final int count = mDisplayDevices.size();
        for (int i = 0; i < count; i++) {
            consumer.accept(mDisplayDevices.get(i));
        }
    }

    public DisplayDevice getByAddressLocked(@NonNull DisplayAddress address) {
        for (int i = mDisplayDevices.size() - 1; i >= 0; i--) {
            final DisplayDevice device = mDisplayDevices.get(i);
            final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
            if (address.equals(info.address)
                    || DisplayAddress.Physical.isPortMatch(address, info.address)) {
                return device;
            }
        }
        return null;
    }

    // String uniqueId -> DisplayDevice object with that given uniqueId
    public DisplayDevice getByUniqueIdLocked(@NonNull String uniqueId) {
        for (int i = mDisplayDevices.size() - 1; i >= 0; i--) {
            final DisplayDevice displayDevice = mDisplayDevices.get(i);
            if (displayDevice.getUniqueId().equals(uniqueId)) {
                return displayDevice;
            }
        }
        return null;
    }

    private void handleDisplayDeviceAdded(DisplayDevice device) {
        synchronized (mSyncRoot) {
            DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
            if (mDisplayDevices.contains(device)) {
                Slog.w(TAG, "Attempted to add already added display device: " + info);
                return;
            }
            Slog.i(TAG, "Display device added: " + info);
            device.mDebugLastLoggedDeviceInfo = info;

            mDisplayDevices.add(device);
            sendEventLocked(device, DISPLAY_DEVICE_EVENT_ADDED);
        }
    }

    private void handleDisplayDeviceChanged(DisplayDevice device) {
        synchronized (mSyncRoot) {
            final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
            if (!mDisplayDevices.contains(device)) {
                Slog.w(TAG, "Attempted to change non-existent display device: " + info);
                return;
            }
            if (DEBUG) {
                Trace.traceBegin(Trace.TRACE_TAG_POWER,
                        "handleDisplayDeviceChanged");
            }
            int diff = device.mDebugLastLoggedDeviceInfo.diff(info);
            if (diff == DisplayDeviceInfo.DIFF_STATE) {
                Slog.i(TAG, "Display device changed state: \"" + info.name
                        + "\", " + Display.stateToString(info.state));
            } else if (diff == DisplayDeviceInfo.DIFF_ROTATION) {
                Slog.i(TAG, "Display device rotated: \"" + info.name
                        + "\", " + Surface.rotationToString(info.rotation));
            } else if (diff
                    == (DisplayDeviceInfo.DIFF_MODE_ID | DisplayDeviceInfo.DIFF_RENDER_TIMINGS)) {
                Slog.i(TAG, "Display device changed render timings: \"" + info.name
                        + "\", renderFrameRate=" + info.renderFrameRate
                        + ", presentationDeadlineNanos=" + info.presentationDeadlineNanos
                        + ", appVsyncOffsetNanos=" + info.appVsyncOffsetNanos);
            } else if (diff == DisplayDeviceInfo.DIFF_COMMITTED_STATE) {
                if (DEBUG) {
                    Slog.i(TAG, "Display device changed committed state: \"" + info.name
                            + "\", " + Display.stateToString(info.committedState));
                }
            } else if (diff != DisplayDeviceInfo.DIFF_HDR_SDR_RATIO) {
                Slog.i(TAG, "Display device changed: " + info);
            }

            if ((diff & DisplayDeviceInfo.DIFF_COLOR_MODE) != 0) {
                try {
                    mPersistentDataStore.setColorMode(device, info.colorMode);
                } finally {
                    mPersistentDataStore.saveIfNeeded();
                }
            }
            device.mDebugLastLoggedDeviceInfo = info;

            device.applyPendingDisplayDeviceInfoChangesLocked();
            sendChangedEventLocked(device, diff);
            if (DEBUG) {
                Trace.traceEnd(Trace.TRACE_TAG_POWER);
            }
        }
    }

    private void handleDisplayDeviceRemoved(DisplayDevice device) {
        synchronized (mSyncRoot) {
            DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
            if (!mDisplayDevices.remove(device)) {
                Slog.w(TAG, "Attempted to remove non-existent display device: " + info);
                return;
            }

            Slog.i(TAG, "Display device removed: " + info);
            device.mDebugLastLoggedDeviceInfo = info;
            sendEventLocked(device, DISPLAY_DEVICE_EVENT_REMOVED);
        }
    }

    private void sendEventLocked(DisplayDevice device, int event) {
        final int size = mListeners.size();
        for (int i = 0; i < size; i++) {
            mListeners.get(i).onDisplayDeviceEventLocked(device, event);
        }
    }

    @GuardedBy("mSyncRoot")
    private void sendChangedEventLocked(DisplayDevice device, int diff) {
        final int size = mListeners.size();
        for (int i = 0; i < size; i++) {
            mListeners.get(i).onDisplayDeviceChangedLocked(device, diff);
        }
    }

    /**
     * Listens to {@link DisplayDevice} events from {@link DisplayDeviceRepository}.
     */
    public interface Listener {
        void onDisplayDeviceEventLocked(DisplayDevice device, int event);

        void onDisplayDeviceChangedLocked(DisplayDevice device, int diff);

        // TODO: multi-display - Try to remove the need for requestTraversal...it feels like
        // a shoe-horned method for a shoe-horned feature.
        void onTraversalRequested();
    };
}
