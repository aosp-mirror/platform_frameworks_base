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
import android.hardware.display.DisplayManager;
import android.hardware.display.IDisplayManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemProperties;
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

    private final Context mContext;
    private final boolean mHeadless;

    private final ArrayList<DisplayAdapter> mDisplayAdapters = new ArrayList<DisplayAdapter>();
    private final DisplayInfo mDefaultDisplayInfo = new DisplayInfo();
    private DisplayDevice mDefaultDisplayDevice;

    public DisplayManagerService(Context context) {
        mContext = context;
        mHeadless = SystemProperties.get(SYSTEM_HEADLESS).equals("1");

        registerDefaultDisplayAdapter();
    }

    private void registerDefaultDisplayAdapter() {
        if (mHeadless) {
            registerDisplayAdapter(new HeadlessDisplayAdapter(mContext));
        } else {
            registerDisplayAdapter(new LocalDisplayAdapter(mContext));
        }
    }

    // FIXME: this isn't the right API for the long term
    public void getDefaultExternalDisplayDeviceInfo(DisplayDeviceInfo info) {
        // hardcoded assuming 720p touch screen plugged into HDMI and USB
        // need to redesign this
        info.width = 1280;
        info.height = 720;
    }

    /**
     * Returns true if the device is headless.
     *
     * @return True if the device is headless.
     */
    public boolean isHeadless() {
        return mHeadless;
    }

    /**
     * Set the new display orientation.
     * @param displayId The logical display id.
     * @param orientation One of the Surface.ROTATION_* constants.
     */
    public void setDisplayOrientation(int displayId, int orientation) {
        synchronized (mLock) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                throw new UnsupportedOperationException();
            }

            IBinder displayToken = mDefaultDisplayDevice.getDisplayToken();
            if (displayToken != null) {
                Surface.openTransaction();
                try {
                    Surface.setDisplayOrientation(displayToken, orientation);
                } finally {
                    Surface.closeTransaction();
                }
            }
        }
    }

    /**
     * Save away new DisplayInfo data.
     * @param displayId The logical display id.
     * @param info The new data to be stored.
     */
    public void setDisplayInfo(int displayId, DisplayInfo info) {
        synchronized (mLock) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                throw new UnsupportedOperationException();
            }
            mDefaultDisplayInfo.copyFrom(info);
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
            if (displayId != Display.DEFAULT_DISPLAY) {
                return false;
            }
            outInfo.copyFrom(mDefaultDisplayInfo);
            return true;
        }
    }

    private void registerDisplayAdapter(DisplayAdapter adapter) {
        mDisplayAdapters.add(adapter);
        adapter.register(new DisplayAdapter.Listener() {
            @Override
            public void onDisplayDeviceAdded(DisplayDevice device) {
                mDefaultDisplayDevice = device;
                DisplayDeviceInfo deviceInfo = new DisplayDeviceInfo();
                device.getInfo(deviceInfo);
                copyDisplayInfoFromDeviceInfo(mDefaultDisplayInfo, deviceInfo);
            }

            @Override
            public void onDisplayDeviceRemoved(DisplayDevice device) {
            }
        });
    }

    private void copyDisplayInfoFromDeviceInfo(
            DisplayInfo displayInfo, DisplayDeviceInfo deviceInfo) {
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

        synchronized (mLock) {
            for (DisplayAdapter adapter : mDisplayAdapters) {
                pw.println("Adapter: " + adapter.getName());
            }

            pw.println("Default display info: " + mDefaultDisplayInfo);
        }

        pw.println("Default display: "
                + DisplayManager.getInstance().getRealDisplay(Display.DEFAULT_DISPLAY));
    }
}
