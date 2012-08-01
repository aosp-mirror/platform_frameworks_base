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
    private final ArrayList<DisplayAdapter> mDisplayAdapters = new ArrayList<DisplayAdapter>();

    // TODO: represent this as a map between logical and physical devices
    private DisplayInfo mDefaultDisplayInfo;
    private DisplayDevice mDefaultDisplayDevice;
    private DisplayDeviceInfo mDefaultDisplayDeviceInfo;

    public DisplayManagerService() {
        mHeadless = SystemProperties.get(SYSTEM_HEADLESS).equals("1");
        registerDisplayAdapters();
        initializeDefaultDisplay();
    }

    public void setContext(Context context) {
        mContext = context;
    }

    // FIXME: this isn't the right API for the long term
    public void setDefaultDisplayInfo(DisplayInfo info) {
        synchronized (mLock) {
            mDefaultDisplayInfo.copyFrom(info);
        }
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

    @Override // Binder call
    public boolean getDisplayInfo(int displayId, DisplayInfo outInfo) {
        synchronized (mLock) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                outInfo.copyFrom(mDefaultDisplayInfo);
                return true;
            }
            return false;
        }
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
            pw.println("Displays for adapter " + adapter.getName());
            for (DisplayDevice device : adapter.getDisplayDevices()) {
                device.getInfo(info);
                pw.print("  ");
                pw.println(info);
            }
        }
    }

    private void registerDisplayAdapters() {
        if (mHeadless) {
            registerDisplayAdapter(new HeadlessDisplayAdapter());
        } else {
            registerDisplayAdapter(new SurfaceFlingerDisplayAdapter());
        }
    }

    private void registerDisplayAdapter(DisplayAdapter adapter) {
        // TODO: do this dynamically
        mDisplayAdapters.add(adapter);
        mDefaultDisplayDevice = adapter.getDisplayDevices()[0];
        mDefaultDisplayDeviceInfo = new DisplayDeviceInfo();
        mDefaultDisplayDevice.getInfo(mDefaultDisplayDeviceInfo);
    }

    private void initializeDefaultDisplay() {
        // Bootstrap the default logical display using the default physical display.
        mDefaultDisplayInfo = new DisplayInfo();
        mDefaultDisplayInfo.appWidth = mDefaultDisplayDeviceInfo.width;
        mDefaultDisplayInfo.appHeight = mDefaultDisplayDeviceInfo.height;
        mDefaultDisplayInfo.logicalWidth = mDefaultDisplayDeviceInfo.width;
        mDefaultDisplayInfo.logicalHeight = mDefaultDisplayDeviceInfo.height;
        mDefaultDisplayInfo.rotation = Surface.ROTATION_0;
        mDefaultDisplayInfo.refreshRate = mDefaultDisplayDeviceInfo.refreshRate;
        mDefaultDisplayInfo.logicalDensityDpi = mDefaultDisplayDeviceInfo.densityDpi;
        mDefaultDisplayInfo.physicalXDpi = mDefaultDisplayDeviceInfo.xDpi;
        mDefaultDisplayInfo.physicalYDpi = mDefaultDisplayDeviceInfo.yDpi;
        mDefaultDisplayInfo.smallestNominalAppWidth = mDefaultDisplayDeviceInfo.width;
        mDefaultDisplayInfo.smallestNominalAppHeight = mDefaultDisplayDeviceInfo.height;
        mDefaultDisplayInfo.largestNominalAppWidth = mDefaultDisplayDeviceInfo.width;
        mDefaultDisplayInfo.largestNominalAppHeight = mDefaultDisplayDeviceInfo.height;
    }
}
