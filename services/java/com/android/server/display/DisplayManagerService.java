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

import com.android.internal.util.IndentingPrintWriter;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IDisplayManager;
import android.hardware.display.IDisplayManagerCallback;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

/**
 * Manages attached displays.
 * <p>
 * The {@link DisplayManagerService} manages the global lifecycle of displays,
 * decides how to configure logical displays based on the physical display devices currently
 * attached, sends notifications to the system and to applications when the state
 * changes, and so on.
 * </p><p>
 * The display manager service relies on a collection of {@link DisplayAdapter} components,
 * for discovering and configuring physical display devices attached to the system.
 * There are separate display adapters for each manner that devices are attached:
 * one display adapter for built-in local displays, one for simulated non-functional
 * displays when the system is headless, one for simulated overlay displays used for
 * development, one for wifi displays, etc.
 * </p><p>
 * Display adapters are only weakly coupled to the display manager service.
 * Display adapters communicate changes in display device state to the display manager
 * service asynchronously via a {@link DisplayAdapter.DisplayAdapterListener} registered
 * by the display manager service.  This separation of concerns is important for
 * two main reasons.  First, it neatly encapsulates the responsibilities of these
 * two classes: display adapters handle individual display devices whereas
 * the display manager service handles the global state.  Second, it eliminates
 * the potential for deadlocks resulting from asynchronous display device discovery.
 * </p><p>
 * To keep things simple, display adapters and display devices are single-threaded
 * and are only accessed on the display manager's handler thread.  Of course, the
 * display manager must be accessible by multiple thread (especially for
 * incoming binder calls) so all of the display manager's state is synchronized
 * and guarded by a lock.
 * </p>
 */
public final class DisplayManagerService extends IDisplayManager.Stub {
    private static final String TAG = "DisplayManagerService";
    private static final boolean DEBUG = false;

    private static final String SYSTEM_HEADLESS = "ro.config.headless";
    private static final long WAIT_FOR_DEFAULT_DISPLAY_TIMEOUT = 10000;

    private static final int MSG_REGISTER_DEFAULT_DISPLAY_ADAPTER = 1;
    private static final int MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS = 2;
    private static final int MSG_DELIVER_DISPLAY_EVENT = 3;

    private final Object mLock = new Object();

    private final Context mContext;
    private final boolean mHeadless;

    private final DisplayManagerHandler mHandler;
    private final DisplayAdapterListener mDisplayAdapterListener = new DisplayAdapterListener();
    private final SparseArray<CallbackRecord> mCallbacks =
            new SparseArray<CallbackRecord>();

    // List of all currently registered display adapters.
    private final ArrayList<DisplayAdapter> mDisplayAdapters = new ArrayList<DisplayAdapter>();

    // List of all currently connected display devices.
    private final ArrayList<DisplayDevice> mDisplayDevices = new ArrayList<DisplayDevice>();

    // List of all logical displays, indexed by logical display id.
    private final SparseArray<LogicalDisplay> mLogicalDisplays = new SparseArray<LogicalDisplay>();
    private int mNextNonDefaultDisplayId = Display.DEFAULT_DISPLAY + 1;

    // True if in safe mode.
    // This option may disable certain display adapters.
    private boolean mSafeMode;

    // True if we are in a special boot mode where only core applications and
    // services should be started.  This option may disable certain display adapters.
    private boolean mOnlyCore;

    // Temporary callback list, used when sending display events to applications.
    private ArrayList<CallbackRecord> mTempCallbacks = new ArrayList<CallbackRecord>();

    public DisplayManagerService(Context context, Handler uiHandler) {
        mContext = context;
        mHeadless = SystemProperties.get(SYSTEM_HEADLESS).equals("1");

        mHandler = new DisplayManagerHandler(uiHandler.getLooper());
        mHandler.sendEmptyMessage(MSG_REGISTER_DEFAULT_DISPLAY_ADAPTER);
    }

    /**
     * Pauses the boot process to wait for the first display to be initialized.
     */
    public boolean waitForDefaultDisplay() {
        synchronized (mLock) {
            long timeout = SystemClock.uptimeMillis() + WAIT_FOR_DEFAULT_DISPLAY_TIMEOUT;
            while (mLogicalDisplays.get(Display.DEFAULT_DISPLAY) == null) {
                long delay = timeout - SystemClock.uptimeMillis();
                if (delay <= 0) {
                    return false;
                }
                if (DEBUG) {
                    Slog.d(TAG, "waitForDefaultDisplay: waiting, timeout=" + delay);
                }
                try {
                    mLock.wait(delay);
                } catch (InterruptedException ex) {
                }
            }
        }
        return true;
    }

