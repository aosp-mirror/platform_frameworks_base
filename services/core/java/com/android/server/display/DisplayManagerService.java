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

import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
import static android.hardware.display.DisplayManager
        .VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;

import android.Manifest;
import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Point;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayViewport;
import android.hardware.display.DisplayManagerInternal.DisplayTransactionListener;
import android.hardware.display.IDisplayManager;
import android.hardware.display.IDisplayManagerCallback;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.WifiDisplayStatus;
import android.hardware.input.InputManagerInternal;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.text.TextUtils;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.WindowManagerInternal;

import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.UiThread;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
public final class DisplayManagerService extends SystemService {
    private static final String TAG = "DisplayManagerService";
    private static final boolean DEBUG = false;

    // When this system property is set to 0, WFD is forcibly disabled on boot.
    // When this system property is set to 1, WFD is forcibly enabled on boot.
    // Otherwise WFD is enabled according to the value of config_enableWifiDisplay.
    private static final String FORCE_WIFI_DISPLAY_ENABLE = "persist.debug.wfd.enable";

    private static final long WAIT_FOR_DEFAULT_DISPLAY_TIMEOUT = 10000;

    private static final int MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS = 1;
    private static final int MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS = 2;
    private static final int MSG_DELIVER_DISPLAY_EVENT = 3;
    private static final int MSG_REQUEST_TRAVERSAL = 4;
    private static final int MSG_UPDATE_VIEWPORT = 5;

    private final Context mContext;
    private final DisplayManagerHandler mHandler;
    private final Handler mUiHandler;
    private final DisplayAdapterListener mDisplayAdapterListener;
    private WindowManagerInternal mWindowManagerInternal;
    private InputManagerInternal mInputManagerInternal;
    private IMediaProjectionManager mProjectionService;

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

    // Display power controller.
    private DisplayPowerController mDisplayPowerController;

    // The overall display state, independent of changes that might influence one
    // display or another in particular.
    private int mGlobalDisplayState = Display.STATE_ON;

    // The overall display brightness.
    // For now, this only applies to the built-in display but we may split it up eventually.
    private int mGlobalDisplayBrightness = PowerManager.BRIGHTNESS_DEFAULT;

    // Set to true when there are pending display changes that have yet to be applied
    // to the surface flinger state.
    private boolean mPendingTraversal;

    // The Wifi display adapter, or null if not registered.
    private WifiDisplayAdapter mWifiDisplayAdapter;

    // The number of active wifi display scan requests.
    private int mWifiDisplayScanRequestCount;

    // The virtual display adapter, or null if not registered.
    private VirtualDisplayAdapter mVirtualDisplayAdapter;

    // The stable device screen height and width. These are not tied to a specific display, even
    // the default display, because they need to be stable over the course of the device's entire
    // life, even if the default display changes (e.g. a new monitor is plugged into a PC-like
    // device).
    private Point mStableDisplaySize = new Point();

    // Viewports of the default display and the display that should receive touch
    // input from an external source.  Used by the input system.
    private final DisplayViewport mDefaultViewport = new DisplayViewport();
    private final DisplayViewport mExternalTouchViewport = new DisplayViewport();
    private final ArrayList<DisplayViewport> mVirtualTouchViewports = new ArrayList<>();

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
    private final ArrayList<DisplayViewport> mTempVirtualTouchViewports = new ArrayList<>();

    // The default color mode for default displays. Overrides the usual
    // Display.Display.COLOR_MODE_DEFAULT for displays with the
    // DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY flag set.
    private final int mDefaultDisplayDefaultColorMode;

    // Temporary list of deferred work to perform when setting the display state.
    // Only used by requestDisplayState.  The field is self-synchronized and only
    // intended for use inside of the requestGlobalDisplayStateInternal function.
    private final ArrayList<Runnable> mTempDisplayStateWorkQueue = new ArrayList<Runnable>();

    // Lists of UIDs that are present on the displays. Maps displayId -> array of UIDs.
    private final SparseArray<IntArray> mDisplayAccessUIDs = new SparseArray<>();

    private final Injector mInjector;

    public DisplayManagerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    DisplayManagerService(Context context, Injector injector) {
        super(context);
        mInjector = injector;
        mContext = context;
        mHandler = new DisplayManagerHandler(DisplayThread.get().getLooper());
        mUiHandler = UiThread.getHandler();
        mDisplayAdapterListener = new DisplayAdapterListener();
        mSingleDisplayDemoMode = SystemProperties.getBoolean("persist.demo.singledisplay", false);
        mDefaultDisplayDefaultColorMode = mContext.getResources().getInteger(
            com.android.internal.R.integer.config_defaultDisplayDefaultColorMode);

        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mGlobalDisplayBrightness = pm.getDefaultScreenBrightnessSetting();
    }

