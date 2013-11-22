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
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IDisplayManager;
import android.hardware.display.IDisplayManagerCallback;
import android.hardware.display.WifiDisplayStatus;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.server.UiThread;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * service asynchronously via a {@link DisplayAdapter.Listener} registered
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

    // When this system property is set to 0, WFD is forcibly disabled on boot.
    // When this system property is set to 1, WFD is forcibly enabled on boot.
    // Otherwise WFD is enabled according to the value of config_enableWifiDisplay.
    private static final String FORCE_WIFI_DISPLAY_ENABLE = "persist.debug.wfd.enable";

    private static final String SYSTEM_HEADLESS = "ro.config.headless";
    private static final long WAIT_FOR_DEFAULT_DISPLAY_TIMEOUT = 10000;

    private static final int MSG_REGISTER_DEFAULT_DISPLAY_ADAPTER = 1;
    private static final int MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS = 2;
    private static final int MSG_DELIVER_DISPLAY_EVENT = 3;
    private static final int MSG_REQUEST_TRAVERSAL = 4;
    private static final int MSG_UPDATE_VIEWPORT = 5;

    private static final int DISPLAY_BLANK_STATE_UNKNOWN = 0;
    private static final int DISPLAY_BLANK_STATE_BLANKED = 1;
    private static final int DISPLAY_BLANK_STATE_UNBLANKED = 2;

    private final Context mContext;
    private final boolean mHeadless;
    private final DisplayManagerHandler mHandler;
    private final Handler mUiHandler;
    private final DisplayAdapterListener mDisplayAdapterListener;
    private WindowManagerFuncs mWindowManagerFuncs;
    private InputManagerFuncs mInputManagerFuncs;

    // The synchronization root for the display manager.
    // This lock guards most of the display manager's state.
    // NOTE: This is synchronized on while holding WindowManagerService.mWindowMap so never call
    // into WindowManagerService methods that require mWindowMap while holding this unless you are
    // very very sure that no deadlock can occur.
    private final SyncRoot mSyncRoot = new SyncRoot();

    // True if in safe mode.
    // This option may disable certain display adapters.
    public boolean mSafeMode;

    // True if we are in a special boot mode where only core applications and
    // services should be started.  This option may disable certain display adapters.
    public boolean mOnlyCore;

    // True if the display manager service should pretend there is only one display
    // and only tell applications about the existence of the default logical display.
    // The display manager can still mirror content to secondary displays but applications
    // cannot present unique content on those displays.
    // Used for demonstration purposes only.
    private final boolean mSingleDisplayDemoMode;

    // All callback records indexed by calling process id.
    public final SparseArray<CallbackRecord> mCallbacks =
            new SparseArray<CallbackRecord>();

    // List of all currently registered display adapters.
    private final ArrayList<DisplayAdapter> mDisplayAdapters = new ArrayList<DisplayAdapter>();

    // List of all currently connected display devices.
    private final ArrayList<DisplayDevice> mDisplayDevices = new ArrayList<DisplayDevice>();

    // List of all logical displays indexed by logical display id.
    private final SparseArray<LogicalDisplay> mLogicalDisplays =
            new SparseArray<LogicalDisplay>();
    private int mNextNonDefaultDisplayId = Display.DEFAULT_DISPLAY + 1;

    // List of all display transaction listeners.
    private final CopyOnWriteArrayList<DisplayTransactionListener> mDisplayTransactionListeners =
            new CopyOnWriteArrayList<DisplayTransactionListener>();

    // Set to true if all displays have been blanked by the power manager.
    private int mAllDisplayBlankStateFromPowerManager = DISPLAY_BLANK_STATE_UNKNOWN;

    // Set to true when there are pending display changes that have yet to be applied
    // to the surface flinger state.
    private boolean mPendingTraversal;

    // The Wifi display adapter, or null if not registered.
    private WifiDisplayAdapter mWifiDisplayAdapter;

    // The number of active wifi display scan requests.
    private int mWifiDisplayScanRequestCount;

    // The virtual display adapter, or null if not registered.
    private VirtualDisplayAdapter mVirtualDisplayAdapter;

    // Viewports of the default display and the display that should receive touch
    // input from an external source.  Used by the input system.
    private final DisplayViewport mDefaultViewport = new DisplayViewport();
    private final DisplayViewport mExternalTouchViewport = new DisplayViewport();

    // Persistent data store for all internal settings maintained by the display manager service.
    private final PersistentDataStore mPersistentDataStore = new PersistentDataStore();

    // Temporary callback list, used when sending display events to applications.
    // May be used outside of the lock but only on the handler thread.
    private final ArrayList<CallbackRecord> mTempCallbacks = new ArrayList<CallbackRecord>();

    // Temporary display info, used for comparing display configurations.
    private final DisplayInfo mTempDisplayInfo = new DisplayInfo();

    // Temporary viewports, used when sending new viewport information to the
    // input system.  May be used outside of the lock but only on the handler thread.
    private final DisplayViewport mTempDefaultViewport = new DisplayViewport();
    private final DisplayViewport mTempExternalTouchViewport = new DisplayViewport();

    public DisplayManagerService(Context context, Handler mainHandler) {
        mContext = context;
        mHeadless = SystemProperties.get(SYSTEM_HEADLESS).equals("1");

        mHandler = new DisplayManagerHandler(mainHandler.getLooper());
        mUiHandler = UiThread.getHandler();
        mDisplayAdapterListener = new DisplayAdapterListener();
        mSingleDisplayDemoMode = SystemProperties.getBoolean("persist.demo.singledisplay", false);

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
     * Called during initialization to associate the display manager with the
     * window manager.
     */
    public void setWindowManager(WindowManagerFuncs windowManagerFuncs) {
        synchronized (mSyncRoot) {
            mWindowManagerFuncs = windowManagerFuncs;
            scheduleTraversalLocked(false);
        }
    }

    /**
     * Called during initialization to associate the display manager with the
     * input manager.
     */
    public void setInputManager(InputManagerFuncs inputManagerFuncs) {
        synchronized (mSyncRoot) {
            mInputManagerFuncs = inputManagerFuncs;
            scheduleTraversalLocked(false);
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
     * Registers a display transaction listener to provide the client a chance to
     * update its surfaces within the same transaction as any display layout updates.
     *
     * @param listener The listener to register.
     */
    public void registerDisplayTransactionListener(DisplayTransactionListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        // List is self-synchronized copy-on-write.
        mDisplayTransactionListeners.add(listener);
    }

    /**
     * Unregisters a display transaction listener to provide the client a chance to
     * update its surfaces within the same transaction as any display layout updates.
     *
     * @param listener The listener to unregister.
     */
    public void unregisterDisplayTransactionListener(DisplayTransactionListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        // List is self-synchronized copy-on-write.
        mDisplayTransactionListeners.remove(listener);
    }

    /**
     * Overrides the display information of a particular logical display.
     * This is used by the window manager to control the size and characteristics
     * of the default display.  It is expected to apply the requested change
     * to the display information synchronously so that applications will immediately
     * observe the new state.
     *
     * NOTE: This method must be the only entry point by which the window manager
     * influences the logical configuration of displays.
     *
     * @param displayId The logical display id.
     * @param info The new data to be stored.
     */
    public void setDisplayInfoOverrideFromWindowManager(
            int displayId, DisplayInfo info) {
        synchronized (mSyncRoot) {
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display != null) {
                if (display.setDisplayInfoOverrideFromWindowManagerLocked(info)) {
                    sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
                    scheduleTraversalLocked(false);
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

        // List is self-synchronized copy-on-write.
        for (DisplayTransactionListener listener : mDisplayTransactionListeners) {
            listener.onDisplayTransaction();
        }
    }

    /**
     * Called by the power manager to blank all displays.
     */
    public void blankAllDisplaysFromPowerManager() {
        synchronized (mSyncRoot) {
            if (mAllDisplayBlankStateFromPowerManager != DISPLAY_BLANK_STATE_BLANKED) {
                mAllDisplayBlankStateFromPowerManager = DISPLAY_BLANK_STATE_BLANKED;
                updateAllDisplayBlankingLocked();
                scheduleTraversalLocked(false);
            }
        }
    }

    /**
     * Called by the power manager to unblank all displays.
     */
    public void unblankAllDisplaysFromPowerManager() {
        synchronized (mSyncRoot) {
            if (mAllDisplayBlankStateFromPowerManager != DISPLAY_BLANK_STATE_UNBLANKED) {
                mAllDisplayBlankStateFromPowerManager = DISPLAY_BLANK_STATE_UNBLANKED;
                updateAllDisplayBlankingLocked();
                scheduleTraversalLocked(false);
            }
        }
    }

    /**
     * Returns information about the specified logical display.
     *
     * @param displayId The logical display id.
     * @return The logical display info, or null if the display does not exist.  The
     * returned object must be treated as immutable.
     */
    @Override // Binder call
    public DisplayInfo getDisplayInfo(int displayId) {
        final int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                LogicalDisplay display = mLogicalDisplays.get(displayId);
                if (display != null) {
                    DisplayInfo info = display.getDisplayInfoLocked();
                    if (info.hasAccess(callingUid)) {
                        return info;
                    }
                }
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Returns the list of all display ids.
     */
    @Override // Binder call
    public int[] getDisplayIds() {
        final int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
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
        } finally {
            Binder.restoreCallingIdentity(token);
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

    private void onCallbackDied(CallbackRecord record) {
        synchronized (mSyncRoot) {
            mCallbacks.remove(record.mPid);
            stopWifiDisplayScanLocked(record);
        }
    }

    @Override // Binder call
    public void startWifiDisplayScan() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                "Permission required to start wifi display scans");

        final int callingPid = Binder.getCallingPid();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                CallbackRecord record = mCallbacks.get(callingPid);
                if (record == null) {
                    throw new IllegalStateException("The calling process has not "
                            + "registered an IDisplayManagerCallback.");
                }
                startWifiDisplayScanLocked(record);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void startWifiDisplayScanLocked(CallbackRecord record) {
        if (!record.mWifiDisplayScanRequested) {
            record.mWifiDisplayScanRequested = true;
            if (mWifiDisplayScanRequestCount++ == 0) {
                if (mWifiDisplayAdapter != null) {
                    mWifiDisplayAdapter.requestStartScanLocked();
                }
            }
        }
    }

    @Override // Binder call
    public void stopWifiDisplayScan() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                "Permission required to stop wifi display scans");

        final int callingPid = Binder.getCallingPid();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                CallbackRecord record = mCallbacks.get(callingPid);
                if (record == null) {
                    throw new IllegalStateException("The calling process has not "
                            + "registered an IDisplayManagerCallback.");
                }
                stopWifiDisplayScanLocked(record);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void stopWifiDisplayScanLocked(CallbackRecord record) {
        if (record.mWifiDisplayScanRequested) {
            record.mWifiDisplayScanRequested = false;
            if (--mWifiDisplayScanRequestCount == 0) {
                if (mWifiDisplayAdapter != null) {
                    mWifiDisplayAdapter.requestStopScanLocked();
                }
            } else if (mWifiDisplayScanRequestCount < 0) {
                Log.wtf(TAG, "mWifiDisplayScanRequestCount became negative: "
                        + mWifiDisplayScanRequestCount);
                mWifiDisplayScanRequestCount = 0;
            }
        }
    }

    @Override // Binder call
    public void connectWifiDisplay(String address) {
        if (address == null) {
            throw new IllegalArgumentException("address must not be null");
        }
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                "Permission required to connect to a wifi display");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                if (mWifiDisplayAdapter != null) {
                    mWifiDisplayAdapter.requestConnectLocked(address);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void pauseWifiDisplay() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                "Permission required to pause a wifi display session");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                if (mWifiDisplayAdapter != null) {
                    mWifiDisplayAdapter.requestPauseLocked();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void resumeWifiDisplay() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                "Permission required to resume a wifi display session");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                if (mWifiDisplayAdapter != null) {
                    mWifiDisplayAdapter.requestResumeLocked();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override // Binder call
    public void disconnectWifiDisplay() {
        // This request does not require special permissions.
        // Any app can request disconnection from the currently active wifi display.
        // This exception should no longer be needed once wifi display control moves
        // to the media router service.

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                if (mWifiDisplayAdapter != null) {
                    mWifiDisplayAdapter.requestDisconnectLocked();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override // Binder call
    public void renameWifiDisplay(String address, String alias) {
        if (address == null) {
            throw new IllegalArgumentException("address must not be null");
        }
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                "Permission required to rename to a wifi display");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                if (mWifiDisplayAdapter != null) {
                    mWifiDisplayAdapter.requestRenameLocked(address, alias);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override // Binder call
    public void forgetWifiDisplay(String address) {
        if (address == null) {
            throw new IllegalArgumentException("address must not be null");
        }
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                "Permission required to forget to a wifi display");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                if (mWifiDisplayAdapter != null) {
                    mWifiDisplayAdapter.requestForgetLocked(address);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override // Binder call
    public WifiDisplayStatus getWifiDisplayStatus() {
        // This request does not require special permissions.
        // Any app can get information about available wifi displays.

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                if (mWifiDisplayAdapter != null) {
                    return mWifiDisplayAdapter.getWifiDisplayStatusLocked();
                }
                return new WifiDisplayStatus();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override // Binder call
    public int createVirtualDisplay(IBinder appToken, String packageName,
            String name, int width, int height, int densityDpi, Surface surface, int flags) {
        final int callingUid = Binder.getCallingUid();
        if (!validatePackageName(callingUid, packageName)) {
            throw new SecurityException("packageName must match the calling uid");
        }
        if (appToken == null) {
            throw new IllegalArgumentException("appToken must not be null");
        }
        if (TextUtils.isEmpty(name)) {
            throw new IllegalArgumentException("name must be non-null and non-empty");
        }
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            throw new IllegalArgumentException("width, height, and densityDpi must be "
                    + "greater than 0");
        }
        if (surface == null) {
            throw new IllegalArgumentException("surface must not be null");
        }
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) {
            if (mContext.checkCallingPermission(android.Manifest.permission.CAPTURE_VIDEO_OUTPUT)
                    != PackageManager.PERMISSION_GRANTED
                    && mContext.checkCallingPermission(
                            android.Manifest.permission.CAPTURE_SECURE_VIDEO_OUTPUT)
                            != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires CAPTURE_VIDEO_OUTPUT or "
                        + "CAPTURE_SECURE_VIDEO_OUTPUT permission to create a "
                        + "public virtual display.");
            }
        }
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE) != 0) {
            if (mContext.checkCallingPermission(
                    android.Manifest.permission.CAPTURE_SECURE_VIDEO_OUTPUT)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires CAPTURE_SECURE_VIDEO_OUTPUT "
                        + "to create a secure virtual display.");
            }
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                if (mVirtualDisplayAdapter == null) {
                    Slog.w(TAG, "Rejecting request to create private virtual display "
                            + "because the virtual display adapter is not available.");
                    return -1;
                }

                DisplayDevice device = mVirtualDisplayAdapter.createVirtualDisplayLocked(
                        appToken, callingUid, packageName, name, width, height, densityDpi,
                        surface, flags);
                if (device == null) {
                    return -1;
                }

                handleDisplayDeviceAddedLocked(device);
                LogicalDisplay display = findLogicalDisplayForDeviceLocked(device);
                if (display != null) {
                    return display.getDisplayIdLocked();
                }

                // Something weird happened and the logical display was not created.
                Slog.w(TAG, "Rejecting request to create virtual display "
                        + "because the logical display was not created.");
                mVirtualDisplayAdapter.releaseVirtualDisplayLocked(appToken);
                handleDisplayDeviceRemovedLocked(device);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return -1;
    }

    @Override // Binder call
    public void releaseVirtualDisplay(IBinder appToken) {
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mSyncRoot) {
                if (mVirtualDisplayAdapter == null) {
                    return;
                }

                DisplayDevice device =
                        mVirtualDisplayAdapter.releaseVirtualDisplayLocked(appToken);
                if (device != null) {
                    handleDisplayDeviceRemovedLocked(device);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean validatePackageName(int uid, String packageName) {
        if (packageName != null) {
            String[] packageNames = mContext.getPackageManager().getPackagesForUid(uid);
            if (packageNames != null) {
                for (String n : packageNames) {
                    if (n.equals(packageName)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
                registerOverlayDisplayAdapterLocked();
                registerWifiDisplayAdapterLocked();
                registerVirtualDisplayAdapterLocked();
            }
        }
    }

    private void registerOverlayDisplayAdapterLocked() {
        registerDisplayAdapterLocked(new OverlayDisplayAdapter(
                mSyncRoot, mContext, mHandler, mDisplayAdapterListener, mUiHandler));
    }

    private void registerWifiDisplayAdapterLocked() {
        if (mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableWifiDisplay)
                || SystemProperties.getInt(FORCE_WIFI_DISPLAY_ENABLE, -1) == 1) {
            mWifiDisplayAdapter = new WifiDisplayAdapter(
                    mSyncRoot, mContext, mHandler, mDisplayAdapterListener,
                    mPersistentDataStore);
            registerDisplayAdapterLocked(mWifiDisplayAdapter);
        }
    }

    private void registerVirtualDisplayAdapterLocked() {
        mVirtualDisplayAdapter = new VirtualDisplayAdapter(
                mSyncRoot, mContext, mHandler, mDisplayAdapterListener);
        registerDisplayAdapterLocked(mVirtualDisplayAdapter);
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
            handleDisplayDeviceAddedLocked(device);
        }
    }

    private void handleDisplayDeviceAddedLocked(DisplayDevice device) {
        if (mDisplayDevices.contains(device)) {
            Slog.w(TAG, "Attempted to add already added display device: "
                    + device.getDisplayDeviceInfoLocked());
            return;
        }

        Slog.i(TAG, "Display device added: " + device.getDisplayDeviceInfoLocked());

        mDisplayDevices.add(device);
        addLogicalDisplayLocked(device);
        updateDisplayBlankingLocked(device);
        scheduleTraversalLocked(false);
    }

    private void handleDisplayDeviceChanged(DisplayDevice device) {
        synchronized (mSyncRoot) {
            if (!mDisplayDevices.contains(device)) {
                Slog.w(TAG, "Attempted to change non-existent display device: "
                        + device.getDisplayDeviceInfoLocked());
                return;
            }

            Slog.i(TAG, "Display device changed: " + device.getDisplayDeviceInfoLocked());

            device.applyPendingDisplayDeviceInfoChangesLocked();
            if (updateLogicalDisplaysLocked()) {
                scheduleTraversalLocked(false);
            }
        }
    }

    private void handleDisplayDeviceRemoved(DisplayDevice device) {
        synchronized (mSyncRoot) {
            handleDisplayDeviceRemovedLocked(device);
        }
    }
    private void handleDisplayDeviceRemovedLocked(DisplayDevice device) {
        if (!mDisplayDevices.remove(device)) {
            Slog.w(TAG, "Attempted to remove non-existent display device: "
                    + device.getDisplayDeviceInfoLocked());
            return;
        }

        Slog.i(TAG, "Display device removed: " + device.getDisplayDeviceInfoLocked());

        updateLogicalDisplaysLocked();
        scheduleTraversalLocked(false);
    }

    private void updateAllDisplayBlankingLocked() {
        final int count = mDisplayDevices.size();
        for (int i = 0; i < count; i++) {
            DisplayDevice device = mDisplayDevices.get(i);
            updateDisplayBlankingLocked(device);
        }
    }

    private void updateDisplayBlankingLocked(DisplayDevice device) {
        // Blank or unblank the display immediately to match the state requested
        // by the power manager (if known).
        DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        if ((info.flags & DisplayDeviceInfo.FLAG_NEVER_BLANK) == 0) {
            switch (mAllDisplayBlankStateFromPowerManager) {
                case DISPLAY_BLANK_STATE_BLANKED:
                    device.blankLocked();
                    break;
                case DISPLAY_BLANK_STATE_UNBLANKED:
                    device.unblankLocked();
                    break;
            }
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

        if (!isDefault && mSingleDisplayDemoMode) {
            Slog.i(TAG, "Not creating a logical display for a secondary display "
                    + " because single display demo mode is enabled: " + deviceInfo);
            return;
        }

        final int displayId = assignDisplayIdLocked(isDefault);
        final int layerStack = assignLayerStackLocked(displayId);

        LogicalDisplay display = new LogicalDisplay(displayId, layerStack, device);
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
        // Clear all viewports before configuring displays so that we can keep
        // track of which ones we have configured.
        clearViewportsLocked();

        // Configure each display device.
        final int count = mDisplayDevices.size();
        for (int i = 0; i < count; i++) {
            DisplayDevice device = mDisplayDevices.get(i);
            configureDisplayInTransactionLocked(device);
            device.performTraversalInTransactionLocked();
        }

        // Tell the input system about these new viewports.
        if (mInputManagerFuncs != null) {
            mHandler.sendEmptyMessage(MSG_UPDATE_VIEWPORT);
        }
    }

    /**
     * Tells the display manager whether there is interesting unique content on the
     * specified logical display.  This is used to control automatic mirroring.
     * <p>
     * If the display has unique content, then the display manager arranges for it
     * to be presented on a physical display if appropriate.  Otherwise, the display manager
     * may choose to make the physical display mirror some other logical display.
     * </p>
     *
     * @param displayId The logical display id to update.
     * @param hasContent True if the logical display has content.
     * @param inTraversal True if called from WindowManagerService during a window traversal prior
     * to call to performTraversalInTransactionFromWindowManager.
     */
    public void setDisplayHasContent(int displayId, boolean hasContent, boolean inTraversal) {
        synchronized (mSyncRoot) {
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display != null && display.hasContentLocked() != hasContent) {
                if (DEBUG) {
                    Slog.d(TAG, "Display " + displayId + " hasContent flag changed: "
                            + "hasContent=" + hasContent + ", inTraversal=" + inTraversal);
                }

                display.setHasContentLocked(hasContent);
                scheduleTraversalLocked(inTraversal);
            }
        }
    }

    private void clearViewportsLocked() {
        mDefaultViewport.valid = false;
        mExternalTouchViewport.valid = false;
    }

    private void configureDisplayInTransactionLocked(DisplayDevice device) {
        DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        boolean isPrivate = (info.flags & DisplayDeviceInfo.FLAG_PRIVATE) != 0;

        // Find the logical display that the display device is showing.
        // Private displays never mirror other displays.
        LogicalDisplay display = findLogicalDisplayForDeviceLocked(device);
        if (!isPrivate) {
            if (display != null && !display.hasContentLocked()) {
                // If the display does not have any content of its own, then
                // automatically mirror the default logical display contents.
                display = null;
            }
            if (display == null) {
                display = mLogicalDisplays.get(Display.DEFAULT_DISPLAY);
            }
        }

        // Apply the logical display configuration to the display device.
        if (display == null) {
            // TODO: no logical display for the device, blank it
            Slog.w(TAG, "Missing logical display to use for physical display device: "
                    + device.getDisplayDeviceInfoLocked());
            return;
        }
        boolean isBlanked = (mAllDisplayBlankStateFromPowerManager == DISPLAY_BLANK_STATE_BLANKED)
                && (info.flags & DisplayDeviceInfo.FLAG_NEVER_BLANK) == 0;
        display.configureDisplayInTransactionLocked(device, isBlanked);

        // Update the viewports if needed.
        if (!mDefaultViewport.valid
                && (info.flags & DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY) != 0) {
            setViewportLocked(mDefaultViewport, display, device);
        }
        if (!mExternalTouchViewport.valid
                && info.touch == DisplayDeviceInfo.TOUCH_EXTERNAL) {
            setViewportLocked(mExternalTouchViewport, display, device);
        }
    }

    private static void setViewportLocked(DisplayViewport viewport,
            LogicalDisplay display, DisplayDevice device) {
        viewport.valid = true;
        viewport.displayId = display.getDisplayIdLocked();
        device.populateViewportLocked(viewport);
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
    private void scheduleTraversalLocked(boolean inTraversal) {
        if (!mPendingTraversal && mWindowManagerFuncs != null) {
            mPendingTraversal = true;
            if (!inTraversal) {
                mHandler.sendEmptyMessage(MSG_REQUEST_TRAVERSAL);
            }
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

        synchronized (mSyncRoot) {
            pw.println("  mHeadless=" + mHeadless);
            pw.println("  mOnlyCode=" + mOnlyCore);
            pw.println("  mSafeMode=" + mSafeMode);
            pw.println("  mPendingTraversal=" + mPendingTraversal);
            pw.println("  mAllDisplayBlankStateFromPowerManager="
                    + mAllDisplayBlankStateFromPowerManager);
            pw.println("  mNextNonDefaultDisplayId=" + mNextNonDefaultDisplayId);
            pw.println("  mDefaultViewport=" + mDefaultViewport);
            pw.println("  mExternalTouchViewport=" + mExternalTouchViewport);
            pw.println("  mSingleDisplayDemoMode=" + mSingleDisplayDemoMode);
            pw.println("  mWifiDisplayScanRequestCount=" + mWifiDisplayScanRequestCount);

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

            final int callbackCount = mCallbacks.size();
            pw.println();
            pw.println("Callbacks: size=" + callbackCount);
            for (int i = 0; i < callbackCount; i++) {
                CallbackRecord callback = mCallbacks.valueAt(i);
                pw.println("  " + i + ": mPid=" + callback.mPid
                        + ", mWifiDisplayScanRequested=" + callback.mWifiDisplayScanRequested);
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
         * Request that the window manager call
         * {@link #performTraversalInTransactionFromWindowManager} within a surface
         * transaction at a later time.
         */
        void requestTraversal();
    }

    /**
     * Private interface to the input manager.
     */
    public interface InputManagerFuncs {
        /**
         * Sets information about the displays as needed by the input system.
         * The input system should copy this information if required.
         */
        void setDisplayViewports(DisplayViewport defaultViewport,
                DisplayViewport externalTouchViewport);
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

                case MSG_UPDATE_VIEWPORT: {
                    synchronized (mSyncRoot) {
                        mTempDefaultViewport.copyFrom(mDefaultViewport);
                        mTempExternalTouchViewport.copyFrom(mExternalTouchViewport);
                    }
                    mInputManagerFuncs.setDisplayViewports(
                            mTempDefaultViewport, mTempExternalTouchViewport);
                    break;
                }
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
                scheduleTraversalLocked(false);
            }
        }
    }

    private final class CallbackRecord implements DeathRecipient {
        public final int mPid;
        private final IDisplayManagerCallback mCallback;

        public boolean mWifiDisplayScanRequested;

        public CallbackRecord(int pid, IDisplayManagerCallback callback) {
            mPid = pid;
            mCallback = callback;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Display listener for pid " + mPid + " died.");
            }
            onCallbackDied(this);
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