    /**
     * Called when the system is ready to go.
     */
    public void systemReady(boolean safeMode, boolean onlyCore) {
        synchronized (mLock) {
            mSafeMode = safeMode;
            mOnlyCore = onlyCore;
        }
        mHandler.sendEmptyMessage(MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS);
    }

    // Runs on handler.
    private void registerDefaultDisplayAdapter() {
        // Register default display adapter.
        if (mHeadless) {
            registerDisplayAdapter(new HeadlessDisplayAdapter(mContext));
        } else {
            registerDisplayAdapter(new LocalDisplayAdapter(mContext));
        }
    }

    // Runs on handler.
    private void registerAdditionalDisplayAdapters() {
        if (shouldRegisterNonEssentialDisplayAdapters()) {
            registerDisplayAdapter(new OverlayDisplayAdapter(mContext));
        }
    }

    private boolean shouldRegisterNonEssentialDisplayAdapters() {
        // In safe mode, we disable non-essential display adapters to give the user
        // an opportunity to fix broken settings or other problems that might affect
        // system stability.
        // In only-core mode, we disable non-essential display adapters to minimize
        // the number of dependencies that are started while in this mode and to
        // prevent problems that might occur due to the device being encrypted.
        synchronized (mLock) {
            return !mSafeMode && !mOnlyCore;
        }
    }

    // Runs on handler.
    private void registerDisplayAdapter(DisplayAdapter adapter) {
        synchronized (mLock) {
            mDisplayAdapters.add(adapter);
        }

        adapter.register(mDisplayAdapterListener);
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
     * Sets the new logical display orientation.
     *
     * @param displayId The logical display id.
     * @param orientation One of the Surface.ROTATION_* constants.
     */
    public void setDisplayOrientation(int displayId, int orientation) {
        synchronized (mLock) {
            // TODO: update mirror transforms
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display != null && display.mPrimaryDisplayDevice != null) {
                IBinder displayToken = display.mPrimaryDisplayDevice.getDisplayToken();
                if (displayToken != null) {
                    Surface.openTransaction();
                    try {
                        Surface.setDisplayOrientation(displayToken, orientation);
                    } finally {
                        Surface.closeTransaction();
                    }
                }

                display.mBaseDisplayInfo.rotation = orientation;
                sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
            }
        }
    }