    public void setupSchedulerPolicies() {
        // android.display and android.anim is critical to user experience and we should make sure
        // it is not in the default foregroup groups, add it to top-app to make sure it uses all the
        // cores and scheduling settings for top-app when it runs.
        Process.setThreadGroupAndCpuset(DisplayThread.get().getThreadId(),
                Process.THREAD_GROUP_TOP_APP);
        Process.setThreadGroupAndCpuset(AnimationThread.get().getThreadId(),
                Process.THREAD_GROUP_TOP_APP);
    }

    @Override
    public void onStart() {
        // We need to pre-load the persistent data store so it's ready before the default display
        // adapter is up so that we have it's configuration. We could load it lazily, but since
        // we're going to have to read it in eventually we may as well do it here rather than after
        // we've waited for the display to register itself with us.
		synchronized(mSyncRoot) {
			mPersistentDataStore.loadIfNeeded();
			loadStableDisplayValuesLocked();
        }
        mHandler.sendEmptyMessage(MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS);

        publishBinderService(Context.DISPLAY_SERVICE, new BinderService(),
                true /*allowIsolated*/);
        publishLocalService(DisplayManagerInternal.class, new LocalService());
        publishLocalService(DisplayTransformManager.class, new DisplayTransformManager());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_WAIT_FOR_DEFAULT_DISPLAY) {
            synchronized (mSyncRoot) {
                long timeout = SystemClock.uptimeMillis()
                        + mInjector.getDefaultDisplayDelayTimeout();
                while (mLogicalDisplays.get(Display.DEFAULT_DISPLAY) == null ||
                        mVirtualDisplayAdapter == null) {
                    long delay = timeout - SystemClock.uptimeMillis();
                    if (delay <= 0) {
                        throw new RuntimeException("Timeout waiting for default display "
                                + "to be initialized. DefaultDisplay="
                                + mLogicalDisplays.get(Display.DEFAULT_DISPLAY)
                                + ", mVirtualDisplayAdapter=" + mVirtualDisplayAdapter);
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
        }
    }

    // TODO: Use dependencies or a boot phase
    public void windowManagerAndInputReady() {
        synchronized (mSyncRoot) {
            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
            mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
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

    @VisibleForTesting
    Handler getDisplayHandler() {
        return mHandler;
    }

    private void loadStableDisplayValuesLocked() {
        final Point size = mPersistentDataStore.getStableDisplaySize();
        if (size.x > 0 && size.y > 0) {
            // Just set these values directly so we don't write the display persistent data again
            // unnecessarily
            mStableDisplaySize.set(size.x, size.y);
        } else {
            final Resources res = mContext.getResources();
            final int width = res.getInteger(
                    com.android.internal.R.integer.config_stableDeviceDisplayWidth);
            final int height = res.getInteger(
                    com.android.internal.R.integer.config_stableDeviceDisplayHeight);
            if (width > 0 && height > 0) {
                setStableDisplaySizeLocked(width, height);
            }
        }
    }

    private Point getStableDisplaySizeInternal() {
        Point r = new Point();
        synchronized (mSyncRoot) {
            if (mStableDisplaySize.x > 0 && mStableDisplaySize.y > 0) {
                r.set(mStableDisplaySize.x, mStableDisplaySize.y);
            }
        }
        return r;
    }

    private void registerDisplayTransactionListenerInternal(
            DisplayTransactionListener listener) {
        // List is self-synchronized copy-on-write.
        mDisplayTransactionListeners.add(listener);
    }

    private void unregisterDisplayTransactionListenerInternal(
            DisplayTransactionListener listener) {
        // List is self-synchronized copy-on-write.
        mDisplayTransactionListeners.remove(listener);
    }

    private void setDisplayInfoOverrideFromWindowManagerInternal(
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
     * @see DisplayManagerInternal#getNonOverrideDisplayInfo(int, DisplayInfo)
     */
    private void getNonOverrideDisplayInfoInternal(int displayId, DisplayInfo outInfo) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display != null) {
                display.getNonOverrideDisplayInfoLocked(outInfo);
            }
        }
    }

    @VisibleForTesting
    void performTraversalInTransactionFromWindowManagerInternal() {
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

    private void requestGlobalDisplayStateInternal(int state, int brightness) {
        if (state == Display.STATE_UNKNOWN) {
            state = Display.STATE_ON;
        }
        if (state == Display.STATE_OFF) {
            brightness = PowerManager.BRIGHTNESS_OFF;
        } else if (brightness < 0) {
            brightness = PowerManager.BRIGHTNESS_DEFAULT;
        } else if (brightness > PowerManager.BRIGHTNESS_ON) {
            brightness = PowerManager.BRIGHTNESS_ON;
        }

        synchronized (mTempDisplayStateWorkQueue) {
            try {
                // Update the display state within the lock.
                // Note that we do not need to schedule traversals here although it
                // may happen as a side-effect of displays changing state.
                synchronized (mSyncRoot) {
                    if (mGlobalDisplayState == state
                            && mGlobalDisplayBrightness == brightness) {
                        return; // no change
                    }

                    Trace.traceBegin(Trace.TRACE_TAG_POWER, "requestGlobalDisplayState("
                            + Display.stateToString(state)
                            + ", brightness=" + brightness + ")");
                    mGlobalDisplayState = state;
                    mGlobalDisplayBrightness = brightness;
                    applyGlobalDisplayStateLocked(mTempDisplayStateWorkQueue);
                }

                // Setting the display power state can take hundreds of milliseconds
                // to complete so we defer the most expensive part of the work until
                // after we have exited the critical section to avoid blocking other
                // threads for a long time.
                for (int i = 0; i < mTempDisplayStateWorkQueue.size(); i++) {
                    mTempDisplayStateWorkQueue.get(i).run();
                }
                Trace.traceEnd(Trace.TRACE_TAG_POWER);
            } finally {
                mTempDisplayStateWorkQueue.clear();
            }
        }
    }

    private DisplayInfo getDisplayInfoInternal(int displayId, int callingUid) {
        synchronized (mSyncRoot) {
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display != null) {
                DisplayInfo info = display.getDisplayInfoLocked();
                if (info.hasAccess(callingUid)
                        || isUidPresentOnDisplayInternal(callingUid, displayId)) {
                    return info;
                }
            }
            return null;
        }
    }

    private int[] getDisplayIdsInternal(int callingUid) {
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
    }

    private void registerCallbackInternal(IDisplayManagerCallback callback, int callingPid) {
        synchronized (mSyncRoot) {
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

    private void startWifiDisplayScanInternal(int callingPid) {
        synchronized (mSyncRoot) {
            CallbackRecord record = mCallbacks.get(callingPid);
            if (record == null) {
                throw new IllegalStateException("The calling process has not "
                        + "registered an IDisplayManagerCallback.");
            }
            startWifiDisplayScanLocked(record);
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

    private void stopWifiDisplayScanInternal(int callingPid) {
        synchronized (mSyncRoot) {
            CallbackRecord record = mCallbacks.get(callingPid);
            if (record == null) {
                throw new IllegalStateException("The calling process has not "
                        + "registered an IDisplayManagerCallback.");
            }
            stopWifiDisplayScanLocked(record);
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
                Slog.wtf(TAG, "mWifiDisplayScanRequestCount became negative: "
                        + mWifiDisplayScanRequestCount);
                mWifiDisplayScanRequestCount = 0;
            }
        }
    }

    private void connectWifiDisplayInternal(String address) {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestConnectLocked(address);
            }
        }
    }

    private void pauseWifiDisplayInternal() {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestPauseLocked();
            }
        }
    }

    private void resumeWifiDisplayInternal() {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestResumeLocked();
            }
        }
    }

    private void disconnectWifiDisplayInternal() {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestDisconnectLocked();
            }
        }
    }

    private void renameWifiDisplayInternal(String address, String alias) {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestRenameLocked(address, alias);
            }
        }
    }

    private void forgetWifiDisplayInternal(String address) {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                mWifiDisplayAdapter.requestForgetLocked(address);
            }
        }
    }

    private WifiDisplayStatus getWifiDisplayStatusInternal() {
        synchronized (mSyncRoot) {
            if (mWifiDisplayAdapter != null) {
                return mWifiDisplayAdapter.getWifiDisplayStatusLocked();
            }
            return new WifiDisplayStatus();
        }
    }

    private void requestColorModeInternal(int displayId, int colorMode) {
        synchronized (mSyncRoot) {
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display != null &&
                    display.getRequestedColorModeLocked() != colorMode) {
                display.setRequestedColorModeLocked(colorMode);
                scheduleTraversalLocked(false);
            }
        }
    }

    private int createVirtualDisplayInternal(IVirtualDisplayCallback callback,
            IMediaProjection projection, int callingUid, String packageName, String name, int width,
            int height, int densityDpi, Surface surface, int flags, String uniqueId) {
        synchronized (mSyncRoot) {
            if (mVirtualDisplayAdapter == null) {
                Slog.w(TAG, "Rejecting request to create private virtual display "
                        + "because the virtual display adapter is not available.");
                return -1;
            }

            DisplayDevice device = mVirtualDisplayAdapter.createVirtualDisplayLocked(
                    callback, projection, callingUid, packageName, name, width, height, densityDpi,
                    surface, flags, uniqueId);
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
            mVirtualDisplayAdapter.releaseVirtualDisplayLocked(callback.asBinder());
            handleDisplayDeviceRemovedLocked(device);
        }
        return -1;
    }

    private void resizeVirtualDisplayInternal(IBinder appToken,
            int width, int height, int densityDpi) {
        synchronized (mSyncRoot) {
            if (mVirtualDisplayAdapter == null) {
                return;
            }

            mVirtualDisplayAdapter.resizeVirtualDisplayLocked(appToken, width, height, densityDpi);
        }
    }

    private void setVirtualDisplaySurfaceInternal(IBinder appToken, Surface surface) {
        synchronized (mSyncRoot) {
            if (mVirtualDisplayAdapter == null) {
                return;
            }

            mVirtualDisplayAdapter.setVirtualDisplaySurfaceLocked(appToken, surface);
        }
    }

    private void releaseVirtualDisplayInternal(IBinder appToken) {
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
    }

    private void registerDefaultDisplayAdapters() {
        // Register default display adapters.
        synchronized (mSyncRoot) {
            // main display adapter
            registerDisplayAdapterLocked(new LocalDisplayAdapter(
                    mSyncRoot, mContext, mHandler, mDisplayAdapterListener));

            // Standalone VR devices rely on a virtual display as their primary display for
            // 2D UI. We register virtual display adapter along side the main display adapter
            // here so that it is ready by the time the system sends the home Intent for
            // early apps like SetupWizard/Launcher. In particular, SUW is displayed using
            // the virtual display inside VR before any VR-specific apps even run.
            mVirtualDisplayAdapter = mInjector.getVirtualDisplayAdapter(mSyncRoot, mContext,
                    mHandler, mDisplayAdapterListener);
            if (mVirtualDisplayAdapter != null) {
                registerDisplayAdapterLocked(mVirtualDisplayAdapter);
            }
        }
    }

    private void registerAdditionalDisplayAdapters() {
        synchronized (mSyncRoot) {
            if (shouldRegisterNonEssentialDisplayAdaptersLocked()) {
                registerOverlayDisplayAdapterLocked();
                registerWifiDisplayAdapterLocked();
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
        DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        if (mDisplayDevices.contains(device)) {
            Slog.w(TAG, "Attempted to add already added display device: " + info);
            return;
        }

        Slog.i(TAG, "Display device added: " + info);
        device.mDebugLastLoggedDeviceInfo = info;

        mDisplayDevices.add(device);
        LogicalDisplay display = addLogicalDisplayLocked(device);
        Runnable work = updateDisplayStateLocked(device);
        if (work != null) {
            work.run();
        }
        scheduleTraversalLocked(false);
    }

    private void handleDisplayDeviceChanged(DisplayDevice device) {
        synchronized (mSyncRoot) {
            DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
            if (!mDisplayDevices.contains(device)) {
                Slog.w(TAG, "Attempted to change non-existent display device: " + info);
                return;
            }

            int diff = device.mDebugLastLoggedDeviceInfo.diff(info);
            if (diff == DisplayDeviceInfo.DIFF_STATE) {
                Slog.i(TAG, "Display device changed state: \"" + info.name
                        + "\", " + Display.stateToString(info.state));
            } else if (diff != 0) {
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
        DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        if (!mDisplayDevices.remove(device)) {
            Slog.w(TAG, "Attempted to remove non-existent display device: " + info);
            return;
        }

        Slog.i(TAG, "Display device removed: " + info);
        device.mDebugLastLoggedDeviceInfo = info;

        updateLogicalDisplaysLocked();
        scheduleTraversalLocked(false);
    }

    private void applyGlobalDisplayStateLocked(List<Runnable> workQueue) {
        final int count = mDisplayDevices.size();
        for (int i = 0; i < count; i++) {
            DisplayDevice device = mDisplayDevices.get(i);
            Runnable runnable = updateDisplayStateLocked(device);
            if (runnable != null) {
                workQueue.add(runnable);
            }
        }
    }

    private Runnable updateDisplayStateLocked(DisplayDevice device) {
        // Blank or unblank the display immediately to match the state requested
        // by the display power controller (if known).
        DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        if ((info.flags & DisplayDeviceInfo.FLAG_NEVER_BLANK) == 0) {
            return device.requestDisplayStateLocked(mGlobalDisplayState, mGlobalDisplayBrightness);
        }
        return null;
    }

    // Adds a new logical display based on the given display device.
    // Sends notifications if needed.
    private LogicalDisplay addLogicalDisplayLocked(DisplayDevice device) {
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
            return null;
        }

        final int displayId = assignDisplayIdLocked(isDefault);
        final int layerStack = assignLayerStackLocked(displayId);

        LogicalDisplay display = new LogicalDisplay(displayId, layerStack, device);
        display.updateLocked(mDisplayDevices);
        if (!display.isValidLocked()) {
            // This should never happen currently.
            Slog.w(TAG, "Ignoring display device because the logical display "
                    + "created from it was not considered valid: " + deviceInfo);
            return null;
        }

        configureColorModeLocked(display, device);
        if (isDefault) {
            recordStableDisplayStatsIfNeededLocked(display);
        }

        mLogicalDisplays.put(displayId, display);

        // Wake up waitForDefaultDisplay.
        if (isDefault) {
            mSyncRoot.notifyAll();
        }

        sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);
        return display;
    }

    private int assignDisplayIdLocked(boolean isDefault) {
        return isDefault ? Display.DEFAULT_DISPLAY : mNextNonDefaultDisplayId++;
    }

    private int assignLayerStackLocked(int displayId) {
        // Currently layer stacks and display ids are the same.
        // This need not be the case.
        return displayId;
    }

    private void configureColorModeLocked(LogicalDisplay display, DisplayDevice device) {
        if (display.getPrimaryDisplayDeviceLocked() == device) {
            int colorMode = mPersistentDataStore.getColorMode(device);
            if (colorMode == Display.COLOR_MODE_INVALID) {
                if ((device.getDisplayDeviceInfoLocked().flags
                     & DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY) != 0) {
                    colorMode = mDefaultDisplayDefaultColorMode;
                } else {
                    colorMode = Display.COLOR_MODE_DEFAULT;
                }
            }
            display.setRequestedColorModeLocked(colorMode);
        }
    }

    // If we've never recorded stable device stats for this device before and they aren't
    // explicitly configured, go ahead and record the stable device stats now based on the status
    // of the default display at first boot.
    private void recordStableDisplayStatsIfNeededLocked(LogicalDisplay d) {
        if (mStableDisplaySize.x <= 0 && mStableDisplaySize.y <= 0) {
            DisplayInfo info = d.getDisplayInfoLocked();
            setStableDisplaySizeLocked(info.getNaturalWidth(), info.getNaturalHeight());
        }
    }

    private void setStableDisplaySizeLocked(int width, int height) {
        mStableDisplaySize = new Point(width, height);
        try {
            mPersistentDataStore.setStableDisplaySize(mStableDisplaySize);
        } finally {
            mPersistentDataStore.saveIfNeeded();
        }
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
        if (mInputManagerInternal != null) {
            mHandler.sendEmptyMessage(MSG_UPDATE_VIEWPORT);
        }
    }

    private void setDisplayPropertiesInternal(int displayId, boolean hasContent,
            float requestedRefreshRate, int requestedModeId, boolean inTraversal) {
        synchronized (mSyncRoot) {
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display == null) {
                return;
            }
            if (display.hasContentLocked() != hasContent) {
                if (DEBUG) {
                    Slog.d(TAG, "Display " + displayId + " hasContent flag changed: "
                            + "hasContent=" + hasContent + ", inTraversal=" + inTraversal);
                }

                display.setHasContentLocked(hasContent);
                scheduleTraversalLocked(inTraversal);
            }
            if (requestedModeId == 0 && requestedRefreshRate != 0) {
                // Scan supported modes returned by display.getInfo() to find a mode with the same
                // size as the default display mode but with the specified refresh rate instead.
                requestedModeId = display.getDisplayInfoLocked().findDefaultModeByRefreshRate(
                        requestedRefreshRate);
            }
            if (display.getRequestedModeIdLocked() != requestedModeId) {
                if (DEBUG) {
                    Slog.d(TAG, "Display " + displayId + " switching to mode " + requestedModeId);
                }
                display.setRequestedModeIdLocked(requestedModeId);
                scheduleTraversalLocked(inTraversal);
            }
        }
    }

    private void setDisplayOffsetsInternal(int displayId, int x, int y) {
        synchronized (mSyncRoot) {
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display == null) {
                return;
            }
            if (display.getDisplayOffsetXLocked() != x
                    || display.getDisplayOffsetYLocked() != y) {
                if (DEBUG) {
                    Slog.d(TAG, "Display " + displayId + " burn-in offset set to ("
                            + x + ", " + y + ")");
                }
                display.setDisplayOffsetsLocked(x, y);
                scheduleTraversalLocked(false);
            }
        }
    }

    // Updates the lists of UIDs that are present on displays.
    private void setDisplayAccessUIDsInternal(SparseArray<IntArray> newDisplayAccessUIDs) {
        synchronized (mSyncRoot) {
            mDisplayAccessUIDs.clear();
            for (int i = newDisplayAccessUIDs.size() - 1; i >= 0; i--) {
                mDisplayAccessUIDs.append(newDisplayAccessUIDs.keyAt(i),
                        newDisplayAccessUIDs.valueAt(i));
            }
        }
    }

    // Checks if provided UID's content is present on the display and UID has access to it.
    private boolean isUidPresentOnDisplayInternal(int uid, int displayId) {
        synchronized (mSyncRoot) {
            final IntArray displayUIDs = mDisplayAccessUIDs.get(displayId);
            return displayUIDs != null && displayUIDs.indexOf(uid) != -1;
        }
    }

    private void clearViewportsLocked() {
        mDefaultViewport.valid = false;
        mExternalTouchViewport.valid = false;
        mVirtualTouchViewports.clear();
    }

    private void configureDisplayInTransactionLocked(DisplayDevice device) {
        final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        final boolean ownContent = (info.flags & DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY) != 0;

        // Find the logical display that the display device is showing.
        // Certain displays only ever show their own content.
        LogicalDisplay display = findLogicalDisplayForDeviceLocked(device);
        if (!ownContent) {
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
        display.configureDisplayInTransactionLocked(device, info.state == Display.STATE_OFF);

        // Update the viewports if needed.
        if (!mDefaultViewport.valid
                && (info.flags & DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY) != 0) {
            setViewportLocked(mDefaultViewport, display, device);
        }
        if (!mExternalTouchViewport.valid
                && info.touch == DisplayDeviceInfo.TOUCH_EXTERNAL) {
            setViewportLocked(mExternalTouchViewport, display, device);
        }

        if (info.touch == DisplayDeviceInfo.TOUCH_VIRTUAL && !TextUtils.isEmpty(info.uniqueId)) {
            final DisplayViewport viewport = getVirtualTouchViewportLocked(info.uniqueId);
            setViewportLocked(viewport, display, device);
        }
    }

    /** Gets the virtual device viewport or creates it if not yet created. */
    private DisplayViewport getVirtualTouchViewportLocked(@NonNull String uniqueId) {
        DisplayViewport viewport;
        final int count = mVirtualTouchViewports.size();
        for (int i = 0; i < count; i++) {
            viewport = mVirtualTouchViewports.get(i);
            if (uniqueId.equals(viewport.uniqueId)) {
                return viewport;
            }
        }

        viewport = new DisplayViewport();
        viewport.uniqueId = uniqueId;
        mVirtualTouchViewports.add(viewport);
        return viewport;
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
        if (!mPendingTraversal && mWindowManagerInternal != null) {
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

    private IMediaProjectionManager getProjectionService() {
        if (mProjectionService == null) {
            IBinder b = ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE);
            mProjectionService = IMediaProjectionManager.Stub.asInterface(b);
        }
        return mProjectionService;
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("DISPLAY MANAGER (dumpsys display)");

        synchronized (mSyncRoot) {
            pw.println("  mOnlyCode=" + mOnlyCore);
            pw.println("  mSafeMode=" + mSafeMode);
            pw.println("  mPendingTraversal=" + mPendingTraversal);
            pw.println("  mGlobalDisplayState=" + Display.stateToString(mGlobalDisplayState));
            pw.println("  mNextNonDefaultDisplayId=" + mNextNonDefaultDisplayId);
            pw.println("  mDefaultViewport=" + mDefaultViewport);
            pw.println("  mExternalTouchViewport=" + mExternalTouchViewport);
            pw.println("  mVirtualTouchViewports=" + mVirtualTouchViewports);
            pw.println("  mDefaultDisplayDefaultColorMode=" + mDefaultDisplayDefaultColorMode);
            pw.println("  mSingleDisplayDemoMode=" + mSingleDisplayDemoMode);
            pw.println("  mWifiDisplayScanRequestCount=" + mWifiDisplayScanRequestCount);
            pw.println("  mStableDisplaySize=" + mStableDisplaySize);


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

            if (mDisplayPowerController != null) {
                mDisplayPowerController.dump(pw);
            }

            pw.println();
            mPersistentDataStore.dump(pw);
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

    @VisibleForTesting
    static class Injector {
        VirtualDisplayAdapter getVirtualDisplayAdapter(SyncRoot syncRoot, Context context,
                Handler handler, DisplayAdapter.Listener displayAdapterListener) {
            return new VirtualDisplayAdapter(syncRoot, context, handler, displayAdapterListener);
        }

        long getDefaultDisplayDelayTimeout() {
            return WAIT_FOR_DEFAULT_DISPLAY_TIMEOUT;
        }
    }

    @VisibleForTesting
    DisplayDeviceInfo getDisplayDeviceInfoInternal(int displayId) {
        synchronized (mSyncRoot) {
            LogicalDisplay display = mLogicalDisplays.get(displayId);
            if (display != null) {
                DisplayDevice displayDevice = display.getPrimaryDisplayDeviceLocked();
                return displayDevice.getDisplayDeviceInfoLocked();
            }
            return null;
        }
    }

    private final class DisplayManagerHandler extends Handler {
        public DisplayManagerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS:
                    registerDefaultDisplayAdapters();
                    break;

                case MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS:
                    registerAdditionalDisplayAdapters();
                    break;

                case MSG_DELIVER_DISPLAY_EVENT:
                    deliverDisplayEvent(msg.arg1, msg.arg2);
                    break;

                case MSG_REQUEST_TRAVERSAL:
                    mWindowManagerInternal.requestTraversalFromDisplayManager();
                    break;

                case MSG_UPDATE_VIEWPORT: {
                    synchronized (mSyncRoot) {
                        mTempDefaultViewport.copyFrom(mDefaultViewport);
                        mTempExternalTouchViewport.copyFrom(mExternalTouchViewport);
                        if (!mTempVirtualTouchViewports.equals(mVirtualTouchViewports)) {
                          mTempVirtualTouchViewports.clear();
                          for (DisplayViewport d : mVirtualTouchViewports) {
                              mTempVirtualTouchViewports.add(d.makeCopy());
                          }
                        }
                    }
                    mInputManagerInternal.setDisplayViewports(mTempDefaultViewport,
                            mTempExternalTouchViewport, mTempVirtualTouchViewports);
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

    @VisibleForTesting
    final class BinderService extends IDisplayManager.Stub {
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
                return getDisplayInfoInternal(displayId, callingUid);
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
                return getDisplayIdsInternal(callingUid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Returns the stable device display size, in pixels.
         */
        @Override // Binder call
        public Point getStableDisplaySize() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getStableDisplaySizeInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void registerCallback(IDisplayManagerCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("listener must not be null");
            }

            final int callingPid = Binder.getCallingPid();
            final long token = Binder.clearCallingIdentity();
            try {
                registerCallbackInternal(callback, callingPid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void startWifiDisplayScan() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    "Permission required to start wifi display scans");

            final int callingPid = Binder.getCallingPid();
            final long token = Binder.clearCallingIdentity();
            try {
                startWifiDisplayScanInternal(callingPid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void stopWifiDisplayScan() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    "Permission required to stop wifi display scans");

            final int callingPid = Binder.getCallingPid();
            final long token = Binder.clearCallingIdentity();
            try {
                stopWifiDisplayScanInternal(callingPid);
            } finally {
                Binder.restoreCallingIdentity(token);
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
                connectWifiDisplayInternal(address);
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
                disconnectWifiDisplayInternal();
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
                renameWifiDisplayInternal(address, alias);
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
                forgetWifiDisplayInternal(address);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void pauseWifiDisplay() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    "Permission required to pause a wifi display session");

            final long token = Binder.clearCallingIdentity();
            try {
                pauseWifiDisplayInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void resumeWifiDisplay() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    "Permission required to resume a wifi display session");

            final long token = Binder.clearCallingIdentity();
            try {
                resumeWifiDisplayInternal();
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
                return getWifiDisplayStatusInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void requestColorMode(int displayId, int colorMode) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONFIGURE_DISPLAY_COLOR_MODE,
                    "Permission required to change the display color mode");
            final long token = Binder.clearCallingIdentity();
            try {
                requestColorModeInternal(displayId, colorMode);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public int createVirtualDisplay(IVirtualDisplayCallback callback,
                IMediaProjection projection, String packageName, String name,
                int width, int height, int densityDpi, Surface surface, int flags,
                String uniqueId) {
            final int callingUid = Binder.getCallingUid();
            if (!validatePackageName(callingUid, packageName)) {
                throw new SecurityException("packageName must match the calling uid");
            }
            if (callback == null) {
                throw new IllegalArgumentException("appToken must not be null");
            }
            if (TextUtils.isEmpty(name)) {
                throw new IllegalArgumentException("name must be non-null and non-empty");
            }
            if (width <= 0 || height <= 0 || densityDpi <= 0) {
                throw new IllegalArgumentException("width, height, and densityDpi must be "
                        + "greater than 0");
            }
            if (surface != null && surface.isSingleBuffered()) {
                throw new IllegalArgumentException("Surface can't be single-buffered");
            }

            if ((flags & VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) {
                flags |= VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;

                // Public displays can't be allowed to show content when locked.
                if ((flags & VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD) != 0) {
                    throw new IllegalArgumentException(
                            "Public display must not be marked as SHOW_WHEN_LOCKED_INSECURE");
                }
            }
            if ((flags & VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) != 0) {
                flags &= ~VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
            }

            if (projection != null) {
                try {
                    if (!getProjectionService().isValidMediaProjection(projection)) {
                        throw new SecurityException("Invalid media projection");
                    }
                    flags = projection.applyVirtualDisplayFlags(flags);
                } catch (RemoteException e) {
                    throw new SecurityException("unable to validate media projection or flags");
                }
            }

            if (callingUid != Process.SYSTEM_UID &&
                    (flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0) {
                if (!canProjectVideo(projection)) {
                    throw new SecurityException("Requires CAPTURE_VIDEO_OUTPUT or "
                            + "CAPTURE_SECURE_VIDEO_OUTPUT permission, or an appropriate "
                            + "MediaProjection token in order to create a screen sharing virtual "
                            + "display.");
                }
            }
            if ((flags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0) {
                if (!canProjectSecureVideo(projection)) {
                    throw new SecurityException("Requires CAPTURE_SECURE_VIDEO_OUTPUT "
                            + "or an appropriate MediaProjection token to create a "
                            + "secure virtual display.");
                }
            }

            final long token = Binder.clearCallingIdentity();
            try {
                return createVirtualDisplayInternal(callback, projection, callingUid, packageName,
                        name, width, height, densityDpi, surface, flags, uniqueId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void resizeVirtualDisplay(IVirtualDisplayCallback callback,
                int width, int height, int densityDpi) {
            final long token = Binder.clearCallingIdentity();
            try {
                resizeVirtualDisplayInternal(callback.asBinder(), width, height, densityDpi);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void setVirtualDisplaySurface(IVirtualDisplayCallback callback, Surface surface) {
            if (surface != null && surface.isSingleBuffered()) {
                throw new IllegalArgumentException("Surface can't be single-buffered");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                setVirtualDisplaySurfaceInternal(callback.asBinder(), surface);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void releaseVirtualDisplay(IVirtualDisplayCallback callback) {
            final long token = Binder.clearCallingIdentity();
            try {
                releaseVirtualDisplayInternal(callback.asBinder());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            final long token = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
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

        private boolean canProjectVideo(IMediaProjection projection) {
            if (projection != null) {
                try {
                    if (projection.canProjectVideo()) {
                        return true;
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to query projection service for permissions", e);
                }
            }
            if (mContext.checkCallingPermission(
                    android.Manifest.permission.CAPTURE_VIDEO_OUTPUT)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
            return canProjectSecureVideo(projection);
        }

        private boolean canProjectSecureVideo(IMediaProjection projection) {
            if (projection != null) {
                try {
                    if (projection.canProjectSecureVideo()){
                        return true;
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to query projection service for permissions", e);
                }
            }
            return mContext.checkCallingPermission(
                    android.Manifest.permission.CAPTURE_SECURE_VIDEO_OUTPUT)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private final class LocalService extends DisplayManagerInternal {
        @Override
        public void initPowerManagement(final DisplayPowerCallbacks callbacks, Handler handler,
                SensorManager sensorManager) {
            synchronized (mSyncRoot) {
                DisplayBlanker blanker = new DisplayBlanker() {
                    @Override
                    public void requestDisplayState(int state, int brightness) {
                        // The order of operations is important for legacy reasons.
                        if (state == Display.STATE_OFF) {
                            requestGlobalDisplayStateInternal(state, brightness);
                        }

                        callbacks.onDisplayStateChange(state);

                        if (state != Display.STATE_OFF) {
                            requestGlobalDisplayStateInternal(state, brightness);
                        }
                    }
                };
                mDisplayPowerController = new DisplayPowerController(
                        mContext, callbacks, handler, sensorManager, blanker);
            }
        }

        @Override
        public boolean requestPowerState(DisplayPowerRequest request,
                boolean waitForNegativeProximity) {
            return mDisplayPowerController.requestPowerState(request,
                    waitForNegativeProximity);
        }

        @Override
        public boolean isProximitySensorAvailable() {
            return mDisplayPowerController.isProximitySensorAvailable();
        }

        @Override
        public DisplayInfo getDisplayInfo(int displayId) {
            return getDisplayInfoInternal(displayId, Process.myUid());
        }

        @Override
        public void registerDisplayTransactionListener(DisplayTransactionListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("listener must not be null");
            }

            registerDisplayTransactionListenerInternal(listener);
        }

        @Override
        public void unregisterDisplayTransactionListener(DisplayTransactionListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("listener must not be null");
            }

            unregisterDisplayTransactionListenerInternal(listener);
        }

        @Override
        public void setDisplayInfoOverrideFromWindowManager(int displayId, DisplayInfo info) {
            setDisplayInfoOverrideFromWindowManagerInternal(displayId, info);
        }

        @Override
        public void getNonOverrideDisplayInfo(int displayId, DisplayInfo outInfo) {
            getNonOverrideDisplayInfoInternal(displayId, outInfo);
        }

        @Override
        public void performTraversalInTransactionFromWindowManager() {
            performTraversalInTransactionFromWindowManagerInternal();
        }

        @Override
        public void setDisplayProperties(int displayId, boolean hasContent,
                float requestedRefreshRate, int requestedMode, boolean inTraversal) {
            setDisplayPropertiesInternal(displayId, hasContent, requestedRefreshRate,
                    requestedMode, inTraversal);
        }

        @Override
        public void setDisplayOffsets(int displayId, int x, int y) {
            setDisplayOffsetsInternal(displayId, x, y);
        }

        @Override
        public void setDisplayAccessUIDs(SparseArray<IntArray> newDisplayAccessUIDs) {
            setDisplayAccessUIDsInternal(newDisplayAccessUIDs);
        }

        @Override
        public boolean isUidPresentOnDisplay(int uid, int displayId) {
            return isUidPresentOnDisplayInternal(uid, displayId);
        }
    }
}
