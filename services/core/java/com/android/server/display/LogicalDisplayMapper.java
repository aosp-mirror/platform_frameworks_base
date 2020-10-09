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

import android.os.SystemProperties;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Responsible for creating {@link LogicalDisplay}s and associating them to the
 * {@link DisplayDevice} objects supplied through {@link DisplayAdapter.Listener}.
 *
 * For devices with a single internal display, the mapping is done once and left
 * alone. For devices with multiple built-in displays, such as foldable devices,
 * {@link LogicalDisplay}s can be remapped to different {@link DisplayDevice}s.
 */
class LogicalDisplayMapper implements DisplayDeviceRepository.Listener {
    private static final String TAG = "LogicalDisplayMapper";

    public static final int LOGICAL_DISPLAY_EVENT_ADDED = 1;
    public static final int LOGICAL_DISPLAY_EVENT_CHANGED = 2;
    public static final int LOGICAL_DISPLAY_EVENT_REMOVED = 3;

    /**
     * Temporary display info, used for comparing display configurations.
     */
    private final DisplayInfo mTempDisplayInfo = new DisplayInfo();
    private final DisplayInfo mTempNonOverrideDisplayInfo = new DisplayInfo();

    /**
     * True if the display mapper service should pretend there is only one display
     * and only tell applications about the existence of the default logical display.
     * The display manager can still mirror content to secondary displays but applications
     * cannot present unique content on those displays.
     * Used for demonstration purposes only.
     */
    private final boolean mSingleDisplayDemoMode;

    /**
     * List of all logical displays indexed by logical display id.
     * Any modification to mLogicalDisplays must invalidate the DisplayManagerGlobal cache.
     * TODO: multi-display - Move the aforementioned comment?
     */
    private final SparseArray<LogicalDisplay> mLogicalDisplays =
            new SparseArray<LogicalDisplay>();
    private int mNextNonDefaultDisplayId = Display.DEFAULT_DISPLAY + 1;

    private final DisplayDeviceRepository mDisplayDeviceRepo;
    private final PersistentDataStore mPersistentDataStore;
    private final Listener mListener;

    LogicalDisplayMapper(DisplayDeviceRepository repo, Listener listener,
            PersistentDataStore persistentDataStore) {
        mDisplayDeviceRepo = repo;
        mPersistentDataStore = persistentDataStore;
        mListener = listener;
        mSingleDisplayDemoMode = SystemProperties.getBoolean("persist.demo.singledisplay", false);
        mDisplayDeviceRepo.addListener(this);
    }

    @Override
    public void onDisplayDeviceEventLocked(DisplayDevice device, int event) {
        switch (event) {
            case DisplayDeviceRepository.DISPLAY_DEVICE_EVENT_ADDED:
                handleDisplayDeviceAddedLocked(device);
                break;

            case DisplayDeviceRepository.DISPLAY_DEVICE_EVENT_CHANGED:
                updateLogicalDisplaysLocked();
                break;

            case DisplayDeviceRepository.DISPLAY_DEVICE_EVENT_REMOVED:
                updateLogicalDisplaysLocked();
                break;
        }
    }

    @Override
    public void onTraversalRequested() {
        mListener.onTraversalRequested();
    }

    public LogicalDisplay getLocked(int displayId) {
        return mLogicalDisplays.get(displayId);
    }

