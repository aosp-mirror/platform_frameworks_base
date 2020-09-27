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
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.display.DisplayManagerService.SyncRoot;

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

    public static final int DISPLAY_DEVICE_EVENT_ADDED = 1;
    public static final int DISPLAY_DEVICE_EVENT_CHANGED = 2;
    public static final int DISPLAY_DEVICE_EVENT_REMOVED = 3;

    /**
     * List of all currently connected display devices. Indexed by the displayId.
     * TODO: multi-display - break the notion that this is indexed by displayId.
     */
    @GuardedBy("mSyncRoot")
    private final List<DisplayDevice> mDisplayDevices = new ArrayList<>();

    /** Listener for {link DisplayDevice} events. */
    private final Listener mListener;

    /** Global lock object from {@link DisplayManagerService}. */
    private final SyncRoot mSyncRoot;

    DisplayDeviceRepository(@NonNull SyncRoot syncRoot, @NonNull Listener listener) {
        mSyncRoot = syncRoot;
        mListener = listener;
    }

    @Override
    public void onDisplayDeviceEvent(DisplayDevice device, int event) {
        switch (event) {
            case DISPLAY_DEVICE_EVENT_ADDED:
                handleDisplayDeviceAdded(device);
                break;

            case DISPLAY_DEVICE_EVENT_CHANGED:
                handleDisplayDeviceChanged(device);
                break;

            case DISPLAY_DEVICE_EVENT_REMOVED:
                handleDisplayDeviceRemoved(device);
                break;
        }
    }

    @Override
    public void onTraversalRequested() {
        mListener.onTraversalRequested();
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
            mListener.onDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_ADDED);
        }
    }

    private void handleDisplayDeviceChanged(DisplayDevice device) {
        synchronized (mSyncRoot) {
            DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
            if (!mDisplayDevices.contains(device)) {
                Slog.w(TAG, "Attempted to change non-existent display device: " + info);
                return;
            }
            mListener.onDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_CHANGED);
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
            mListener.onDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_REMOVED);
        }
    }

    /**
     * Listens to {@link DisplayDevice} events from {@link DisplayDeviceRepository}.
     */
    interface Listener {
        void onDisplayDeviceEventLocked(DisplayDevice device, int event);

        // TODO: multi-display - Try to remove the need for requestTraversal...it feels like
        // a shoe-horned method for a shoe-horned feature.
        void onTraversalRequested();
    };
}
