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

import static android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY;
import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.Manifest.permission.CAPTURE_SECURE_VIDEO_OUTPUT;
import static android.Manifest.permission.CAPTURE_VIDEO_OUTPUT;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.MANAGE_DISPLAYS;
import static android.Manifest.permission.RESTRICT_DISPLAY_MODES;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;
import static android.hardware.display.DisplayManager.EventsMask;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED;
import static android.hardware.display.DisplayManagerGlobal.DisplayEvent;
import static android.hardware.display.DisplayViewport.VIEWPORT_EXTERNAL;
import static android.hardware.display.DisplayViewport.VIEWPORT_INTERNAL;
import static android.hardware.display.DisplayViewport.VIEWPORT_VIRTUAL;
import static android.hardware.display.HdrConversionMode.HDR_CONVERSION_UNSUPPORTED;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.ROOT_UID;
import static android.provider.Settings.Secure.RESOLUTION_MODE_FULL;
import static android.provider.Settings.Secure.RESOLUTION_MODE_HIGH;
import static android.provider.Settings.Secure.RESOLUTION_MODE_UNKNOWN;

import android.Manifest;
import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.compat.CompatChanges;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.flags.Flags;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.hardware.OverlayProperties;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateManagerInternal;
import android.hardware.display.AmbientBrightnessDayStats;
import android.hardware.display.BrightnessChangeEvent;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.Curve;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayGroupListener;
import android.hardware.display.DisplayManagerInternal.DisplayTransactionListener;
import android.hardware.display.DisplayViewport;
import android.hardware.display.DisplayedContentSample;
import android.hardware.display.DisplayedContentSamplingAttributes;
import android.hardware.display.HdrConversionMode;
import android.hardware.display.IDisplayManager;
import android.hardware.display.IDisplayManagerCallback;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.display.WifiDisplayStatus;
import android.hardware.graphics.common.DisplayDecorationSupport;
import android.hardware.input.HostUsiVersion;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IThermalService;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfigInterface;
import android.provider.Settings;
import android.sysprop.DisplayProperties;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.IntArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.Spline;
import android.view.ContentRecordingSession;
import android.view.Display;
import android.view.DisplayEventReceiver;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.RefreshRateRange;
import android.window.DisplayWindowPolicyController;
import android.window.ScreenCapture;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.foldables.FoldGracePeriodProvider;
import com.android.internal.foldables.FoldLockSettingAvailabilityProvider;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.SettingsWrapper;
import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.UiThread;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.display.config.SensorData;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.layout.Layout;
import com.android.server.display.mode.DisplayModeDirector;
import com.android.server.display.notifications.DisplayNotificationManager;
import com.android.server.display.utils.DebugUtils;
import com.android.server.display.utils.SensorUtils;
import com.android.server.input.InputManagerInternal;
import com.android.server.utils.FoldSettingProvider;
import com.android.server.wm.SurfaceAnimationThread;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;


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
 * one display adapter for physical displays, one for simulated non-functional
 * displays when the system is headless, one for simulated overlay displays used for
 * development, one for wifi displays, etc.
 * </p><p>
 * Display adapters are only weakly coupled to the display manager service.
 * Display adapters communicate changes in display device state to the display manager
 * service asynchronously via a {@link DisplayAdapter.Listener}, and through
 * the {@link DisplayDeviceRepository.Listener}, which is ultimately registered
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
@SuppressWarnings("MissingPermission")
public final class DisplayManagerService extends SystemService {
    private static final String TAG = "DisplayManagerService";

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.DisplayManagerService DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    // When this system property is set to 0, WFD is forcibly disabled on boot.
    // When this system property is set to 1, WFD is forcibly enabled on boot.
    // Otherwise WFD is enabled according to the value of config_enableWifiDisplay.
    private static final String FORCE_WIFI_DISPLAY_ENABLE = "persist.debug.wfd.enable";

    private static final String PROP_DEFAULT_DISPLAY_TOP_INSET = "persist.sys.displayinset.top";

    private static final long WAIT_FOR_DEFAULT_DISPLAY_TIMEOUT = 10000;
    // This value needs to be in sync with the threshold
    // in RefreshRateConfigs::getFrameRateDivisor.
    private static final float THRESHOLD_FOR_REFRESH_RATES_DIVISORS = 0.0009f;

    private static final int MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS = 1;
    private static final int MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS = 2;
    private static final int MSG_DELIVER_DISPLAY_EVENT = 3;
    private static final int MSG_REQUEST_TRAVERSAL = 4;
    private static final int MSG_UPDATE_VIEWPORT = 5;
    private static final int MSG_LOAD_BRIGHTNESS_CONFIGURATIONS = 6;
    private static final int MSG_DELIVER_DISPLAY_EVENT_FRAME_RATE_OVERRIDE = 7;
    private static final int MSG_DELIVER_DISPLAY_GROUP_EVENT = 8;
    private static final int MSG_RECEIVED_DEVICE_STATE = 9;
    private static final int[] EMPTY_ARRAY = new int[0];
    private static final HdrConversionMode HDR_CONVERSION_MODE_UNSUPPORTED = new HdrConversionMode(
            HDR_CONVERSION_UNSUPPORTED);

    private final Context mContext;
    private final DisplayManagerHandler mHandler;
    private final Handler mUiHandler;
    private final DisplayModeDirector mDisplayModeDirector;
    private final ExternalDisplayPolicy mExternalDisplayPolicy;
    private WindowManagerInternal mWindowManagerInternal;
    private InputManagerInternal mInputManagerInternal;
    private ActivityManagerInternal mActivityManagerInternal;
    private final UidImportanceListener mUidImportanceListener = new UidImportanceListener();
    @Nullable
    private IMediaProjectionManager mProjectionService;
    private DeviceStateManagerInternal mDeviceStateManager;
    @GuardedBy("mSyncRoot")
    private int[] mUserDisabledHdrTypes = {};
    @Display.HdrCapabilities.HdrType
    private int[] mSupportedHdrOutputType;
    @GuardedBy("mSyncRoot")
    private boolean mAreUserDisabledHdrTypesAllowed = true;

    // This value indicates whether or not HDR output control is enabled.
    // It is read from DeviceConfig and is updated via a listener if the config changes.
    private volatile boolean mIsHdrOutputControlEnabled;

    // Display mode chosen by user.
    private Display.Mode mUserPreferredMode;
    @HdrConversionMode.ConversionMode
    private final int mDefaultHdrConversionMode;
    // HDR conversion mode chosen by user
    @GuardedBy("mSyncRoot")
    private HdrConversionMode mHdrConversionMode = null;
    // Actual HDR conversion mode, which takes app overrides into account.
    private HdrConversionMode mOverrideHdrConversionMode = null;
    @GuardedBy("mSyncRoot")
    private int mSystemPreferredHdrOutputType = Display.HdrCapabilities.HDR_TYPE_INVALID;


    // The synchronization root for the display manager.
    // This lock guards most of the display manager's state.
    // NOTE: This is synchronized on while holding WindowManagerService.mWindowMap so never call
    // into WindowManagerService methods that require mWindowMap while holding this unless you are
    // very very sure that no deadlock can occur.
    private final SyncRoot mSyncRoot = new SyncRoot();

    // True if in safe mode.
    // This option may disable certain display adapters.
    public boolean mSafeMode;

    // All callback records indexed by calling process id.
    private final SparseArray<CallbackRecord> mCallbacks = new SparseArray<>();

    /**
     *  All {@link IVirtualDevice} and {@link DisplayWindowPolicyController}s indexed by
     *  {@link DisplayInfo#displayId}.
     */
    final SparseArray<Pair<IVirtualDevice, DisplayWindowPolicyController>>
            mDisplayWindowPolicyControllers = new SparseArray<>();

    /**
     * Provides {@link HighBrightnessModeMetadata}s for {@link DisplayDevice}s.
     */
    private final HighBrightnessModeMetadataMapper mHighBrightnessModeMetadataMapper =
            new HighBrightnessModeMetadataMapper();

    // List of all currently registered display adapters.
    private final ArrayList<DisplayAdapter> mDisplayAdapters = new ArrayList<>();

    /**
     * Repository of all active {@link DisplayDevice}s.
     */
    private final DisplayDeviceRepository mDisplayDeviceRepo;

    /**
     * Contains all the {@link LogicalDisplay} instances and is responsible for mapping
     * {@link DisplayDevice}s to {@link LogicalDisplay}s. DisplayManagerService listens to display
     * event on this object.
     */
    private final LogicalDisplayMapper mLogicalDisplayMapper;

    // List of all display transaction listeners.
    private final CopyOnWriteArrayList<DisplayTransactionListener> mDisplayTransactionListeners =
            new CopyOnWriteArrayList<>();

    /** List of all display group listeners. */
    private final CopyOnWriteArrayList<DisplayGroupListener> mDisplayGroupListeners =
            new CopyOnWriteArrayList<>();

    /** All {@link DisplayPowerController}s indexed by {@link LogicalDisplay} ID. */
    private final SparseArray<DisplayPowerControllerInterface> mDisplayPowerControllers =
            new SparseArray<>();

    /** {@link DisplayBlanker} used by all {@link DisplayPowerController}s. */
    private final DisplayBlanker mDisplayBlanker = new DisplayBlanker() {
        // Synchronized to avoid race conditions when updating multiple display states.
        @Override
        public synchronized void requestDisplayState(int displayId, int state, float brightness,
                float sdrBrightness) {
            boolean allInactive = true;
            boolean allOff = true;
            final boolean stateChanged;
            synchronized (mSyncRoot) {
                final int index = mDisplayStates.indexOfKey(displayId);
                if (index > -1) {
                    final int currentState = mDisplayStates.valueAt(index);
                    stateChanged = state != currentState;
                    if (stateChanged) {
                        final int size = mDisplayStates.size();
                        for (int i = 0; i < size; i++) {
                            final int displayState = i == index ? state : mDisplayStates.valueAt(i);
                            if (displayState != Display.STATE_OFF) {
                                allOff = false;
                            }
                            if (Display.isActiveState(displayState)) {
                                allInactive = false;
                            }
                            if (!allOff && !allInactive) {
                                break;
                            }
                        }
                    }
                } else {
                    stateChanged = false;
                }
            }

            // The order of operations is important for legacy reasons.
            if (state == Display.STATE_OFF) {
                requestDisplayStateInternal(displayId, state, brightness, sdrBrightness);
            }

            if (stateChanged) {
                mDisplayPowerCallbacks.onDisplayStateChange(allInactive, allOff);
            }

            if (state != Display.STATE_OFF) {
                requestDisplayStateInternal(displayId, state, brightness, sdrBrightness);
            }
        }
    };

    /**
     * Used to inform {@link com.android.server.power.PowerManagerService} of changes to display
     * state.
     */
    private DisplayManagerInternal.DisplayPowerCallbacks mDisplayPowerCallbacks;

    /** The {@link Handler} used by all {@link DisplayPowerController}s. */
    private Handler mPowerHandler;

    // A map from LogicalDisplay ID to display power state.
    @GuardedBy("mSyncRoot")
    private final SparseIntArray mDisplayStates = new SparseIntArray();

    // A map from LogicalDisplay ID to display brightness.
    @GuardedBy("mSyncRoot")
    private final SparseArray<BrightnessPair> mDisplayBrightnesses = new SparseArray<>();

    // Set to true when there are pending display changes that have yet to be applied
    // to the surface flinger state.
    private boolean mPendingTraversal;

    // The Wifi display adapter, or null if not registered.
    private WifiDisplayAdapter mWifiDisplayAdapter;

    // The number of active wifi display scan requests.
    private int mWifiDisplayScanRequestCount;

    // The virtual display adapter, or null if not registered.
    private VirtualDisplayAdapter mVirtualDisplayAdapter;

    // The User ID of the current user
    private @UserIdInt int mCurrentUserId;

    // The stable device screen height and width. These are not tied to a specific display, even
    // the default display, because they need to be stable over the course of the device's entire
    // life, even if the default display changes (e.g. a new monitor is plugged into a PC-like
    // device).
    private Point mStableDisplaySize = new Point();

    // Whether the system has finished booting or not.
    private boolean mSystemReady;

    // The top inset of the default display.
    // This gets persisted so that the boot animation knows how to transition from the display's
    // full size to the size configured by the user. Right now we only persist and animate the top
    // inset, but theoretically we could do it for all of them.
    private int mDefaultDisplayTopInset;

    // Viewports of the default display and the display that should receive touch
    // input from an external source.  Used by the input system.
    @GuardedBy("mSyncRoot")
    private final ArrayList<DisplayViewport> mViewports = new ArrayList<>();

    // Persistent data store for all internal settings maintained by the display manager service.
    private final PersistentDataStore mPersistentDataStore = new PersistentDataStore();

    // Temporary callback list, used when sending display events to applications.
    // May be used outside of the lock but only on the handler thread.
    private final ArrayList<CallbackRecord> mTempCallbacks = new ArrayList<>();

    // Pending callback records indexed by calling process uid and pid.
    // Must be used outside of the lock mSyncRoot and should be self-locked.
    @GuardedBy("mPendingCallbackSelfLocked")
    private final SparseArray<SparseArray<PendingCallback>> mPendingCallbackSelfLocked =
            new SparseArray<>();

    // Temporary viewports, used when sending new viewport information to the
    // input system.  May be used outside of the lock but only on the handler thread.
    private final ArrayList<DisplayViewport> mTempViewports = new ArrayList<>();

    // The default color mode for default displays. Overrides the usual
    // Display.Display.COLOR_MODE_DEFAULT for local displays.
    private final int mDefaultDisplayDefaultColorMode;

    // Lists of UIDs that are present on the displays. Maps displayId -> array of UIDs.
    private final SparseArray<IntArray> mDisplayAccessUIDs = new SparseArray<>();

    private final Injector mInjector;

    // The minimum brightness curve, which guarantess that any brightness curve that dips below it
    // is rejected by the system.
    private final Curve mMinimumBrightnessCurve;
    private final Spline mMinimumBrightnessSpline;
    private final ColorSpace mWideColorSpace;
    private final OverlayProperties mOverlayProperties;

    private SensorManager mSensorManager;
    private BrightnessTracker mBrightnessTracker;

    private SmallAreaDetectionController mSmallAreaDetectionController;


    // Whether minimal post processing is allowed by the user.
    @GuardedBy("mSyncRoot")
    private boolean mMinimalPostProcessingAllowed;

    // Receives notifications about changes to Settings.
    private SettingsObserver mSettingsObserver;

    // Keeps note of what state the device is in, used for idle screen brightness mode.
    private boolean mIsDocked;
    private boolean mIsDreaming;

    private boolean mBootCompleted = false;

    // If we would like to keep a particular eye on a package, we can set the package name.
    private final boolean mExtraDisplayEventLogging;
    private final String mExtraDisplayLoggingPackageName;