    /**
     * Overrides the display information of a particular logical display.
     * This is used by the window manager to control the size and characteristics
     * of the default display.
     *
     * @param displayId The logical display id.
     * @param info The new data to be stored.
     */
    public void setDisplayInfoOverrideFromWindowManager(int displayId, DisplayInfo info) {
        synchronized (mLock) {
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display != null) {
                if (info != null) {
                    if (display.mOverrideDisplayInfo == null) {
                        display.mOverrideDisplayInfo = new DisplayInfo();
                    }
                    display.mOverrideDisplayInfo.copyFrom(info);
                } else {
                    display.mOverrideDisplayInfo = null;
                }

                sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
            }
        }
    }

    /**
     * Returns information about the specified logical display.
     *
     * @param displayId The logical display id.
     * @param The logical display info, or null if the display does not exist.
     */
    @Override // Binder call
    public DisplayInfo getDisplayInfo(int displayId) {
        synchronized (mLock) {
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display != null) {
                if (display.mOverrideDisplayInfo != null) {
                    return new DisplayInfo(display.mOverrideDisplayInfo);
                }
                return new DisplayInfo(display.mBaseDisplayInfo);
            }
            return null;
        }
    }

    @Override // Binder call
    public int[] getDisplayIds() {
        synchronized (mLock) {
            final int count = mLogicalDisplays.size();
            int[] displayIds = new int[count];
            for (int i = 0; i > count; i++) {
                displayIds[i] = mLogicalDisplays.keyAt(i);
            }
            return displayIds;
        }
    }

    @Override // Binder call
    public void registerCallback(IDisplayManagerCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (mLock) {
            int callingPid = Binder.getCallingPid();
            if (mCallbacks.get(callingPid) != null) {
                throw new SecurityException("The calling process has already "
                        + "registered an IDisplayManagerCallback.");
            }

            CallbackRecord record = new CallbackRecord(callingPid, callback);
            try {
                IBinder binder = callback.asBinder();
                binder.linkToDeath(record, 0);
            } catch (RemoteException ex) {
                // give up
                throw new RuntimeException(ex);
            }

            mCallbacks.put(callingPid, record);
        }
    }

    private void onCallbackDied(int pid) {
        synchronized (mLock) {
            mCallbacks.remove(pid);
        }
    }

    // Runs on handler.
    private void handleDisplayDeviceAdded(DisplayDevice device) {
        synchronized (mLock) {
            if (mDisplayDevices.contains(device)) {
                Slog.w(TAG, "Attempted to add already added display device: " + device);
                return;
            }

            mDisplayDevices.add(device);

            LogicalDisplay display = new LogicalDisplay(device);
            display.updateFromPrimaryDisplayDevice();

            boolean isDefault = (display.mPrimaryDisplayDeviceInfo.flags
                    & DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY) != 0;
            if (isDefault && mLogicalDisplays.get(Display.DEFAULT_DISPLAY) != null) {
                Slog.w(TAG, "Attempted to add a second default device: " + device);
                isDefault = false;
            }

            int displayId = isDefault ? Display.DEFAULT_DISPLAY : mNextNonDefaultDisplayId++;
            mLogicalDisplays.put(displayId, display);

            sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);

            // Wake up waitForDefaultDisplay.
            if (isDefault) {
                mLock.notifyAll();
            }
        }
    }

    // Runs on handler.
    private void handleDisplayDeviceChanged(DisplayDevice device) {
        synchronized (mLock) {
            if (!mDisplayDevices.contains(device)) {
                Slog.w(TAG, "Attempted to change non-existent display device: " + device);
                return;
            }

            for (int i = mLogicalDisplays.size(); i-- > 0; ) {
                LogicalDisplay display = mLogicalDisplays.valueAt(i);
                if (display.mPrimaryDisplayDevice == device) {
                    final int displayId = mLogicalDisplays.keyAt(i);
                    display.updateFromPrimaryDisplayDevice();
                    sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
                }
            }
        }
    }

    // Runs on handler.
    private void handleDisplayDeviceRemoved(DisplayDevice device) {
        synchronized (mLock) {
            if (!mDisplayDevices.remove(device)) {
                Slog.w(TAG, "Attempted to remove non-existent display device: " + device);
                return;
            }

            for (int i = mLogicalDisplays.size(); i-- > 0; ) {
                LogicalDisplay display = mLogicalDisplays.valueAt(i);
                if (display.mPrimaryDisplayDevice == device) {
                    final int displayId = mLogicalDisplays.keyAt(i);
                    mLogicalDisplays.removeAt(i);
                    sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_REMOVED);
                }
            }
        }
    }

    // Posts a message to send a display event at the next opportunity.
    private void sendDisplayEventLocked(int displayId, int event) {
        Message msg = mHandler.obtainMessage(MSG_DELIVER_DISPLAY_EVENT, displayId, event);
        mHandler.sendMessage(msg);
    }

    // Runs on handler.
    // This method actually sends display event notifications.
    // Note that it must be very careful not to be holding the lock while sending
    // is in progress.
    private void deliverDisplayEvent(int displayId, int event) {
        if (DEBUG) {
            Slog.d(TAG, "Delivering display event: displayId=" + displayId + ", event=" + event);
        }

        final int count;
        synchronized (mLock) {
            count = mCallbacks.size();
            mTempCallbacks.clear();
            for (int i = 0; i < count; i++) {
                mTempCallbacks.add(mCallbacks.valueAt(i));
            }
        }

        for (int i = 0; i < count; i++) {
            mTempCallbacks.get(i).notifyDisplayEventAsync(displayId, event);
        }
        mTempCallbacks.clear();
    }

    @Override // Binder call
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        if (mContext == null
                || mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                        != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump DisplayManager from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }

        pw.println("DISPLAY MANAGER (dumpsys display)");
        pw.println("  mHeadless=" + mHeadless);

        mHandler.runWithScissors(new Runnable() {
            @Override
            public void run() {
                dumpLocal(pw);
            }
        });
    }

    // Runs on handler.
    private void dumpLocal(PrintWriter pw) {
        synchronized (mLock) {
            IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");

            pw.println();
            pw.println("Display Adapters: size=" + mDisplayAdapters.size());
            for (DisplayAdapter adapter : mDisplayAdapters) {
                pw.println("  " + adapter.getName());
                adapter.dump(ipw);
            }

            pw.println();
            pw.println("Display Devices: size=" + mDisplayDevices.size());
            for (DisplayDevice device : mDisplayDevices) {
                pw.println("  " + device);
            }

            final int logicalDisplayCount = mLogicalDisplays.size();
            pw.println();
            pw.println("Logical Displays: size=" + logicalDisplayCount);
            for (int i = 0; i < logicalDisplayCount; i++) {
                int displayId = mLogicalDisplays.keyAt(i);
                LogicalDisplay display = mLogicalDisplays.valueAt(i);
                pw.println("  Display " + displayId + ":");
                pw.println("    mPrimaryDisplayDevice=" + display.mPrimaryDisplayDevice);
                pw.println("    mBaseDisplayInfo=" + display.mBaseDisplayInfo);
                pw.println("    mOverrideDisplayInfo="
                        + display.mOverrideDisplayInfo);
            }
        }
    }

    private final class DisplayManagerHandler extends Handler {
        public DisplayManagerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_DEFAULT_DISPLAY_ADAPTER:
                    registerDefaultDisplayAdapter();
                    break;

                case MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS:
                    registerAdditionalDisplayAdapters();
                    break;

                case MSG_DELIVER_DISPLAY_EVENT:
                    deliverDisplayEvent(msg.arg1, msg.arg2);
                    break;
            }
        }
    }

    private final class DisplayAdapterListener implements DisplayAdapter.Listener {
        @Override
        public void onDisplayDeviceEvent(DisplayDevice device, int event) {
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
        }
    }

    private final class CallbackRecord implements DeathRecipient {
        private final int mPid;
        private final IDisplayManagerCallback mCallback;

        public CallbackRecord(int pid, IDisplayManagerCallback callback) {
            mPid = pid;
            mCallback = callback;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Display listener for pid " + mPid + " died.");
            }
            onCallbackDied(mPid);
        }

        public void notifyDisplayEventAsync(int displayId, int event) {
            try {
                mCallback.onDisplayEvent(displayId, event);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process "
                        + mPid + " that displays changed, assuming it died.", ex);
                binderDied();
            }
        }
    }

    /**
     * Each logical display is primarily associated with one display device.
     * The primary display device is nominally responsible for the basic properties
     * of the logical display such as its size, refresh rate, and dpi.
     *
     * A logical display may be mirrored onto other display devices besides its
     * primary display device, but it always remains bound to its primary.
     * Note that the contents of a logical display may not always be visible, even
     * on its primary display device, such as in the case where the logical display's
     * primary display device is currently mirroring content from a different logical display.
     */
    private final static class LogicalDisplay {
        public final DisplayInfo mBaseDisplayInfo = new DisplayInfo();
        public DisplayInfo mOverrideDisplayInfo; // set by the window manager

        public final DisplayDevice mPrimaryDisplayDevice;
        public final DisplayDeviceInfo mPrimaryDisplayDeviceInfo = new DisplayDeviceInfo();

        public LogicalDisplay(DisplayDevice primaryDisplayDevice) {
            mPrimaryDisplayDevice = primaryDisplayDevice;
        }

        public void updateFromPrimaryDisplayDevice() {
            // Bootstrap the logical display using its associated primary physical display.
            mPrimaryDisplayDevice.getInfo(mPrimaryDisplayDeviceInfo);

            mBaseDisplayInfo.appWidth = mPrimaryDisplayDeviceInfo.width;
            mBaseDisplayInfo.appHeight = mPrimaryDisplayDeviceInfo.height;
            mBaseDisplayInfo.logicalWidth = mPrimaryDisplayDeviceInfo.width;
            mBaseDisplayInfo.logicalHeight = mPrimaryDisplayDeviceInfo.height;
            mBaseDisplayInfo.rotation = Surface.ROTATION_0;
            mBaseDisplayInfo.refreshRate = mPrimaryDisplayDeviceInfo.refreshRate;
            mBaseDisplayInfo.logicalDensityDpi = mPrimaryDisplayDeviceInfo.densityDpi;
            mBaseDisplayInfo.physicalXDpi = mPrimaryDisplayDeviceInfo.xDpi;
            mBaseDisplayInfo.physicalYDpi = mPrimaryDisplayDeviceInfo.yDpi;
            mBaseDisplayInfo.smallestNominalAppWidth = mPrimaryDisplayDeviceInfo.width;
            mBaseDisplayInfo.smallestNominalAppHeight = mPrimaryDisplayDeviceInfo.height;
            mBaseDisplayInfo.largestNominalAppWidth = mPrimaryDisplayDeviceInfo.width;
            mBaseDisplayInfo.largestNominalAppHeight = mPrimaryDisplayDeviceInfo.height;
        }
    }
}
