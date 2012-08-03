/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.IDisplayManager;
import android.os.Binder;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Manages the properties, media routing and power state of attached displays.
 * <p>
 * The display manager service does not own or directly control the displays.
 * Instead, other components in the system register their display adapters with the
 * display manager service which acts as a central controller.
 * </p>
 */
public final class DisplayManagerService extends IDisplayManager.Stub {
    private static final String TAG = "DisplayManagerService";

    private static final String SYSTEM_HEADLESS = "ro.config.headless";

    private final Object mLock = new Object();

    private Context mContext;
    private final boolean mHeadless;

    private int mDisplayIdSeq = Display.DEFAULT_DISPLAY;

    /** All registered DisplayAdapters. */
    private final ArrayList<DisplayAdapter> mDisplayAdapters = new ArrayList<DisplayAdapter>();

    /** All the DisplayAdapters showing the given displayId. */
    private final SparseArray<ArrayList<DisplayAdapter>> mLogicalToPhysicals =
            new SparseArray<ArrayList<DisplayAdapter>>();

    /** All the DisplayInfos in the system indexed by deviceId */
    private final SparseArray<DisplayInfo> mDisplayInfos = new SparseArray<DisplayInfo>();

    private final ArrayList<DisplayCallback> mCallbacks =
            new ArrayList<DisplayManagerService.DisplayCallback>();

    public DisplayManagerService() {
        mHeadless = SystemProperties.get(SYSTEM_HEADLESS).equals("1");
        registerDefaultDisplayAdapter();
    }

    private void registerDefaultDisplayAdapter() {
        if (mHeadless) {
            registerDisplayAdapter(new HeadlessDisplayAdapter());
        } else {
            registerDisplayAdapter(new SurfaceFlingerDisplayAdapter());
        }
    }

    public void setContext(Context context) {
        mContext = context;
    }

    // FIXME: this isn't the right API for the long term
    public void getDefaultExternalDisplayDeviceInfo(DisplayDeviceInfo info) {
        // hardcoded assuming 720p touch screen plugged into HDMI and USB
        // need to redesign this
        info.width = 1280;
        info.height = 720;
    }

    public boolean isHeadless() {
        return mHeadless;
    }

    /**
     * Save away new DisplayInfo data.
     * @param displayId The local DisplayInfo to store the new data in.
     * @param info The new data to be stored.
     */
    public void setDisplayInfo(int displayId, DisplayInfo info) {
        synchronized (mLock) {
            DisplayInfo localInfo = mDisplayInfos.get(displayId);
            if (localInfo == null) {
                localInfo = new DisplayInfo();
                mDisplayInfos.put(displayId, localInfo);
            }
            localInfo.copyFrom(info);
        }
    }

    /**
     * Return requested DisplayInfo.
     * @param displayId The data to retrieve.
     * @param outInfo The structure to receive the data.
     */
    @Override // Binder call
    public boolean getDisplayInfo(int displayId, DisplayInfo outInfo) {
        synchronized (mLock) {
            DisplayInfo localInfo = mDisplayInfos.get(displayId);
            if (localInfo == null) {
                return false;
            }
            outInfo.copyFrom(localInfo);
            return true;
        }
    }

    /**
     * Inform the service of a new physical display. A new logical displayId is created and the new
     * physical display is immediately bound to it. Use removeAdapterFromDisplay to disconnect it.
     *
     * @param adapter The wrapper for information associated with the physical display.
     */
    public void registerDisplayAdapter(DisplayAdapter adapter) {

        int displayId;
        DisplayCallback[] callbacks;

        synchronized (mLock) {
            displayId = mDisplayIdSeq;
            do {
                // Find the next unused displayId. (Pretend like it might ever wrap around).
                mDisplayIdSeq++;
                if (mDisplayIdSeq < 0) {
                    mDisplayIdSeq = Display.DEFAULT_DISPLAY + 1;
                }
            } while (mDisplayInfos.get(mDisplayIdSeq) != null);

            adapter.setDisplayId(displayId);

            createDisplayInfoLocked(displayId, adapter);

            ArrayList<DisplayAdapter> list = new ArrayList<DisplayAdapter>();
            list.add(adapter);
            mLogicalToPhysicals.put(displayId, list);

            mDisplayAdapters.add(adapter);
            callbacks = mCallbacks.toArray(new DisplayCallback[mCallbacks.size()]);
        }

        for (int i = callbacks.length - 1; i >= 0; i--) {
            callbacks[i].displayAdded(displayId);
        }

        // TODO: Notify SurfaceFlinger of new addition.
    }