    public LogicalDisplay getLocked(DisplayDevice device) {
        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (display.getPrimaryDisplayDeviceLocked() == device) {
                return display;
            }
        }
        return null;
    }

    public int[] getDisplayIdsLocked(int callingUid) {
        final int count = mLogicalDisplays.size();
        int[] displayIds = new int[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            DisplayInfo info = display.getDisplayInfoLocked();
            if (info.hasAccess(callingUid)) {
                displayIds[n++] = mLogicalDisplays.keyAt(i);
            }
        }
        if (n != count) {
            displayIds = Arrays.copyOfRange(displayIds, 0, n);
        }
        return displayIds;
    }

    public void forEachLocked(Consumer<LogicalDisplay> consumer) {
        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            consumer.accept(mLogicalDisplays.valueAt(i));
        }
    }

    public void dumpLocked(PrintWriter pw) {
        pw.println("LogicalDisplayMapper:");
        pw.println("  mSingleDisplayDemoMode=" + mSingleDisplayDemoMode);
        pw.println("  mNextNonDefaultDisplayId=" + mNextNonDefaultDisplayId);

        final int logicalDisplayCount = mLogicalDisplays.size();
        pw.println();
        pw.println("  Logical Displays: size=" + logicalDisplayCount);

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "   ");
        ipw.increaseIndent();

        for (int i = 0; i < logicalDisplayCount; i++) {
            int displayId = mLogicalDisplays.keyAt(i);
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            pw.println("   Display " + displayId + ":");
            display.dumpLocked(ipw);
        }
    }

    private void handleDisplayDeviceAddedLocked(DisplayDevice device) {
        DisplayDeviceInfo deviceInfo = device.getDisplayDeviceInfoLocked();
        boolean isDefault = (deviceInfo.flags & DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY) != 0;
        if (isDefault && mLogicalDisplays.get(Display.DEFAULT_DISPLAY) != null) {
            Slog.w(TAG, "Ignoring attempt to add a second default display: " + deviceInfo);
            isDefault = false;
        }

        if (!isDefault && mSingleDisplayDemoMode) {
            Slog.i(TAG, "Not creating a logical display for a secondary display "
                    + " because single display demo mode is enabled: " + deviceInfo);
            return;
        }

        final int displayId = assignDisplayIdLocked(isDefault);
        final int layerStack = assignLayerStackLocked(displayId);

        LogicalDisplay display = new LogicalDisplay(displayId, layerStack, device);
        display.updateLocked(mDisplayDeviceRepo);
        if (!display.isValidLocked()) {
            // This should never happen currently.
            Slog.w(TAG, "Ignoring display device because the logical display "
                    + "created from it was not considered valid: " + deviceInfo);
            return;
        }

        mLogicalDisplays.put(displayId, display);

        mListener.onLogicalDisplayEventLocked(display,
                LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_ADDED);
    }

    /**
     * Updates all existing logical displays given the current set of display devices.
     * Removes invalid logical displays. Sends notifications if needed.
     */
    private void updateLogicalDisplaysLocked() {
        for (int i = mLogicalDisplays.size() - 1; i >= 0; i--) {
            final int displayId = mLogicalDisplays.keyAt(i);
            LogicalDisplay display = mLogicalDisplays.valueAt(i);

            mTempDisplayInfo.copyFrom(display.getDisplayInfoLocked());
            display.getNonOverrideDisplayInfoLocked(mTempNonOverrideDisplayInfo);
            display.updateLocked(mDisplayDeviceRepo);
            if (!display.isValidLocked()) {
                mLogicalDisplays.removeAt(i);

                mListener.onLogicalDisplayEventLocked(display,
                        LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_REMOVED);
            } else if (!mTempDisplayInfo.equals(display.getDisplayInfoLocked())) {
                mListener.onLogicalDisplayEventLocked(display,
                        LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_CHANGED);
            } else {
                // While applications shouldn't know nor care about the non-overridden info, we
                // still need to let WindowManager know so it can update its own internal state for
                // things like display cutouts.
                display.getNonOverrideDisplayInfoLocked(mTempDisplayInfo);
                if (!mTempNonOverrideDisplayInfo.equals(mTempDisplayInfo)) {
                    mListener.onLogicalDisplayEventLocked(display,
                            LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_CHANGED);
                }
            }
        }
    }

    private int assignDisplayIdLocked(boolean isDefault) {
        return isDefault ? Display.DEFAULT_DISPLAY : mNextNonDefaultDisplayId++;
    }

    private int assignLayerStackLocked(int displayId) {
        // Currently layer stacks and display ids are the same.
        // This need not be the case.
        return displayId;
    }

    public interface Listener {
        void onLogicalDisplayEventLocked(LogicalDisplay display, int event);
        void onTraversalRequested();
    }
}