    private final BroadcastReceiver mIdleModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_DOCK_EVENT.equals(intent.getAction())) {
                int dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                mIsDocked = dockState == Intent.EXTRA_DOCK_STATE_DESK
                        || dockState == Intent.EXTRA_DOCK_STATE_LE_DESK
                        || dockState == Intent.EXTRA_DOCK_STATE_HE_DESK;
            }
            if (Intent.ACTION_DREAMING_STARTED.equals(intent.getAction())) {
                mIsDreaming = true;
            } else if (Intent.ACTION_DREAMING_STOPPED.equals(intent.getAction())) {
                mIsDreaming = false;
            }
            setDockedAndIdleEnabled(/* enabled= */(mIsDocked && mIsDreaming),
                    Display.DEFAULT_DISPLAY);
        }
    };

    private final BroadcastReceiver mResolutionRestoreReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SETTING_RESTORED.equals(intent.getAction())) {
                if (Settings.Secure.SCREEN_RESOLUTION_MODE.equals(
                        intent.getExtra(Intent.EXTRA_SETTING_NAME))) {
                    restoreResolutionFromBackup();
                }
            }
        }
    };

    private final DisplayModeDirector.DisplayDeviceConfigProvider mDisplayDeviceConfigProvider =
            displayId -> {
                synchronized (mSyncRoot) {
                    final DisplayDevice device = getDeviceForDisplayLocked(displayId);
                    if (device == null) {
                        return null;
                    }
                    return device.getDisplayDeviceConfig();
                }
            };

    private final BrightnessSynchronizer mBrightnessSynchronizer;

    private final DeviceConfigParameterProvider mConfigParameterProvider;

    private final DisplayManagerFlags mFlags;

    private final DisplayNotificationManager mDisplayNotificationManager;
    private final ExternalDisplayStatsService mExternalDisplayStatsService;

    /**
     * Applications use {@link android.view.Display#getRefreshRate} and
     * {@link android.view.Display.Mode#getRefreshRate} to know what is the display refresh rate.
     * Starting with Android S, the platform might throttle down applications frame rate to a
     * divisor of the refresh rate if it is more preferable (for example if the application called
     * to {@link android.view.Surface#setFrameRate}).
     * Applications will experience {@link android.view.Choreographer#postFrameCallback} callbacks
     * and backpressure at the throttled frame rate.
     *
     * {@link android.view.Display#getRefreshRate} will always return the application frame rate
     * and not the physical display refresh rate to allow applications to do frame pacing correctly.
     *
     * {@link android.view.Display.Mode#getRefreshRate} will return the application frame rate if
     * compiled to a previous release and starting with Android S it will return the physical
     * display refresh rate.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.S)
    static final long DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE = 170503758L;

    public DisplayManagerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    DisplayManagerService(Context context, Injector injector) {
        super(context);
        FoldSettingProvider foldSettingProvider = new FoldSettingProvider(context,
                new SettingsWrapper(),
                new FoldLockSettingAvailabilityProvider(context.getResources()));
        mInjector = injector;
        mContext = context;
        mFlags = injector.getFlags();
        mHandler = new DisplayManagerHandler(DisplayThread.get().getLooper());
        mUiHandler = UiThread.getHandler();
        mDisplayDeviceRepo = new DisplayDeviceRepository(mSyncRoot, mPersistentDataStore);
        mLogicalDisplayMapper = new LogicalDisplayMapper(mContext,
                foldSettingProvider, new FoldGracePeriodProvider(),
                mDisplayDeviceRepo, new LogicalDisplayListener(), mSyncRoot, mHandler, mFlags);
        mDisplayModeDirector = new DisplayModeDirector(
                context, mHandler, mFlags, mDisplayDeviceConfigProvider);
        mBrightnessSynchronizer = new BrightnessSynchronizer(mContext,
                mFlags.isBrightnessIntRangeUserPerceptionEnabled());
        Resources resources = mContext.getResources();
        mDefaultDisplayDefaultColorMode = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultDisplayDefaultColorMode);
        mDefaultDisplayTopInset = SystemProperties.getInt(PROP_DEFAULT_DISPLAY_TOP_INSET, -1);
        mDefaultHdrConversionMode = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableDefaultHdrConversionPassthrough)
                        ? HdrConversionMode.HDR_CONVERSION_PASSTHROUGH
                        : HdrConversionMode.HDR_CONVERSION_SYSTEM;
        float[] lux = getFloatArray(resources.obtainTypedArray(
                com.android.internal.R.array.config_minimumBrightnessCurveLux));
        float[] nits = getFloatArray(resources.obtainTypedArray(
                com.android.internal.R.array.config_minimumBrightnessCurveNits));
        mMinimumBrightnessCurve = new Curve(lux, nits);
        mMinimumBrightnessSpline = Spline.createSpline(lux, nits);

        mCurrentUserId = UserHandle.USER_SYSTEM;
        ColorSpace[] colorSpaces = SurfaceControl.getCompositionColorSpaces();
        mWideColorSpace = colorSpaces[1];
        mOverlayProperties = SurfaceControl.getOverlaySupport();
        mSystemReady = false;
        mConfigParameterProvider = new DeviceConfigParameterProvider(DeviceConfigInterface.REAL);
        mExtraDisplayLoggingPackageName = DisplayProperties.debug_vri_package().orElse(null);
        mExtraDisplayEventLogging = !TextUtils.isEmpty(mExtraDisplayLoggingPackageName);

        mExternalDisplayStatsService = new ExternalDisplayStatsService(mContext, mHandler);
        mDisplayNotificationManager = new DisplayNotificationManager(mFlags, mContext,
                mExternalDisplayStatsService);
        mExternalDisplayPolicy = new ExternalDisplayPolicy(new ExternalDisplayPolicyInjector());
    }

    public void setupSchedulerPolicies() {
        // android.display and android.anim is critical to user experience and we should make sure
        // it is not in the default foregroup groups, add it to top-app to make sure it uses all
        // the cores and scheduling settings for top-app when it runs.
        Process.setThreadGroupAndCpuset(DisplayThread.get().getThreadId(),
                Process.THREAD_GROUP_TOP_APP);
        Process.setThreadGroupAndCpuset(AnimationThread.get().getThreadId(),
                Process.THREAD_GROUP_TOP_APP);
        Process.setThreadGroupAndCpuset(SurfaceAnimationThread.get().getThreadId(),
                Process.THREAD_GROUP_TOP_APP);
    }

    @Override
    public void onStart() {
        // We need to pre-load the persistent data store so it's ready before the default display
        // adapter is up so that we have it's configuration. We could load it lazily, but since
        // we're going to have to read it in eventually we may as well do it here rather than after
        // we've waited for the display to register itself with us.
        synchronized (mSyncRoot) {
            mPersistentDataStore.loadIfNeeded();
            loadStableDisplayValuesLocked();
        }
        mHandler.sendEmptyMessage(MSG_REGISTER_DEFAULT_DISPLAY_ADAPTERS);

        // If there was a runtime restart then we may have stale caches left around, so we need to
        // make sure to invalidate them upon every start.
        DisplayManagerGlobal.invalidateLocalDisplayInfoCaches();

        publishBinderService(Context.DISPLAY_SERVICE, new BinderService(),
                true /*allowIsolated*/, DUMP_FLAG_PRIORITY_CRITICAL);
        publishLocalService(DisplayManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_WAIT_FOR_DEFAULT_DISPLAY) {
            synchronized (mSyncRoot) {
                long timeout = SystemClock.uptimeMillis()
                        + mInjector.getDefaultDisplayDelayTimeout();
                while (mLogicalDisplayMapper.getDisplayLocked(Display.DEFAULT_DISPLAY) == null
                        || mVirtualDisplayAdapter == null) {
                    long delay = timeout - SystemClock.uptimeMillis();
                    if (delay <= 0) {
                        throw new RuntimeException("Timeout waiting for default display "
                                + "to be initialized. DefaultDisplay="
                                + mLogicalDisplayMapper.getDisplayLocked(Display.DEFAULT_DISPLAY)
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
        } else if (phase == PHASE_BOOT_COMPLETED) {
            synchronized (mSyncRoot) {
                mBootCompleted = true;
                for (int i = 0; i < mDisplayPowerControllers.size(); i++) {
                    mDisplayPowerControllers.valueAt(i).onBootCompleted();
                }
            }
            mDisplayModeDirector.onBootCompleted();
            mLogicalDisplayMapper.onBootCompleted();
            mDisplayNotificationManager.onBootCompleted();
            mExternalDisplayPolicy.onBootCompleted();
        }
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        final int newUserId = to.getUserIdentifier();
        final int userSerial = getUserManager().getUserSerialNumber(newUserId);
        synchronized (mSyncRoot) {
            boolean userSwitching = mCurrentUserId != newUserId;
            if (userSwitching) {
                mCurrentUserId = newUserId;
            }
            mLogicalDisplayMapper.forEachLocked(logicalDisplay -> {
                if (logicalDisplay.getDisplayInfoLocked().type != Display.TYPE_INTERNAL) {
                    return;
                }
                final DisplayPowerControllerInterface dpc = mDisplayPowerControllers.get(
                        logicalDisplay.getDisplayIdLocked());
                if (dpc == null) {
                    return;
                }
                if (userSwitching) {
                    BrightnessConfiguration config =
                            getBrightnessConfigForDisplayWithPdsFallbackLocked(
                            logicalDisplay.getPrimaryDisplayDeviceLocked().getUniqueId(),
                            userSerial);
                    dpc.setBrightnessConfiguration(config, /* shouldResetShortTermModel= */ true);
                }
                final DisplayDevice device = logicalDisplay.getPrimaryDisplayDeviceLocked();
                float newBrightness = device == null ? PowerManager.BRIGHTNESS_INVALID_FLOAT
                        : mPersistentDataStore.getBrightness(device, userSerial);
                if (Float.isNaN(newBrightness)) {
                    newBrightness = logicalDisplay.getDisplayInfoLocked().brightnessDefault;
                }
                dpc.onSwitchUser(newUserId, userSerial, newBrightness);
            });
            handleSettingsChange();
        }
    }

    /**
     * The 2nd stage initialization
     * TODO: Use dependencies or a boot phase
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public void windowManagerAndInputReady() {
        synchronized (mSyncRoot) {
            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
            mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
            mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
            ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
            activityManager.addOnUidImportanceListener(mUidImportanceListener, IMPORTANCE_CACHED);

            mDeviceStateManager = LocalServices.getService(DeviceStateManagerInternal.class);
            mContext.getSystemService(DeviceStateManager.class).registerCallback(
                    new HandlerExecutor(mHandler), new DeviceStateListener());

            mLogicalDisplayMapper.onWindowManagerReady();
            scheduleTraversalLocked(false);
        }
    }

    /**
     * Called when the system is ready to go.
     */
    public void systemReady(boolean safeMode) {
        synchronized (mSyncRoot) {
            mSafeMode = safeMode;
            mSystemReady = true;
            mIsHdrOutputControlEnabled =
                    mConfigParameterProvider.isHdrOutputControlFeatureEnabled();
            mConfigParameterProvider.addOnPropertiesChangedListener(BackgroundThread.getExecutor(),
                    properties -> mIsHdrOutputControlEnabled =
                            mConfigParameterProvider.isHdrOutputControlFeatureEnabled());
            // Just in case the top inset changed before the system was ready. At this point, any
            // relevant configuration should be in place.
            recordTopInsetLocked(mLogicalDisplayMapper.getDisplayLocked(Display.DEFAULT_DISPLAY));

            updateSettingsLocked();
            updateUserDisabledHdrTypesFromSettingsLocked();
            updateUserPreferredDisplayModeSettingsLocked();
            if (mIsHdrOutputControlEnabled) {
                updateHdrConversionModeSettingsLocked();
            }
        }

        mDisplayModeDirector.setDesiredDisplayModeSpecsListener(
                new DesiredDisplayModeSpecsObserver());
        mDisplayModeDirector.start(mSensorManager);

        mHandler.sendEmptyMessage(MSG_REGISTER_ADDITIONAL_DISPLAY_ADAPTERS);

        mSettingsObserver = new SettingsObserver();

        mBrightnessSynchronizer.startSynchronizing();

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        filter.addAction(Intent.ACTION_DOCK_EVENT);

        mContext.registerReceiver(mIdleModeReceiver, filter);

        if (mFlags.isResolutionBackupRestoreEnabled()) {
            final IntentFilter restoreFilter = new IntentFilter(Intent.ACTION_SETTING_RESTORED);
            mContext.registerReceiver(mResolutionRestoreReceiver, restoreFilter);
        }

        mSmallAreaDetectionController = (mFlags.isSmallAreaDetectionEnabled())
                ? SmallAreaDetectionController.create(mContext) : null;
    }

    @VisibleForTesting
    Handler getDisplayHandler() {
        return mHandler;
    }

    @VisibleForTesting
    DisplayDeviceRepository getDisplayDeviceRepository() {
        return mDisplayDeviceRepo;
    }

    @VisibleForTesting
    LogicalDisplayMapper getLogicalDisplayMapper() {
        return mLogicalDisplayMapper;
    }

    @VisibleForTesting
    boolean isMinimalPostProcessingAllowed() {
        synchronized (mSyncRoot) {
            return mMinimalPostProcessingAllowed;
        }
    }

    @VisibleForTesting
    void setMinimalPostProcessingAllowed(boolean allowed) {
        synchronized (mSyncRoot) {
            mMinimalPostProcessingAllowed = allowed;
        }
    }

    DisplayNotificationManager getDisplayNotificationManager() {
        return mDisplayNotificationManager;
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

    @VisibleForTesting
    void setDisplayInfoOverrideFromWindowManagerInternal(int displayId, DisplayInfo info) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null) {
                if (display.setDisplayInfoOverrideFromWindowManagerLocked(info)) {
                    handleLogicalDisplayChangedLocked(display);
                }
            }
        }
    }

    /**
     * @see DisplayManagerInternal#getNonOverrideDisplayInfo(int, DisplayInfo)
     */
    private void getNonOverrideDisplayInfoInternal(int displayId, DisplayInfo outInfo) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null) {
                display.getNonOverrideDisplayInfoLocked(outInfo);
            }
        }
    }

    @VisibleForTesting
    void performTraversalInternal(SurfaceControl.Transaction t,
            SparseArray<SurfaceControl.Transaction> displayTransactions) {
        synchronized (mSyncRoot) {
            if (!mPendingTraversal) {
                return;
            }
            mPendingTraversal = false;

            performTraversalLocked(t, displayTransactions);
        }

        // List is self-synchronized copy-on-write.
        for (DisplayTransactionListener listener : mDisplayTransactionListeners) {
            listener.onDisplayTransaction(t);
        }
    }

    private float clampBrightness(int displayState, float brightnessState) {
        if (displayState == Display.STATE_OFF) {
            brightnessState = PowerManager.BRIGHTNESS_OFF_FLOAT;
        } else if (brightnessState != PowerManager.BRIGHTNESS_OFF_FLOAT
                && brightnessState < PowerManager.BRIGHTNESS_MIN) {
            brightnessState = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        } else if (brightnessState > PowerManager.BRIGHTNESS_MAX) {
            brightnessState = PowerManager.BRIGHTNESS_MAX;
        }
        return brightnessState;
    }

    private void requestDisplayStateInternal(int displayId, int state, float brightnessState,
            float sdrBrightnessState) {
        if (state == Display.STATE_UNKNOWN) {
            state = Display.STATE_ON;
        }

        brightnessState = clampBrightness(state, brightnessState);
        sdrBrightnessState = clampBrightness(state, sdrBrightnessState);

        // Update the display state within the lock.
        // Note that we do not need to schedule traversals here although it
        // may happen as a side-effect of displays changing state.
        final Runnable runnable;
        final String traceMessage;
        synchronized (mSyncRoot) {
            final int index = mDisplayStates.indexOfKey(displayId);

            final BrightnessPair brightnessPair =
                    index < 0 ? null : mDisplayBrightnesses.valueAt(index);
            if (index < 0 || (mDisplayStates.valueAt(index) == state
                    && brightnessPair.brightness == brightnessState
                    && brightnessPair.sdrBrightness == sdrBrightnessState)) {
                return; // Display no longer exists or no change.
            }

            if (Trace.isTagEnabled(Trace.TRACE_TAG_POWER)) {
                traceMessage = Display.stateToString(state)
                           + ", brightness=" + brightnessState
                           + ", sdrBrightness=" + sdrBrightnessState;
                Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_POWER,
                        "requestDisplayStateInternal:" + displayId,
                        traceMessage, displayId);
            }

            mDisplayStates.setValueAt(index, state);
            brightnessPair.brightness = brightnessState;
            brightnessPair.sdrBrightness = sdrBrightnessState;
            // TODO(b/297503094) Preventing disabled display from being turned on should happen
            // elsewhere.
            LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (!display.isEnabledLocked() && state != Display.STATE_OFF) {
                // If the display is disabled, any request other than turning it off should fail.
                return;
            }
            runnable = updateDisplayStateLocked(display.getPrimaryDisplayDeviceLocked());
            if (Trace.isTagEnabled(Trace.TRACE_TAG_POWER)) {
                Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_POWER,
                        "requestDisplayStateInternal:" + displayId, displayId);
            }
        }

        // Setting the display power state can take hundreds of milliseconds
        // to complete so we defer the most expensive part of the work until
        // after we have exited the critical section to avoid blocking other
        // threads for a long time.
        if (runnable != null) {
            runnable.run();
        }
    }

    private class UidImportanceListener implements ActivityManager.OnUidImportanceListener {
        @Override
        public void onUidImportance(int uid, int importance) {
            synchronized (mPendingCallbackSelfLocked) {
                if (importance >= IMPORTANCE_GONE) {
                    // Clean up as the app is already gone
                    Slog.d(TAG, "Drop pending events for gone uid " + uid);
                    mPendingCallbackSelfLocked.delete(uid);
                    return;
                } else if (importance >= IMPORTANCE_CACHED) {
                    // Nothing to do as the app is still in cached mode
                    return;
                }

                // Do we care about this uid?
                SparseArray<PendingCallback> pendingCallbacks = mPendingCallbackSelfLocked.get(uid);
                if (pendingCallbacks == null) {
                    return;
                }

                // Send the pending events out when a certain uid becomes non-cached
                if (DEBUG) {
                    Slog.d(TAG, "Uid " + uid + " becomes " + importance);
                }
                for (int i = 0; i < pendingCallbacks.size(); i++) {
                    PendingCallback pendingCallback = pendingCallbacks.valueAt(i);
                    if (pendingCallback != null) {
                        pendingCallback.sendPendingDisplayEvent();
                    }
                }
                mPendingCallbackSelfLocked.delete(uid);
            }
        }
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver() {
            super(mHandler);

            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(
                        Settings.Secure.MINIMAL_POST_PROCESSING_ALLOWED), false, this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            handleSettingsChange();
        }
    }

    private void handleSettingsChange() {
        synchronized (mSyncRoot) {
            updateSettingsLocked();
            scheduleTraversalLocked(false);
        }
    }

    private void updateSettingsLocked() {
        setMinimalPostProcessingAllowed(Settings.Secure.getIntForUser(
                mContext.getContentResolver(), Settings.Secure.MINIMAL_POST_PROCESSING_ALLOWED,
                1, UserHandle.USER_CURRENT) != 0);
    }

    private void restoreResolutionFromBackup() {
        int savedMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SCREEN_RESOLUTION_MODE,
                RESOLUTION_MODE_UNKNOWN, UserHandle.USER_CURRENT);
        if (savedMode == RESOLUTION_MODE_UNKNOWN) {
            // Nothing to restore.
            return;
        }

        synchronized (mSyncRoot) {
            LogicalDisplay display =
                    mLogicalDisplayMapper.getDisplayLocked(Display.DEFAULT_DISPLAY);
            DisplayDevice device = display == null ? null : display.getPrimaryDisplayDeviceLocked();
            if (device == null) {
                Slog.w(TAG, "No default display device present to restore resolution mode");
                return;
            }

            Point[] supportedRes = device.getSupportedResolutionsLocked();
            if (supportedRes.length != 2) {
                if (DEBUG) {
                    Slog.d(TAG, "Skipping resolution restore - " + supportedRes.length);
                }
                return;
            }

            // We follow the same logic as Settings but in reverse. If the display supports 2
            // resolutions, we treat the small (index=0) one as HIGH and the larger (index=1)
            // one as FULL and restore the correct resolution accordingly.
            int index = savedMode == RESOLUTION_MODE_HIGH ? 0 : 1;
            Point res = supportedRes[index];
            Display.Mode newMode = new Display.Mode(res.x, res.y, /*refreshRate=*/ 0);
            Slog.i(TAG, "Restoring resolution from backup: (" + savedMode + ") "
                    + res.x + "x" + res.y);
            setUserPreferredDisplayModeInternal(Display.DEFAULT_DISPLAY, newMode);
        }
    }

    private void updateUserDisabledHdrTypesFromSettingsLocked() {
        mAreUserDisabledHdrTypesAllowed = (Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.ARE_USER_DISABLED_HDR_FORMATS_ALLOWED,
                1) != 0);

        String userDisabledHdrTypes = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.USER_DISABLED_HDR_FORMATS);

        if (userDisabledHdrTypes != null) {
            try {
                String[] userDisabledHdrTypeStrings =
                        TextUtils.split(userDisabledHdrTypes, ",");
                mUserDisabledHdrTypes = new int[userDisabledHdrTypeStrings.length];
                for (int i = 0; i < userDisabledHdrTypeStrings.length; i++) {
                    mUserDisabledHdrTypes[i] = Integer.parseInt(userDisabledHdrTypeStrings[i]);
                }

                if (!mAreUserDisabledHdrTypesAllowed) {
                    mLogicalDisplayMapper.forEachLocked(
                            display -> {
                                display.setUserDisabledHdrTypes(mUserDisabledHdrTypes);
                                handleLogicalDisplayChangedLocked(display);
                            });
                }

            } catch (NumberFormatException e) {
                Slog.e(TAG, "Failed to parse USER_DISABLED_HDR_FORMATS. "
                        + "Clearing the setting.", e);
                clearUserDisabledHdrTypesLocked();
            }
        } else {
            clearUserDisabledHdrTypesLocked();
        }
    }

    private void clearUserDisabledHdrTypesLocked() {
        synchronized (mSyncRoot) {
            mUserDisabledHdrTypes = new int[]{};
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.USER_DISABLED_HDR_FORMATS, "");
        }
    }

    private void updateUserPreferredDisplayModeSettingsLocked() {
        final float refreshRate = Settings.Global.getFloat(mContext.getContentResolver(),
                Settings.Global.USER_PREFERRED_REFRESH_RATE, Display.INVALID_DISPLAY_REFRESH_RATE);
        final int height = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.USER_PREFERRED_RESOLUTION_HEIGHT, Display.INVALID_DISPLAY_HEIGHT);
        final int width = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.USER_PREFERRED_RESOLUTION_WIDTH, Display.INVALID_DISPLAY_WIDTH);
        Display.Mode mode = new Display.Mode(width, height, refreshRate);
        mUserPreferredMode = isResolutionAndRefreshRateValid(mode) ? mode : null;
        if (mUserPreferredMode != null) {
            mDisplayDeviceRepo.forEachLocked((DisplayDevice device) -> {
                device.setUserPreferredDisplayModeLocked(mode);
            });
        } else {
            mLogicalDisplayMapper.forEachLocked(this::configurePreferredDisplayModeLocked);
        }
    }

    private DisplayInfo getDisplayInfoForFrameRateOverride(DisplayEventReceiver.FrameRateOverride[]
            frameRateOverrides, DisplayInfo info, int callingUid) {
        float frameRateHz = info.renderFrameRate;
        for (DisplayEventReceiver.FrameRateOverride frameRateOverride : frameRateOverrides) {
            if (frameRateOverride.uid == callingUid) {
                frameRateHz = frameRateOverride.frameRateHz;
                break;
            }
        }

        if (frameRateHz == 0) {
            return info;
        }

        // For non-apps users we always return the physical refresh rate from display mode
        boolean displayModeReturnsPhysicalRefreshRate =
                callingUid < FIRST_APPLICATION_UID
                        || CompatChanges.isChangeEnabled(
                                DISPLAY_MODE_RETURNS_PHYSICAL_REFRESH_RATE, callingUid);

        // Override the refresh rate only if it is a divisor of the current
        // refresh rate. This calculation needs to be in sync with the native code
        // in RefreshRateSelector::getFrameRateDivisor
        Display.Mode currentMode = info.getMode();
        float numPeriods = currentMode.getRefreshRate() / frameRateHz;
        float numPeriodsRound = Math.round(numPeriods);
        if (Math.abs(numPeriods - numPeriodsRound) > THRESHOLD_FOR_REFRESH_RATES_DIVISORS) {
            return info;
        }
        frameRateHz = currentMode.getRefreshRate() / numPeriodsRound;

        DisplayInfo overriddenInfo = new DisplayInfo();
        overriddenInfo.copyFrom(info);
        for (Display.Mode mode : info.supportedModes) {
            if (!mode.equalsExceptRefreshRate(currentMode)) {
                continue;
            }

            if (mode.getRefreshRate() >= frameRateHz - THRESHOLD_FOR_REFRESH_RATES_DIVISORS
                    && mode.getRefreshRate()
                    <= frameRateHz + THRESHOLD_FOR_REFRESH_RATES_DIVISORS) {
                if (DEBUG) {
                    Slog.d(TAG, "found matching modeId " + mode.getModeId());
                }
                overriddenInfo.refreshRateOverride = mode.getRefreshRate();

                if (!displayModeReturnsPhysicalRefreshRate) {
                    overriddenInfo.modeId = mode.getModeId();
                }
                return overriddenInfo;
            }
        }

        overriddenInfo.refreshRateOverride = frameRateHz;
        if (!displayModeReturnsPhysicalRefreshRate) {
            overriddenInfo.supportedModes = Arrays.copyOf(info.supportedModes,
                    info.supportedModes.length + 1);
            overriddenInfo.supportedModes[overriddenInfo.supportedModes.length - 1] =
                    new Display.Mode(Display.DISPLAY_MODE_ID_FOR_FRAME_RATE_OVERRIDE,
                            currentMode.getPhysicalWidth(), currentMode.getPhysicalHeight(),
                            overriddenInfo.refreshRateOverride,
                            currentMode.getVsyncRate(),
                            new float[0], currentMode.getSupportedHdrTypes());
            overriddenInfo.modeId =
                    overriddenInfo.supportedModes[overriddenInfo.supportedModes.length - 1]
                            .getModeId();
        }
        return overriddenInfo;
    }

    private DisplayInfo getDisplayInfoInternal(int displayId, int callingUid) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null) {
                final DisplayInfo info =
                        getDisplayInfoForFrameRateOverride(display.getFrameRateOverrides(),
                                display.getDisplayInfoLocked(), callingUid);
                if (info.hasAccess(callingUid)
                        || isUidPresentOnDisplayInternal(callingUid, displayId)) {
                    return info;
                }
            }
            return null;
        }
    }

    private void registerCallbackInternal(IDisplayManagerCallback callback, int callingPid,
            int callingUid, @EventsMask long eventsMask) {
        synchronized (mSyncRoot) {
            CallbackRecord record = mCallbacks.get(callingPid);

            if (record != null) {
                record.updateEventsMask(eventsMask);
                return;
            }

            record = new CallbackRecord(callingPid, callingUid, callback, eventsMask);
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

    private void setUserDisabledHdrTypesInternal(int[] userDisabledHdrTypes) {
        synchronized (mSyncRoot) {
            if (userDisabledHdrTypes == null) {
                Slog.e(TAG, "Null is not an expected argument to "
                        + "setUserDisabledHdrTypesInternal");
                return;
            }

            // Verify if userDisabledHdrTypes contains expected HDR types
            if (!isSubsetOf(Display.HdrCapabilities.HDR_TYPES, userDisabledHdrTypes)) {
                Slog.e(TAG, "userDisabledHdrTypes contains unexpected types");
                return;
            }

            Arrays.sort(userDisabledHdrTypes);
            if (Arrays.equals(mUserDisabledHdrTypes, userDisabledHdrTypes)) {
                return;
            }
            String userDisabledFormatsString = "";
            if (userDisabledHdrTypes.length != 0) {
                userDisabledFormatsString = TextUtils.join(",",
                        Arrays.stream(userDisabledHdrTypes).boxed().toArray());
            }
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.USER_DISABLED_HDR_FORMATS, userDisabledFormatsString);
            mUserDisabledHdrTypes = userDisabledHdrTypes;
            if (!mAreUserDisabledHdrTypesAllowed) {
                mLogicalDisplayMapper.forEachLocked(
                        display -> {
                            display.setUserDisabledHdrTypes(userDisabledHdrTypes);
                            handleLogicalDisplayChangedLocked(display);
                        });
            }
        }
    }

    private boolean isSubsetOf(int[] sortedSuperset, int[] subset) {
        for (int i : subset) {
            if (Arrays.binarySearch(sortedSuperset, i) < 0) {
                return false;
            }
        }
        return true;
    }

    private void setAreUserDisabledHdrTypesAllowedInternal(
            boolean areUserDisabledHdrTypesAllowed) {
        synchronized (mSyncRoot) {
            if (mAreUserDisabledHdrTypesAllowed == areUserDisabledHdrTypesAllowed) {
                return;
            }
            mAreUserDisabledHdrTypesAllowed = areUserDisabledHdrTypesAllowed;
            if (mUserDisabledHdrTypes.length == 0) {
                return;
            }
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.ARE_USER_DISABLED_HDR_FORMATS_ALLOWED,
                    areUserDisabledHdrTypesAllowed ? 1 : 0);
            int userDisabledHdrTypes[] = {};
            if (!mAreUserDisabledHdrTypesAllowed) {
                userDisabledHdrTypes = mUserDisabledHdrTypes;
            }
            int[] finalUserDisabledHdrTypes = userDisabledHdrTypes;
            mLogicalDisplayMapper.forEachLocked(
                    display -> {
                        display.setUserDisabledHdrTypes(finalUserDisabledHdrTypes);
                        handleLogicalDisplayChangedLocked(display);
                    });
        }
    }

    private void requestColorModeInternal(int displayId, int colorMode) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null &&
                    display.getRequestedColorModeLocked() != colorMode) {
                display.setRequestedColorModeLocked(colorMode);
                scheduleTraversalLocked(false);
            }
        }
    }

    private boolean validatePackageName(int uid, String packageName) {
        // Root doesn't have a package name.
        if (uid == ROOT_UID) {
            return true;
        }
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
        if (checkCallingPermission(CAPTURE_VIDEO_OUTPUT, "canProjectVideo()")) {
            return true;
        }
        return canProjectSecureVideo(projection);
    }

    private boolean canProjectSecureVideo(IMediaProjection projection) {
        if (projection != null) {
            try {
                if (projection.canProjectSecureVideo()) {
                    return true;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to query projection service for permissions", e);
            }
        }
        return checkCallingPermission(CAPTURE_SECURE_VIDEO_OUTPUT, "canProjectSecureVideo()");
    }

    private boolean checkCallingPermission(String permission, String func) {
        if (mContext.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        final String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid() + " requires " + permission;
        Slog.w(TAG, msg);
        return false;
    }

    private int createVirtualDisplayInternal(VirtualDisplayConfig virtualDisplayConfig,
            IVirtualDisplayCallback callback, IMediaProjection projection,
            IVirtualDevice virtualDevice, DisplayWindowPolicyController dwpc, String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (!validatePackageName(callingUid, packageName)) {
            throw new SecurityException("packageName must match the calling uid");
        }
        if (callback == null) {
            throw new IllegalArgumentException("appToken must not be null");
        }
        if (virtualDisplayConfig == null) {
            throw new IllegalArgumentException("virtualDisplayConfig must not be null");
        }
        final Surface surface = virtualDisplayConfig.getSurface();
        int flags = virtualDisplayConfig.getFlags();
        if (virtualDevice != null) {
            final VirtualDeviceManager vdm = mContext.getSystemService(VirtualDeviceManager.class);
            try {
                if (!vdm.isValidVirtualDeviceId(virtualDevice.getDeviceId())) {
                    throw new SecurityException("Invalid virtual device");
                }
            } catch (RemoteException ex) {
                throw new SecurityException("Unable to validate virtual device");
            }
            final VirtualDeviceManagerInternal localVdm =
                    getLocalService(VirtualDeviceManagerInternal.class);
            flags |= localVdm.getBaseVirtualDisplayFlags(virtualDevice);
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
        if ((flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0) {
            flags &= ~VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP;
        }
        // Put the display in the virtual device's display group only if it's not a mirror display,
        // and if it doesn't need its own display group. So effectively, mirror displays go into the
        // default display group.
        if ((flags & VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP) == 0
                && (flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) == 0
                && virtualDevice != null) {
            flags |= VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP;
        }

        // Check if the host app is attempting to reuse the token or capture again on the same
        // MediaProjection instance. Don't start recording if so; MediaProjectionManagerService
        // decides how to respond based on the target SDK.
        boolean waitForPermissionConsent = false;
        final long firstToken = Binder.clearCallingIdentity();
        try {
            if (projection != null) {
                if (!getProjectionService().isCurrentProjection(projection)) {
                    throw new SecurityException("Cannot create VirtualDisplay with "
                            + "non-current MediaProjection");
                }
                if (!projection.isValid()) {
                    // Just log; MediaProjectionManagerService throws an exception.
                    Slog.w(TAG, "Reusing token: create virtual display for app reusing token");
                    // If the exception wasn't thrown, we continue and re-show the permission dialog
                    getProjectionService().requestConsentForInvalidProjection(projection);
                    // Declare that mirroring shouldn't begin until user reviews the permission
                    // dialog.
                    waitForPermissionConsent = true;
                }
                flags = projection.applyVirtualDisplayFlags(flags);
            }
        } catch (RemoteException e) {
            throw new SecurityException("Unable to validate media projection or flags", e);
        } finally {
            Binder.restoreCallingIdentity(firstToken);
        }

        if (callingUid != Process.SYSTEM_UID
                && (flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0) {
            // Only a valid media projection or a virtual device can create a mirror virtual
            // display.
            if (!canProjectVideo(projection)
                    && !isMirroringSupportedByVirtualDevice(virtualDevice)) {
                throw new SecurityException("Requires CAPTURE_VIDEO_OUTPUT or "
                        + "CAPTURE_SECURE_VIDEO_OUTPUT permission, or an appropriate "
                        + "MediaProjection token in order to create a screen sharing virtual "
                        + "display. In order to create a virtual display that does not perform "
                        + "screen sharing (mirroring), please use the flag "
                        + "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY.");
            }
        }
        if (callingUid != Process.SYSTEM_UID && (flags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0) {
            if (!canProjectSecureVideo(projection)) {
                throw new SecurityException("Requires CAPTURE_SECURE_VIDEO_OUTPUT "
                        + "or an appropriate MediaProjection token to create a "
                        + "secure virtual display.");
            }
        }

        if (callingUid != Process.SYSTEM_UID && (flags & VIRTUAL_DISPLAY_FLAG_TRUSTED) != 0) {
            if (!checkCallingPermission(ADD_TRUSTED_DISPLAY, "createVirtualDisplay()")) {
                EventLog.writeEvent(0x534e4554, "162627132", callingUid,
                        "Attempt to create a trusted display without holding permission!");
                throw new SecurityException("Requires ADD_TRUSTED_DISPLAY permission to "
                        + "create a trusted virtual display.");
            }
        }

        // Mirror virtual displays created by a virtual device are not allowed to show
        // presentations.
        if (virtualDevice != null && (flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0
                && (flags & VIRTUAL_DISPLAY_FLAG_PRESENTATION) != 0) {
            Slog.d(TAG, "Mirror displays created by a virtual device cannot show "
                    + "presentations, hence ignoring flag VIRTUAL_DISPLAY_FLAG_PRESENTATION.");
            flags &= ~VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        }

        if (callingUid != Process.SYSTEM_UID
                && (flags & VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP) != 0) {
            // The virtualDevice instance has been validated above using isValidVirtualDevice
            if (virtualDevice == null
                    && !checkCallingPermission(ADD_TRUSTED_DISPLAY, "createVirtualDisplay()")) {
                throw new SecurityException("Requires ADD_TRUSTED_DISPLAY permission to "
                        + "create a virtual display which is not in the default DisplayGroup.");
            }
        }

        if ((flags & VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED) != 0) {
            if (callingUid != Process.SYSTEM_UID
                    && !checkCallingPermission(ADD_ALWAYS_UNLOCKED_DISPLAY,
                    "createVirtualDisplay()")) {
                throw new SecurityException(
                        "Requires ADD_ALWAYS_UNLOCKED_DISPLAY permission to "
                                + "create an always unlocked virtual display.");
            }
        }

        if ((flags & VIRTUAL_DISPLAY_FLAG_TRUSTED) == 0) {
            flags &= ~VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
        }

        // Sometimes users can have sensitive information in system decoration windows. An app
        // could create a virtual display with system decorations support and read the user info
        // from the surface.
        // We should only allow adding flag VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
        // to trusted virtual displays.
        final int trustedDisplayWithSysDecorFlag =
                (VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                        | VIRTUAL_DISPLAY_FLAG_TRUSTED);
        if ((flags & trustedDisplayWithSysDecorFlag)
                == VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                && !checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "createVirtualDisplay()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        final long secondToken = Binder.clearCallingIdentity();
        try {
            final int displayId;
            final String displayUniqueId = VirtualDisplayAdapter.generateDisplayUniqueId(
                    packageName, callingUid, virtualDisplayConfig);

            if (virtualDisplayConfig.isHomeSupported()) {
                if ((flags & VIRTUAL_DISPLAY_FLAG_TRUSTED) == 0) {
                    Slog.w(TAG, "Display created with home support but lacks "
                            + "VIRTUAL_DISPLAY_FLAG_TRUSTED, ignoring the home support request.");
                } else if ((flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0) {
                    Slog.w(TAG, "Display created with home support but has "
                            + "VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ignoring the home support "
                            + "request.");
                } else {
                    mWindowManagerInternal.setHomeSupportedOnDisplay(displayUniqueId,
                            Display.TYPE_VIRTUAL, true);
                }
            }

            synchronized (mSyncRoot) {
                displayId =
                        createVirtualDisplayLocked(
                                callback,
                                projection,
                                callingUid,
                                packageName,
                                displayUniqueId,
                                virtualDevice,
                                surface,
                                flags,
                                virtualDisplayConfig);
                if (displayId != Display.INVALID_DISPLAY && virtualDevice != null && dwpc != null) {
                    mDisplayWindowPolicyControllers.put(
                            displayId, Pair.create(virtualDevice, dwpc));
                    Slog.d(TAG, "Virtual Display: successfully created virtual display");
                }
            }

            if (displayId == Display.INVALID_DISPLAY && virtualDisplayConfig.isHomeSupported()
                    && (flags & VIRTUAL_DISPLAY_FLAG_TRUSTED) != 0) {
                // Failed to create the virtual display, so we should clean up the WM settings
                // because it won't receive the onDisplayRemoved callback.
                mWindowManagerInternal.clearDisplaySettings(displayUniqueId, Display.TYPE_VIRTUAL);
            }

            // Build a session describing the MediaProjection instance, if there is one. A session
            // for a VirtualDisplay or physical display mirroring is handled in DisplayContent.
            ContentRecordingSession session = null;
            try {
                if (projection != null) {
                    IBinder taskWindowContainerToken = projection.getLaunchCookie() == null ? null
                            : projection.getLaunchCookie().binder;
                    if (taskWindowContainerToken == null) {
                        // Record a particular display.
                        session = ContentRecordingSession.createDisplaySession(
                                virtualDisplayConfig.getDisplayIdToMirror());
                    } else {
                        // Record a single task indicated by the launch cookie.
                        session = ContentRecordingSession.createTaskSession(
                                taskWindowContainerToken);
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to retrieve the projection's launch cookie", e);
            }

            // Ensure session details are only set when mirroring (through VirtualDisplay flags or
            // MediaProjection).
            final boolean shouldMirror =
                    projection != null || (flags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0;
            // When calling WindowManagerService#setContentRecordingSession, WindowManagerService
            // attempts to acquire a lock before executing its main body. Due to this, we need
            // to be sure that it isn't called while the DisplayManagerService is also holding
            // a lock, to avoid a deadlock scenario.
            if (shouldMirror && displayId != Display.INVALID_DISPLAY && session != null) {
                // Only attempt to set content recording session if there are details to set and a
                // VirtualDisplay has been successfully constructed.
                session.setVirtualDisplayId(displayId);
                // Don't start mirroring until user re-grants consent.
                session.setWaitingForConsent(waitForPermissionConsent);

                // We set the content recording session here on the server side instead of using
                // a second AIDL call in MediaProjection. By ensuring that a virtual display has
                // been constructed before calling setContentRecordingSession, we avoid a race
                // condition between the DisplayManagerService & WindowManagerService which could
                // lead to the MediaProjection being pre-emptively torn down.
                try {
                    if (!getProjectionService().setContentRecordingSession(session, projection)) {
                        // Unable to start mirroring, so release VirtualDisplay. Projection service
                        // handles stopping the projection.
                        Slog.w(TAG, "Content Recording: failed to start mirroring - "
                                + "releasing virtual display " + displayId);
                        releaseVirtualDisplayInternal(callback.asBinder());
                        return Display.INVALID_DISPLAY;
                    } else if (projection != null) {
                        // Indicate that this projection has been used to record, and can't be used
                        // again.
                        Slog.d(TAG, "Content Recording: notifying MediaProjection of successful"
                                + " VirtualDisplay creation.");
                        projection.notifyVirtualDisplayCreated(displayId);
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to tell MediaProjectionManagerService to set the "
                            + "content recording session", e);
                    return displayId;
                }
                Slog.d(TAG, "Virtual Display: successfully set up virtual display "
                        + displayId);
            }
            return displayId;
        } finally {
            Binder.restoreCallingIdentity(secondToken);
        }
    }

    private int createVirtualDisplayLocked(
            IVirtualDisplayCallback callback,
            IMediaProjection projection,
            int callingUid,
            String packageName,
            String uniqueId,
            IVirtualDevice virtualDevice,
            Surface surface,
            int flags,
            VirtualDisplayConfig virtualDisplayConfig) {
        if (mVirtualDisplayAdapter == null) {
            Slog.w(
                    TAG,
                    "Rejecting request to create private virtual display "
                            + "because the virtual display adapter is not available.");
            return -1;
        }

        Slog.d(TAG, "Virtual Display: creating DisplayDevice with VirtualDisplayAdapter");
        DisplayDevice device = mVirtualDisplayAdapter.createVirtualDisplayLocked(
                callback, projection, callingUid, packageName, uniqueId, surface, flags,
                virtualDisplayConfig);
        if (device == null) {
            Slog.w(TAG, "Virtual Display: VirtualDisplayAdapter failed to create DisplayDevice");
            return -1;
        }

        // If the display is to be added to a device display group, we need to make the
        // LogicalDisplayMapper aware of the link between the new display and its associated virtual
        // device before triggering DISPLAY_DEVICE_EVENT_ADDED.
        if ((flags & VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP) != 0) {
            if (virtualDevice != null) {
                try {
                    final int virtualDeviceId = virtualDevice.getDeviceId();
                    mLogicalDisplayMapper.associateDisplayDeviceWithVirtualDevice(
                            device, virtualDeviceId);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            } else {
                Slog.i(
                        TAG,
                        "Display created with VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP set, but no"
                            + " virtual device. The display will not be added to a device display"
                            + " group.");
            }
        }

        // DisplayDevice events are handled manually for Virtual Displays.
        // TODO: multi-display Fix this so that generic add/remove events are not handled in a
        // different code path for virtual displays.  Currently this happens so that we can
        // return a valid display ID synchronously upon successful Virtual Display creation.
        // This code can run on any binder thread, while onDisplayDeviceAdded() callbacks are
        // called on the DisplayThread (which we don't want to wait for?).
        // One option would be to actually wait here on the binder thread
        // to be notified when the virtual display is created (or failed).
        mDisplayDeviceRepo.onDisplayDeviceEvent(device, DisplayAdapter.DISPLAY_DEVICE_EVENT_ADDED);

        final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
        if (display != null) {
            return display.getDisplayIdLocked();
        }

        // Something weird happened and the logical display was not created.
        Slog.w(TAG, "Rejecting request to create virtual display "
                + "because the logical display was not created.");
        mVirtualDisplayAdapter.releaseVirtualDisplayLocked(callback.asBinder());
        mDisplayDeviceRepo.onDisplayDeviceEvent(device,
                DisplayAdapter.DISPLAY_DEVICE_EVENT_REMOVED);
        return -1;
    }

    private static boolean isMirroringSupportedByVirtualDevice(IVirtualDevice virtualDevice) {
        return Flags.interactiveScreenMirror() && virtualDevice != null;
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
            Slog.d(TAG, "Virtual Display: Display Device released");
            if (device != null) {
                // TODO: multi-display - handle virtual displays the same as other display adapters.
                mDisplayDeviceRepo.onDisplayDeviceEvent(device,
                        DisplayAdapter.DISPLAY_DEVICE_EVENT_REMOVED);
            }
        }
    }

    private void setVirtualDisplayStateInternal(IBinder appToken, boolean isOn) {
        synchronized (mSyncRoot) {
            if (mVirtualDisplayAdapter == null) {
                return;
            }

            mVirtualDisplayAdapter.setVirtualDisplayStateLocked(appToken, isOn);
        }
    }

    private void registerDefaultDisplayAdapters() {
        // Register default display adapters.
        synchronized (mSyncRoot) {
            // main display adapter
            registerDisplayAdapterLocked(mInjector.getLocalDisplayAdapter(mSyncRoot, mContext,
                    mHandler, mDisplayDeviceRepo, mFlags,
                    mDisplayNotificationManager));

            // Standalone VR devices rely on a virtual display as their primary display for
            // 2D UI. We register virtual display adapter along side the main display adapter
            // here so that it is ready by the time the system sends the home Intent for
            // early apps like SetupWizard/Launcher. In particular, SUW is displayed using
            // the virtual display inside VR before any VR-specific apps even run.
            mVirtualDisplayAdapter = mInjector.getVirtualDisplayAdapter(mSyncRoot, mContext,
                    mHandler, mDisplayDeviceRepo, mFlags);
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
                mSyncRoot, mContext, mHandler, mDisplayDeviceRepo, mUiHandler, mFlags));
    }

    private void registerWifiDisplayAdapterLocked() {
        if (mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableWifiDisplay)
                || SystemProperties.getInt(FORCE_WIFI_DISPLAY_ENABLE, -1) == 1) {
            mWifiDisplayAdapter = new WifiDisplayAdapter(
                    mSyncRoot, mContext, mHandler, mDisplayDeviceRepo,
                    mPersistentDataStore, mFlags);
            registerDisplayAdapterLocked(mWifiDisplayAdapter);
        }
    }

    private boolean shouldRegisterNonEssentialDisplayAdaptersLocked() {
        // In safe mode, we disable non-essential display adapters to give the user
        // an opportunity to fix broken settings or other problems that might affect
        // system stability.
        return !mSafeMode;
    }

    private void registerDisplayAdapterLocked(DisplayAdapter adapter) {
        mDisplayAdapters.add(adapter);
        adapter.registerLocked();
    }

    @GuardedBy("mSyncRoot")
    private void handleLogicalDisplayDisconnectedLocked(LogicalDisplay display) {
        if (!mFlags.isConnectedDisplayManagementEnabled()) {
            Slog.e(TAG, "DisplayDisconnected shouldn't be received when the flag is off");
            return;
        }
        releaseDisplayAndEmitEvent(display, DisplayManagerGlobal.EVENT_DISPLAY_DISCONNECTED);
        mExternalDisplayPolicy.handleLogicalDisplayDisconnectedLocked(display);
    }

    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    private void setupLogicalDisplay(LogicalDisplay display) {
        final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
        final int displayId = display.getDisplayIdLocked();
        final boolean isDefault = displayId == Display.DEFAULT_DISPLAY;
        configureColorModeLocked(display, device);
        if (!mAreUserDisabledHdrTypesAllowed) {
            display.setUserDisabledHdrTypes(mUserDisabledHdrTypes);
        }
        if (isDefault) {
            notifyDefaultDisplayDeviceUpdated(display);
            recordStableDisplayStatsIfNeededLocked(display);
            recordTopInsetLocked(display);
        }
        if (mUserPreferredMode != null) {
            device.setUserPreferredDisplayModeLocked(mUserPreferredMode);
        } else {
            configurePreferredDisplayModeLocked(display);
        }

        DisplayPowerControllerInterface dpc = addDisplayPowerControllerLocked(display);
        if (dpc != null) {
            final int leadDisplayId = display.getLeadDisplayIdLocked();
            updateDisplayPowerControllerLeaderLocked(dpc, leadDisplayId);

            // Loop through all the displays and check if any should follow this one - it could be
            // that the follower display was added before the lead display.
            mLogicalDisplayMapper.forEachLocked(d -> {
                if (d.getLeadDisplayIdLocked() == displayId) {
                    DisplayPowerControllerInterface followerDpc =
                            mDisplayPowerControllers.get(d.getDisplayIdLocked());
                    if (followerDpc != null) {
                        updateDisplayPowerControllerLeaderLocked(followerDpc, displayId);
                    }
                }
            });
        }

        mDisplayStates.append(displayId, Display.STATE_UNKNOWN);

        final float brightnessDefault = display.getDisplayInfoLocked().brightnessDefault;
        mDisplayBrightnesses.append(displayId,
                new BrightnessPair(brightnessDefault, brightnessDefault));

        DisplayManagerGlobal.invalidateLocalDisplayInfoCaches();
    }

    private void updateLogicalDisplayState(LogicalDisplay display) {
        Runnable work = updateDisplayStateLocked(display.getPrimaryDisplayDeviceLocked());
        if (work != null) {
            work.run();
        }
        scheduleTraversalLocked(false);
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void handleLogicalDisplayConnectedLocked(LogicalDisplay display) {
        if (!mFlags.isConnectedDisplayManagementEnabled()) {
            Slog.e(TAG, "DisplayConnected shouldn't be received when the flag is off");
            return;
        }

        setupLogicalDisplay(display);

        if (ExternalDisplayPolicy.isExternalDisplayLocked(display)) {
            mExternalDisplayPolicy.handleExternalDisplayConnectedLocked(display);
        } else {
            sendDisplayEventLocked(display, DisplayManagerGlobal.EVENT_DISPLAY_CONNECTED);
        }

        updateLogicalDisplayState(display);
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void handleLogicalDisplayAddedLocked(LogicalDisplay display) {
        final int displayId = display.getDisplayIdLocked();
        final boolean isDefault = displayId == Display.DEFAULT_DISPLAY;
        if (!mFlags.isConnectedDisplayManagementEnabled()) {
            setupLogicalDisplay(display);
        }

        // Wake up waitForDefaultDisplay.
        if (isDefault) {
            mSyncRoot.notifyAll();
        }

        sendDisplayEventIfEnabledLocked(display, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);

        updateLogicalDisplayState(display);

        mExternalDisplayPolicy.handleLogicalDisplayAddedLocked(display);
    }

    private void handleLogicalDisplayChangedLocked(@NonNull LogicalDisplay display) {
        updateViewportPowerStateLocked(display);

        final int displayId = display.getDisplayIdLocked();
        if (displayId == Display.DEFAULT_DISPLAY) {
            recordTopInsetLocked(display);
        }
        // We don't bother invalidating the display info caches here because any changes to the
        // display info will trigger a cache invalidation inside of LogicalDisplay before we hit
        // this point.
        sendDisplayEventIfEnabledLocked(display, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);

        applyDisplayChangedLocked(display);
    }

    private void applyDisplayChangedLocked(@NonNull LogicalDisplay display) {
        final int displayId = display.getDisplayIdLocked();
        scheduleTraversalLocked(false);
        mPersistentDataStore.saveIfNeeded();

        DisplayPowerControllerInterface dpc = mDisplayPowerControllers.get(displayId);
        if (dpc != null) {
            final int leadDisplayId = display.getLeadDisplayIdLocked();
            updateDisplayPowerControllerLeaderLocked(dpc, leadDisplayId);

            HighBrightnessModeMetadata hbmMetadata =
                    mHighBrightnessModeMetadataMapper.getHighBrightnessModeMetadataLocked(display);
            if (hbmMetadata != null) {
                dpc.onDisplayChanged(hbmMetadata, leadDisplayId);
            }
        }
    }

    private void updateDisplayPowerControllerLeaderLocked(
            @NonNull DisplayPowerControllerInterface dpc, int leadDisplayId) {
        if (dpc.getLeadDisplayId() == leadDisplayId) {
            // Lead display hasn't changed, nothing to do.
            return;
        }

        // If it has changed, then we need to unregister from the previous leader if there was one.
        final int prevLeaderId = dpc.getLeadDisplayId();
        if (prevLeaderId != Layout.NO_LEAD_DISPLAY) {
            final DisplayPowerControllerInterface prevLeader =
                    mDisplayPowerControllers.get(prevLeaderId);
            if (prevLeader != null) {
                prevLeader.removeDisplayBrightnessFollower(dpc);
            }
        }

        // And then, if it's following, register it with the new one.
        if (leadDisplayId != Layout.NO_LEAD_DISPLAY) {
            final DisplayPowerControllerInterface newLeader =
                    mDisplayPowerControllers.get(leadDisplayId);
            if (newLeader != null) {
                newLeader.addDisplayBrightnessFollower(dpc);
            }
        }
    }

    private void handleLogicalDisplayFrameRateOverridesChangedLocked(
            @NonNull LogicalDisplay display) {
        final int displayId = display.getDisplayIdLocked();
        // We don't bother invalidating the display info caches here because any changes to the
        // display info will trigger a cache invalidation inside of LogicalDisplay before we hit
        // this point.
        sendDisplayEventFrameRateOverrideLocked(displayId);
        scheduleTraversalLocked(false);
    }

    private void handleLogicalDisplayRemovedLocked(@NonNull LogicalDisplay display) {
        // With display management, the display is removed when disabled, and it might still exist.
        // Resources must only be released when the disconnected signal is received.
        if (mFlags.isConnectedDisplayManagementEnabled()) {
            if (display.isValidLocked()) {
                updateViewportPowerStateLocked(display);
            }

            // Note: This method is only called if the display was enabled before being removed.
            sendDisplayEventLocked(display, DisplayManagerGlobal.EVENT_DISPLAY_REMOVED);

            if (display.isValidLocked()) {
                applyDisplayChangedLocked(display);
            }
            return;
        }

        releaseDisplayAndEmitEvent(display, DisplayManagerGlobal.EVENT_DISPLAY_REMOVED);
    }

    private void releaseDisplayAndEmitEvent(LogicalDisplay display, int event) {
        final int displayId = display.getDisplayIdLocked();

        final DisplayPowerControllerInterface dpc =
                mDisplayPowerControllers.removeReturnOld(displayId);
        if (dpc != null) {
            updateDisplayPowerControllerLeaderLocked(dpc, Layout.NO_LEAD_DISPLAY);
            dpc.stop();
        }
        mDisplayStates.delete(displayId);
        mDisplayBrightnesses.delete(displayId);
        DisplayManagerGlobal.invalidateLocalDisplayInfoCaches();

        if (mDisplayWindowPolicyControllers.contains(displayId)) {
            final IVirtualDevice virtualDevice =
                    mDisplayWindowPolicyControllers.removeReturnOld(displayId).first;
            if (virtualDevice != null) {
                mHandler.post(() -> {
                    getLocalService(VirtualDeviceManagerInternal.class)
                            .onVirtualDisplayRemoved(virtualDevice, displayId);
                });
            }
        }

        sendDisplayEventLocked(display, event);
        scheduleTraversalLocked(false);
    }

    private void handleLogicalDisplaySwappedLocked(@NonNull LogicalDisplay display) {
        handleLogicalDisplayChangedLocked(display);

        final int displayId = display.getDisplayIdLocked();
        if (displayId == Display.DEFAULT_DISPLAY) {
            notifyDefaultDisplayDeviceUpdated(display);
        }
        mHandler.sendEmptyMessage(MSG_LOAD_BRIGHTNESS_CONFIGURATIONS);
    }

    private void handleLogicalDisplayHdrSdrRatioChangedLocked(@NonNull LogicalDisplay display) {
        sendDisplayEventIfEnabledLocked(display,
                DisplayManagerGlobal.EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED);
    }

    private void notifyDefaultDisplayDeviceUpdated(LogicalDisplay display) {
        mDisplayModeDirector.defaultDisplayDeviceUpdated(display.getPrimaryDisplayDeviceLocked()
                .mDisplayDeviceConfig);
    }

    private void handleLogicalDisplayDeviceStateTransitionLocked(@NonNull LogicalDisplay display) {
        final int displayId = display.getDisplayIdLocked();
        final DisplayPowerControllerInterface dpc = mDisplayPowerControllers.get(displayId);
        if (dpc != null) {
            final int leadDisplayId = display.getLeadDisplayIdLocked();
            updateDisplayPowerControllerLeaderLocked(dpc, leadDisplayId);

            HighBrightnessModeMetadata hbmMetadata =
                    mHighBrightnessModeMetadataMapper.getHighBrightnessModeMetadataLocked(display);
            if (hbmMetadata != null) {
                dpc.onDisplayChanged(hbmMetadata, leadDisplayId);
            }
        }
    }

    private Runnable updateDisplayStateLocked(DisplayDevice device) {
        // Blank or unblank the display immediately to match the state requested
        // by the display power controller (if known).
        DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        if ((info.flags & DisplayDeviceInfo.FLAG_NEVER_BLANK) == 0) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
            if (display == null) {
                return null;
            }
            final int displayId = display.getDisplayIdLocked();
            final int state = mDisplayStates.get(displayId);

            // Only send a request for display state if display state has already been initialized.
            if (state != Display.STATE_UNKNOWN) {
                final BrightnessPair brightnessPair = mDisplayBrightnesses.get(displayId);
                return device.requestDisplayStateLocked(
                        state,
                        brightnessPair.brightness,
                        brightnessPair.sdrBrightness,
                        display.getDisplayOffloadSessionLocked());
            }
        }
        return null;
    }

    private void configureColorModeLocked(LogicalDisplay display, DisplayDevice device) {
        if (display.getPrimaryDisplayDeviceLocked() == device) {
            int colorMode = mPersistentDataStore.getColorMode(device);
            if (colorMode == Display.COLOR_MODE_INVALID) {
                if (display.getDisplayIdLocked() == Display.DEFAULT_DISPLAY) {
                    colorMode = mDefaultDisplayDefaultColorMode;
                } else {
                    colorMode = Display.COLOR_MODE_DEFAULT;
                }
            }
            display.setRequestedColorModeLocked(colorMode);
        }
    }

    private void configurePreferredDisplayModeLocked(LogicalDisplay display) {
        final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
        final Point userPreferredResolution =
                mPersistentDataStore.getUserPreferredResolution(device);
        final float refreshRate = mPersistentDataStore.getUserPreferredRefreshRate(device);
        if (userPreferredResolution == null && Float.isNaN(refreshRate)) {
            return;
        }
        Display.Mode.Builder modeBuilder = new Display.Mode.Builder();
        if (userPreferredResolution != null) {
            modeBuilder.setResolution(userPreferredResolution.x, userPreferredResolution.y);
        }
        if (!Float.isNaN(refreshRate)) {
            modeBuilder.setRefreshRate(refreshRate);
        }
        device.setUserPreferredDisplayModeLocked(modeBuilder.build());
    }

    @GuardedBy("mSyncRoot")
    private void storeHdrConversionModeLocked(HdrConversionMode hdrConversionMode) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.HDR_CONVERSION_MODE, hdrConversionMode.getConversionMode());
        final int preferredHdrOutputType =
                hdrConversionMode.getConversionMode() == HdrConversionMode.HDR_CONVERSION_FORCE
                        ? hdrConversionMode.getPreferredHdrOutputType()
                        : Display.HdrCapabilities.HDR_TYPE_INVALID;
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.HDR_FORCE_CONVERSION_TYPE, preferredHdrOutputType);
    }

    @GuardedBy("mSyncRoot")
    void updateHdrConversionModeSettingsLocked() {
        final int conversionMode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.HDR_CONVERSION_MODE, mDefaultHdrConversionMode);
        final int preferredHdrOutputType = conversionMode == HdrConversionMode.HDR_CONVERSION_FORCE
                ? Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.HDR_FORCE_CONVERSION_TYPE,
                        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
                : Display.HdrCapabilities.HDR_TYPE_INVALID;
        mHdrConversionMode = new HdrConversionMode(conversionMode, preferredHdrOutputType);
        setHdrConversionModeInternal(mHdrConversionMode);
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

    private void recordTopInsetLocked(@Nullable LogicalDisplay d) {
        // We must only persist the inset after boot has completed, otherwise we will end up
        // overwriting the persisted value before the masking flag has been loaded from the
        // resource overlay.
        if (!mSystemReady || d == null) {
            return;
        }
        int topInset = d.getInsets().top;
        if (topInset == mDefaultDisplayTopInset) {
            return;
        }
        mDefaultDisplayTopInset = topInset;
        SystemProperties.set(PROP_DEFAULT_DISPLAY_TOP_INSET, Integer.toString(topInset));
    }

    private void setStableDisplaySizeLocked(int width, int height) {
        mStableDisplaySize = new Point(width, height);
        try {
            mPersistentDataStore.setStableDisplaySize(mStableDisplaySize);
        } finally {
            mPersistentDataStore.saveIfNeeded();
        }
    }

    @VisibleForTesting
    Curve getMinimumBrightnessCurveInternal() {
        return mMinimumBrightnessCurve;
    }

    int getPreferredWideGamutColorSpaceIdInternal() {
        return mWideColorSpace.getId();
    }

    OverlayProperties getOverlaySupportInternal() {
        return mOverlayProperties;
    }

    void setUserPreferredDisplayModeInternal(int displayId, Display.Mode mode) {
        synchronized (mSyncRoot) {
            if (mode != null && !isResolutionAndRefreshRateValid(mode)
                    && displayId == Display.INVALID_DISPLAY) {
                throw new IllegalArgumentException("width, height and refresh rate of mode should "
                        + "be greater than 0 when setting the global user preferred display mode.");
            }

            final int resolutionHeight = mode == null ? Display.INVALID_DISPLAY_HEIGHT
                    : mode.getPhysicalHeight();
            final int resolutionWidth = mode == null ? Display.INVALID_DISPLAY_WIDTH
                    : mode.getPhysicalWidth();
            final float refreshRate = mode == null ? Display.INVALID_DISPLAY_REFRESH_RATE
                    : mode.getRefreshRate();

            storeModeInPersistentDataStoreLocked(
                    displayId, resolutionWidth, resolutionHeight, refreshRate);
            if (displayId != Display.INVALID_DISPLAY) {
                setUserPreferredModeForDisplayLocked(displayId, mode);
            } else {
                mUserPreferredMode = mode;
                storeModeInGlobalSettingsLocked(
                        resolutionWidth, resolutionHeight, refreshRate, mode);
            }
        }
    }

    private void storeModeInPersistentDataStoreLocked(int displayId, int resolutionWidth,
            int resolutionHeight, float refreshRate) {
        DisplayDevice displayDevice = getDeviceForDisplayLocked(displayId);
        if (displayDevice == null) {
            return;
        }
        try {
            mPersistentDataStore.setUserPreferredResolution(
                    displayDevice, resolutionWidth, resolutionHeight);
            mPersistentDataStore.setUserPreferredRefreshRate(displayDevice, refreshRate);
        } finally {
            mPersistentDataStore.saveIfNeeded();
        }
    }

    private void setUserPreferredModeForDisplayLocked(int displayId, Display.Mode mode) {
        DisplayDevice displayDevice = getDeviceForDisplayLocked(displayId);
        if (displayDevice == null) {
            return;
        }

        // We do not yet support backup and restore for our PersistentDataStore, however, we want to
        // preserve the user's choice for HIGH/FULL resolution setting, so we when we are given a
        // a new resolution for the default display (normally stored in PDS), we will also save it
        // to a setting that is backed up.
        // TODO(b/330943343) - Consider a full fidelity DisplayBackupHelper for this instead.
        if (mFlags.isResolutionBackupRestoreEnabled() && displayId == Display.DEFAULT_DISPLAY) {
            // Checks to see which of the two resolutions is selected
            // TODO(b/330906790) Uses the same logic as Settings, but should be made to support
            //     more than two resolutions using explicit mode enums long-term.
            Point[] resolutions = displayDevice.getSupportedResolutionsLocked();
            if (resolutions.length == 2) {
                Point newMode = new Point(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                int resolutionMode = newMode.equals(resolutions[0]) ? RESOLUTION_MODE_HIGH
                        : newMode.equals(resolutions[1]) ? RESOLUTION_MODE_FULL
                        : RESOLUTION_MODE_UNKNOWN;
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.SCREEN_RESOLUTION_MODE, resolutionMode,
                        UserHandle.USER_CURRENT);
            }
        }

        displayDevice.setUserPreferredDisplayModeLocked(mode);
    }

    private void storeModeInGlobalSettingsLocked(
            int resolutionWidth, int resolutionHeight, float refreshRate, Display.Mode mode) {
        Settings.Global.putFloat(mContext.getContentResolver(),
                Settings.Global.USER_PREFERRED_REFRESH_RATE, refreshRate);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.USER_PREFERRED_RESOLUTION_HEIGHT, resolutionHeight);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.USER_PREFERRED_RESOLUTION_WIDTH, resolutionWidth);
        mDisplayDeviceRepo.forEachLocked((DisplayDevice device) -> {
            device.setUserPreferredDisplayModeLocked(mode);
        });
    }

    @GuardedBy("mSyncRoot")
    private int[] getEnabledAutoHdrTypesLocked() {
        IntArray autoHdrOutputTypesArray = new IntArray();
        for (int type : getSupportedHdrOutputTypesInternal()) {
            boolean isDisabled = false;
            for (int disabledType : mUserDisabledHdrTypes) {
                if (type == disabledType) {
                    isDisabled = true;
                    break;
                }
            }
            if (!isDisabled) {
                autoHdrOutputTypesArray.add(type);
            }
        }
        return autoHdrOutputTypesArray.toArray();
    }

    @GuardedBy("mSyncRoot")
    private boolean hdrConversionIntroducesLatencyLocked() {
        HdrConversionMode mode = getHdrConversionModeSettingInternal();
        final int preferredHdrOutputType =
                mode.getConversionMode() == HdrConversionMode.HDR_CONVERSION_SYSTEM
                        ? mSystemPreferredHdrOutputType : mode.getPreferredHdrOutputType();
        if (preferredHdrOutputType != Display.HdrCapabilities.HDR_TYPE_INVALID) {
            int[] hdrTypesWithLatency = mInjector.getHdrOutputTypesWithLatency();
            return ArrayUtils.contains(hdrTypesWithLatency, preferredHdrOutputType);
        }
        return false;
    }

    Display.Mode getUserPreferredDisplayModeInternal(int displayId) {
        synchronized (mSyncRoot) {
            if (displayId == Display.INVALID_DISPLAY) {
                return mUserPreferredMode;
            }
            DisplayDevice displayDevice = getDeviceForDisplayLocked(displayId);
            if (displayDevice == null) {
                return null;
            }
            return displayDevice.getUserPreferredDisplayModeLocked();
        }
    }

    Display.Mode getSystemPreferredDisplayModeInternal(int displayId) {
        synchronized (mSyncRoot) {
            final DisplayDevice device = getDeviceForDisplayLocked(displayId);
            if (device == null) {
                return null;
            }
            return device.getSystemPreferredDisplayModeLocked();
        }
    }

    void setHdrConversionModeInternal(HdrConversionMode hdrConversionMode) {
        if (!mInjector.getHdrOutputConversionSupport()) {
            return;
        }
        int[] autoHdrOutputTypes = null;
        synchronized (mSyncRoot) {
            if (hdrConversionMode.getConversionMode() == HdrConversionMode.HDR_CONVERSION_SYSTEM
                    && hdrConversionMode.getPreferredHdrOutputType()
                    != Display.HdrCapabilities.HDR_TYPE_INVALID) {
                throw new IllegalArgumentException("preferredHdrOutputType must not be set if"
                        + " the conversion mode is HDR_CONVERSION_SYSTEM");
            }
            mHdrConversionMode = hdrConversionMode;
            storeHdrConversionModeLocked(mHdrConversionMode);

            // For auto mode, all supported HDR types are allowed except the ones specifically
            // disabled by the user.
            if (hdrConversionMode.getConversionMode() == HdrConversionMode.HDR_CONVERSION_SYSTEM) {
                autoHdrOutputTypes = getEnabledAutoHdrTypesLocked();
            }

            int conversionMode = hdrConversionMode.getConversionMode();
            int preferredHdrType = hdrConversionMode.getPreferredHdrOutputType();
            // If the HDR conversion is disabled by an app through WindowManager.LayoutParams, then
            // set HDR conversion mode to HDR_CONVERSION_PASSTHROUGH.
            if (mOverrideHdrConversionMode == null) {
                // HDR_CONVERSION_FORCE with HDR_TYPE_INVALID is used to represent forcing SDR type.
                // But, internally SDR is selected by using passthrough mode.
                if (conversionMode == HdrConversionMode.HDR_CONVERSION_FORCE
                        && preferredHdrType == Display.HdrCapabilities.HDR_TYPE_INVALID) {
                    conversionMode = HdrConversionMode.HDR_CONVERSION_PASSTHROUGH;
                }
            } else {
                conversionMode = mOverrideHdrConversionMode.getConversionMode();
                preferredHdrType = mOverrideHdrConversionMode.getPreferredHdrOutputType();
                autoHdrOutputTypes = null;
            }
            mSystemPreferredHdrOutputType = mInjector.setHdrConversionMode(
                    conversionMode, preferredHdrType, autoHdrOutputTypes);
        }
    }

    HdrConversionMode getHdrConversionModeSettingInternal() {
        if (!mInjector.getHdrOutputConversionSupport()) {
            return HDR_CONVERSION_MODE_UNSUPPORTED;
        }
        synchronized (mSyncRoot) {
            if (mHdrConversionMode != null) {
                return mHdrConversionMode;
            }
        }
        return new HdrConversionMode(mDefaultHdrConversionMode);
    }

    HdrConversionMode getHdrConversionModeInternal() {
        if (!mInjector.getHdrOutputConversionSupport()) {
            return HDR_CONVERSION_MODE_UNSUPPORTED;
        }
        HdrConversionMode mode;
        synchronized (mSyncRoot) {
            mode = mOverrideHdrConversionMode != null
                    ? mOverrideHdrConversionMode
                    : mHdrConversionMode;
            // Handle default: PASSTHROUGH. Don't include the system-preferred type.
            if (mode == null
                    && mDefaultHdrConversionMode == HdrConversionMode.HDR_CONVERSION_PASSTHROUGH) {
                return new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_PASSTHROUGH);
            }
            // Handle default or current mode: SYSTEM. Include the system preferred type.
            // mOverrideHdrConversionMode and mHdrConversionMode do not include the system
            // preferred type, it is kept separately in mSystemPreferredHdrOutputType.
            if (mode == null
                    || mode.getConversionMode() == HdrConversionMode.HDR_CONVERSION_SYSTEM) {
                return new HdrConversionMode(
                        HdrConversionMode.HDR_CONVERSION_SYSTEM, mSystemPreferredHdrOutputType);
            }
        }
        return mode;
    }

    private @Display.HdrCapabilities.HdrType int[] getSupportedHdrOutputTypesInternal() {
        if (mSupportedHdrOutputType == null) {
            mSupportedHdrOutputType = mInjector.getSupportedHdrOutputTypes();
        }
        return mSupportedHdrOutputType;
    }

    void setShouldAlwaysRespectAppRequestedModeInternal(boolean enabled) {
        mDisplayModeDirector.setShouldAlwaysRespectAppRequestedMode(enabled);
    }

    boolean shouldAlwaysRespectAppRequestedModeInternal() {
        return mDisplayModeDirector.shouldAlwaysRespectAppRequestedMode();
    }

    void setRefreshRateSwitchingTypeInternal(@DisplayManager.SwitchingType int newValue) {
        mDisplayModeDirector.setModeSwitchingType(newValue);
    }

    @DisplayManager.SwitchingType
    int getRefreshRateSwitchingTypeInternal() {
        return mDisplayModeDirector.getModeSwitchingType();
    }

    private DisplayDecorationSupport getDisplayDecorationSupportInternal(int displayId) {
        final IBinder displayToken = getDisplayToken(displayId);
        if (null == displayToken) {
            return null;
        }
        return SurfaceControl.getDisplayDecorationSupport(displayToken);
    }

    private void setBrightnessConfigurationForDisplayInternal(
            @Nullable BrightnessConfiguration c, String uniqueId, @UserIdInt int userId,
            String packageName) {
        validateBrightnessConfiguration(c);
        final int userSerial = getUserManager().getUserSerialNumber(userId);
        synchronized (mSyncRoot) {
            try {
                DisplayDevice displayDevice = mDisplayDeviceRepo.getByUniqueIdLocked(uniqueId);
                if (displayDevice == null) {
                    return;
                }
                if (mLogicalDisplayMapper.getDisplayLocked(displayDevice) != null
                        && mLogicalDisplayMapper.getDisplayLocked(displayDevice)
                        .getDisplayInfoLocked().type == Display.TYPE_INTERNAL && c != null) {
                    FrameworkStatsLog.write(FrameworkStatsLog.BRIGHTNESS_CONFIGURATION_UPDATED,
                                c.getCurve().first,
                                c.getCurve().second,
                                // should not be logged for virtual displays
                                uniqueId);
                }
                mPersistentDataStore.setBrightnessConfigurationForDisplayLocked(c, displayDevice,
                        userSerial, packageName);
            } finally {
                mPersistentDataStore.saveIfNeeded();
            }
            if (userId != mCurrentUserId) {
                return;
            }
            DisplayPowerControllerInterface dpc = getDpcFromUniqueIdLocked(uniqueId);
            if (dpc != null) {
                dpc.setBrightnessConfiguration(c, /* shouldResetShortTermModel= */ true);
            }
        }
    }

    private DisplayPowerControllerInterface getDpcFromUniqueIdLocked(String uniqueId) {
        final DisplayDevice displayDevice = mDisplayDeviceRepo.getByUniqueIdLocked(uniqueId);
        final LogicalDisplay logicalDisplay = mLogicalDisplayMapper.getDisplayLocked(displayDevice);
        if (logicalDisplay != null) {
            final int displayId = logicalDisplay.getDisplayIdLocked();
            return mDisplayPowerControllers.get(displayId);
        }
        return null;
    }

    @VisibleForTesting
    void validateBrightnessConfiguration(BrightnessConfiguration config) {
        if (config == null) {
            return;
        }
        if (isBrightnessConfigurationTooDark(config)) {
            throw new IllegalArgumentException("brightness curve is too dark");
        }
    }

    private boolean isBrightnessConfigurationTooDark(BrightnessConfiguration config) {
        Pair<float[], float[]> curve = config.getCurve();
        float[] lux = curve.first;
        float[] nits = curve.second;
        for (int i = 0; i < lux.length; i++) {
            if (nits[i] < mMinimumBrightnessSpline.interpolate(lux[i])) {
                return true;
            }
        }
        return false;
    }

    private void loadBrightnessConfigurations() {
        int userSerial = getUserManager().getUserSerialNumber(mContext.getUserId());
        synchronized (mSyncRoot) {
            mLogicalDisplayMapper.forEachLocked((logicalDisplay) -> {
                final String uniqueId =
                        logicalDisplay.getPrimaryDisplayDeviceLocked().getUniqueId();
                final BrightnessConfiguration config =
                        getBrightnessConfigForDisplayWithPdsFallbackLocked(uniqueId, userSerial);
                if (config != null) {
                    final DisplayPowerControllerInterface dpc = mDisplayPowerControllers.get(
                            logicalDisplay.getDisplayIdLocked());
                    if (dpc != null) {
                        dpc.setBrightnessConfiguration(config,
                                /* shouldResetShortTermModel= */ false);
                    }
                }
            });
        }
    }

    private void performTraversalLocked(SurfaceControl.Transaction t,
            SparseArray<SurfaceControl.Transaction> displayTransactions) {
        // Clear all viewports before configuring displays so that we can keep
        // track of which ones we have configured.
        clearViewportsLocked();

        // Configure each display device.
        mLogicalDisplayMapper.forEachLocked((LogicalDisplay display) -> {
            final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
            final SurfaceControl.Transaction displayTransaction =
                    displayTransactions.get(display.getDisplayIdLocked(), t);
            if (device != null) {
                configureDisplayLocked(displayTransaction, device);
                device.performTraversalLocked(displayTransaction);
            }
        });

        // Tell the input system about these new viewports.
        if (mInputManagerInternal != null) {
            mHandler.sendEmptyMessage(MSG_UPDATE_VIEWPORT);
        }
    }

    void setDisplayPropertiesInternal(int displayId, boolean hasContent,
            float requestedRefreshRate, int requestedModeId, float requestedMinRefreshRate,
            float requestedMaxRefreshRate, boolean preferMinimalPostProcessing,
            boolean disableHdrConversion, boolean inTraversal) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display == null) {
                return;
            }

            boolean shouldScheduleTraversal = false;

            if (display.hasContentLocked() != hasContent) {
                if (DEBUG) {
                    Slog.d(TAG, "Display " + displayId + " hasContent flag changed: "
                            + "hasContent=" + hasContent + ", inTraversal=" + inTraversal);
                }

                display.setHasContentLocked(hasContent);
                shouldScheduleTraversal = true;
            }

            mDisplayModeDirector.getAppRequestObserver().setAppRequest(displayId, requestedModeId,
                    requestedRefreshRate, requestedMinRefreshRate, requestedMaxRefreshRate);

            // TODO(b/202378408) set minimal post-processing only if it's supported once we have a
            // separate API for disabling on-device processing.
            boolean mppRequest = isMinimalPostProcessingAllowed() && preferMinimalPostProcessing;
            // If HDR conversion introduces latency, disable that in case minimal
            // post-processing is requested
            boolean disableHdrConversionForLatency =
                    mppRequest && hdrConversionIntroducesLatencyLocked();

            if (display.getRequestedMinimalPostProcessingLocked() != mppRequest) {
                display.setRequestedMinimalPostProcessingLocked(mppRequest);
                shouldScheduleTraversal = true;
            }

            if (shouldScheduleTraversal) {
                scheduleTraversalLocked(inTraversal);
            }

            if (mHdrConversionMode == null) {
                return;
            }
            // HDR conversion is disabled in two cases:
            // - HDR conversion introduces latency and minimal post-processing is requested
            // - app requests to disable HDR conversion
            if (mOverrideHdrConversionMode == null && (disableHdrConversion
                    || disableHdrConversionForLatency)) {
                mOverrideHdrConversionMode =
                            new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_PASSTHROUGH);
                setHdrConversionModeInternal(mHdrConversionMode);
                handleLogicalDisplayChangedLocked(display);
            } else if (mOverrideHdrConversionMode != null && !disableHdrConversion
                    && !disableHdrConversionForLatency) {
                mOverrideHdrConversionMode = null;
                setHdrConversionModeInternal(mHdrConversionMode);
                handleLogicalDisplayChangedLocked(display);
            }
        }
    }

    private void setDisplayOffsetsInternal(int displayId, int x, int y) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
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

    private void setDisplayScalingDisabledInternal(int displayId, boolean disable) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display == null) {
                return;
            }
            if (display.isDisplayScalingDisabled() != disable) {
                if (DEBUG) {
                    Slog.d(TAG, "Display " + displayId + " content scaling disabled = " + disable);
                }
                display.setDisplayScalingDisabledLocked(disable);
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

    @Nullable
    private IBinder getDisplayToken(int displayId) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null) {
                final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
                if (device != null) {
                    return device.getDisplayTokenLocked();
                }
            }
        }

        return null;
    }

    private ScreenCapture.ScreenshotHardwareBuffer systemScreenshotInternal(int displayId) {
        final ScreenCapture.DisplayCaptureArgs captureArgs;
        synchronized (mSyncRoot) {
            final IBinder token = getDisplayToken(displayId);
            if (token == null) {
                return null;
            }
            final LogicalDisplay logicalDisplay = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (logicalDisplay == null) {
                return null;
            }

            final DisplayInfo displayInfo = logicalDisplay.getDisplayInfoLocked();
            captureArgs = new ScreenCapture.DisplayCaptureArgs.Builder(token)
                    .setSize(displayInfo.getNaturalWidth(), displayInfo.getNaturalHeight())
                    .setCaptureSecureLayers(true)
                    .setAllowProtected(true)
                    .build();
        }
        return ScreenCapture.captureDisplay(captureArgs);
    }

    private ScreenCapture.ScreenshotHardwareBuffer userScreenshotInternal(int displayId) {
        final ScreenCapture.DisplayCaptureArgs captureArgs;
        synchronized (mSyncRoot) {
            final IBinder token = getDisplayToken(displayId);
            if (token == null) {
                return null;
            }

            captureArgs = new ScreenCapture.DisplayCaptureArgs.Builder(token)
                            .build();
        }
        return ScreenCapture.captureDisplay(captureArgs);
    }

    @VisibleForTesting
    DisplayedContentSamplingAttributes getDisplayedContentSamplingAttributesInternal(
            int displayId) {
        final IBinder token = getDisplayToken(displayId);
        if (token == null) {
            return null;
        }
        return SurfaceControl.getDisplayedContentSamplingAttributes(token);
    }

    @VisibleForTesting
    boolean setDisplayedContentSamplingEnabledInternal(
            int displayId, boolean enable, int componentMask, int maxFrames) {
        final IBinder token = getDisplayToken(displayId);
        if (token == null) {
            return false;
        }
        return SurfaceControl.setDisplayedContentSamplingEnabled(
                token, enable, componentMask, maxFrames);
    }

    @VisibleForTesting
    DisplayedContentSample getDisplayedContentSampleInternal(int displayId,
            long maxFrames, long timestamp) {
        final IBinder token = getDisplayToken(displayId);
        if (token == null) {
            return null;
        }
        return SurfaceControl.getDisplayedContentSample(token, maxFrames, timestamp);
    }

    void resetBrightnessConfigurations() {
        mPersistentDataStore.setBrightnessConfigurationForUser(null, mContext.getUserId(),
                mContext.getPackageName());
        mLogicalDisplayMapper.forEachLocked((logicalDisplay -> {
            if (logicalDisplay.getDisplayInfoLocked().type != Display.TYPE_INTERNAL) {
                return;
            }
            final String uniqueId = logicalDisplay.getPrimaryDisplayDeviceLocked().getUniqueId();
            setBrightnessConfigurationForDisplayInternal(null, uniqueId, mContext.getUserId(),
                    mContext.getPackageName());
        }));
    }

    void setAutoBrightnessLoggingEnabled(boolean enabled) {
        synchronized (mSyncRoot) {
            final DisplayPowerControllerInterface displayPowerController =
                    mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY);
            if (displayPowerController != null) {
                displayPowerController.setAutoBrightnessLoggingEnabled(enabled);
            }
        }
    }

    void setDisplayWhiteBalanceLoggingEnabled(boolean enabled) {
        synchronized (mSyncRoot) {
            final DisplayPowerControllerInterface displayPowerController =
                    mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY);
            if (displayPowerController != null) {
                displayPowerController.setDisplayWhiteBalanceLoggingEnabled(enabled);
            }
        }
    }

    void setDisplayModeDirectorLoggingEnabled(boolean enabled) {
        synchronized (mSyncRoot) {
            mDisplayModeDirector.setLoggingEnabled(enabled);
        }
    }

    Display.Mode getActiveDisplayModeAtStart(int displayId) {
        synchronized (mSyncRoot) {
            final DisplayDevice device = getDeviceForDisplayLocked(displayId);
            if (device == null) {
                return null;
            }
            return device.getActiveDisplayModeAtStartLocked();
        }
    }

    void setAmbientColorTemperatureOverride(float cct) {
        synchronized (mSyncRoot) {
            final DisplayPowerControllerInterface displayPowerController =
                    mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY);
            if (displayPowerController != null) {
                displayPowerController.setAmbientColorTemperatureOverride(cct);
            }
        }
    }

    void setDockedAndIdleEnabled(boolean enabled, int displayId) {
        synchronized (mSyncRoot) {
            final DisplayPowerControllerInterface displayPowerController =
                    mDisplayPowerControllers.get(displayId);
            if (displayPowerController != null) {
                displayPowerController.setAutomaticScreenBrightnessMode(enabled
                        ? AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_IDLE
                        : AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DEFAULT);
            }
        }
    }

    private void clearViewportsLocked() {
        mViewports.clear();
    }

    private Optional<Integer> getViewportType(DisplayDeviceInfo info) {
        // Get the corresponding viewport type.
        switch (info.touch) {
            case DisplayDeviceInfo.TOUCH_INTERNAL:
                return Optional.of(VIEWPORT_INTERNAL);
            case DisplayDeviceInfo.TOUCH_EXTERNAL:
                return Optional.of(VIEWPORT_EXTERNAL);
            case DisplayDeviceInfo.TOUCH_VIRTUAL:
                if (!TextUtils.isEmpty(info.uniqueId)) {
                    return Optional.of(VIEWPORT_VIRTUAL);
                }
                // fallthrough
            default:
                if (DEBUG) {
                    Slog.w(TAG, "Display " + info + " does not support input device matching.");
                }
        }
        return Optional.empty();
    }

    private void configureDisplayLocked(SurfaceControl.Transaction t, DisplayDevice device) {
        final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();

        // Find the logical display that the display device is showing.
        // Certain displays only ever show their own content.
        LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);

        // Apply the logical display configuration to the display device.
        if (display == null) {
            // TODO: no logical display for the device, blank it
            Slog.w(TAG, "Missing logical display to use for physical display device: "
                    + device.getDisplayDeviceInfoLocked());
            return;
        }
        display.configureDisplayLocked(t, device, info.state == Display.STATE_OFF);
        final Optional<Integer> viewportType = getViewportType(info);
        if (viewportType.isPresent()) {
            populateViewportLocked(viewportType.get(), display.getDisplayIdLocked(), device, info);
        }
    }

    /**
     * Get internal or external viewport. Create it if does not currently exist.
     * @param viewportType - either INTERNAL or EXTERNAL
     * @return the viewport with the requested type
     */
    private DisplayViewport getViewportLocked(int viewportType, String uniqueId) {
        if (viewportType != VIEWPORT_INTERNAL && viewportType != VIEWPORT_EXTERNAL
                && viewportType != VIEWPORT_VIRTUAL) {
            Slog.wtf(TAG, "Cannot call getViewportByTypeLocked for type "
                    + DisplayViewport.typeToString(viewportType));
            return null;
        }

        DisplayViewport viewport;
        final int count = mViewports.size();
        for (int i = 0; i < count; i++) {
            viewport = mViewports.get(i);
            if (viewport.type == viewportType && uniqueId.equals(viewport.uniqueId)) {
                return viewport;
            }
        }

        // Creates the viewport if none exists.
        viewport = new DisplayViewport();
        viewport.type = viewportType;
        viewport.uniqueId = uniqueId;
        mViewports.add(viewport);
        return viewport;
    }

    private void populateViewportLocked(int viewportType, int displayId, DisplayDevice device,
            DisplayDeviceInfo info) {
        final DisplayViewport viewport = getViewportLocked(viewportType, info.uniqueId);
        device.populateViewportLocked(viewport);
        viewport.valid = true;
        viewport.displayId = displayId;
        viewport.isActive = Display.isActiveState(info.state);
    }

    private void updateViewportPowerStateLocked(LogicalDisplay display) {
        final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
        final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        final Optional<Integer> viewportType = getViewportType(info);
        if (viewportType.isPresent()) {
            for (DisplayViewport d : mViewports) {
                if (d.type == viewportType.get() && info.uniqueId.equals(d.uniqueId)) {
                    // Update display view port power state
                    d.isActive = Display.isActiveState(info.state);
                }
            }
            if (mInputManagerInternal != null) {
                mHandler.sendEmptyMessage(MSG_UPDATE_VIEWPORT);
            }
        }
    }

    // Send a display event if the display is enabled
    private void sendDisplayEventIfEnabledLocked(@NonNull LogicalDisplay display,
                                                 @DisplayEvent int event) {
        final boolean displayIsEnabled = display.isEnabledLocked();
        if (Trace.isTagEnabled(Trace.TRACE_TAG_POWER)) {
            Trace.instant(Trace.TRACE_TAG_POWER,
                    "sendDisplayEventLocked#event=" + event + ",displayEnabled="
                            + displayIsEnabled);
        }

        // Only send updates outside of DisplayManagerService for enabled displays
        if (displayIsEnabled) {
            sendDisplayEventLocked(display, event);
        } else if (mExtraDisplayEventLogging) {
            Slog.i(TAG, "Not Sending Display Event; display is not enabled: " + display);
        }
    }

    private void sendDisplayEventLocked(@NonNull LogicalDisplay display, @DisplayEvent int event) {
        int displayId = display.getDisplayIdLocked();
        Message msg = mHandler.obtainMessage(MSG_DELIVER_DISPLAY_EVENT, displayId, event);
        if (mExtraDisplayEventLogging) {
            Slog.i(TAG, "Deliver Display Event on Handler: " + event);
        }
        mHandler.sendMessage(msg);
    }

    private void sendDisplayGroupEvent(int groupId, int event) {
        Message msg = mHandler.obtainMessage(MSG_DELIVER_DISPLAY_GROUP_EVENT, groupId, event);
        mHandler.sendMessage(msg);
    }

    private void sendDisplayEventFrameRateOverrideLocked(int displayId) {
        Message msg = mHandler.obtainMessage(MSG_DELIVER_DISPLAY_EVENT_FRAME_RATE_OVERRIDE,
                displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
        mHandler.sendMessage(msg);
    }

    // Requests that performTraversals be called at a
    // later time to apply changes to surfaces and displays.
    private void scheduleTraversalLocked(boolean inTraversal) {
        if (!mPendingTraversal && mWindowManagerInternal != null) {
            mPendingTraversal = true;
            if (!inTraversal) {
                mHandler.sendEmptyMessage(MSG_REQUEST_TRAVERSAL);
            }
        }
    }

    // Check if the target app is in cached mode
    private boolean isUidCached(int uid) {
        if (mActivityManagerInternal == null || uid < FIRST_APPLICATION_UID) {
            return false;
        }
        int procState = mActivityManagerInternal.getUidProcessState(uid);
        int importance = ActivityManager.RunningAppProcessInfo.procStateToImportance(procState);
        return importance >= IMPORTANCE_CACHED;
    }

    // Runs on Handler thread.
    // Delivers display event notifications to callbacks.
    private void deliverDisplayEvent(int displayId, ArraySet<Integer> uids,
            @DisplayEvent int event) {
        if (DEBUG || mExtraDisplayEventLogging) {
            Slog.d(TAG, "Delivering display event: displayId="
                    + displayId + ", event=" + event
                    + (uids != null ? ", uids=" + uids : ""));
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_POWER)) {
            Trace.instant(Trace.TRACE_TAG_POWER,
                    "deliverDisplayEvent#event=" + event + ",displayId="
                            + displayId   + (uids != null ? ", uids=" + uids : ""));
        }
        // Grab the lock and copy the callbacks.
        final int count;
        synchronized (mSyncRoot) {
            count = mCallbacks.size();
            mTempCallbacks.clear();
            for (int i = 0; i < count; i++) {
                if (uids == null || uids.contains(mCallbacks.valueAt(i).mUid)) {
                    mTempCallbacks.add(mCallbacks.valueAt(i));
                }
            }
        }

        // After releasing the lock, send the notifications out.
        for (int i = 0; i < mTempCallbacks.size(); i++) {
            CallbackRecord callbackRecord = mTempCallbacks.get(i);
            final int uid = callbackRecord.mUid;
            final int pid = callbackRecord.mPid;
            if (isUidCached(uid)) {
                // For cached apps, save the pending event until it becomes non-cached
                synchronized (mPendingCallbackSelfLocked) {
                    SparseArray<PendingCallback> pendingCallbacks = mPendingCallbackSelfLocked.get(
                            uid);
                    if (extraLogging(callbackRecord.mPackageName)) {
                        Slog.i(TAG, "Uid is cached: " + uid
                                + ", pendingCallbacks: " + pendingCallbacks);
                    }
                    if (pendingCallbacks == null) {
                        pendingCallbacks = new SparseArray<>();
                        mPendingCallbackSelfLocked.put(uid, pendingCallbacks);
                    }
                    PendingCallback pendingCallback = pendingCallbacks.get(pid);
                    if (pendingCallback == null) {
                        pendingCallbacks.put(pid,
                                new PendingCallback(callbackRecord, displayId, event));
                    } else {
                        pendingCallback.addDisplayEvent(displayId, event);
                    }
                }
            } else {
                callbackRecord.notifyDisplayEventAsync(displayId, event);
            }
        }
        mTempCallbacks.clear();
    }

    private boolean extraLogging(String packageName) {
        return mExtraDisplayEventLogging && mExtraDisplayLoggingPackageName.equals(packageName);
    }

    // Runs on Handler thread.
    // Delivers display group event notifications to callbacks.
    private void deliverDisplayGroupEvent(int groupId, int event) {
        if (DEBUG) {
            Slog.d(TAG, "Delivering display group event: groupId=" + groupId + ", event="
                    + event);
        }

        switch (event) {
            case LogicalDisplayMapper.DISPLAY_GROUP_EVENT_ADDED:
                for (DisplayGroupListener listener : mDisplayGroupListeners) {
                    listener.onDisplayGroupAdded(groupId);
                }
                break;

            case LogicalDisplayMapper.DISPLAY_GROUP_EVENT_CHANGED:
                for (DisplayGroupListener listener : mDisplayGroupListeners) {
                    listener.onDisplayGroupChanged(groupId);
                }
                break;

            case LogicalDisplayMapper.DISPLAY_GROUP_EVENT_REMOVED:
                for (DisplayGroupListener listener : mDisplayGroupListeners) {
                    listener.onDisplayGroupRemoved(groupId);
                }
                break;
        }
    }

    private IMediaProjectionManager getProjectionService() {
        if (mProjectionService == null) {
            mProjectionService = mInjector.getProjectionService();
        }
        return mProjectionService;
    }

    private UserManager getUserManager() {
        return mContext.getSystemService(UserManager.class);
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("DISPLAY MANAGER (dumpsys display)");
        BrightnessTracker brightnessTrackerLocal;

        synchronized (mSyncRoot) {
            brightnessTrackerLocal = mBrightnessTracker;

            pw.println("  mSafeMode=" + mSafeMode);
            pw.println("  mPendingTraversal=" + mPendingTraversal);
            pw.println("  mViewports=" + mViewports);
            pw.println("  mDefaultDisplayDefaultColorMode=" + mDefaultDisplayDefaultColorMode);
            pw.println("  mWifiDisplayScanRequestCount=" + mWifiDisplayScanRequestCount);
            pw.println("  mStableDisplaySize=" + mStableDisplaySize);
            pw.println("  mMinimumBrightnessCurve=" + mMinimumBrightnessCurve);

            if (mUserPreferredMode != null) {
                pw.println(" mUserPreferredMode=" + mUserPreferredMode);
            }

            pw.println();
            if (!mAreUserDisabledHdrTypesAllowed) {
                pw.println("  mUserDisabledHdrTypes: size=" + mUserDisabledHdrTypes.length);
                for (int type : mUserDisabledHdrTypes) {
                    pw.println("  " + type);
                }
            }

            if (mHdrConversionMode != null) {
                pw.println("  mHdrConversionMode=" + mHdrConversionMode);
            }

            pw.println();
            final int displayStateCount = mDisplayStates.size();
            pw.println("Display States: size=" + displayStateCount);
            for (int i = 0; i < displayStateCount; i++) {
                final int displayId = mDisplayStates.keyAt(i);
                final int displayState = mDisplayStates.valueAt(i);
                final BrightnessPair brightnessPair = mDisplayBrightnesses.valueAt(i);
                pw.println("  Display Id=" + displayId);
                pw.println("  Display State=" + Display.stateToString(displayState));
                pw.println("  Display Brightness=" + brightnessPair.brightness);
                pw.println("  Display SdrBrightness=" + brightnessPair.sdrBrightness);
            }

            IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
            ipw.increaseIndent();

            pw.println();
            pw.println("Display Adapters: size=" + mDisplayAdapters.size());
            for (DisplayAdapter adapter : mDisplayAdapters) {
                pw.println("  " + adapter.getName());
                adapter.dumpLocked(ipw);
            }

            pw.println();
            pw.println("Display Devices: size=" + mDisplayDeviceRepo.sizeLocked());
            mDisplayDeviceRepo.forEachLocked(device -> {
                pw.println("  " + device.getDisplayDeviceInfoLocked());
                device.dumpLocked(ipw);
            });

            pw.println();
            mLogicalDisplayMapper.dumpLocked(pw);

            final int callbackCount = mCallbacks.size();
            pw.println();
            pw.println("Callbacks: size=" + callbackCount);
            for (int i = 0; i < callbackCount; i++) {
                CallbackRecord callback = mCallbacks.valueAt(i);
                pw.println("  " + i + ": mPid=" + callback.mPid
                        + ", mWifiDisplayScanRequested=" + callback.mWifiDisplayScanRequested);
            }

            final int displayPowerControllerCount = mDisplayPowerControllers.size();
            pw.println();
            pw.println("Display Power Controllers: size=" + displayPowerControllerCount);
            for (int i = 0; i < displayPowerControllerCount; i++) {
                mDisplayPowerControllers.valueAt(i).dump(pw);
            }
            pw.println();
            mPersistentDataStore.dump(pw);

            final int displayWindowPolicyControllerCount = mDisplayWindowPolicyControllers.size();
            pw.println();
            pw.println("Display Window Policy Controllers: size="
                    + displayWindowPolicyControllerCount);
            for (int i = 0; i < displayWindowPolicyControllerCount; i++) {
                pw.print("Display " + mDisplayWindowPolicyControllers.keyAt(i) + ":");
                mDisplayWindowPolicyControllers.valueAt(i).second.dump("  ", pw);
            }
        }
        if (brightnessTrackerLocal != null) {
            pw.println();
            brightnessTrackerLocal.dump(pw);
        }
        pw.println();
        mDisplayModeDirector.dump(pw);
        mBrightnessSynchronizer.dump(pw);
        if (mSmallAreaDetectionController != null) {
            mSmallAreaDetectionController.dump(pw);
        }

        pw.println();
        mFlags.dump(pw);

    }

    private static float[] getFloatArray(TypedArray array) {
        int length = array.length();
        float[] floatArray = new float[length];
        for (int i = 0; i < length; i++) {
            floatArray[i] = array.getFloat(i, Float.NaN);
        }
        array.recycle();
        return floatArray;
    }

    private static boolean isResolutionAndRefreshRateValid(Display.Mode mode) {
        return mode.getPhysicalWidth() > 0 && mode.getPhysicalHeight() > 0
                && mode.getRefreshRate() > 0.0f;
    }

    void enableConnectedDisplay(int displayId, boolean enabled) {
        synchronized (mSyncRoot) {
            final var logicalDisplay = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (logicalDisplay == null) {
                Slog.w(TAG, "enableConnectedDisplay: Can not find displayId=" + displayId);
            } else if (ExternalDisplayPolicy.isExternalDisplayLocked(logicalDisplay)) {
                mExternalDisplayPolicy.setExternalDisplayEnabledLocked(logicalDisplay, enabled);
            } else {
                mLogicalDisplayMapper.setDisplayEnabledLocked(logicalDisplay, enabled);
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

    @VisibleForTesting
    static class Injector {
        VirtualDisplayAdapter getVirtualDisplayAdapter(SyncRoot syncRoot, Context context,
                Handler handler, DisplayAdapter.Listener displayAdapterListener,
                DisplayManagerFlags flags) {
            return new VirtualDisplayAdapter(syncRoot, context, handler, displayAdapterListener,
                    flags);
        }

        LocalDisplayAdapter getLocalDisplayAdapter(SyncRoot syncRoot, Context context,
                Handler handler, DisplayAdapter.Listener displayAdapterListener,
                DisplayManagerFlags flags,
                DisplayNotificationManager displayNotificationManager) {
            return new LocalDisplayAdapter(syncRoot, context, handler, displayAdapterListener,
                    flags, displayNotificationManager);
        }

        long getDefaultDisplayDelayTimeout() {
            return WAIT_FOR_DEFAULT_DISPLAY_TIMEOUT;
        }

        int setHdrConversionMode(int conversionMode, int preferredHdrOutputType,
                int[] autoHdrTypes) {
            return DisplayControl.setHdrConversionMode(conversionMode, preferredHdrOutputType,
                    autoHdrTypes);
        }

        @Display.HdrCapabilities.HdrType
        int[] getSupportedHdrOutputTypes() {
            return DisplayControl.getSupportedHdrOutputTypes();
        }

        int[] getHdrOutputTypesWithLatency() {
            return DisplayControl.getHdrOutputTypesWithLatency();
        }

        boolean getHdrOutputConversionSupport() {
            return DisplayControl.getHdrOutputConversionSupport();
        }

        IMediaProjectionManager getProjectionService() {
            IBinder b = ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE);
            return  IMediaProjectionManager.Stub.asInterface(b);
        }

        DisplayManagerFlags getFlags() {
            return new DisplayManagerFlags();
        }
    }

    @VisibleForTesting
    DisplayDeviceInfo getDisplayDeviceInfoInternal(int displayId) {
        synchronized (mSyncRoot) {
            final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (display != null) {
                final DisplayDevice displayDevice = display.getPrimaryDisplayDeviceLocked();
                return displayDevice.getDisplayDeviceInfoLocked();
            }
            return null;
        }
    }

    @VisibleForTesting
    Surface getVirtualDisplaySurfaceInternal(IBinder appToken) {
        synchronized (mSyncRoot) {
            if (mVirtualDisplayAdapter == null) {
                return null;
            }
            return mVirtualDisplayAdapter.getVirtualDisplaySurfaceLocked(appToken);
        }
    }

    private void initializeDisplayPowerControllersLocked() {
        mLogicalDisplayMapper.forEachLocked(this::addDisplayPowerControllerLocked);
    }

    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    private DisplayPowerControllerInterface addDisplayPowerControllerLocked(
            LogicalDisplay display) {
        if (mPowerHandler == null) {
            // initPowerManagement has not yet been called.
            return null;
        }

        if (mBrightnessTracker == null && display.getDisplayIdLocked() == Display.DEFAULT_DISPLAY) {
            mBrightnessTracker = new BrightnessTracker(mContext, null);
        }

        final int userSerial = getUserManager().getUserSerialNumber(mContext.getUserId());
        final BrightnessSetting brightnessSetting = new BrightnessSetting(userSerial,
                mPersistentDataStore, display, mSyncRoot);
        final DisplayPowerControllerInterface displayPowerController;

        // If display is internal and has a HighBrightnessModeMetadata mapping, use that.
        // Or create a new one and use that.
        // We also need to pass a mapping of the HighBrightnessModeTimeInfoMap to
        // displayPowerController, so the hbm info can be correctly associated
        // with the corresponding displaydevice.
        HighBrightnessModeMetadata hbmMetadata =
                mHighBrightnessModeMetadataMapper.getHighBrightnessModeMetadataLocked(display);
        displayPowerController = new DisplayPowerController(
                mContext, /* injector= */ null, mDisplayPowerCallbacks, mPowerHandler,
                mSensorManager, mDisplayBlanker, display, mBrightnessTracker, brightnessSetting,
                () -> handleBrightnessChange(display), hbmMetadata, mBootCompleted, mFlags);
        mDisplayPowerControllers.append(display.getDisplayIdLocked(), displayPowerController);
        return displayPowerController;
    }

    private void handleBrightnessChange(LogicalDisplay display) {
        synchronized (mSyncRoot) {
            sendDisplayEventIfEnabledLocked(display,
                    DisplayManagerGlobal.EVENT_DISPLAY_BRIGHTNESS_CHANGED);
        }
    }

    private DisplayDevice getDeviceForDisplayLocked(int displayId) {
        final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
        return display == null ? null : display.getPrimaryDisplayDeviceLocked();
    }

    private BrightnessConfiguration getBrightnessConfigForDisplayWithPdsFallbackLocked(
            String uniqueId, int userSerial) {
        BrightnessConfiguration config =
                mPersistentDataStore.getBrightnessConfigurationForDisplayLocked(
                        uniqueId, userSerial);
        if (config == null) {
            // Get from global configurations
            config = mPersistentDataStore.getBrightnessConfiguration(userSerial);
        }
        return config;
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
                    deliverDisplayEvent(msg.arg1, null, msg.arg2);
                    break;

                case MSG_REQUEST_TRAVERSAL:
                    mWindowManagerInternal.requestTraversalFromDisplayManager();
                    break;

                case MSG_RECEIVED_DEVICE_STATE:
                    mWindowManagerInternal.onDisplayManagerReceivedDeviceState(msg.arg1);
                    break;

                case MSG_UPDATE_VIEWPORT: {
                    final boolean changed;
                    synchronized (mSyncRoot) {
                        changed = !mTempViewports.equals(mViewports);
                        if (changed) {
                            mTempViewports.clear();
                            for (DisplayViewport d : mViewports) {
                                mTempViewports.add(d.makeCopy());
                            }
                        }
                    }
                    if (changed) {
                        mInputManagerInternal.setDisplayViewports(mTempViewports);
                    }
                    break;
                }

                case MSG_LOAD_BRIGHTNESS_CONFIGURATIONS:
                    loadBrightnessConfigurations();
                    break;

                case MSG_DELIVER_DISPLAY_EVENT_FRAME_RATE_OVERRIDE:
                    ArraySet<Integer> uids;
                    synchronized (mSyncRoot) {
                        int displayId = msg.arg1;
                        final LogicalDisplay display =
                                mLogicalDisplayMapper.getDisplayLocked(displayId);
                        if (display == null) {
                            break;
                        }
                        uids = display.getPendingFrameRateOverrideUids();
                        display.clearPendingFrameRateOverrideUids();
                    }
                    deliverDisplayEvent(msg.arg1, uids, msg.arg2);
                    break;

                case MSG_DELIVER_DISPLAY_GROUP_EVENT:
                    deliverDisplayGroupEvent(msg.arg1, msg.arg2);
                    break;

            }
        }
    }

    private final class LogicalDisplayListener implements LogicalDisplayMapper.Listener {

        @GuardedBy("mSyncRoot")
        @Override
        public void onLogicalDisplayEventLocked(LogicalDisplay display, int event) {
            switch (event) {
                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_ADDED:
                    handleLogicalDisplayAddedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_CHANGED:
                    handleLogicalDisplayChangedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_REMOVED:
                    handleLogicalDisplayRemovedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_SWAPPED:
                    handleLogicalDisplaySwappedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_FRAME_RATE_OVERRIDES_CHANGED:
                    handleLogicalDisplayFrameRateOverridesChangedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_DEVICE_STATE_TRANSITION:
                    handleLogicalDisplayDeviceStateTransitionLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_HDR_SDR_RATIO_CHANGED:
                    handleLogicalDisplayHdrSdrRatioChangedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_CONNECTED:
                    handleLogicalDisplayConnectedLocked(display);
                    break;

                case LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_DISCONNECTED:
                    handleLogicalDisplayDisconnectedLocked(display);
                    break;
            }
        }

        @Override
        public void onDisplayGroupEventLocked(int groupId, int event) {
            sendDisplayGroupEvent(groupId, event);
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
        public final int mUid;
        private final IDisplayManagerCallback mCallback;
        private @EventsMask AtomicLong mEventsMask;
        private final String mPackageName;

        public boolean mWifiDisplayScanRequested;

        CallbackRecord(int pid, int uid, IDisplayManagerCallback callback,
                @EventsMask long eventsMask) {
            mPid = pid;
            mUid = uid;
            mCallback = callback;
            mEventsMask = new AtomicLong(eventsMask);

            String[] packageNames = mContext.getPackageManager().getPackagesForUid(uid);
            mPackageName = packageNames == null ? null : packageNames[0];
        }

        public void updateEventsMask(@EventsMask long eventsMask) {
            mEventsMask.set(eventsMask);
        }

        @Override
        public void binderDied() {
            if (DEBUG || extraLogging(mPackageName)) {
                Slog.d(TAG, "Display listener for pid " + mPid + " died.");
            }
            if (Trace.isTagEnabled(Trace.TRACE_TAG_POWER)) {
                Trace.instant(Trace.TRACE_TAG_POWER,
                        "displayManagerBinderDied#mPid=" + mPid);
            }
            onCallbackDied(this);
        }

        /**
         * @return {@code false} if RemoteException happens; otherwise {@code true} for success.
         */
        public boolean notifyDisplayEventAsync(int displayId, @DisplayEvent int event) {
            if (!shouldSendEvent(event)) {
                if (extraLogging(mPackageName)) {
                    Slog.i(TAG,
                            "Not sending displayEvent: " + event + " due to mask:" + mEventsMask);
                }
                if (Trace.isTagEnabled(Trace.TRACE_TAG_POWER)) {
                    Trace.instant(Trace.TRACE_TAG_POWER,
                            "notifyDisplayEventAsync#notSendingEvent=" + event + ",mEventsMask="
                                    + mEventsMask);
                }
                return true;
            }

            try {
                mCallback.onDisplayEvent(displayId, event);
                return true;
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process "
                        + mPid + " that displays changed, assuming it died.", ex);
                binderDied();
                return false;
            }
        }

        private boolean shouldSendEvent(@DisplayEvent int event) {
            final long mask = mEventsMask.get();
            switch (event) {
                case DisplayManagerGlobal.EVENT_DISPLAY_ADDED:
                    return (mask & DisplayManager.EVENT_FLAG_DISPLAY_ADDED) != 0;
                case DisplayManagerGlobal.EVENT_DISPLAY_CHANGED:
                    return (mask & DisplayManager.EVENT_FLAG_DISPLAY_CHANGED) != 0;
                case DisplayManagerGlobal.EVENT_DISPLAY_BRIGHTNESS_CHANGED:
                    return (mask & DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS) != 0;
                case DisplayManagerGlobal.EVENT_DISPLAY_REMOVED:
                    return (mask & DisplayManager.EVENT_FLAG_DISPLAY_REMOVED) != 0;
                case DisplayManagerGlobal.EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED:
                    return (mask & DisplayManager.EVENT_FLAG_HDR_SDR_RATIO_CHANGED) != 0;
                case DisplayManagerGlobal.EVENT_DISPLAY_CONNECTED:
                    // fallthrough
                case DisplayManagerGlobal.EVENT_DISPLAY_DISCONNECTED:
                    return (mask & DisplayManager.EVENT_FLAG_DISPLAY_CONNECTION_CHANGED) != 0;
                default:
                    // This should never happen.
                    Slog.e(TAG, "Unknown display event " + event);
                    return true;
            }
        }
    }

    private static final class PendingCallback {
        private final CallbackRecord mCallbackRecord;
        private final ArrayList<Pair<Integer, Integer>> mDisplayEvents;

        PendingCallback(CallbackRecord cr, int displayId, int event) {
            mCallbackRecord = cr;
            mDisplayEvents = new ArrayList<>();
            mDisplayEvents.add(new Pair<>(displayId, event));
        }

        public void addDisplayEvent(int displayId, int event) {
            // Ignore redundant events. Further optimization is possible by merging adjacent events.
            Pair<Integer, Integer> last = mDisplayEvents.get(mDisplayEvents.size() - 1);
            if (last.first == displayId && last.second == event) {
                Slog.d(TAG,
                        "Ignore redundant display event " + displayId + "/" + event + " to "
                                + mCallbackRecord.mUid + "/" + mCallbackRecord.mPid);
                return;
            }

            mDisplayEvents.add(new Pair<>(displayId, event));
        }

        public void sendPendingDisplayEvent() {
            for (int i = 0; i < mDisplayEvents.size(); i++) {
                Pair<Integer, Integer> displayEvent = mDisplayEvents.get(i);
                if (DEBUG) {
                    Slog.d(TAG, "Send pending display event #" + i + " " + displayEvent.first + "/"
                            + displayEvent.second + " to " + mCallbackRecord.mUid + "/"
                            + mCallbackRecord.mPid);
                }
                if (!mCallbackRecord.notifyDisplayEventAsync(displayEvent.first,
                        displayEvent.second)) {
                    Slog.d(TAG, "Drop pending events for dead process " + mCallbackRecord.mPid);
                    break;
                }
            }
            mDisplayEvents.clear();
        }
    }

    @VisibleForTesting
    final class BinderService extends IDisplayManager.Stub {
        /**
         * Returns information about the specified logical display.
         *
         * @param displayId The logical display id.
         * @return The logical display info, return {@code null} if the display does not exist or
         * the calling UID isn't present on the display.  The returned object must be treated as
         * immutable.
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
        public int[] getDisplayIds(boolean includeDisabled) {
            final int callingUid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    return mLogicalDisplayMapper.getDisplayIdsLocked(callingUid, includeDisabled);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public boolean isUidPresentOnDisplay(int uid, int displayId) {
            final long token = Binder.clearCallingIdentity();
            try {
                return isUidPresentOnDisplayInternal(uid, displayId);
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
            registerCallbackWithEventMask(callback, DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                    | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                    | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED);
        }

        @Override // Binder call
        @SuppressLint("AndroidFrameworkRequiresPermission") // Permission only required sometimes
        public void registerCallbackWithEventMask(IDisplayManagerCallback callback,
                @EventsMask long eventsMask) {
            if (callback == null) {
                throw new IllegalArgumentException("listener must not be null");
            }

            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();

            if (mFlags.isConnectedDisplayManagementEnabled()) {
                if ((eventsMask & DisplayManager.EVENT_FLAG_DISPLAY_CONNECTION_CHANGED) != 0) {
                    mContext.enforceCallingOrSelfPermission(MANAGE_DISPLAYS,
                            "Permission required to get signals about connection events.");
                }
            }

            final long token = Binder.clearCallingIdentity();
            try {
                registerCallbackInternal(callback, callingPid, callingUid, eventsMask);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
        @Override // Binder call
        public void startWifiDisplayScan() {
            startWifiDisplayScan_enforcePermission();

            final int callingPid = Binder.getCallingPid();
            final long token = Binder.clearCallingIdentity();
            try {
                startWifiDisplayScanInternal(callingPid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
        @Override // Binder call
        public void stopWifiDisplayScan() {
            stopWifiDisplayScan_enforcePermission();

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

        @EnforcePermission(android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
        @Override // Binder call
        public void pauseWifiDisplay() {
            pauseWifiDisplay_enforcePermission();

            final long token = Binder.clearCallingIdentity();
            try {
                pauseWifiDisplayInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
        @Override // Binder call
        public void resumeWifiDisplay() {
            resumeWifiDisplay_enforcePermission();

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

        @EnforcePermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        @Override // Binder call
        public void setUserDisabledHdrTypes(int[] userDisabledFormats) {
            setUserDisabledHdrTypes_enforcePermission();

            final long token = Binder.clearCallingIdentity();
            try {
                setUserDisabledHdrTypesInternal(userDisabledFormats);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void overrideHdrTypes(int displayId, int[] modes) {
            IBinder displayToken;
            synchronized (mSyncRoot) {
                displayToken = getDisplayToken(displayId);
                if (displayToken == null) {
                    throw new IllegalArgumentException("Invalid display: " + displayId);
                }
            }

            DisplayControl.overrideHdrTypes(displayToken, modes);
        }

        @EnforcePermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        @Override // Binder call
        public void setAreUserDisabledHdrTypesAllowed(boolean areUserDisabledHdrTypesAllowed) {
            setAreUserDisabledHdrTypesAllowed_enforcePermission();
            final long token = Binder.clearCallingIdentity();
            try {
                setAreUserDisabledHdrTypesAllowedInternal(areUserDisabledHdrTypesAllowed);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public boolean areUserDisabledHdrTypesAllowed() {
            synchronized (mSyncRoot) {
                return mAreUserDisabledHdrTypesAllowed;
            }
        }

        @Override // Binder call
        public int[] getUserDisabledHdrTypes() {
            synchronized (mSyncRoot) {
                return mUserDisabledHdrTypes;
            }
        }

        @EnforcePermission(android.Manifest.permission.CONFIGURE_DISPLAY_COLOR_MODE)
        @Override // Binder call
        public void requestColorMode(int displayId, int colorMode) {
            requestColorMode_enforcePermission();
            final long token = Binder.clearCallingIdentity();
            try {
                requestColorModeInternal(displayId, colorMode);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public int createVirtualDisplay(VirtualDisplayConfig virtualDisplayConfig,
                IVirtualDisplayCallback callback, IMediaProjection projection,
                String packageName) {
            return createVirtualDisplayInternal(virtualDisplayConfig, callback, projection,
                    null, null, packageName);
        }

        @Override // Binder call
        public void resizeVirtualDisplay(IVirtualDisplayCallback callback,
                int width, int height, int densityDpi) {
            if (width <= 0 || height <= 0 || densityDpi <= 0) {
                throw new IllegalArgumentException("width, height, and densityDpi must be "
                        + "greater than 0");
            }
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
        public void setVirtualDisplayState(IVirtualDisplayCallback callback, boolean isOn) {
            final long token = Binder.clearCallingIdentity();
            try {
                setVirtualDisplayStateInternal(callback.asBinder(), isOn);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void dump(@NonNull FileDescriptor fd, @NonNull final PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            final long token = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.BRIGHTNESS_SLIDER_USAGE)
        @Override // Binder call
        public ParceledListSlice<BrightnessChangeEvent> getBrightnessEvents(String callingPackage) {
            getBrightnessEvents_enforcePermission();

            final int callingUid = Binder.getCallingUid();
            AppOpsManager appOpsManager = mContext.getSystemService(AppOpsManager.class);
            final int mode = appOpsManager.noteOp(AppOpsManager.OP_GET_USAGE_STATS,
                    callingUid, callingPackage);
            final boolean hasUsageStats;
            if (mode == AppOpsManager.MODE_DEFAULT) {
                // The default behavior here is to check if PackageManager has given the app
                // permission.
                hasUsageStats = mContext.checkCallingPermission(
                        Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            } else {
                hasUsageStats = mode == AppOpsManager.MODE_ALLOWED;
            }

            final int userId = UserHandle.getUserId(callingUid);
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    return mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                            .getBrightnessEvents(userId, hasUsageStats);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.ACCESS_AMBIENT_LIGHT_STATS)
        @Override // Binder call
        public ParceledListSlice<AmbientBrightnessDayStats> getAmbientBrightnessStats() {
            getAmbientBrightnessStats_enforcePermission();
            final int callingUid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(callingUid);
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    return mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                            .getAmbientBrightnessStats(userId);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS)
        @Override // Binder call
        public void setBrightnessConfigurationForUser(
                BrightnessConfiguration c, @UserIdInt int userId, String packageName) {
            setBrightnessConfigurationForUser_enforcePermission();
            if (userId != UserHandle.getCallingUserId()) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS,
                        "Permission required to change the display brightness"
                                + " configuration of another user");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    mLogicalDisplayMapper.forEachLocked(logicalDisplay -> {
                        if (logicalDisplay.getDisplayInfoLocked().type != Display.TYPE_INTERNAL) {
                            return;
                        }
                        final DisplayDevice displayDevice =
                                logicalDisplay.getPrimaryDisplayDeviceLocked();
                        setBrightnessConfigurationForDisplayInternal(c, displayDevice.getUniqueId(),
                                userId, packageName);
                    });
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS)
        @Override // Binder call
        public void setBrightnessConfigurationForDisplay(BrightnessConfiguration c,
                String uniqueId, int userId, String packageName) {
            setBrightnessConfigurationForDisplay_enforcePermission();
            if (userId != UserHandle.getCallingUserId()) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS,
                        "Permission required to change the display brightness"
                                + " configuration of another user");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                setBrightnessConfigurationForDisplayInternal(c, uniqueId, userId, packageName);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS)
        @Override // Binder call
        public BrightnessConfiguration getBrightnessConfigurationForDisplay(String uniqueId,
                int userId) {
            getBrightnessConfigurationForDisplay_enforcePermission();
            if (userId != UserHandle.getCallingUserId()) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS,
                        "Permission required to read the display brightness"
                                + " configuration of another user");
            }
            final long token = Binder.clearCallingIdentity();
            final int userSerial = getUserManager().getUserSerialNumber(userId);
            try {
                synchronized (mSyncRoot) {
                    // Get from per-display configurations
                    BrightnessConfiguration config =
                            getBrightnessConfigForDisplayWithPdsFallbackLocked(
                                    uniqueId, userSerial);
                    if (config == null) {
                        // Get default configuration
                        DisplayPowerControllerInterface dpc = getDpcFromUniqueIdLocked(uniqueId);
                        if (dpc != null) {
                            config = dpc.getDefaultBrightnessConfiguration();
                        }
                    }
                    return config;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }



        @Override // Binder call
        public BrightnessConfiguration getBrightnessConfigurationForUser(int userId) {
            final String uniqueId;
            synchronized (mSyncRoot) {
                DisplayDevice displayDevice = mLogicalDisplayMapper.getDisplayLocked(
                        Display.DEFAULT_DISPLAY).getPrimaryDisplayDeviceLocked();
                uniqueId = displayDevice.getUniqueId();
            }
            return getBrightnessConfigurationForDisplay(uniqueId, userId);


        }

        @EnforcePermission(android.Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS)
        @Override // Binder call
        public BrightnessConfiguration getDefaultBrightnessConfiguration() {
            getDefaultBrightnessConfiguration_enforcePermission();
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    return mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                            .getDefaultBrightnessConfiguration();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
        @Override
        public BrightnessInfo getBrightnessInfo(int displayId) {
            getBrightnessInfo_enforcePermission();
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(
                            displayId, /* includeDisabled= */ false);
                    if (display == null || !display.isEnabledLocked()) {
                        return null;
                    }
                    DisplayPowerControllerInterface dpc = mDisplayPowerControllers.get(displayId);
                    if (dpc != null) {
                        return dpc.getBrightnessInfo();
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return null;
        }

        @Override // Binder call
        public boolean isMinimalPostProcessingRequested(int displayId) {
            synchronized (mSyncRoot) {
                return mLogicalDisplayMapper.getDisplayLocked(displayId)
                        .getRequestedMinimalPostProcessingLocked();
            }
        }

        @EnforcePermission(android.Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
        @Override // Binder call
        public void setTemporaryBrightness(int displayId, float brightness) {
            setTemporaryBrightness_enforcePermission();
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    mDisplayPowerControllers.get(displayId)
                            .setTemporaryBrightness(brightness);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
        @Override // Binder call
        public void setBrightness(int displayId, float brightness) {
            setBrightness_enforcePermission();
            if (!isValidBrightness(brightness)) {
                Slog.w(TAG, "Attempted to set invalid brightness" + brightness);
                return;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    DisplayPowerControllerInterface dpc = mDisplayPowerControllers.get(displayId);
                    if (dpc != null) {
                        dpc.setBrightness(brightness);
                    }
                    mPersistentDataStore.saveIfNeeded();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public float getBrightness(int displayId) {
            float brightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS,
                    "Permission required to set the display's brightness");
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    DisplayPowerControllerInterface dpc = mDisplayPowerControllers.get(displayId);
                    if (dpc != null) {
                        brightness = dpc.getScreenBrightnessSetting();
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return brightness;
        }

        @EnforcePermission(android.Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
        @Override // Binder call
        public void setTemporaryAutoBrightnessAdjustment(float adjustment) {
            setTemporaryAutoBrightnessAdjustment_enforcePermission();
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mSyncRoot) {
                    mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                            .setTemporaryAutoBrightnessAdjustment(adjustment);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, @NonNull String[] args, ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) {
            new DisplayManagerShellCommand(DisplayManagerService.this, mFlags).exec(this, in, out,
                    err, args, callback, resultReceiver);
        }

        @Override // Binder call
        public Curve getMinimumBrightnessCurve() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getMinimumBrightnessCurveInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public int getPreferredWideGamutColorSpaceId() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getPreferredWideGamutColorSpaceIdInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.MODIFY_USER_PREFERRED_DISPLAY_MODE)
        @Override // Binder call
        public void setUserPreferredDisplayMode(int displayId, Display.Mode mode) {
            setUserPreferredDisplayMode_enforcePermission();
            final long token = Binder.clearCallingIdentity();
            try {
                setUserPreferredDisplayModeInternal(displayId, mode);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public Display.Mode getUserPreferredDisplayMode(int displayId) {
            final long token = Binder.clearCallingIdentity();
            try {
                return getUserPreferredDisplayModeInternal(displayId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public Display.Mode getSystemPreferredDisplayMode(int displayId) {
            final long token = Binder.clearCallingIdentity();
            try {
                return getSystemPreferredDisplayModeInternal(displayId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void setHdrConversionMode(HdrConversionMode hdrConversionMode) {
            if (!mIsHdrOutputControlEnabled) {
                return;
            }
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.MODIFY_HDR_CONVERSION_MODE,
                    "Permission required to set the HDR conversion mode.");
            final long token = Binder.clearCallingIdentity();
            try {
                setHdrConversionModeInternal(hdrConversionMode);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public HdrConversionMode getHdrConversionModeSetting() {
            if (!mIsHdrOutputControlEnabled) {
                return HDR_CONVERSION_MODE_UNSUPPORTED;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return getHdrConversionModeSettingInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public HdrConversionMode getHdrConversionMode() {
            if (!mIsHdrOutputControlEnabled) {
                return HDR_CONVERSION_MODE_UNSUPPORTED;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return getHdrConversionModeInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Display.HdrCapabilities.HdrType
        @Override // Binder call
        public int[] getSupportedHdrOutputTypes() {
            if (!mIsHdrOutputControlEnabled) {
                return EMPTY_ARRAY;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return getSupportedHdrOutputTypesInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.OVERRIDE_DISPLAY_MODE_REQUESTS)
        @Override // Binder call
        public void setShouldAlwaysRespectAppRequestedMode(boolean enabled) {
            setShouldAlwaysRespectAppRequestedMode_enforcePermission();
            final long token = Binder.clearCallingIdentity();
            try {
                setShouldAlwaysRespectAppRequestedModeInternal(enabled);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.OVERRIDE_DISPLAY_MODE_REQUESTS)
        @Override // Binder call
        public boolean shouldAlwaysRespectAppRequestedMode() {
            shouldAlwaysRespectAppRequestedMode_enforcePermission();
            final long token = Binder.clearCallingIdentity();
            try {
                return shouldAlwaysRespectAppRequestedModeInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(android.Manifest.permission.MODIFY_REFRESH_RATE_SWITCHING_TYPE)
        @Override // Binder call
        public void setRefreshRateSwitchingType(int newValue) {
            setRefreshRateSwitchingType_enforcePermission();
            final long token = Binder.clearCallingIdentity();
            try {
                setRefreshRateSwitchingTypeInternal(newValue);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public int getRefreshRateSwitchingType() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getRefreshRateSwitchingTypeInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public DisplayDecorationSupport getDisplayDecorationSupport(int displayId) {
            final long token = Binder.clearCallingIdentity();
            try {
                return getDisplayDecorationSupportInternal(displayId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setDisplayIdToMirror(IBinder token, int displayId) {
            synchronized (mSyncRoot) {
                final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
                if (mVirtualDisplayAdapter != null) {
                    mVirtualDisplayAdapter.setDisplayIdToMirror(token,
                            display == null ? Display.INVALID_DISPLAY : displayId);
                }
            }
        }

        @Override
        public OverlayProperties getOverlaySupport() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getOverlaySupportInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @EnforcePermission(MANAGE_DISPLAYS)
        public void enableConnectedDisplay(int displayId) {
            enableConnectedDisplay_enforcePermission();
            DisplayManagerService.this.enableConnectedDisplay(displayId, true);
        }

        @EnforcePermission(MANAGE_DISPLAYS)
        public void disableConnectedDisplay(int displayId) {
            disableConnectedDisplay_enforcePermission();
            DisplayManagerService.this.enableConnectedDisplay(displayId, false);
        }

        @EnforcePermission(RESTRICT_DISPLAY_MODES)
        @Override // Binder call
        public void requestDisplayModes(IBinder token, int displayId, @Nullable int[] modeIds) {
            requestDisplayModes_enforcePermission();
            DisplayManagerService.this.mDisplayModeDirector.requestDisplayModes(
                    token, displayId, modeIds);
        }
    }

    private static boolean isValidBrightness(float brightness) {
        return !Float.isNaN(brightness)
                && (brightness >= PowerManager.BRIGHTNESS_MIN)
                && (brightness <= PowerManager.BRIGHTNESS_MAX);
    }

    @VisibleForTesting
    void overrideSensorManager(SensorManager sensorManager) {
        synchronized (mSyncRoot) {
            mSensorManager = sensorManager;
        }
    }

    @VisibleForTesting
    final class LocalService extends DisplayManagerInternal {

        @Override
        public void initPowerManagement(final DisplayPowerCallbacks callbacks, Handler handler,
                SensorManager sensorManager) {
            synchronized (mSyncRoot) {
                mDisplayPowerCallbacks = callbacks;
                mSensorManager = sensorManager;
                mPowerHandler = handler;
                initializeDisplayPowerControllersLocked();
            }

            mHandler.sendEmptyMessage(MSG_LOAD_BRIGHTNESS_CONFIGURATIONS);
        }

        @Override
        public int createVirtualDisplay(VirtualDisplayConfig config,
                IVirtualDisplayCallback callback, IVirtualDevice virtualDevice,
                DisplayWindowPolicyController dwpc, String packageName) {
            return createVirtualDisplayInternal(config, callback, null, virtualDevice, dwpc,
                    packageName);
        }

        @Override
        public boolean requestPowerState(int groupId, DisplayPowerRequest request,
                boolean waitForNegativeProximity) {
            synchronized (mSyncRoot) {
                final DisplayGroup displayGroup = mLogicalDisplayMapper.getDisplayGroupLocked(
                        groupId);
                if (displayGroup == null) {
                    return true;
                }

                final int size = displayGroup.getSizeLocked();
                boolean ready = true;
                for (int i = 0; i < size; i++) {
                    final int id = displayGroup.getIdLocked(i);
                    final DisplayDevice displayDevice = mLogicalDisplayMapper.getDisplayLocked(
                            id).getPrimaryDisplayDeviceLocked();
                    final int flags = displayDevice.getDisplayDeviceInfoLocked().flags;
                    if ((flags & DisplayDeviceInfo.FLAG_NEVER_BLANK) == 0) {
                        final DisplayPowerControllerInterface displayPowerController =
                                mDisplayPowerControllers.get(id);
                        if (displayPowerController != null) {
                            ready &= displayPowerController.requestPowerState(request,
                                    waitForNegativeProximity);
                        }
                    }
                }

                return ready;
            }
        }

        @Override
        public boolean isProximitySensorAvailable() {
            synchronized (mSyncRoot) {
                return mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                        .isProximitySensorAvailable();
            }
        }

        @Override
        public void registerDisplayGroupListener(DisplayGroupListener listener) {
            mDisplayGroupListeners.add(listener);
        }

        @Override
        public void unregisterDisplayGroupListener(DisplayGroupListener listener) {
            mDisplayGroupListeners.remove(listener);
        }

        @Override
        public ScreenCapture.ScreenshotHardwareBuffer systemScreenshot(int displayId) {
            return systemScreenshotInternal(displayId);
        }

        @Override
        public ScreenCapture.ScreenshotHardwareBuffer userScreenshot(int displayId) {
            return userScreenshotInternal(displayId);
        }

        @Override
        public DisplayInfo getDisplayInfo(int displayId) {
            return getDisplayInfoInternal(displayId, Process.myUid());
        }

        @Override
        public Set<DisplayInfo> getPossibleDisplayInfo(int displayId) {
            synchronized (mSyncRoot) {
                Set<DisplayInfo> possibleInfo = new ArraySet<>();
                // For each of supported device states, retrieve the display layout of that state,
                // and return all of the DisplayInfos (one per state) for the given display id.
                if (mDeviceStateManager == null) {
                    Slog.w(TAG, "Can't get supported states since DeviceStateManager not ready");
                    return possibleInfo;
                }
                final int[] supportedStates =
                        mDeviceStateManager.getSupportedStateIdentifiers();
                DisplayInfo displayInfo;
                for (int state : supportedStates) {
                    displayInfo = mLogicalDisplayMapper.getDisplayInfoForStateLocked(state,
                            displayId);
                    if (displayInfo != null) {
                        possibleInfo.add(displayInfo);
                    }
                }
                return possibleInfo;
            }
        }

        @Override
        public Point getDisplayPosition(int displayId) {
            synchronized (mSyncRoot) {
                final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
                if (display != null) {
                    return display.getDisplayPosition();
                }
                return null;
            }
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
        public void performTraversal(SurfaceControl.Transaction t,
                SparseArray<SurfaceControl.Transaction> displayTransactions) {
            performTraversalInternal(t, displayTransactions);
        }

        @Override
        public void setDisplayProperties(int displayId, boolean hasContent,
                float requestedRefreshRate, int requestedMode, float requestedMinRefreshRate,
                float requestedMaxRefreshRate, boolean requestedMinimalPostProcessing,
                boolean disableHdrConversion, boolean inTraversal) {
            setDisplayPropertiesInternal(displayId, hasContent, requestedRefreshRate,
                    requestedMode, requestedMinRefreshRate, requestedMaxRefreshRate,
                    requestedMinimalPostProcessing, disableHdrConversion, inTraversal);
        }

        @Override
        public void setDisplayOffsets(int displayId, int x, int y) {
            setDisplayOffsetsInternal(displayId, x, y);
        }

        @Override
        public void setDisplayScalingDisabled(int displayId, boolean disableScaling) {
            setDisplayScalingDisabledInternal(displayId, disableScaling);
        }

        @Override
        public void setDisplayAccessUIDs(SparseArray<IntArray> newDisplayAccessUIDs) {
            setDisplayAccessUIDsInternal(newDisplayAccessUIDs);
        }

        @Override
        public void persistBrightnessTrackerState() {
            synchronized (mSyncRoot) {
                mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                        .persistBrightnessTrackerState();
            }
        }

        @Override
        public void onOverlayChanged() {
            synchronized (mSyncRoot) {
                mDisplayDeviceRepo.forEachLocked(DisplayDevice::onOverlayChangedLocked);
            }
        }

        @Override
        public DisplayedContentSamplingAttributes getDisplayedContentSamplingAttributes(
                int displayId) {
            return getDisplayedContentSamplingAttributesInternal(displayId);
        }

        @Override
        public boolean setDisplayedContentSamplingEnabled(
                int displayId, boolean enable, int componentMask, int maxFrames) {
            return setDisplayedContentSamplingEnabledInternal(
                    displayId, enable, componentMask, maxFrames);
        }

        @Override
        public DisplayedContentSample getDisplayedContentSample(int displayId,
                long maxFrames, long timestamp) {
            return getDisplayedContentSampleInternal(displayId, maxFrames, timestamp);
        }

        @Override
        public void ignoreProximitySensorUntilChanged() {
            mDisplayPowerControllers.get(Display.DEFAULT_DISPLAY)
                    .ignoreProximitySensorUntilChanged();
        }

        @Override
        public int getRefreshRateSwitchingType() {
            return getRefreshRateSwitchingTypeInternal();
        }

        @Override
        public RefreshRateRange getRefreshRateForDisplayAndSensor(int displayId, String sensorName,
                String sensorType) {
            final SensorManager sensorManager;
            synchronized (mSyncRoot) {
                sensorManager = mSensorManager;
            }
            if (sensorManager == null) {
                return null;
            }

            // Verify that the specified sensor exists.
            final Sensor sensor = SensorUtils.findSensor(sensorManager, sensorType, sensorName,
                    SensorUtils.NO_FALLBACK);
            if (sensor == null) {
                return null;
            }

            synchronized (mSyncRoot) {
                final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
                if (display == null) {
                    return null;
                }
                final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
                if (device == null) {
                    return null;
                }
                final DisplayDeviceConfig config = device.getDisplayDeviceConfig();
                SensorData sensorData = config.getProximitySensor();
                if (sensorData != null && sensorData.matches(sensorName, sensorType)) {
                    return new RefreshRateRange(sensorData.minRefreshRate,
                            sensorData.maxRefreshRate);
                }
            }
            return null;
        }

        @Override
        public List<RefreshRateLimitation> getRefreshRateLimitations(int displayId) {
            final DisplayDeviceConfig config;
            synchronized (mSyncRoot) {
                final DisplayDevice device = getDeviceForDisplayLocked(displayId);
                if (device == null) {
                    return null;
                }
                config = device.getDisplayDeviceConfig();
            }
            return config.getRefreshRateLimitations();
        }

        @Override
        public void setWindowManagerMirroring(int displayId, boolean isMirroring) {
            synchronized (mSyncRoot) {
                final DisplayDevice device = getDeviceForDisplayLocked(displayId);
                if (device != null) {
                    device.setWindowManagerMirroringLocked(isMirroring);
                }
            }
        }

        @Override
        public Point getDisplaySurfaceDefaultSize(int displayId) {
            final DisplayDevice device;
            synchronized (mSyncRoot) {
                device = getDeviceForDisplayLocked(displayId);
                if (device == null) {
                    return null;
                }
                return device.getDisplaySurfaceDefaultSizeLocked();
            }
        }

        @Override
        public void onEarlyInteractivityChange(boolean interactive) {
            mLogicalDisplayMapper.onEarlyInteractivityChange(interactive);
        }

        @Override
        public DisplayWindowPolicyController getDisplayWindowPolicyController(int displayId) {
            synchronized (mSyncRoot) {
                if (mDisplayWindowPolicyControllers.contains(displayId)) {
                    return mDisplayWindowPolicyControllers.get(displayId).second;
                }
                return null;
            }
        }

        @Override
        public int getDisplayIdToMirror(int displayId) {
            synchronized (mSyncRoot) {
                final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
                if (display == null) {
                    return Display.INVALID_DISPLAY;
                }

                final DisplayDevice displayDevice = display.getPrimaryDisplayDeviceLocked();
                final boolean ownContent = (displayDevice.getDisplayDeviceInfoLocked().flags
                        & DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY) != 0;
                // If the display has enabled mirroring, but specified that it will be managed by
                // WindowManager, return an invalid display id. This is to ensure we don't
                // accidentally select the display id to mirror based on DM logic and instead allow
                // the caller to specify what area to mirror.
                if (ownContent || displayDevice.isWindowManagerMirroringLocked()) {
                    return Display.INVALID_DISPLAY;
                }

                int displayIdToMirror = displayDevice.getDisplayIdToMirrorLocked();
                LogicalDisplay displayToMirror = mLogicalDisplayMapper.getDisplayLocked(
                        displayIdToMirror);
                // If the displayId for the requested mirror doesn't exist, fallback to mirroring
                // default display.
                if (displayToMirror == null) {
                    displayIdToMirror = Display.DEFAULT_DISPLAY;
                }
                return displayIdToMirror;
            }
        }

        @Override
        public SurfaceControl.DisplayPrimaries getDisplayNativePrimaries(int displayId) {
            IBinder displayToken;
            synchronized (mSyncRoot) {
                displayToken = getDisplayToken(displayId);
                if (displayToken == null) {
                    throw new IllegalArgumentException("Invalid displayId=" + displayId);
                }
            }

            return SurfaceControl.getDisplayNativePrimaries(displayToken);
        }

        @Override
        public HostUsiVersion getHostUsiVersion(int displayId) {
            synchronized (mSyncRoot) {
                final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
                if (display == null) {
                    return null;
                }

                return display.getPrimaryDisplayDeviceLocked().getDisplayDeviceConfig()
                        .getHostUsiVersion();
            }
        }

        @Override
        public AmbientLightSensorData getAmbientLightSensorData(int displayId) {
            synchronized (mSyncRoot) {
                final LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(displayId);
                if (display == null) {
                    return null;
                }
                final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
                if (device == null) {
                    return null;
                }
                SensorData data = device.getDisplayDeviceConfig().getAmbientLightSensor();
                return new AmbientLightSensorData(data.name, data.type);
            }
        }

        @Override
        public IntArray getDisplayGroupIds() {
            Set<Integer> visitedIds = new ArraySet<>();
            IntArray displayGroupIds = new IntArray();
            synchronized (mSyncRoot) {
                mLogicalDisplayMapper.forEachLocked(logicalDisplay -> {
                    int groupId = mLogicalDisplayMapper.getDisplayGroupIdFromDisplayIdLocked(
                            logicalDisplay.getDisplayIdLocked());
                    if (!visitedIds.contains(groupId)) {
                        visitedIds.add(groupId);
                        displayGroupIds.add(groupId);
                    }
                });
            }
            return displayGroupIds;
        }

        @Override
        public DisplayManagerInternal.DisplayOffloadSession registerDisplayOffloader(
                int displayId, @NonNull DisplayManagerInternal.DisplayOffloader displayOffloader) {
            if (!mFlags.isDisplayOffloadEnabled()) {
                return null;
            }
            synchronized (mSyncRoot) {
                LogicalDisplay logicalDisplay = mLogicalDisplayMapper.getDisplayLocked(displayId);
                if (logicalDisplay == null) {
                    Slog.w(TAG, "registering DisplayOffloader: LogicalDisplay for displayId="
                            + displayId + " is not found. No Op.");
                    return null;
                }

                DisplayPowerControllerInterface displayPowerController =
                        mDisplayPowerControllers.get(logicalDisplay.getDisplayIdLocked());
                if (displayPowerController == null) {
                    Slog.w(TAG,
                            "setting doze state override: DisplayPowerController for displayId="
                                    + displayId + " is unavailable. No Op.");
                    return null;
                }

                DisplayOffloadSessionImpl session = new DisplayOffloadSessionImpl(displayOffloader,
                        displayPowerController);
                logicalDisplay.setDisplayOffloadSessionLocked(session);
                displayPowerController.setDisplayOffloadSession(session);
                return session;
            }
        }

        @Override
        public void onPresentation(int displayId, boolean isShown) {
            mExternalDisplayPolicy.onPresentation(displayId, isShown);
        }
    }

    class DesiredDisplayModeSpecsObserver
            implements DisplayModeDirector.DesiredDisplayModeSpecsListener {

        private final Consumer<LogicalDisplay> mSpecsChangedConsumer = display -> {
            int displayId = display.getDisplayIdLocked();
            DisplayModeDirector.DesiredDisplayModeSpecs desiredDisplayModeSpecs =
                    mDisplayModeDirector.getDesiredDisplayModeSpecs(displayId);
            DisplayModeDirector.DesiredDisplayModeSpecs existingDesiredDisplayModeSpecs =
                    display.getDesiredDisplayModeSpecsLocked();
            if (DEBUG) {
                Slog.i(TAG,
                        "Comparing display specs: " + desiredDisplayModeSpecs
                                + ", existing: " + existingDesiredDisplayModeSpecs);
            }
            if (!desiredDisplayModeSpecs.equals(existingDesiredDisplayModeSpecs)) {
                display.setDesiredDisplayModeSpecsLocked(desiredDisplayModeSpecs);
                mChanged = true;
            }
        };

        @GuardedBy("mSyncRoot")
        private boolean mChanged = false;

        public void onDesiredDisplayModeSpecsChanged() {
            synchronized (mSyncRoot) {
                mChanged = false;
                mLogicalDisplayMapper.forEachLocked(mSpecsChangedConsumer,
                        /* includeDisabled= */ false);
                if (mChanged) {
                    scheduleTraversalLocked(false);
                    mChanged = false;
                }
            }
        }
    }

    /**
     * Listens to changes in device state and reports the state to LogicalDisplayMapper.
     */
    class DeviceStateListener implements DeviceStateManager.DeviceStateCallback {

        @Override
        public void onDeviceStateChanged(DeviceState deviceState) {
            synchronized (mSyncRoot) {
                // Notify WindowManager that we are about to handle new device state, this should
                // be sent before any work related to the device state in DisplayManager, so
                // WindowManager could do implement that depends on the device state and display
                // changes (serializes device state update and display change events)
                Message msg = mHandler.obtainMessage(MSG_RECEIVED_DEVICE_STATE);
                msg.arg1 = deviceState.getIdentifier();
                mHandler.sendMessage(msg);

                mLogicalDisplayMapper
                        .setDeviceStateLocked(deviceState.getIdentifier());
            }
        }
    };

    private class BrightnessPair {
        public float brightness;
        public float sdrBrightness;

        BrightnessPair(float brightness, float sdrBrightness) {
            this.brightness = brightness;
            this.sdrBrightness = sdrBrightness;
        }
    }

    /**
     * Functional interface for providing time.
     * TODO(b/184781936): merge with PowerManagerService.Clock
     */
    @VisibleForTesting
    public interface Clock {
        /**
         * Returns current time in milliseconds since boot, not counting time spent in deep sleep.
         */
        long uptimeMillis();
    }

    /**
     * Implements necessary functionality for {@link ExternalDisplayPolicy}
     */
    private class ExternalDisplayPolicyInjector implements ExternalDisplayPolicy.Injector {
        /**
         * Sends event for the display.
         */
        @Override
        public void sendExternalDisplayEventLocked(@NonNull final LogicalDisplay display,
                @DisplayEvent int event) {
            sendDisplayEventLocked(display, event);
        }

        /**
         * Gets thermal service
         */
        @Override
        @Nullable
        public IThermalService getThermalService() {
            return IThermalService.Stub.asInterface(ServiceManager.getService(
                    Context.THERMAL_SERVICE));
        }

        /**
         * @return display manager flags.
         */
        @Override
        @NonNull
        public DisplayManagerFlags getFlags() {
            return mFlags;
        }

        /**
         * @return Logical display mapper.
         */
        @Override
        @NonNull
        public LogicalDisplayMapper getLogicalDisplayMapper() {
            return mLogicalDisplayMapper;
        }

        /**
         * @return Sync root, for synchronization on this object across display manager.
         */
        @Override
        @NonNull
        public SyncRoot getSyncRoot() {
            return mSyncRoot;
        }

        /**
         * Notification manager for display manager
         */
        @Override
        @NonNull
        public DisplayNotificationManager getDisplayNotificationManager() {
            return mDisplayNotificationManager;
        }

        /**
         * Handler to use for notification sending to avoid requiring POST_NOTIFICATION permission.
         */
        @Override
        @NonNull
        public Handler getHandler() {
            return mHandler;
        }

        /**
         * Gets service used for metrics collection.
         */
        @Override
        @NonNull
        public ExternalDisplayStatsService getExternalDisplayStatsService() {
            return mExternalDisplayStatsService;
        }
    }
}
