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

import java.io.FileDescriptor;
import java.io.PrintWriter;
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
 * </p>
 *
 * <h3>Synchronization</h3>
 * <p>
 * Because the display manager may be accessed by multiple threads, the synchronization
 * story gets a little complicated.  In particular, the window manager may call into
 * the display manager while holding a surface transaction with the expectation that
 * it can apply changes immediately.  Unfortunately, that means we can't just do
 * everything asynchronously (*grump*).
 * </p><p>
 * To make this work, all of the objects that belong to the display manager must
 * use the same lock.  We call this lock the synchronization root and it has a unique
 * type {@link DisplayManagerService.SyncRoot}.  Methods that require this lock are
 * named with the "Locked" suffix.
 * </p><p>
 * Where things get tricky is that the display manager is not allowed to make
 * any potentially reentrant calls, especially into the window manager.  We generally
 * avoid this by making all potentially reentrant out-calls asynchronous.
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
    private static final int MSG_REQUEST_TRAVERSAL = 4;

    private final Context mContext;
    private final boolean mHeadless;
    private final DisplayManagerHandler mHandler;
    private final Handler mUiHandler;
    private final DisplayAdapterListener mDisplayAdapterListener;
    private WindowManagerFuncs mWindowManagerFuncs;

    // The synchronization root for the display manager.
    // This lock guards most of the display manager's state.
    private final SyncRoot mSyncRoot = new SyncRoot();

    // True if in safe mode.
    // This option may disable certain display adapters.
    public boolean mSafeMode;

    // True if we are in a special boot mode where only core applications and
    // services should be started.  This option may disable certain display adapters.
    public boolean mOnlyCore;

    // All callback records indexed by calling process id.
    public final SparseArray<CallbackRecord> mCallbacks =
            new SparseArray<CallbackRecord>();

    // List of all currently registered display adapters.
    private final ArrayList<DisplayAdapter> mDisplayAdapters = new ArrayList<DisplayAdapter>();

    // List of all currently connected display devices.
    private final ArrayList<DisplayDevice> mDisplayDevices = new ArrayList<DisplayDevice>();

    // List of all removed display devices.
    private final ArrayList<DisplayDevice> mRemovedDisplayDevices = new ArrayList<DisplayDevice>();

    // List of all logical displays indexed by logical display id.
    private final SparseArray<LogicalDisplay> mLogicalDisplays =
            new SparseArray<LogicalDisplay>();
    private int mNextNonDefaultDisplayId = Display.DEFAULT_DISPLAY + 1;

    // Set to true when there are pending display changes that have yet to be applied
    // to the surface flinger state.
    private boolean mPendingTraversal;

    // Temporary callback list, used when sending display events to applications.
    // May be used outside of the lock but only on the handler thread.
    private final ArrayList<CallbackRecord> mTempCallbacks = new ArrayList<CallbackRecord>();

    // Temporary display info, used for comparing display configurations.
    private final DisplayInfo mTempDisplayInfo = new DisplayInfo();

    public DisplayManagerService(Context context, Handler mainHandler, Handler uiHandler) {
        mContext = context;
        mHeadless = SystemProperties.get(SYSTEM_HEADLESS).equals("1");

        mHandler = new DisplayManagerHandler(mainHandler.getLooper());
        mUiHandler = uiHandler;
        mDisplayAdapterListener = new DisplayAdapterListener();

        mHandler.sendEmptyMessage(MSG_REGISTER_DEFAULT_DISPLAY_ADAPTER);
    }

    /**
     * Pauses the boot process to wait for the first display to be initialized.
     */
    public boolean waitForDefaultDisplay() {
        synchronized (mSyncRoot) {
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
                    mSyncRoot.wait(delay);
                } catch (InterruptedException ex) {
                }
            }
        }
        return true;
    }

    /**
     * Called during initialization to associated the display manager with the
     * window manager.
     */
    public void setWindowManager(WindowManagerFuncs windowManagerFuncs) {
        synchronized (mSyncRoot) {
            mWindowManagerFuncs = windowManagerFuncs;
            scheduleTraversalLocked();
        }
    }

    /**
     * Called when the system is ready to go.
     */
    public void systemReady(boolean safeMode, boolean onlyCore) {
        synchronized (mSyncRoot) {
            mSafeMode = safeMode;
            mOnlyCore = onlyCore;
        }

        mHandler.sendEmptyMessage(MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS);
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
     * Overrides the display information of a particular logical display.
     * This is used by the window manager to control the size and characteristics
     * of the default display.  It is expected to apply the requested change
     * to the display information synchronously so that applications will immediately
     * observe the new state.
     *
     * @param displayId The logical display id.
     * @param info The new data to be stored.
     */
    public void setDisplayInfoOverrideFromWindowManager(
            int displayId, DisplayInfo info) {
        synchronized (mSyncRoot) {
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display != null) {
                mTempDisplayInfo.copyFrom(display.getDisplayInfoLocked());
                display.setDisplayInfoOverrideFromWindowManagerLocked(info);
                if (!mTempDisplayInfo.equals(display.getDisplayInfoLocked())) {
                    sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
                    scheduleTraversalLocked();
                }
            }
        }
    }

    /**
     * Called by the window manager to perform traversals while holding a
     * surface flinger transaction.
     */
    public void performTraversalInTransactionFromWindowManager() {
        synchronized (mSyncRoot) {
            if (!mPendingTraversal) {
                return;
            }
            mPendingTraversal = false;

            performTraversalInTransactionLocked();
        }
    }

    /**
     * Returns information about the specified logical display.
     *
     * @param displayId The logical display id.
     * @param The logical display info, or null if the display does not exist.
     * This object must be treated as immutable.
     */
    @Override // Binder call
    public DisplayInfo getDisplayInfo(int displayId) {
        synchronized (mSyncRoot) {
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display != null) {
                return display.getDisplayInfoLocked();
            }
            return null;
        }
    }

    /**
     * Returns the list of all display ids.
     */
    @Override // Binder call
    public int[] getDisplayIds() {
        synchronized (mSyncRoot) {
            final int count = mLogicalDisplays.size();
            int[] displayIds = new int[count];
            for (int i = 0; i < count; i++) {
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

        synchronized (mSyncRoot) {
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
        synchronized (mSyncRoot) {
            mCallbacks.remove(pid);
        }
    }

    private void registerDefaultDisplayAdapter() {
        // Register default display adapter.
        synchronized (mSyncRoot) {
            if (mHeadless) {
                registerDisplayAdapterLocked(new HeadlessDisplayAdapter(
                        mSyncRoot, mContext, mHandler, mDisplayAdapterListener));
            } else {
                registerDisplayAdapterLocked(new LocalDisplayAdapter(
                        mSyncRoot, mContext, mHandler, mDisplayAdapterListener));
            }
        }
    }

    private void registerAdditionalDisplayAdapters() {
        synchronized (mSyncRoot) {
            if (shouldRegisterNonEssentialDisplayAdaptersLocked()) {
                registerDisplayAdapterLocked(new OverlayDisplayAdapter(
                        mSyncRoot, mContext, mHandler, mDisplayAdapterListener, mUiHandler));
                registerDisplayAdapterLocked(new WifiDisplayAdapter(
                        mSyncRoot, mContext, mHandler, mDisplayAdapterListener));
            }
        }
    }

    private boolean shouldRegisterNonEssentialDisplayAdaptersLocked() {
        // In safe mode, we disable non-essential display adapters to give the user
        // an opportunity to fix broken settings or other problems that might affect
        // system stability.
        // In only-core mode, we disable non-essential display adapters to minimize
        // the number of dependencies that are started while in this mode and to
        // prevent problems that might occur due to the device being encrypted.
        return !mSafeMode && !mOnlyCore;
    }

    private void registerDisplayAdapterLocked(DisplayAdapter adapter) {
        mDisplayAdapters.add(adapter);
        adapter.registerLocked();
    }

    private void handleDisplayDeviceAdded(DisplayDevice device) {
        synchronized (mSyncRoot) {
            if (mDisplayDevices.contains(device)) {
                Slog.w(TAG, "Attempted to add already added display device: "
                        + device.getDisplayDeviceInfoLocked());
                return;
            }

            mDisplayDevices.add(device);
            addLogicalDisplayLocked(device);
            scheduleTraversalLocked();
        }
    }

    private void handleDisplayDeviceChanged(DisplayDevice device) {
        synchronized (mSyncRoot) {
            if (!mDisplayDevices.contains(device)) {
                Slog.w(TAG, "Attempted to change non-existent display device: "
                        + device.getDisplayDeviceInfoLocked());
                return;
            }

            device.applyPendingDisplayDeviceInfoChangesLocked();
            if (updateLogicalDisplaysLocked()) {
                scheduleTraversalLocked();
            }
        }
    }

    private void handleDisplayDeviceRemoved(DisplayDevice device) {
        synchronized (mSyncRoot) {
            if (!mDisplayDevices.remove(device)) {
                Slog.w(TAG, "Attempted to remove non-existent display device: "
                        + device.getDisplayDeviceInfoLocked());
                return;
            }

            mRemovedDisplayDevices.add(device);
            updateLogicalDisplaysLocked();
            scheduleTraversalLocked();
        }
    }

    // Adds a new logical display based on the given display device.
    // Sends notifications if needed.
    private void addLogicalDisplayLocked(DisplayDevice device) {
        DisplayDeviceInfo deviceInfo = device.getDisplayDeviceInfoLocked();
        boolean isDefault = (deviceInfo.flags
                & DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY) != 0;
        if (isDefault && mLogicalDisplays.get(Display.DEFAULT_DISPLAY) != null) {
            Slog.w(TAG, "Ignoring attempt to add a second default display: " + deviceInfo);
            isDefault = false;
        }

        final int displayId = assignDisplayIdLocked(isDefault);
        final int layerStack = assignLayerStackLocked(displayId);

        LogicalDisplay display = new LogicalDisplay(layerStack, device);
        display.updateLocked(mDisplayDevices);
        if (!display.isValidLocked()) {
            // This should never happen currently.
            Slog.w(TAG, "Ignoring display device because the logical display "
                    + "created from it was not considered valid: " + deviceInfo);
            return;
        }

        mLogicalDisplays.put(displayId, display);

        // Wake up waitForDefaultDisplay.
        if (isDefault) {
            mSyncRoot.notifyAll();
        }

        sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);
    }

    private int assignDisplayIdLocked(boolean isDefault) {
        return isDefault ? Display.DEFAULT_DISPLAY : mNextNonDefaultDisplayId++;
    }

    private int assignLayerStackLocked(int displayId) {
        // Currently layer stacks and display ids are the same.
        // This need not be the case.
        return displayId;
    }

    // Updates all existing logical displays given the current set of display devices.
    // Removes invalid logical displays.
    // Sends notifications if needed.
    private boolean updateLogicalDisplaysLocked() {
        boolean changed = false;
        for (int i = mLogicalDisplays.size(); i-- > 0; ) {
            final int displayId = mLogicalDisplays.keyAt(i);
            LogicalDisplay display = mLogicalDisplays.valueAt(i);

            mTempDisplayInfo.copyFrom(display.getDisplayInfoLocked());
            display.updateLocked(mDisplayDevices);
            if (!display.isValidLocked()) {
                mLogicalDisplays.removeAt(i);
                sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_REMOVED);
                changed = true;
            } else if (!mTempDisplayInfo.equals(display.getDisplayInfoLocked())) {
                sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
                changed = true;
            }
        }
        return changed;
    }

    private void performTraversalInTransactionLocked() {
        // Perform one last traversal for each removed display device.
        final int removedCount = mRemovedDisplayDevices.size();
        for (int i = 0; i < removedCount; i++) {
            DisplayDevice device = mRemovedDisplayDevices.get(i);
            device.performTraversalInTransactionLocked();
        }
        mRemovedDisplayDevices.clear();

        // Configure each display device.
        final int count = mDisplayDevices.size();
        for (int i = 0; i < count; i++) {
            DisplayDevice device = mDisplayDevices.get(i);
            configureDisplayInTransactionLocked(device);
            device.performTraversalInTransactionLocked();
        }
    }

    private void configureDisplayInTransactionLocked(DisplayDevice device) {
        // TODO: add a proper per-display mirroring control
        boolean isMirroring = SystemProperties.getBoolean("debug.display.mirror", true);

        // Find the logical display that the display device is showing.
        LogicalDisplay display = null;
        if (!isMirroring) {
            display = findLogicalDisplayForDeviceLocked(device);
        }
        if (display == null) {
            display = mLogicalDisplays.get(Display.DEFAULT_DISPLAY);
        }

        // Apply the logical display configuration to the display device.
        if (display == null) {
            // TODO: no logical display for the device, blank it
            Slog.d(TAG, "Missing logical display to use for physical display device: "
                    + device.getDisplayDeviceInfoLocked());
        } else {
            display.configureDisplayInTransactionLocked(device);
        }
    }

    private LogicalDisplay findLogicalDisplayForDeviceLocked(DisplayDevice device) {
        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (display.getPrimaryDisplayDeviceLocked() == device) {
                return display;
            }
        }
        return null;
    }

    private void sendDisplayEventLocked(int displayId, int event) {
        Message msg = mHandler.obtainMessage(MSG_DELIVER_DISPLAY_EVENT, displayId, event);
        mHandler.sendMessage(msg);
    }

    // Requests that performTraversalsInTransactionFromWindowManager be called at a
    // later time to apply changes to surfaces and displays.
    private void scheduleTraversalLocked() {
        if (!mPendingTraversal && mWindowManagerFuncs != null) {
            mPendingTraversal = true;
            mHandler.sendEmptyMessage(MSG_REQUEST_TRAVERSAL);
        }
    }

    // Runs on Handler thread.
    // Delivers display event notifications to callbacks.
    private void deliverDisplayEvent(int displayId, int event) {
        if (DEBUG) {
            Slog.d(TAG, "Delivering display event: displayId="
                    + displayId + ", event=" + event);
        }

        // Grab the lock and copy the callbacks.
        final int count;
        synchronized (mSyncRoot) {
            count = mCallbacks.size();
            mTempCallbacks.clear();
            for (int i = 0; i < count; i++) {
                mTempCallbacks.add(mCallbacks.valueAt(i));
            }
        }

        // After releasing the lock, send the notifications out.
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

        synchronized (mSyncRoot) {
            IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
            ipw.increaseIndent();

            pw.println();
            pw.println("Display Adapters: size=" + mDisplayAdapters.size());
            for (DisplayAdapter adapter : mDisplayAdapters) {
                pw.println("  " + adapter.getName());
                adapter.dumpLocked(ipw);
            }

            pw.println();
            pw.println("Display Devices: size=" + mDisplayDevices.size());
            for (DisplayDevice device : mDisplayDevices) {
                pw.println("  " + device.getDisplayDeviceInfoLocked());
                device.dumpLocked(ipw);
            }

            final int logicalDisplayCount = mLogicalDisplays.size();
            pw.println();
            pw.println("Logical Displays: size=" + logicalDisplayCount);
            for (int i = 0; i < logicalDisplayCount; i++) {
                int displayId = mLogicalDisplays.keyAt(i);
                LogicalDisplay display = mLogicalDisplays.valueAt(i);
                pw.println("  Display " + displayId + ":");
                display.dumpLocked(ipw);
            }
        }
    }

    /**
     * This is the object that everything in the display manager locks on.
     * We make it an inner class within the {@link DisplayManagerService} to so that it is
     * clear that the object belongs to the display manager service and that it is
     * a unique object with a special purpose.
     */
    public static final class SyncRoot {
    }

    /**
     * Private interface to the window manager.
     */
    public interface WindowManagerFuncs {
        /**
         * Request that the window manager call {@link #performTraversalInTransaction}
         * within a surface transaction at a later time.
         */
        void requestTraversal();
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

                case MSG_REQUEST_TRAVERSAL:
                    mWindowManagerFuncs.requestTraversal();
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

        @Override
        public void onTraversalRequested() {
            synchronized (mSyncRoot) {
                scheduleTraversalLocked();
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
}