    /**
     * Connect a logical display to a physical display. Will remove the physical display from any
     * logical display it is currently attached to.
     *
     * @param displayId The logical display. Will be created if it does not already exist.
     * @param adapter The physical display.
     */
    public void addAdapterToDisplay(int displayId, DisplayAdapter adapter) {
        if (adapter == null) {
            // TODO: Or throw NPE?
            Slog.e(TAG, "addDeviceToDisplay: Attempt to add null adapter");
            return;
        }

        synchronized (mLock) {
            if (!mDisplayAdapters.contains(adapter)) {
                // TOOD: Handle unregistered adapter with exception or return value.
                Slog.e(TAG, "addDeviceToDisplay: Attempt to add an unregistered adapter");
                return;
            }

            DisplayInfo displayInfo = mDisplayInfos.get(displayId);
            if (displayInfo == null) {
                createDisplayInfoLocked(displayId, adapter);
            }

            Integer oldDisplayId = adapter.getDisplayId();
            if (oldDisplayId != Display.NO_DISPLAY) {
                if (oldDisplayId == displayId) {
                    // adapter already added to displayId.
                    return;
                }

                removeAdapterLocked(adapter);
            }

            ArrayList<DisplayAdapter> list = mLogicalToPhysicals.get(displayId);
            if (list == null) {
                list = new ArrayList<DisplayAdapter>();
                mLogicalToPhysicals.put(displayId, list);
            }
            list.add(adapter);
            adapter.setDisplayId(displayId);
        }

        // TODO: Notify SurfaceFlinger of new addition.
    }

    /**
     * Disconnect the physical display from whichever logical display it is attached to.
     * @param adapter The physical display to detach.
     */
    public void removeAdapterFromDisplay(DisplayAdapter adapter) {
        if (adapter == null) {
            // TODO: Or throw NPE?
            return;
        }

        synchronized (mLock) {
            if (!mDisplayAdapters.contains(adapter)) {
                // TOOD: Handle unregistered adapter with exception or return value.
                Slog.e(TAG, "removeDeviceFromDisplay: Attempt to remove an unregistered adapter");
                return;
            }

            removeAdapterLocked(adapter);
        }

        // TODO: Notify SurfaceFlinger of removal.
    }

    public void registerDisplayCallback(final DisplayCallback callback) {
        synchronized (mLock) {
            if (!mCallbacks.contains(callback)) {
                mCallbacks.add(callback);
            }
        }
    }

    public void unregisterDisplayCallback(final DisplayCallback callback) {
        synchronized (mLock) {
            mCallbacks.remove(callback);
        }
    }

    /**
     * Create a new logical DisplayInfo and fill it in with information from the physical display.
     * @param displayId The logical identifier.
     * @param adapter The physical display for initial values.
     */
    private void createDisplayInfoLocked(int displayId, DisplayAdapter adapter) {
        DisplayInfo displayInfo = new DisplayInfo();
        DisplayDeviceInfo deviceInfo = new DisplayDeviceInfo();
        adapter.getDisplayDevice().getInfo(deviceInfo);
        copyDisplayInfoFromDeviceInfo(displayInfo, deviceInfo);
        mDisplayInfos.put(displayId, displayInfo);
    }

    /**
     * Disconnect a physical display from its logical display. If there are no more physical
     * displays attached to the logical display, delete the logical display.
     * @param adapter The physical display to detach.
     */
    void removeAdapterLocked(DisplayAdapter adapter) {
        int displayId = adapter.getDisplayId();
        adapter.setDisplayId(Display.NO_DISPLAY);

        ArrayList<DisplayAdapter> list = mLogicalToPhysicals.get(displayId);
        if (list != null) {
            list.remove(adapter);
            if (list.isEmpty()) {
                mLogicalToPhysicals.remove(displayId);
                // TODO: Keep count of Windows attached to logical display and don't delete if
                // there are any outstanding. Also, what keeps the WindowManager from continuing
                // to use the logical display?
                mDisplayInfos.remove(displayId);
            }
        }
    }

    private void copyDisplayInfoFromDeviceInfo(DisplayInfo displayInfo,
                                               DisplayDeviceInfo deviceInfo) {
        // Bootstrap the logical display using the physical display.
        displayInfo.appWidth = deviceInfo.width;
        displayInfo.appHeight = deviceInfo.height;
        displayInfo.logicalWidth = deviceInfo.width;
        displayInfo.logicalHeight = deviceInfo.height;
        displayInfo.rotation = Surface.ROTATION_0;
        displayInfo.refreshRate = deviceInfo.refreshRate;
        displayInfo.logicalDensityDpi = deviceInfo.densityDpi;
        displayInfo.physicalXDpi = deviceInfo.xDpi;
        displayInfo.physicalYDpi = deviceInfo.yDpi;
        displayInfo.smallestNominalAppWidth = deviceInfo.width;
        displayInfo.smallestNominalAppHeight = deviceInfo.height;
        displayInfo.largestNominalAppWidth = deviceInfo.width;
        displayInfo.largestNominalAppHeight = deviceInfo.height;
    }

    @Override // Binder call
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext == null
                || mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                        != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump DisplayManager from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }

        pw.println("DISPLAY MANAGER (dumpsys display)\n");

        pw.println("Headless: " + mHeadless);

        DisplayDeviceInfo info = new DisplayDeviceInfo();
        for (DisplayAdapter adapter : mDisplayAdapters) {
            pw.println("Display for adapter " + adapter.getName()
                + " assigned to Display " + adapter.getDisplayId());
            DisplayDevice device = adapter.getDisplayDevice();
            pw.print("  ");
            device.getInfo(info);
            pw.println(info);
        }
    }

    public interface DisplayCallback {
        public void displayAdded(int displayId);
        public void displayRemoved(int displayId);
    }
}
