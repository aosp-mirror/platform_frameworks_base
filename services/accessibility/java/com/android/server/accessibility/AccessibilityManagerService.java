/*
 ** Copyright 2009, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import static android.Manifest.permission.CREATE_VIRTUAL_DEVICE;
import static android.Manifest.permission.INJECT_EVENTS;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.MANAGE_ACCESSIBILITY;
import static android.Manifest.permission.MANAGE_BIND_INSTANT_SERVICE;
import static android.Manifest.permission.MODIFY_ACCESSIBILITY_DATA;
import static android.Manifest.permission.RETRIEVE_WINDOW_CONTENT;
import static android.Manifest.permission.SET_SYSTEM_AUDIO_CAPTION;
import static android.Manifest.permission.STATUS_BAR_SERVICE;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_MANAGER;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_MANAGER_CLIENT;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_SERVICE_CLIENT;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_FINGERPRINT;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_INPUT_FILTER;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_MAGNIFICATION_CONNECTION;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_PACKAGE_BROADCAST_RECEIVER;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_USER_BROADCAST_RECEIVER;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_WINDOW_MANAGER_INTERNAL;
import static android.companion.virtual.VirtualDeviceManager.ACTION_VIRTUAL_DEVICE_REMOVED;
import static android.companion.virtual.VirtualDeviceManager.EXTRA_VIRTUAL_DEVICE_ID;
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_GESTURE;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;
import static android.provider.Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;
import static android.view.accessibility.AccessibilityManager.FlashNotificationReason;

import static com.android.hardware.input.Flags.keyboardA11yMouseKeys;
import static com.android.internal.accessibility.AccessibilityShortcutController.ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.internal.accessibility.common.ShortcutConstants.CHOOSER_PACKAGE_NAME;
import static com.android.internal.accessibility.common.ShortcutConstants.USER_SHORTCUT_TYPES;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.ALL;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.GESTURE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.HARDWARE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.QUICK_SETTINGS;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.SOFTWARE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.TRIPLETAP;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.TWOFINGER_DOUBLETAP;
import static com.android.internal.accessibility.dialog.AccessibilityButtonChooserActivity.EXTRA_TYPE_TO_CHOOSE;
import static com.android.internal.accessibility.util.AccessibilityStatsLogUtils.logAccessibilityShortcutActivated;
import static com.android.internal.accessibility.util.AccessibilityUtils.isUserSetupCompleted;
import static com.android.internal.util.FunctionalUtils.ignoreRemoteException;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.accessibility.AccessibilityUserState.doesShortcutTargetsStringContain;
import static com.android.server.pm.UserManagerService.enforceCurrentUserIfVisibleBackgroundEnabled;
import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.MagnificationConfig;
import android.accessibilityservice.TouchInteractionController;
import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.RequiresNoPermission;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.app.admin.DevicePolicyManager;
import android.app.ecm.EnhancedConfirmationManager;
import android.appwidget.AppWidgetManagerInternal;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.IFingerprintService;
import android.media.AudioManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PermissionEnforcer;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.SettingsStringUtil.SettingStringHelper;
import android.safetycenter.SafetyCenterManager;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.IWindow;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowAttributes;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;
import android.view.accessibility.IMagnificationConnection;
import android.view.accessibility.IUserInitializationCompleteCallback;
import android.view.inputmethod.EditorInfo;

import com.android.internal.R;
import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.internal.accessibility.AccessibilityShortcutController.ExtraDimFrameworkFeatureInfo;
import com.android.internal.accessibility.AccessibilityShortcutController.FrameworkFeatureInfo;
import com.android.internal.accessibility.AccessibilityShortcutController.LaunchableFrameworkFeatureInfo;
import com.android.internal.accessibility.common.ShortcutConstants;
import com.android.internal.accessibility.common.ShortcutConstants.FloatingMenuSize;
import com.android.internal.accessibility.dialog.AccessibilityButtonChooserActivity;
import com.android.internal.accessibility.dialog.AccessibilityShortcutChooserActivity;
import com.android.internal.accessibility.util.AccessibilityUtils;
import com.android.internal.accessibility.util.ShortcutUtils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.inputmethod.IAccessibilityInputMethodSession;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IntPair;
import com.android.internal.util.Preconditions;
import com.android.modules.expresslog.Counter;
import com.android.server.AccessibilityManagerInternal;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.accessibility.magnification.MagnificationConnectionManager;
import com.android.server.accessibility.magnification.MagnificationController;
import com.android.server.accessibility.magnification.MagnificationProcessor;
import com.android.server.accessibility.magnification.MagnificationScaleProvider;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.utils.Slogf;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;
import com.android.settingslib.RestrictedLockUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * This class is instantiated by the system as a system level service and can be
 * accessed only by the system. The task of this service is to be a centralized
 * event dispatch for {@link AccessibilityEvent}s generated across all processes
 * on the device. Events are dispatched to {@link AccessibilityService}s.
 */
public class AccessibilityManagerService extends IAccessibilityManager.Stub
        implements AbstractAccessibilityServiceConnection.SystemSupport,
        AccessibilityUserState.ServiceInfoChangeListener,
        AccessibilityWindowManager.AccessibilityEventSender,
        AccessibilitySecurityPolicy.AccessibilityUserManager,
        SystemActionPerformer.SystemActionsChangedListener,
        SystemActionPerformer.DisplayUpdateCallBack, ProxyManager.SystemSupport {

    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "AccessibilityManagerService";

    // TODO: This is arbitrary. When there is time implement this by watching
    //       when that accessibility services are bound.
    private static final int WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS = 3000;

    // TODO: Restructure service initialization so services aren't connected before all of
    //       their capabilities are ready.
    private static final int WAIT_INPUT_FILTER_INSTALL_TIMEOUT_MS = 1000;


    // This postpones state changes events when a window doesn't exist with the expectation that
    // a race condition will resolve. It is determined by observing elapsed time of the
    // corresponding window added.
    //TODO(b/230810909) : Fix it with a better idea.
    private static final int POSTPONE_WINDOW_STATE_CHANGED_EVENT_TIMEOUT_MILLIS = 500;

    private static final String FUNCTION_REGISTER_UI_TEST_AUTOMATION_SERVICE =
        "registerUiTestAutomationService";

    private static final String GET_WINDOW_TOKEN = "getWindowToken";

    private static final String SET_PIP_ACTION_REPLACEMENT =
            "setPictureInPictureActionReplacingConnection";

    /**
     * An intent action to launch Hearing devices dialog.
     */
    @VisibleForTesting
    static final String ACTION_LAUNCH_HEARING_DEVICES_DIALOG =
            "com.android.systemui.action.LAUNCH_HEARING_DEVICES_DIALOG";

    private static final char COMPONENT_NAME_SEPARATOR = ':';

    private static final int OWN_PROCESS_ID = android.os.Process.myPid();

    public static final int INVALID_SERVICE_ID = -1;

    // Each service has an ID. Also provide one for magnification gesture handling
    public static final int MAGNIFICATION_GESTURE_HANDLER_ID = 0;

    private static int sIdCounter = MAGNIFICATION_GESTURE_HANDLER_ID + 1;
    /**
     * The counter metric id tracking how many times users add qs shortcut for a11y features.
     *
     * <p>Defined in frameworks/proto_logging/stats/express/catalog/accessibility.cfg.
     */
    static final String METRIC_ID_QS_SHORTCUT_ADD = "accessibility.value_qs_shortcut_add";

    /**
     * The counter metric id tracking how many times users remove qs shortcut for a11y features.
     *
     * <p>Defined in frameworks/proto_logging/stats/express/catalog/accessibility.cfg.
     */
    static final String METRIC_ID_QS_SHORTCUT_REMOVE = "accessibility.value_qs_shortcut_remove";


    private final Context mContext;

    private final Object mLock = new Object();

    private final SimpleStringSplitter mStringColonSplitter =
            new SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);

    private final Rect mTempRect = new Rect();
    private final Rect mTempRect1 = new Rect();

    private final PackageManager mPackageManager;

    private final PowerManager mPowerManager;

    private final UserManager mUserManager;

    private final WindowManagerInternal mWindowManagerService;

    private final AccessibilitySecurityPolicy mSecurityPolicy;

    private final AccessibilityWindowManager mA11yWindowManager;

    private final AccessibilityDisplayListener mA11yDisplayListener;

    private final ActivityTaskManagerInternal mActivityTaskManagerService;

    private final MagnificationController mMagnificationController;
    private final MagnificationProcessor mMagnificationProcessor;

    private final Handler mMainHandler;

    // Lazily initialized - access through getSystemActionPerformer()
    private SystemActionPerformer mSystemActionPerformer;

    private InteractionBridge mInteractionBridge;

    private AlertDialog mEnableTouchExplorationDialog;

    private AccessibilityInputFilter mInputFilter;

    private boolean mHasInputFilter;

    private boolean mInputFilterInstalled;

    private KeyEventDispatcher mKeyEventDispatcher;

    private SparseArray<MotionEventInjector> mMotionEventInjectors;

    private FingerprintGestureDispatcher mFingerprintGestureDispatcher;

    private final Set<ComponentName> mTempComponentNameSet = new HashSet<>();

    private final IntArray mTempIntArray = new IntArray(0);

    private final RemoteCallbackList<IAccessibilityManagerClient> mGlobalClients =
            new RemoteCallbackList<>();

    private PackageMonitor mPackageMonitor;

    @VisibleForTesting
    final SparseArray<AccessibilityUserState> mUserStates = new SparseArray<>();

    private final UiAutomationManager mUiAutomationManager = new UiAutomationManager(mLock);
    private final ProxyManager mProxyManager;
    private final AccessibilityTraceManager mTraceManager;
    private final CaptioningManagerImpl mCaptioningManagerImpl;

    private final List<SendWindowStateChangedEventRunnable> mSendWindowStateChangedEventRunnables =
            new ArrayList<>();

    @VisibleForTesting
    final HashSet<IUserInitializationCompleteCallback>
            mUserInitializationCompleteCallbacks =
            new HashSet<IUserInitializationCompleteCallback>();

    @GuardedBy("mLock")
    private @UserIdInt int mCurrentUserId = UserHandle.USER_SYSTEM;

    // TODO(b/255426725): temporary workaround to support visible background users for UiAutomation:
    // when the UiAutomation is set in a visible background user, mCurrentUserId points to that user
    // and mRealCurrentUserId points to the "real" current user; otherwise, mRealCurrentUserId
    // is set as UserHandle.USER_CURRENT.
    @GuardedBy("mLock")
    private @UserIdInt int mRealCurrentUserId = UserHandle.USER_CURRENT;

    // TODO(b/255426725): temporary workaround to support visible background users for UiAutomation
    // purposes - in the long term, the whole service should be refactored so it handles "visible"
    // users, not current user. Notice that because this is temporary, it's not trying to optimize
    // performance / utilization (for example, it's not using an IntArray)
    @GuardedBy("mLock")
    @Nullable // only set when device supports visible background users
    private final SparseBooleanArray mVisibleBgUserIds;

    //TODO: Remove this hack
    private boolean mInitialized;

    private Point mTempPoint = new Point();
    private boolean mIsAccessibilityButtonShown;
    private boolean mInputBound;
    IRemoteAccessibilityInputConnection mRemoteInputConnection;
    EditorInfo mEditorInfo;
    boolean mRestarting;
    boolean mInputSessionRequested;
    private SparseArray<SurfaceControl> mA11yOverlayLayers = new SparseArray<>();

    private final FlashNotificationsController mFlashNotificationsController;
    private final UserManagerInternal mUmi;

    private AccessibilityUserState getCurrentUserStateLocked() {
        return getUserStateLocked(mCurrentUserId);
    }

    /**
     * Changes the magnification mode on the given display.
     *
     * @param displayId the logical display
     * @param magnificationMode the target magnification mode
     */
    public void changeMagnificationMode(int displayId, int magnificationMode) {
        synchronized (mLock) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                persistMagnificationModeSettingsLocked(magnificationMode);
            } else {
                final AccessibilityUserState userState = getCurrentUserStateLocked();
                final int currentMode = userState.getMagnificationModeLocked(displayId);
                if (magnificationMode != currentMode) {
                    userState.setMagnificationModeLocked(displayId, magnificationMode);
                    updateMagnificationModeChangeSettingsLocked(userState, displayId);
                }
            }
        }
    }

    private static final class LocalServiceImpl extends AccessibilityManagerInternal {
        @NonNull
        private final AccessibilityManagerService mService;

        LocalServiceImpl(@NonNull AccessibilityManagerService service) {
            mService = service;
        }

        @Override
        public void setImeSessionEnabled(SparseArray<IAccessibilityInputMethodSession> sessions,
                boolean enabled) {
            mService.scheduleSetImeSessionEnabled(sessions, enabled);
        }

        @Override
        public void unbindInput() {
            mService.scheduleUnbindInput();
        }

        @Override
        public void bindInput() {
            mService.scheduleBindInput();
        }

        @Override
        public void createImeSession(ArraySet<Integer> ignoreSet) {
            mService.scheduleCreateImeSession(ignoreSet);
        }

        @Override
        public void startInput(
                IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
                EditorInfo editorInfo, boolean restarting) {
            mService.scheduleStartInput(remoteAccessibilityInputConnection, editorInfo, restarting);
        }

        @Override
        public void performSystemAction(int actionId) {
            mService.getSystemActionPerformer().performSystemAction(actionId);
        }

        @Override
        public boolean isTouchExplorationEnabled(@UserIdInt int userId) {
            synchronized (mService.mLock) {
                return mService.getUserStateLocked(userId).isTouchExplorationEnabledLocked();
            }
        }
    }

    public static final class Lifecycle extends SystemService {
        private final AccessibilityManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new AccessibilityManagerService(context);
        }

        @Override
        public void onStart() {
            LocalServices.addService(AccessibilityManagerInternal.class,
                    new LocalServiceImpl(mService));
            publishBinderService(Context.ACCESSIBILITY_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            mService.onBootPhase(phase);
        }
    }

    @VisibleForTesting
    AccessibilityManagerService(
            Context context,
            Handler handler,
            PackageManager packageManager,
            AccessibilitySecurityPolicy securityPolicy,
            SystemActionPerformer systemActionPerformer,
            AccessibilityWindowManager a11yWindowManager,
            AccessibilityDisplayListener a11yDisplayListener,
            MagnificationController magnificationController,
            @Nullable AccessibilityInputFilter inputFilter,
            ProxyManager proxyManager,
            PermissionEnforcer permissionEnforcer) {
        super(permissionEnforcer);
        mContext = context;
        mPowerManager =  (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mUserManager = mContext.getSystemService(UserManager.class);
        mWindowManagerService = LocalServices.getService(WindowManagerInternal.class);
        mTraceManager = AccessibilityTraceManager.getInstance(
                mWindowManagerService.getAccessibilityController(), this, mLock);
        mMainHandler = handler;
        mActivityTaskManagerService = LocalServices.getService(ActivityTaskManagerInternal.class);
        mPackageManager = packageManager;
        mSecurityPolicy = securityPolicy;
        mSystemActionPerformer = systemActionPerformer;
        mA11yWindowManager = a11yWindowManager;
        mA11yDisplayListener = a11yDisplayListener;
        mMagnificationController = magnificationController;
        mMagnificationProcessor = new MagnificationProcessor(mMagnificationController);
        mCaptioningManagerImpl = new CaptioningManagerImpl(mContext);
        mProxyManager = proxyManager;
        if (inputFilter != null) {
            mInputFilter = inputFilter;
            mHasInputFilter = true;
        }
        mFlashNotificationsController = new FlashNotificationsController(mContext);
        mUmi = LocalServices.getService(UserManagerInternal.class);
        // TODO(b/255426725): not used on tests
        mVisibleBgUserIds = null;

        init();
    }

    /**
     * Creates a new instance.
     *
     * @param context A {@link Context} instance.
     */
    public AccessibilityManagerService(Context context) {
        super(PermissionEnforcer.fromContext(context));
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
        mUserManager = context.getSystemService(UserManager.class);
        mWindowManagerService = LocalServices.getService(WindowManagerInternal.class);
        mTraceManager = AccessibilityTraceManager.getInstance(
                mWindowManagerService.getAccessibilityController(), this, mLock);
        mMainHandler = new MainHandler(mContext.getMainLooper());
        mActivityTaskManagerService = LocalServices.getService(ActivityTaskManagerInternal.class);
        mPackageManager = mContext.getPackageManager();
        final PolicyWarningUIController policyWarningUIController = new PolicyWarningUIController(
                mMainHandler, context,
                new PolicyWarningUIController.NotificationController(context));
        mSecurityPolicy = new AccessibilitySecurityPolicy(policyWarningUIController, mContext,
                this, LocalServices.getService(PackageManagerInternal.class));
        mA11yWindowManager = new AccessibilityWindowManager(mLock, mMainHandler,
                mWindowManagerService, this, mSecurityPolicy, this, mTraceManager);
        mA11yDisplayListener = new AccessibilityDisplayListener(mContext, mMainHandler);
        mMagnificationController = new MagnificationController(
                this,
                mLock,
                mContext,
                new MagnificationScaleProvider(mContext),
                Executors.newSingleThreadExecutor()
        );
        mMagnificationProcessor = new MagnificationProcessor(mMagnificationController);
        mCaptioningManagerImpl = new CaptioningManagerImpl(mContext);
        mProxyManager = new ProxyManager(mLock, mA11yWindowManager, mContext, mMainHandler,
                mUiAutomationManager, this);
        mFlashNotificationsController = new FlashNotificationsController(mContext);
        mUmi = LocalServices.getService(UserManagerInternal.class);

        if (UserManager.isVisibleBackgroundUsersEnabled()) {
            mVisibleBgUserIds = new SparseBooleanArray();
            mUmi.addUserVisibilityListener((u, v) -> onUserVisibilityChanged(u, v));
        } else {
            mVisibleBgUserIds = null;
        }

        init();
    }

    private void init() {
        mSecurityPolicy.setAccessibilityWindowManager(mA11yWindowManager);
        registerBroadcastReceivers();
        new AccessibilityContentObserver(mMainHandler).register(
                mContext.getContentResolver());
        disableAccessibilityMenuToMigrateIfNeeded();
    }

    /**
     * Returns if the current thread is holding {@link #mLock}. Used for testing
     * deadlock bug fixes.
     *
     * <p><strong>Warning:</strong> this should not be used for production logic
     * because by the time you receive an answer it may no longer be valid.
     * </p>
     */
    @VisibleForTesting
    boolean unsafeIsLockHeld() {
        return Thread.holdsLock(mLock);
    }

    /**
     * Returns if the service is initialized.
     *
     * The service is considered initialized when the user switch happened.
     */
    private boolean isServiceInitializedLocked() {
        return mInitialized;
    }

    @Override
    public int getCurrentUserIdLocked() {
        return mCurrentUserId;
    }

    @GuardedBy("mLock")
    @Override
    public SparseBooleanArray getVisibleUserIdsLocked() {
        return mVisibleBgUserIds;
    }

    @Override
    public boolean isAccessibilityButtonShown() {
        return mIsAccessibilityButtonShown;
    }

    @Override
    public Pair<float[], MagnificationSpec> getWindowTransformationMatrixAndMagnificationSpec(
            int windowId) {
        WindowInfo windowInfo;
        synchronized (mLock) {
            windowInfo = mA11yWindowManager.findWindowInfoByIdLocked(windowId);
        }
        if (windowInfo != null) {
            final MagnificationSpec spec = new MagnificationSpec();
            spec.setTo(windowInfo.mMagnificationSpec);
            return new Pair<>(windowInfo.mTransformMatrix, spec);
        } else {
            // If the framework doesn't track windows, we fall back to get the pair of
            // transformation matrix and MagnificationSpe from the WindowManagerService's
            // WindowState.
            IBinder token;
            synchronized (mLock) {
                token = mA11yWindowManager.getWindowTokenForUserAndWindowIdLocked(mCurrentUserId,
                        windowId);
            }
            Pair<Matrix, MagnificationSpec> pair =
                    mWindowManagerService.getWindowTransformationMatrixAndMagnificationSpec(token);
            final float[] outTransformationMatrix = new float[9];
            final Matrix tmpMatrix = pair.first;
            final MagnificationSpec spec = pair.second;
            if (!spec.isNop()) {
                tmpMatrix.postScale(spec.scale, spec.scale);
                tmpMatrix.postTranslate(spec.offsetX, spec.offsetY);
            }
            tmpMatrix.getValues(outTransformationMatrix);

            return new Pair<>(outTransformationMatrix, pair.second);
        }
    }

    @Override
    @RequiresNoPermission
    public IAccessibilityManager.WindowTransformationSpec getWindowTransformationSpec(
            int windowId) {
        IAccessibilityManager.WindowTransformationSpec windowTransformationSpec =
                new IAccessibilityManager.WindowTransformationSpec();
        Pair<float[], MagnificationSpec> result =
                getWindowTransformationMatrixAndMagnificationSpec(windowId);
        windowTransformationSpec.transformationMatrix = result.first;
        windowTransformationSpec.magnificationSpec = result.second;
        return windowTransformationSpec;
    }

    @Override
    public void onServiceInfoChangedLocked(AccessibilityUserState userState) {
        mSecurityPolicy.onBoundServicesChangedLocked(userState.mUserId,
                userState.mBoundServices);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
    }

    @Nullable
    public FingerprintGestureDispatcher getFingerprintGestureDispatcher() {
        return mFingerprintGestureDispatcher;
    }

    /**
     * Called by the {@link AccessibilityInputFilter} when the filter install state changes.
     */
    public void onInputFilterInstalled(boolean installed) {
        synchronized (mLock) {
            mInputFilterInstalled = installed;
            mLock.notifyAll();
        }
    }

    private void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)) {
                mSecurityPolicy.setAppWidgetManager(
                        LocalServices.getService(AppWidgetManagerInternal.class));
            }
        }

        // SafetyCenterService is ready after this phase.
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            setNonA11yToolNotificationToMatchSafetyCenter();
        }
    }

    private void setNonA11yToolNotificationToMatchSafetyCenter() {
        final boolean sendNotification = !mContext.getSystemService(
                SafetyCenterManager.class).isSafetyCenterEnabled();
        synchronized (mLock) {
            mSecurityPolicy.setSendingNonA11yToolNotificationLocked(sendNotification);
        }
    }

    /**
     * Returns the lock object for any synchronized test blocks.
     * External classes should only use for testing.
     * @return lock object.
     */
    @VisibleForTesting
    Object getLock() {
        return mLock;
    }

    AccessibilityUserState getCurrentUserState() {
        synchronized (mLock) {
            return getCurrentUserStateLocked();
        }
    }

    private AccessibilityUserState getUserState(int userId) {
        synchronized (mLock) {
            return getUserStateLocked(userId);
        }
    }

    @NonNull
    private AccessibilityUserState getUserStateLocked(int userId) {
        AccessibilityUserState state = mUserStates.get(userId);
        if (state == null) {
            state = new AccessibilityUserState(userId, mContext, this);
            mUserStates.put(userId, state);
        }
        return state;
    }

    boolean getBindInstantServiceAllowed(int userId) {
        synchronized (mLock) {
            final AccessibilityUserState userState = getUserStateLocked(userId);
            return userState.getBindInstantServiceAllowedLocked();
        }
    }

    void setBindInstantServiceAllowed(int userId, boolean allowed) {
        mContext.enforceCallingOrSelfPermission(
                MANAGE_BIND_INSTANT_SERVICE, "setBindInstantServiceAllowed");
        synchronized (mLock) {
            final AccessibilityUserState userState = getUserStateLocked(userId);
            if (allowed != userState.getBindInstantServiceAllowedLocked()) {
                userState.setBindInstantServiceAllowedLocked(allowed);
                onUserStateChangedLocked(userState);
            }
        }
    }

    private void onSomePackagesChangedLocked(
            @Nullable List<AccessibilityServiceInfo> parsedAccessibilityServiceInfos,
            @Nullable List<AccessibilityShortcutInfo> parsedAccessibilityShortcutInfos) {
        final AccessibilityUserState userState = getCurrentUserStateLocked();
        // Reload the installed services since some services may have different attributes
        // or resolve info (does not support equals), etc. Remove them then to force reload.
        userState.mInstalledServices.clear();
        if (readConfigurationForUserStateLocked(userState,
                    parsedAccessibilityServiceInfos, parsedAccessibilityShortcutInfos)) {
            onUserStateChangedLocked(userState);
        }
    }

    private void onPackageRemovedLocked(String packageName) {
        final AccessibilityUserState userState = getCurrentUserState();
        final Predicate<ComponentName> filter =
                component -> component != null && component.getPackageName().equals(
                        packageName);
        userState.mBindingServices.removeIf(filter);
        userState.mCrashedServices.removeIf(filter);
        final Iterator<ComponentName> it = userState.mEnabledServices.iterator();
        boolean anyServiceRemoved = false;
        while (it.hasNext()) {
            final ComponentName comp = it.next();
            final String compPkg = comp.getPackageName();
            if (compPkg.equals(packageName)) {
                it.remove();
                userState.mTouchExplorationGrantedServices.remove(comp);
                anyServiceRemoved = true;
            }
        }
        if (anyServiceRemoved) {
            // Update the enabled services setting.
            persistComponentNamesToSettingLocked(
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    userState.mEnabledServices, mCurrentUserId);
            // Update the touch exploration granted services setting.
            persistComponentNamesToSettingLocked(
                    Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                    userState.mTouchExplorationGrantedServices, mCurrentUserId);
            onUserStateChangedLocked(userState);
        }
    }

    /**
     * Handles a package or packages being force stopped.
     * Will disable any relevant services,
     * and remove any button targets of continuous services,
     * denoted by {@link AccessibilityServiceInfo#FLAG_REQUEST_ACCESSIBILITY_BUTTON}.
     * If the result is {@code true},
     * then {@link AccessibilityManagerService#onUserStateChangedLocked(
     * AccessibilityUserState, boolean)} should be called afterwards.
     *
     * @param packages list of packages that have stopped.
     * @param userState user state to be read & modified.
     * @return {@code true} if the lists of enabled services or buttons were changed,
     * {@code false} otherwise.
     */
    @VisibleForTesting
    boolean onPackagesForceStoppedLocked(
            String[] packages, AccessibilityUserState userState) {
        final Set<String> packageSet = new HashSet<>(List.of(packages));
        final ArrayList<ComponentName> continuousServices = new ArrayList<>(
                userState.mInstalledServices.stream().filter(service ->
                        (service.flags & FLAG_REQUEST_ACCESSIBILITY_BUTTON)
                                == FLAG_REQUEST_ACCESSIBILITY_BUTTON
                ).map(AccessibilityServiceInfo::getComponentName).toList());

        // Filter out continuous packages that are not from the array of stopped packages.
        continuousServices.removeIf(
                continuousName -> !packageSet.contains(continuousName.getPackageName()));

        boolean enabledServicesChanged = false;
        final Iterator<ComponentName> it = userState.mEnabledServices.iterator();
        while (it.hasNext()) {
            final ComponentName comp = it.next();
            final String compPkg = comp.getPackageName();
            if (packageSet.contains(compPkg)) {
                it.remove();
                userState.getBindingServicesLocked().remove(comp);
                userState.getCrashedServicesLocked().remove(comp);
                enabledServicesChanged = true;
            }
        }
        if (enabledServicesChanged) {
            persistComponentNamesToSettingLocked(
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    userState.mEnabledServices, userState.mUserId);
        }

        // Remove any button targets that match any stopped continuous services
        Set<String> buttonTargets = userState.getShortcutTargetsLocked(SOFTWARE);
        boolean buttonTargetsChanged = buttonTargets.removeIf(
                target -> continuousServices.stream().anyMatch(
                        continuousName -> continuousName.flattenToString().equals(target)));
        if (buttonTargetsChanged) {
            userState.updateShortcutTargetsLocked(buttonTargets, SOFTWARE);
            persistColonDelimitedSetToSettingLocked(
                    ShortcutUtils.convertToKey(SOFTWARE),
                    userState.mUserId,
                    buttonTargets, str -> str);
        }

        return enabledServicesChanged || buttonTargetsChanged;
    }

    @VisibleForTesting
    PackageMonitor getPackageMonitor() {
        return mPackageMonitor;
    }

    @VisibleForTesting
    void setPackageMonitor(PackageMonitor monitor) {
        mPackageMonitor = monitor;
    }

    @SuppressLint("MissingPermission")
    private void registerBroadcastReceivers() {
        // package changes
        mPackageMonitor = new ManagerPackageMonitor(this);
        final Looper packageMonitorLooper;
        if (Flags.packageMonitorDedicatedThread()) {
            // Use a dedicated thread because the default BackgroundThread used by PackageMonitor
            // is shared by other components and can get busy, causing a delay and eventual ANR when
            // responding to broadcasts sent to this PackageMonitor.
            HandlerThread packageMonitorThread = new HandlerThread(LOG_TAG + " PackageMonitor",
                    Process.THREAD_PRIORITY_BACKGROUND);
            packageMonitorThread.start();
            packageMonitorLooper = packageMonitorThread.getLooper();
        } else {
            packageMonitorLooper = null;
        }
        mPackageMonitor.register(mContext, packageMonitorLooper,  UserHandle.ALL, true);

        // user change and unlock
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_SETTING_RESTORED);

        Handler receiverHandler =
                Flags.managerAvoidReceiverTimeout() ? BackgroundThread.getHandler() : null;
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_USER_BROADCAST_RECEIVER)) {
                    mTraceManager.logTrace(
                            LOG_TAG + ".BR.onReceive",
                            FLAGS_USER_BROADCAST_RECEIVER,
                            "context=" + context + ";intent=" + intent);
                }

                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                    unlockUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    removeUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_SETTING_RESTORED.equals(action)) {
                    final String which = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                    if (which == null) {
                        return;
                    }
                    final String previousValue =
                            intent.getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE);
                    final String newValue =
                            intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE);
                    final int restoredFromSdk =
                            intent.getIntExtra(Intent.EXTRA_SETTING_RESTORED_FROM_SDK_INT, 0);
                    switch (which) {
                        case Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES -> {
                            synchronized (mLock) {
                                restoreEnabledAccessibilityServicesLocked(
                                        previousValue, newValue, restoredFromSdk);
                            }
                        }
                        case ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED -> {
                            synchronized (mLock) {
                                restoreLegacyDisplayMagnificationNavBarIfNeededLocked(
                                        newValue, restoredFromSdk);
                            }
                        }
                        case Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                             Settings.Secure.ACCESSIBILITY_GESTURE_TARGETS,
                             Settings.Secure.ACCESSIBILITY_QS_TARGETS,
                             Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE ->
                                restoreShortcutTargets(newValue,
                                        ShortcutUtils.convertToType(which));
                    }
                }
            }
        }, UserHandle.ALL, intentFilter, null, receiverHandler);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(SafetyCenterManager.ACTION_SAFETY_CENTER_ENABLED_CHANGED);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                setNonA11yToolNotificationToMatchSafetyCenter();
            }
        };
        mContext.registerReceiverAsUser(
                receiver, UserHandle.ALL, filter, null, mMainHandler,
                Context.RECEIVER_EXPORTED);

        if (!android.companion.virtual.flags.Flags.vdmPublicApis()) {
            final BroadcastReceiver virtualDeviceReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final int deviceId = intent.getIntExtra(
                            EXTRA_VIRTUAL_DEVICE_ID, DEVICE_ID_DEFAULT);
                    mProxyManager.clearConnections(deviceId);
                }
            };

            final IntentFilter virtualDeviceFilter = new IntentFilter(
                    ACTION_VIRTUAL_DEVICE_REMOVED);
            mContext.registerReceiver(virtualDeviceReceiver, virtualDeviceFilter,
                    Context.RECEIVER_NOT_EXPORTED);
        }
    }

    /**
     * Disables the component returned by
     * {@link AccessibilityUtils#getAccessibilityMenuComponentToMigrate} so that it does not appear
     * in Settings or other places that query for installed accessibility services.
     *
     * <p>
     * SettingsProvider is responsible for migrating users off of Menu-outside-system,
     * which it performs in its initialization before AccessibilityManagerService is started.
     * </p>
     */
    private void disableAccessibilityMenuToMigrateIfNeeded() {
        int userId;
        synchronized (mLock) {
            userId = mCurrentUserId;
        }
        final ComponentName menuToMigrate =
                AccessibilityUtils.getAccessibilityMenuComponentToMigrate(mPackageManager, userId);
        if (menuToMigrate != null) {
            // PackageManager#setComponentEnabledSetting disables the component for only the user
            // linked to PackageManager's context, but mPackageManager is linked to the system user,
            // so grab a new PackageManager for the current user to support secondary users.
            final PackageManager userPackageManager =
                    mContext.createContextAsUser(UserHandle.of(userId), /* flags = */ 0)
                            .getPackageManager();
            userPackageManager.setComponentEnabledSetting(
                    menuToMigrate,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }

    // Called only during settings restore; currently supports only the owner user
    // TODO: b/22388012
    private void restoreLegacyDisplayMagnificationNavBarIfNeededLocked(String newSetting,
            int restoreFromSdkInt) {
        if (restoreFromSdkInt >= Build.VERSION_CODES.R) {
            return;
        }

        boolean displayMagnificationNavBarEnabled;
        try {
            displayMagnificationNavBarEnabled = Integer.parseInt(newSetting) == 1;
        } catch (NumberFormatException e) {
            Slog.w(LOG_TAG, "number format is incorrect" + e);
            return;
        }

        final AccessibilityUserState userState = getUserStateLocked(UserHandle.USER_SYSTEM);
        final Set<String> targetsFromSetting = new ArraySet<>();
        readColonDelimitedSettingToSet(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                userState.mUserId, str -> str, targetsFromSetting);
        final boolean targetsContainMagnification = targetsFromSetting.contains(
                MAGNIFICATION_CONTROLLER_NAME);
        if (targetsContainMagnification == displayMagnificationNavBarEnabled) {
            return;
        }

        if (displayMagnificationNavBarEnabled) {
            targetsFromSetting.add(MAGNIFICATION_CONTROLLER_NAME);
        } else {
            targetsFromSetting.remove(MAGNIFICATION_CONTROLLER_NAME);
        }
        persistColonDelimitedSetToSettingLocked(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                userState.mUserId, targetsFromSetting, str -> str);
        readAccessibilityShortcutTargetsLocked(userState, SOFTWARE);
        onUserStateChangedLocked(userState);
    }

    @Override
    @RequiresNoPermission
    public long addClient(IAccessibilityManagerClient callback, int userId) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".addClient", FLAGS_ACCESSIBILITY_MANAGER,
                    "callback=" + callback + ";userId=" + userId);
        }

        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);

            AccessibilityUserState userState = getUserStateLocked(resolvedUserId);
            // Support a process moving from the default device to a single virtual
            // device.
            final int deviceId = mProxyManager.getFirstDeviceIdForUidLocked(
                    Binder.getCallingUid());
            Client client = new Client(callback, Binder.getCallingUid(), userState, deviceId);
            // If the client is from a process that runs across users such as
            // the system UI or the system we add it to the global state that
            // is shared across users.
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                if (mProxyManager.isProxyedDeviceId(deviceId)) {
                    if (DEBUG) {
                        Slog.v(LOG_TAG, "Added global client for proxy-ed pid: "
                                + Binder.getCallingPid() + " for device id " + deviceId
                                + " with package names " + Arrays.toString(client.mPackageNames));
                    }
                    return IntPair.of(mProxyManager.getStateLocked(deviceId),
                            client.mLastSentRelevantEventTypes);
                }
                mGlobalClients.register(callback, client);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added global client for pid:" + Binder.getCallingPid());
                }
            } else {
                // If the display belongs to a proxy connections
                if (mProxyManager.isProxyedDeviceId(deviceId)) {
                    if (DEBUG) {
                        Slog.v(LOG_TAG, "Added user client for proxy-ed pid: "
                                + Binder.getCallingPid() + " for device id " + deviceId
                                + " with package names " + Arrays.toString(client.mPackageNames));
                    }
                    return IntPair.of(mProxyManager.getStateLocked(deviceId),
                            client.mLastSentRelevantEventTypes);
                }
                userState.mUserClients.register(callback, client);
                // If this client is not for the current user we do not
                // return a state since it is not for the foreground user.
                // We will send the state to the client on a user switch.
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added user client for pid:" + Binder.getCallingPid()
                            + " and userId:" + mCurrentUserId);
                }
            }
            return IntPair.of(
                    (resolvedUserId == mCurrentUserId) ? getClientStateLocked(userState) : 0,
                    client.mLastSentRelevantEventTypes);
        }
    }

    @Override
    @RequiresNoPermission
    public boolean removeClient(IAccessibilityManagerClient callback, int userId) {
        // TODO(b/190216606): Add tracing for removeClient when implementation is the same in master

        synchronized (mLock) {
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);

            AccessibilityUserState userState = getUserStateLocked(resolvedUserId);
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                boolean unregistered = mGlobalClients.unregister(callback);
                if (DEBUG) {
                    Slog.i(LOG_TAG,
                            "Removed global client for pid:" + Binder.getCallingPid() + "state: "
                                    + unregistered);
                }
                return unregistered;
            } else {
                boolean unregistered = userState.mUserClients.unregister(callback);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Removed user client for pid:" + Binder.getCallingPid()
                            + " and userId:" + resolvedUserId + "state: " + unregistered);
                }
                return unregistered;
            }
        }
    }

    @Override
    @RequiresNoPermission
    public void sendAccessibilityEvent(AccessibilityEvent event, int userId) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".sendAccessibilityEvent", FLAGS_ACCESSIBILITY_MANAGER,
                    "event=" + event + ";userId=" + userId);
        }
        boolean dispatchEvent = false;
        int resolvedUserId;

        synchronized (mLock) {
            if (event.getWindowId() ==
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID) {
                // The replacer window isn't shown to services. Move its events into the pip.
                AccessibilityWindowInfo pip = mA11yWindowManager.getPictureInPictureWindowLocked();
                if (pip != null) {
                    int pipId = pip.getId();
                    event.setWindowId(pipId);
                }
            }

            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            resolvedUserId = mSecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(userId);

            // Make sure the reported package is one the caller has access to.
            event.setPackageName(mSecurityPolicy.resolveValidReportedPackageLocked(
                    event.getPackageName(), UserHandle.getCallingAppId(), resolvedUserId,
                    getCallingPid()));

            // This method does nothing for a background user.
            if (resolvedUserId == mCurrentUserId) {
                if (mSecurityPolicy.canDispatchAccessibilityEventLocked(mCurrentUserId, event)) {
                    mA11yWindowManager.updateActiveAndAccessibilityFocusedWindowLocked(
                            mCurrentUserId, event.getWindowId(), event.getSourceNodeId(),
                            event.getEventType(), event.getAction());
                    mSecurityPolicy.updateEventSourceLocked(event);
                    dispatchEvent = true;
                }
                if (mHasInputFilter && mInputFilter != null) {
                    mMainHandler.sendMessage(obtainMessage(
                            AccessibilityManagerService::sendAccessibilityEventToInputFilter,
                            this, AccessibilityEvent.obtain(event)));
                }
            }
        }

        if (dispatchEvent) {
            // Make sure clients receiving this event will be able to get the
            // current state of the windows as the window manager may be delaying
            // the computation for performance reasons.
            boolean shouldComputeWindows = false;
            int displayId = event.getDisplayId();
            final int windowId = event.getWindowId();
            if (windowId != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID
                    && displayId == Display.INVALID_DISPLAY) {
                displayId = mA11yWindowManager.getDisplayIdByUserIdAndWindowId(
                        resolvedUserId, windowId);
                event.setDisplayId(displayId);
            }
            synchronized (mLock) {
                if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        && displayId != Display.INVALID_DISPLAY
                        && mA11yWindowManager.isTrackingWindowsLocked(displayId)) {
                    shouldComputeWindows = true;
                }
            }
            if (shouldComputeWindows) {
                if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_WINDOW_MANAGER_INTERNAL)) {
                    mTraceManager.logTrace("WindowManagerInternal.computeWindowsForAccessibility",
                            FLAGS_WINDOW_MANAGER_INTERNAL, "display=" + displayId);
                }
                final WindowManagerInternal wm = LocalServices.getService(
                        WindowManagerInternal.class);
                wm.computeWindowsForAccessibility(displayId);
                // The App side sends a event to notify that the window visible or focused,
                // but the window information in framework is not updated yet, so we postpone it.
                if (postponeWindowStateEvent(event)) {
                    return;
                }
            }

            synchronized (mLock) {
                dispatchAccessibilityEventLocked(event);
            }
        }

        if (OWN_PROCESS_ID != Binder.getCallingPid()) {
            event.recycle();
        }
    }

    private void dispatchAccessibilityEventLocked(AccessibilityEvent event) {
        if (mProxyManager.isProxyedDisplay(event.getDisplayId())) {
            mProxyManager.sendAccessibilityEventLocked(event);
        } else {
            notifyAccessibilityServicesDelayedLocked(event, false);
            notifyAccessibilityServicesDelayedLocked(event, true);
        }
        mUiAutomationManager.sendAccessibilityEventLocked(event);
    }

    private void sendAccessibilityEventToInputFilter(AccessibilityEvent event) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.notifyAccessibilityEvent(event);
            }
        }
        event.recycle();
    }

    /**
     * This is the implementation of AccessibilityManager system API.
     * System UI calls into this method through AccessibilityManager system API to register a
     * system action.
     */
    @Override
    @EnforcePermission(MANAGE_ACCESSIBILITY)
    public void registerSystemAction(RemoteAction action, int actionId) {
        registerSystemAction_enforcePermission();
        int currentUserId;
        synchronized (mLock) {
            currentUserId = mCurrentUserId;
        }
        enforceCurrentUserIfVisibleBackgroundEnabled(currentUserId);
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".registerSystemAction",
                    FLAGS_ACCESSIBILITY_MANAGER, "action=" + action + ";actionId=" + actionId);
        }
        getSystemActionPerformer().registerSystemAction(actionId, action);
    }

    /**
     * This is the implementation of AccessibilityManager system API.
     * System UI calls into this method through AccessibilityManager system API to unregister a
     * system action.
     */
    @Override
    @EnforcePermission(MANAGE_ACCESSIBILITY)
    public void unregisterSystemAction(int actionId) {
        unregisterSystemAction_enforcePermission();
        int currentUserId;
        synchronized (mLock) {
            currentUserId = mCurrentUserId;
        }
        enforceCurrentUserIfVisibleBackgroundEnabled(currentUserId);
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".unregisterSystemAction",
                    FLAGS_ACCESSIBILITY_MANAGER, "actionId=" + actionId);
        }

        getSystemActionPerformer().unregisterSystemAction(actionId);
    }

    private SystemActionPerformer getSystemActionPerformer() {
        if (mSystemActionPerformer == null) {
            mSystemActionPerformer =
                    new SystemActionPerformer(mContext, mWindowManagerService, null, this, this);
        }
        return mSystemActionPerformer;
    }

    @Override
    @RequiresNoPermission
    public ParceledListSlice<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(
            int userId) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getInstalledAccessibilityServiceList",
                    FLAGS_ACCESSIBILITY_MANAGER, "userId=" + userId);
        }

        final int resolvedUserId;
        final List<AccessibilityServiceInfo> serviceInfos;
        synchronized (mLock) {
            final int deviceId = mProxyManager.getFirstDeviceIdForUidLocked(
                    Binder.getCallingUid());
            if (mProxyManager.isProxyedDeviceId(deviceId)) {
                return new ParceledListSlice<>(
                        mProxyManager.getInstalledAndEnabledServiceInfosLocked(
                                AccessibilityServiceInfo.FEEDBACK_ALL_MASK, deviceId));
            }
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            serviceInfos = new ArrayList<>(
                    getUserStateLocked(resolvedUserId).mInstalledServices);
        }

        if (Binder.getCallingPid() == OWN_PROCESS_ID) {
            return new ParceledListSlice<>(serviceInfos);
        }
        final PackageManagerInternal pm = LocalServices.getService(
                PackageManagerInternal.class);
        final int callingUid = Binder.getCallingUid();
        for (int i = serviceInfos.size() - 1; i >= 0; i--) {
            final AccessibilityServiceInfo serviceInfo = serviceInfos.get(i);
            if (pm.filterAppAccess(serviceInfo.getComponentName().getPackageName(), callingUid,
                    resolvedUserId)) {
                serviceInfos.remove(i);
            }
        }
        return new ParceledListSlice<>(serviceInfos);
    }

    @Override
    @RequiresNoPermission
    public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(int feedbackType,
            int userId) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getEnabledAccessibilityServiceList",
                    FLAGS_ACCESSIBILITY_MANAGER,
                    "feedbackType=" + feedbackType + ";userId=" + userId);
        }

        synchronized (mLock) {
            final int deviceId = mProxyManager.getFirstDeviceIdForUidLocked(
                    Binder.getCallingUid());
            if (mProxyManager.isProxyedDeviceId(deviceId)) {
                return mProxyManager.getInstalledAndEnabledServiceInfosLocked(feedbackType,
                        deviceId);
            }
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);

            // The automation service can suppress other services.
            final AccessibilityUserState userState = getUserStateLocked(resolvedUserId);
            if (mUiAutomationManager.suppressingAccessibilityServicesLocked()) {
                return Collections.emptyList();
            }

            final List<AccessibilityServiceConnection> services = userState.mBoundServices;
            final int serviceCount = services.size();
            final List<AccessibilityServiceInfo> result = new ArrayList<>(serviceCount);
            for (int i = 0; i < serviceCount; ++i) {
                final AccessibilityServiceConnection service = services.get(i);
                if ((service.mFeedbackType & feedbackType) != 0
                        || feedbackType == AccessibilityServiceInfo.FEEDBACK_ALL_MASK) {
                    result.add(service.getServiceInfo());
                }
            }
            return result;
        }
    }

    @Override
    @RequiresNoPermission
    public void interrupt(int userId) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".interrupt",
                    FLAGS_ACCESSIBILITY_MANAGER, "userId=" + userId);
        }

        List<IAccessibilityServiceClient> interfacesToInterrupt;
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            // This method does nothing for a background user.
            if (resolvedUserId != mCurrentUserId) {
                return;
            }

            final int deviceId = mProxyManager.getFirstDeviceIdForUidLocked(
                    Binder.getCallingUid());
            if (mProxyManager.isProxyedDeviceId(deviceId)) {
                interfacesToInterrupt = new ArrayList<>();
                mProxyManager.addServiceInterfacesLocked(interfacesToInterrupt, deviceId);
            } else {
                List<AccessibilityServiceConnection> services =
                        getUserStateLocked(resolvedUserId).mBoundServices;
                interfacesToInterrupt = new ArrayList<>(services.size());
                for (int i = 0; i < services.size(); i++) {
                    AccessibilityServiceConnection service = services.get(i);
                    IBinder a11yServiceBinder = service.mClientBinder;
                    IAccessibilityServiceClient a11yServiceInterface = service.mClient;
                    if ((a11yServiceBinder != null) && (a11yServiceInterface != null)) {
                        interfacesToInterrupt.add(a11yServiceInterface);
                    }
                }
            }
        }
        for (int i = 0, count = interfacesToInterrupt.size(); i < count; i++) {
            try {
                if (mTraceManager.isA11yTracingEnabledForTypes(
                        FLAGS_ACCESSIBILITY_SERVICE_CLIENT)) {
                    mTraceManager.logTrace(LOG_TAG + ".IAccessibilityServiceClient.onInterrupt",
                            FLAGS_ACCESSIBILITY_SERVICE_CLIENT);
                }
                interfacesToInterrupt.get(i).onInterrupt();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending interrupt request to "
                        + interfacesToInterrupt.get(i), re);
            }
        }
    }

    @Override
    @RequiresNoPermission
    public int addAccessibilityInteractionConnection(IWindow windowToken, IBinder leashToken,
            IAccessibilityInteractionConnection connection, String packageName,
            int userId) throws RemoteException {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".addAccessibilityInteractionConnection",
                    FLAGS_ACCESSIBILITY_MANAGER,
                    "windowToken=" + windowToken + "leashToken=" + leashToken + ";connection="
                            + connection + "; packageName=" + packageName + ";userId=" + userId);
        }

        return mA11yWindowManager.addAccessibilityInteractionConnection(
                windowToken, leashToken, connection, packageName, userId);
    }

    @Override
    @RequiresNoPermission
    public void removeAccessibilityInteractionConnection(IWindow window) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".removeAccessibilityInteractionConnection",
                    FLAGS_ACCESSIBILITY_MANAGER, "window=" + window);
        }
        mA11yWindowManager.removeAccessibilityInteractionConnection(window);
    }

    @Override
    @EnforcePermission(MODIFY_ACCESSIBILITY_DATA)
    public void setPictureInPictureActionReplacingConnection(
            IAccessibilityInteractionConnection connection) throws RemoteException {
        setPictureInPictureActionReplacingConnection_enforcePermission();
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".setPictureInPictureActionReplacingConnection",
                    FLAGS_ACCESSIBILITY_MANAGER, "connection=" + connection);
        }
        mA11yWindowManager.setPictureInPictureActionReplacingConnection(connection);
    }

    @Override
    @EnforcePermission(RETRIEVE_WINDOW_CONTENT)
    public void registerUiTestAutomationService(IBinder owner,
            IAccessibilityServiceClient serviceClient,
            AccessibilityServiceInfo accessibilityServiceInfo,
            int userId,
            int flags) {
        registerUiTestAutomationService_enforcePermission();
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".registerUiTestAutomationService",
                    FLAGS_ACCESSIBILITY_MANAGER,
                    "owner=" + owner + ";serviceClient=" + serviceClient
                    + ";accessibilityServiceInfo=" + accessibilityServiceInfo + ";flags=" + flags);
        }

        synchronized (mLock) {
            changeCurrentUserForTestAutomationIfNeededLocked(userId);
            mUiAutomationManager.registerUiTestAutomationServiceLocked(owner, serviceClient,
                    mContext, accessibilityServiceInfo, sIdCounter++, mMainHandler,
                    mSecurityPolicy, this, getTraceManager(), mWindowManagerService,
                    getSystemActionPerformer(), mA11yWindowManager, flags);
            onUserStateChangedLocked(getCurrentUserStateLocked());
        }
    }

    @Override
    @RequiresNoPermission
    public void unregisterUiTestAutomationService(IAccessibilityServiceClient serviceClient) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".unregisterUiTestAutomationService",
                    FLAGS_ACCESSIBILITY_MANAGER, "serviceClient=" + serviceClient);
        }
        synchronized (mLock) {
            mUiAutomationManager.unregisterUiTestAutomationServiceLocked(serviceClient);
            restoreCurrentUserAfterTestAutomationIfNeededLocked();
        }
    }

    // TODO(b/255426725): temporary workaround to support visible background users for UiAutomation
    @GuardedBy("mLock")
    private void changeCurrentUserForTestAutomationIfNeededLocked(@UserIdInt int userId) {
        if (mVisibleBgUserIds == null) {
            Slogf.d(LOG_TAG, "changeCurrentUserForTestAutomationIfNeededLocked(%d): ignoring "
                    + "because device doesn't support visible background users", userId);
            return;
        }
        if (!mVisibleBgUserIds.get(userId)) {
            Slogf.wtf(LOG_TAG, "changeCurrentUserForTestAutomationIfNeededLocked(): cannot change "
                    + "current user to %d as it's not visible (mVisibleUsers=%s)",
                    userId, mVisibleBgUserIds);
            return;
        }
        if (mCurrentUserId == userId) {
            Slogf.d(LOG_TAG, "changeCurrentUserForTestAutomationIfNeededLocked(): NOT changing "
                    + "current user for test automation purposes as it is already %d",
                    mCurrentUserId);
            return;
        }
        Slogf.i(LOG_TAG, "changeCurrentUserForTestAutomationIfNeededLocked(): changing current user"
                + " from %d to %d for test automation purposes", mCurrentUserId, userId);
        mRealCurrentUserId = mCurrentUserId;
        switchUser(userId);
    }

    // TODO(b/255426725): temporary workaround to support visible background users for UiAutomation
    @GuardedBy("mLock")
    private void restoreCurrentUserAfterTestAutomationIfNeededLocked() {
        if (mVisibleBgUserIds == null) {
            Slogf.d(LOG_TAG, "restoreCurrentUserForTestAutomationIfNeededLocked(): ignoring "
                    + "because device doesn't support visible background users");
            return;
        }
        if (mRealCurrentUserId == UserHandle.USER_CURRENT) {
            Slogf.d(LOG_TAG, "restoreCurrentUserForTestAutomationIfNeededLocked(): ignoring "
                    + "because mRealCurrentUserId is already USER_CURRENT");
            return;
        }
        Slogf.i(LOG_TAG, "restoreCurrentUserForTestAutomationIfNeededLocked(): restoring current "
                + "user to %d after using %d for test automation purposes",
                mRealCurrentUserId, mCurrentUserId);
        int currentUserId = mRealCurrentUserId;
        mRealCurrentUserId = UserHandle.USER_CURRENT;
        switchUser(currentUserId);
    }

    @Override
    @EnforcePermission(RETRIEVE_WINDOW_CONTENT)
    public IBinder getWindowToken(int windowId, int userId) {
        getWindowToken_enforcePermission();
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getWindowToken",
                    FLAGS_ACCESSIBILITY_MANAGER, "windowId=" + windowId + ";userId=" + userId);
        }

        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            final int resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            if (resolvedUserId != mCurrentUserId) {
                return null;
            }
            final AccessibilityWindowInfo accessibilityWindowInfo = mA11yWindowManager
                    .findA11yWindowInfoByIdLocked(windowId);
            if (accessibilityWindowInfo == null) {
                return null;
            }
            // We use AccessibilityWindowInfo#getId instead of windowId. When the windowId comes
            // from an embedded hierarchy, the system can't find correct window token because
            // embedded hierarchy doesn't have windowInfo. Calling
            // AccessibilityWindowManager#findA11yWindowInfoByIdLocked can look for its parent's
            // windowInfo, so it is safer to use AccessibilityWindowInfo#getId
            // to get window token to find real window.
            return mA11yWindowManager.getWindowTokenForUserAndWindowIdLocked(userId,
                    accessibilityWindowInfo.getId());
        }
    }

    /**
     * Invoked remotely over AIDL by SysUi when the accessibility button within the system's
     * navigation area has been clicked, or a gesture shortcut input has been performed.
     *
     * @param displayId The logical display id.
     * @param targetName The flattened {@link ComponentName} string or the class name of a system
     *        class implementing a supported accessibility feature, or {@code null} if there's no
     *        specified target.
     */
    @Override
    @EnforcePermission(STATUS_BAR_SERVICE)
    public void notifyAccessibilityButtonClicked(int displayId, String targetName) {
        notifyAccessibilityButtonClicked_enforcePermission();
        int currentUserId;
        synchronized (mLock) {
            currentUserId = mCurrentUserId;
        }
        enforceCurrentUserIfVisibleBackgroundEnabled(currentUserId);
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".notifyAccessibilityButtonClicked",
                    FLAGS_ACCESSIBILITY_MANAGER,
                    "displayId=" + displayId + ";targetName=" + targetName);
        }

        if (targetName == null) {
            synchronized (mLock) {
                final AccessibilityUserState userState = getCurrentUserStateLocked();
                targetName = userState.getTargetAssignedToAccessibilityButton();
            }
        }
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::performAccessibilityShortcutInternal, this,
                displayId, getShortcutTypeForGenericShortcutCalls(currentUserId), targetName));
    }

    /**
     * AIDL-exposed method to show the dialog
     * for choosing the target for the gesture or button shortcuts.
     * The shortcut is determined by the current navigation mode.
     *
     * @param displayId The id for the display to show the dialog on.
     */
    @Override
    @EnforcePermission(STATUS_BAR_SERVICE)
    public void notifyAccessibilityButtonLongClicked(int displayId) {
        notifyAccessibilityButtonLongClicked_enforcePermission();
        int userId;
        synchronized (mLock) {
            userId = mCurrentUserId;
        }
        showAccessibilityTargetsSelection(displayId,
                getShortcutTypeForGenericShortcutCalls(userId), userId);
    }

    /**
     * Invoked remotely over AIDL by SysUi when the visibility of the accessibility
     * button within the system's navigation area has changed.
     *
     * @param shown {@code true} if the accessibility button is shown to the
     *                  user, {@code false} otherwise
     */
    @Override
    @EnforcePermission(STATUS_BAR_SERVICE)
    public void notifyAccessibilityButtonVisibilityChanged(boolean shown) {
        notifyAccessibilityButtonVisibilityChanged_enforcePermission();
        int currentUserId;
        synchronized (mLock) {
            currentUserId = mCurrentUserId;
        }
        enforceCurrentUserIfVisibleBackgroundEnabled(currentUserId);
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".notifyAccessibilityButtonVisibilityChanged",
                    FLAGS_ACCESSIBILITY_MANAGER, "shown=" + shown);
        }

        synchronized (mLock) {
            notifyAccessibilityButtonVisibilityChangedLocked(shown);
        }
    }

    @Override
    @EnforcePermission(allOf = { STATUS_BAR_SERVICE, MANAGE_ACCESSIBILITY })
    public void notifyQuickSettingsTilesChanged(
            @UserIdInt int userId, @NonNull List<ComponentName> tileComponentNames) {
        notifyQuickSettingsTilesChanged_enforcePermission();
        if (!android.view.accessibility.Flags.a11yQsShortcut()) {
            return;
        }
        if (DEBUG) {
            Slog.d(LOG_TAG, TextUtils.formatSimple(
                    "notifyQuickSettingsTilesChanged userId: %d, tileComponentNames: %s",
                    userId, tileComponentNames));
        }
        final Set<ComponentName> newTileComponentNames = new ArraySet<>(tileComponentNames);
        final Set<ComponentName> addedTiles;
        final Set<ComponentName> removedTiles;
        final Map<ComponentName, AccessibilityServiceInfo> tileServiceToA11yServiceInfo;
        final Map<ComponentName, ComponentName> a11yFeatureToTileService;

        // update in-memory copy of QS_TILES in AccessibilityManager
        synchronized (mLock) {
            AccessibilityUserState userState = getUserStateLocked(userId);

            tileServiceToA11yServiceInfo = userState.getTileServiceToA11yServiceInfoMapLocked();
            a11yFeatureToTileService = userState.getA11yFeatureToTileService();

            ArraySet<ComponentName> currentTiles = userState.getA11yQsTilesInQsPanel();
            // Find newly added tiles
            addedTiles = newTileComponentNames
                    .stream()
                    .filter(tileComponentName -> !currentTiles.contains(tileComponentName))
                    .collect(Collectors.toSet());
            // Find newly removed tiles
            removedTiles = currentTiles
                    .stream()
                    .filter(tileComponentName -> !newTileComponentNames.contains(tileComponentName))
                    .collect(Collectors.toSet());

            if (addedTiles.isEmpty() && removedTiles.isEmpty()) {
                return;
            }

            userState.updateA11yTilesInQsPanelLocked(newTileComponentNames);
        }

        List<String> a11yFeaturesToEnable = new ArrayList<>();
        List<String> a11yFeaturesToRemove = new ArrayList<>();
        // Find the framework features to configure the qs shortcut on/off
        for (Map.Entry<ComponentName, ComponentName> frameworkFeatureWithTile :
                ShortcutConstants.A11Y_FEATURE_TO_FRAMEWORK_TILE.entrySet()) {
            String a11yFeature = frameworkFeatureWithTile.getKey().flattenToString();
            ComponentName tile = frameworkFeatureWithTile.getValue();
            if (addedTiles.contains(tile)) {
                a11yFeaturesToEnable.add(a11yFeature);
            } else if (removedTiles.contains(tile)) {
                a11yFeaturesToRemove.add(a11yFeature);
            }
        }
        // Find the accessibility service/activity to configure the qs shortcut on/off
        for (Map.Entry<ComponentName, ComponentName> a11yFeatureWithTileService :
                a11yFeatureToTileService.entrySet()) {
            String a11yFeature = a11yFeatureWithTileService.getKey().flattenToString();
            ComponentName tileService = a11yFeatureWithTileService.getValue();
            if (addedTiles.contains(tileService)) {
                AccessibilityServiceInfo serviceInfo = tileServiceToA11yServiceInfo.getOrDefault(
                        tileService, null);
                if (serviceInfo != null && isAccessibilityServiceWarningRequired(serviceInfo)) {
                    // TODO(b/314850435): show full device control warning if needed after
                    // SysUI QS Panel can update live
                    // The user attempts to add QS shortcut in QS Panel, but we don't actually
                    // turn on the shortcut due to lack of full device control permission
                    logMetricForQsShortcutConfiguration(/* enable= */ true, /* numOfFeatures= */ 1);
                    continue;
                }
                a11yFeaturesToEnable.add(a11yFeature);
            } else if (removedTiles.contains(tileService)) {
                a11yFeaturesToRemove.add(a11yFeature);
            }
        }
        // Turn on/off a11y qs shortcut for the a11y features based on the change in QS Panel
        if (!a11yFeaturesToEnable.isEmpty()) {
            enableShortcutForTargets(/* enable= */ true, QUICK_SETTINGS,
                    a11yFeaturesToEnable, userId);
        }

        if (!a11yFeaturesToRemove.isEmpty()) {
            enableShortcutForTargets(/* enable= */ false, QUICK_SETTINGS,
                    a11yFeaturesToRemove, userId);
        }
    }

    /**
     * Called when a gesture is detected on a display by the framework.
     *
     * @param gestureEvent the detail of the gesture.
     * @return true if the event is handled.
     */
    public boolean onGesture(AccessibilityGestureEvent gestureEvent) {
        synchronized (mLock) {
            boolean handled = notifyGestureLocked(gestureEvent, false);
            if (!handled) {
                handled = notifyGestureLocked(gestureEvent, true);
            }
            return handled;
        }
    }

    /** Send a motion event to the services. */
    public boolean sendMotionEventToListeningServices(MotionEvent event) {
        boolean result;
        event = MotionEvent.obtain(event);
        if (DEBUG) {
            Slog.d(LOG_TAG, "Sending event to service: " + event);
        }
        result = scheduleNotifyMotionEvent(event);
        return result;
    }

    /**
     * Notifies services that the touch state on a given display has changed.
     */
    public boolean onTouchStateChanged(int displayId, int state) {
            if (DEBUG) {
                Slog.d(LOG_TAG, "Notifying touch state:"
                        + TouchInteractionController.stateToString(state));
            }
        return scheduleNotifyTouchState(displayId, state);
    }

    /**
     * Called when the system action list is changed.
     */
    @Override
    public void onSystemActionsChanged() {
        synchronized (mLock) {
            AccessibilityUserState state = getCurrentUserStateLocked();
            notifySystemActionsChangedLocked(state);
        }
    }

    @Override
    // TODO(b/276459590): Remove when this is resolved at the virtual device/input level.
    public void moveNonProxyTopFocusedDisplayToTopIfNeeded() {
        mA11yWindowManager.moveNonProxyTopFocusedDisplayToTopIfNeeded();
    }

    @Override
    // TODO(b/276459590): Remove when this is resolved at the virtual device/input level.
    public int getLastNonProxyTopFocusedDisplayId() {
        return mA11yWindowManager.getLastNonProxyTopFocusedDisplayId();
    }

    @VisibleForTesting
    void notifySystemActionsChangedLocked(AccessibilityUserState userState) {
        for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            service.notifySystemActionsChangedLocked();
        }
    }

    @VisibleForTesting
    public boolean notifyKeyEvent(KeyEvent event, int policyFlags) {
        synchronized (mLock) {
            List<AccessibilityServiceConnection> boundServices =
                    getCurrentUserStateLocked().mBoundServices;
            if (boundServices.isEmpty()) {
                return false;
            }
            return getKeyEventDispatcher().notifyKeyEventLocked(event, policyFlags, boundServices);
        }
    }

    /**
     * Called by the MagnificationController when the state of display
     * magnification changes.
     *
     * <p>
     * It can notify window magnification change if the service supports controlling all the
     * magnification mode.
     * </p>
     *
     * @param displayId The logical display id
     * @param region The magnification region.
     *               If the config mode is
     *               {@link MagnificationConfig#MAGNIFICATION_MODE_FULLSCREEN},
     *               it is the region of the screen currently active for magnification.
     *               the returned region will be empty if the magnification is not active
     *               (e.g. scale is 1. And the magnification is active if magnification
     *               gestures are enabled or if a service is running that can control
     *               magnification.
     *               If the config mode is
     *               {@link MagnificationConfig#MAGNIFICATION_MODE_WINDOW},
     *               it is the region of screen projected on the magnification window.
     *               The region will be empty if magnification is not activated.
     * @param config The magnification config. That has magnification mode, the new scale and the
     *              new screen-relative center position
     */
    public void notifyMagnificationChanged(int displayId, @NonNull Region region,
            @NonNull MagnificationConfig config) {
        synchronized (mLock) {
            notifyClearAccessibilityCacheLocked();
            notifyMagnificationChangedLocked(displayId, region, config);
        }
    }

    /**
     * Called by AccessibilityInputFilter when it creates or destroys the motionEventInjector.
     * Not using a getter because the AccessibilityInputFilter isn't thread-safe
     *
     * @param motionEventInjectors The array of motionEventInjectors. May be null.
     *
     */
    void setMotionEventInjectors(SparseArray<MotionEventInjector> motionEventInjectors) {
        synchronized (mLock) {
            mMotionEventInjectors = motionEventInjectors;
            // We may be waiting on this object being set
            mLock.notifyAll();
        }
    }

    @Override
    public @Nullable MotionEventInjector getMotionEventInjectorForDisplayLocked(int displayId) {
        final long endMillis = SystemClock.uptimeMillis() + WAIT_INPUT_FILTER_INSTALL_TIMEOUT_MS;
        MotionEventInjector motionEventInjector = null;
        while ((mMotionEventInjectors == null) && (SystemClock.uptimeMillis() < endMillis)) {
            try {
                mLock.wait(endMillis - SystemClock.uptimeMillis());
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }
        if (mMotionEventInjectors == null) {
            Slog.e(LOG_TAG, "MotionEventInjector installation timed out");
        } else {
            motionEventInjector = mMotionEventInjectors.get(displayId);
        }
        return motionEventInjector;
    }

    /**
     * Gets a point within the accessibility focused node where we can send down
     * and up events to perform a click.
     *
     * @param outPoint The click point to populate.
     * @return Whether accessibility a click point was found and set.
     */
    // TODO: (multi-display) Make sure this works for multiple displays.
    public boolean getAccessibilityFocusClickPointInScreen(Point outPoint) {
        return getInteractionBridge().getAccessibilityFocusClickPointInScreenNotLocked(outPoint);
    }

    /**
     * Perform an accessibility action on the view that currently has accessibility focus.
     * Has no effect if no item has accessibility focus, if the item with accessibility
     * focus does not expose the specified action, or if the action fails.
     *
     * @param action The action to perform.
     *
     * @return {@code true} if the action was performed. {@code false} if it was not.
     */
    public boolean performActionOnAccessibilityFocusedItem(
            AccessibilityNodeInfo.AccessibilityAction action) {
        return getInteractionBridge().performActionOnAccessibilityFocusedItemNotLocked(action);
    }

    /**
     * Returns true if accessibility focus is confined to the active window.
     */
    public boolean accessibilityFocusOnlyInActiveWindow() {
        synchronized (mLock) {
            return mA11yWindowManager.accessibilityFocusOnlyInActiveWindowLocked();
        }
    }

    /**
     * Gets the bounds of a window.
     *
     * @param outBounds The output to which to write the bounds.
     */
    boolean getWindowBounds(int windowId, Rect outBounds) {
        IBinder token;
        synchronized (mLock) {
            token = getWindowToken(windowId, mCurrentUserId);
        }
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_WINDOW_MANAGER_INTERNAL)) {
            mTraceManager.logTrace("WindowManagerInternal.getWindowFrame",
                    FLAGS_WINDOW_MANAGER_INTERNAL, "token=" + token + ";outBounds=" + outBounds);
        }
        mWindowManagerService.getWindowFrame(token, outBounds);
        if (!outBounds.isEmpty()) {
            return true;
        }
        return false;
    }

    public int getActiveWindowId() {
        return mA11yWindowManager.getActiveWindowId(mCurrentUserId);
    }

    public void onTouchInteractionStart() {
        mA11yWindowManager.onTouchInteractionStart();
    }

    public void onTouchInteractionEnd() {
        mA11yWindowManager.onTouchInteractionEnd();
    }

    @VisibleForTesting
    void switchUser(int userId) {
        mMagnificationController.updateUserIdIfNeeded(userId);
        List<AccessibilityServiceInfo> parsedAccessibilityServiceInfos = null;
        List<AccessibilityShortcutInfo> parsedAccessibilityShortcutInfos = null;
        parsedAccessibilityServiceInfos = parseAccessibilityServiceInfos(userId);
        parsedAccessibilityShortcutInfos = parseAccessibilityShortcutInfos(userId);
        synchronized (mLock) {
            if (mCurrentUserId == userId && mInitialized) {
                return;
            }

            // Disconnect from services for the old user.
            AccessibilityUserState oldUserState = getCurrentUserStateLocked();
            oldUserState.onSwitchToAnotherUserLocked();

            // Disable the local managers for the old user.
            if (oldUserState.mUserClients.getRegisteredCallbackCount() > 0) {
                mMainHandler.sendMessage(obtainMessage(
                        AccessibilityManagerService::sendStateToClients,
                        this, 0, oldUserState.mUserId));
            }

            // Announce user changes only if more than one exist.
            final boolean announceNewUser = mUserManager.getUsers().size() > 1;

            // The user changed.
            mCurrentUserId = userId;
            AccessibilityUserState userState = getCurrentUserStateLocked();

            readConfigurationForUserStateLocked(userState,
                    parsedAccessibilityServiceInfos, parsedAccessibilityShortcutInfos);
            mSecurityPolicy.onSwitchUserLocked(mCurrentUserId, userState.mEnabledServices);
            // Even if reading did not yield change, we have to update
            // the state since the context in which the current user
            // state was used has changed since it was inactive.
            onUserStateChangedLocked(userState);
            // It's better to have this migration in SettingsProvider. Unfortunately,
            // SettingsProvider migrated database in a very early stage which A11yManagerService
            // haven't finished or started the initialization. We cannot get enough information from
            // A11yManagerService to execute these migrations in SettingsProvider. Passing 0 for
            // restoreFromSdkInt to have this migration check execute every time, because we did not
            // find out a way to detect the device finished the OTA and switch the user.
            migrateAccessibilityButtonSettingsIfNecessaryLocked(userState, null,
                    /* restoreFromSdkInt = */0);
            // Package components are disabled per user, so secondary users also need their migrated
            // Accessibility Menu component disabled.
            disableAccessibilityMenuToMigrateIfNeeded();

            // As an initialization step, update the shortcuts for the current user.
            updateShortcutsForCurrentNavigationMode();

            if (announceNewUser) {
                // Schedule announcement of the current user if needed.
                mMainHandler.sendMessageDelayed(
                        obtainMessage(AccessibilityManagerService::announceNewUserIfNeeded, this),
                        WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS);
            }

            for (IUserInitializationCompleteCallback callback
                    : mUserInitializationCompleteCallbacks) {
                try {
                    callback.onUserInitializationComplete(mCurrentUserId);
                } catch (RemoteException re) {
                    Log.e("AccessibilityManagerService",
                            "Error while dispatching userInitializationComplete callback: ",
                            re);
                }
            }
        }
    }

    private void announceNewUserIfNeeded() {
        synchronized (mLock) {
            AccessibilityUserState userState = getCurrentUserStateLocked();
            if (userState.isHandlingAccessibilityEventsLocked()) {
                String message = mContext.getString(R.string.user_switched,
                        mUserManager.getUserInfo(mCurrentUserId).name);
                AccessibilityEvent event = AccessibilityEvent.obtain(
                        AccessibilityEvent.TYPE_ANNOUNCEMENT);
                event.getText().add(message);
                sendAccessibilityEventLocked(event, mCurrentUserId);
            }
        }
    }

    private void unlockUser(int userId) {
        synchronized (mLock) {
            int parentUserId = mSecurityPolicy.resolveProfileParentLocked(userId);
            if (parentUserId == mCurrentUserId) {
                AccessibilityUserState userState = getUserStateLocked(mCurrentUserId);
                onUserStateChangedLocked(userState);
            }
        }
    }

    private void removeUser(int userId) {
        synchronized (mLock) {
            mUserStates.remove(userId);
        }
        getMagnificationController().onUserRemoved(userId);
    }

    // Called only during settings restore; currently supports only the owner user
    // TODO: http://b/22388012
    void restoreEnabledAccessibilityServicesLocked(String oldSetting, String newSetting,
            int restoreFromSdkInt) {
        readComponentNamesFromStringLocked(oldSetting, mTempComponentNameSet, false);
        readComponentNamesFromStringLocked(newSetting, mTempComponentNameSet, true);

        AccessibilityUserState userState = getUserStateLocked(UserHandle.USER_SYSTEM);
        userState.mEnabledServices.clear();
        userState.mEnabledServices.addAll(mTempComponentNameSet);
        persistComponentNamesToSettingLocked(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userState.mEnabledServices,
                UserHandle.USER_SYSTEM);
        onUserStateChangedLocked(userState);
        migrateAccessibilityButtonSettingsIfNecessaryLocked(userState, null, restoreFromSdkInt);
    }

    /**
     * User could configure accessibility shortcut during the SUW before restoring user data.
     * Merges the current value and the new value to make sure we don't lost the setting the user's
     * preferences of accessibility shortcut updated in SUW are not lost.
     * Called only during settings restore; currently supports only the owner user.
     * <P>
     * Throws an exception if used with {@code TRIPLETAP} or {@code TWOFINGER_DOUBLETAP}.
     * </P>
     * TODO: http://b/22388012
     */
    private void restoreShortcutTargets(String newValue,
            @UserShortcutType int shortcutType) {
        assertNoTapShortcut(shortcutType);
        if (shortcutType == QUICK_SETTINGS && !android.view.accessibility.Flags.a11yQsShortcut()) {
            return;
        }
        if (shortcutType == HARDWARE
                && !android.view.accessibility.Flags.restoreA11yShortcutTargetService()) {
            return;
        }

        synchronized (mLock) {
            final AccessibilityUserState userState = getUserStateLocked(UserHandle.USER_SYSTEM);
            final Set<String> mergedTargets = (shortcutType == HARDWARE)
                    ? new ArraySet<>(ShortcutUtils.getShortcutTargetsFromSettings(
                            mContext, shortcutType, userState.mUserId))
                    : userState.getShortcutTargetsLocked(shortcutType);

            if (Flags.clearDefaultFromA11yShortcutTargetServiceRestore()
                    && shortcutType == HARDWARE) {
                final String defaultService =
                        mContext.getString(R.string.config_defaultAccessibilityService);
                final ComponentName defaultServiceComponent = TextUtils.isEmpty(defaultService)
                        ? null : ComponentName.unflattenFromString(defaultService);
                boolean shouldClearDefaultService = defaultServiceComponent != null
                        && !stringSetContainsComponentName(mergedTargets, defaultServiceComponent);
                readColonDelimitedStringToSet(newValue, str -> str, mergedTargets,
                        /* doMerge = */ true);

                if (shouldClearDefaultService && stringSetContainsComponentName(
                        mergedTargets, defaultServiceComponent)) {
                    Slog.i(LOG_TAG, "Removing default service " + defaultService
                            + " from restore of "
                            + Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE);
                    mergedTargets.removeIf(str ->
                            defaultServiceComponent.equals(ComponentName.unflattenFromString(str)));
                }
                if (mergedTargets.isEmpty()) {
                    return;
                }
            } else {
                readColonDelimitedStringToSet(newValue, str -> str, mergedTargets,
                        /* doMerge = */ true);
            }

            userState.updateShortcutTargetsLocked(mergedTargets, shortcutType);
            persistColonDelimitedSetToSettingLocked(ShortcutUtils.convertToKey(shortcutType),
                    UserHandle.USER_SYSTEM, mergedTargets, str -> str);
            scheduleNotifyClientsOfServicesStateChangeLocked(userState);
            onUserStateChangedLocked(userState);
        }
    }

    private String getRawShortcutSetting(int userId, @UserShortcutType int shortcutType) {
        return Settings.Secure.getStringForUser(mContext.getContentResolver(),
                ShortcutUtils.convertToKey(shortcutType), userId);
    }


    /**
     * Returns {@code true} if the set contains the provided non-null {@link ComponentName}.
     *
     * <p>This ignores values in the set that are not valid {@link ComponentName}s.
     */
    private boolean stringSetContainsComponentName(Set<String> set,
            @NonNull ComponentName componentName) {
        return componentName != null && set.stream()
                .map(ComponentName::unflattenFromString)
                .anyMatch(componentName::equals);
    }

    private int getClientStateLocked(AccessibilityUserState userState) {
        return userState.getClientStateLocked(
            mUiAutomationManager.canIntrospect(),
            mTraceManager.getTraceStateForAccessibilityManagerClientState());
    }

    private InteractionBridge getInteractionBridge() {
        synchronized (mLock) {
            if (mInteractionBridge == null) {
                mInteractionBridge = new InteractionBridge();
            }
            return mInteractionBridge;
        }
    }

    private boolean notifyGestureLocked(AccessibilityGestureEvent gestureEvent, boolean isDefault) {
        // TODO: Now we are giving the gestures to the last enabled
        //       service that can handle them which is the last one
        //       in our list since we write the last enabled as the
        //       last record in the enabled services setting. Ideally,
        //       the user should make the call which service handles
        //       gestures. However, only one service should handle
        //       gestures to avoid user frustration when different
        //       behavior is observed from different combinations of
        //       enabled accessibility services.
        AccessibilityUserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            AccessibilityServiceConnection service = state.mBoundServices.get(i);
            if (service.mRequestTouchExplorationMode && service.mIsDefault == isDefault) {
                service.notifyGesture(gestureEvent);
                return true;
            }
        }
        return false;
    }

    private boolean scheduleNotifyMotionEvent(MotionEvent event) {
        boolean result = false;
        int displayId = event.getDisplayId();
        synchronized (mLock) {
            AccessibilityUserState state = getCurrentUserStateLocked();
            for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
                AccessibilityServiceConnection service = state.mBoundServices.get(i);
                if (service.wantsGenericMotionEvent(event)
                        || (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                        && service.isServiceDetectsGesturesEnabled(displayId))) {
                    service.notifyMotionEvent(event);
                    result = true;
                }
            }
        }
        return result;
    }

    private boolean scheduleNotifyTouchState(int displayId, int touchState) {
        boolean result = false;
        synchronized (mLock) {
            AccessibilityUserState state = getCurrentUserStateLocked();
            for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
                AccessibilityServiceConnection service = state.mBoundServices.get(i);
                if (service.isServiceDetectsGesturesEnabled(displayId)) {
                    service.notifyTouchState(displayId, touchState);
                    result = true;
                }
            }
        }
        return result;
    }

    @Override
    public void notifyClearAccessibilityCacheLocked() {
        AccessibilityUserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            AccessibilityServiceConnection service = state.mBoundServices.get(i);
            service.notifyClearAccessibilityNodeInfoCache();
        }

        mProxyManager.clearCacheLocked();
    }

    @VisibleForTesting
    void updateShortcutsForCurrentNavigationMode() {
        synchronized (mLock) {
            AccessibilityUserState userState = getCurrentUserStateLocked();
            if (!isUserSetupCompleted(mContext)) {
                return;
            }
            final boolean isInGesturalNavigation = Settings.Secure.getIntForUser(
                    mContext.getContentResolver(), Settings.Secure.NAVIGATION_MODE,
                    -1, userState.mUserId) == NAV_BAR_MODE_GESTURAL;

            Set<String> gestureTargets = userState.getShortcutTargetsLocked(GESTURE);
            Set<String> softwareTargets = userState.getShortcutTargetsLocked(SOFTWARE);
            int buttonMode = ShortcutUtils.getButtonMode(mContext, userState.mUserId);

            if (android.provider.Flags.a11yStandaloneGestureEnabled()) {
                if (isInGesturalNavigation) {
                    if (buttonMode == ACCESSIBILITY_BUTTON_MODE_GESTURE) {
                        // GESTURE button mode indicates migrating from old version
                        // User was using gesture, so move all targets into gesture
                        gestureTargets.addAll(softwareTargets);
                        softwareTargets.clear();
                    }
                    buttonMode = ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
                } else {
                    // Only change the current button mode if there are gesture targets
                    // (indicating the user came from gesture mode or is migrating)
                    if (!gestureTargets.isEmpty()) {
                        buttonMode = softwareTargets.isEmpty()
                                ? ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR
                                : ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;

                        softwareTargets.addAll(gestureTargets);
                        gestureTargets.clear();
                    }
                }
            } else {
                if (!gestureTargets.isEmpty()) {
                    // Adjust button mode before clearing out gesture targets
                    if (!softwareTargets.isEmpty()) {
                        buttonMode = ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
                    } else if (isInGesturalNavigation) {
                        buttonMode = ACCESSIBILITY_BUTTON_MODE_GESTURE;
                    } else {
                        buttonMode = ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;
                    }
                    softwareTargets.addAll(gestureTargets);
                    gestureTargets.clear();
                } else if (buttonMode != ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU) {
                    buttonMode = isInGesturalNavigation
                            ? ACCESSIBILITY_BUTTON_MODE_GESTURE
                            : ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;
                }
            }

            updateShortcutTargetSets(userState, Set.of(
                    Pair.create(gestureTargets, GESTURE),
                    Pair.create(softwareTargets, SOFTWARE)
            ));
            ShortcutUtils.setButtonMode(mContext, buttonMode, userState.mUserId);
        }
    }

    private void notifyMagnificationChangedLocked(int displayId, @NonNull Region region,
            @NonNull MagnificationConfig config) {
        final AccessibilityUserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = state.mBoundServices.get(i);
            service.notifyMagnificationChangedLocked(displayId, region, config);
        }
    }

    private void notifyMagnificationShortcutTriggered(int displayId) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.notifyMagnificationShortcutTriggered(displayId);
            }
        }
    }

    private void showAccessibilityTargetsSelection(int displayId, int shortcutType,
            int userId) {
        final Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
        final String chooserClassName = (shortcutType == HARDWARE)
                ? AccessibilityShortcutChooserActivity.class.getName()
                : AccessibilityButtonChooserActivity.class.getName();
        intent.setClassName(CHOOSER_PACKAGE_NAME, chooserClassName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(EXTRA_TYPE_TO_CHOOSE, shortcutType);
        final Bundle bundle = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        mMainHandler.post(() ->
                mContext.startActivityAsUser(intent, bundle, UserHandle.of(userId)));
    }

    private void launchShortcutTargetActivity(int displayId, ComponentName name) {
        final Intent intent = new Intent();
        final Bundle bundle = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        intent.setComponent(name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivityAsUser(intent, bundle, UserHandle.of(mCurrentUserId));
        } catch (ActivityNotFoundException ignore) {
            // ignore the exception
        }
    }

    private void launchAccessibilitySubSettings(int displayId, ComponentName name) {
        final Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
        final Bundle bundle = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(Intent.EXTRA_COMPONENT_NAME, name.flattenToString());
        try {
            mContext.startActivityAsUser(intent, bundle, UserHandle.of(mCurrentUserId));
        } catch (ActivityNotFoundException ignore) {
            // ignore the exception
        }
    }

    private void launchHearingDevicesDialog() {
        final Intent intent = new Intent(ACTION_LAUNCH_HEARING_DEVICES_DIALOG);
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.setPackage(
                mContext.getString(com.android.internal.R.string.config_systemUi));
        mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
    }

    private void notifyAccessibilityButtonVisibilityChangedLocked(boolean available) {
        final AccessibilityUserState state = getCurrentUserStateLocked();
        mIsAccessibilityButtonShown = available;
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection clientConnection = state.mBoundServices.get(i);
            if (clientConnection.mRequestAccessibilityButton) {
                clientConnection.notifyAccessibilityButtonAvailabilityChangedLocked(
                        clientConnection.isAccessibilityButtonAvailableLocked(state));
            }
        }
    }

    /**
     * Finds packages that provide AccessibilityService interfaces, and parses
     * their metadata XML to build up {@link AccessibilityServiceInfo} objects.
     *
     * <p>
     * <strong>Note:</strong> XML parsing is a resource-heavy operation that may
     * stall, so this method should not be called while holding a lock.
     * </p>
     */
    private List<AccessibilityServiceInfo> parseAccessibilityServiceInfos(int userId) {
        List<AccessibilityServiceInfo> result = new ArrayList<>();

        int flags = PackageManager.GET_SERVICES
                | PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

        synchronized (mLock) {
            if (getUserStateLocked(userId).getBindInstantServiceAllowedLocked()) {
                flags |= PackageManager.MATCH_INSTANT;
            }
        }

        List<ResolveInfo> installedServices = mPackageManager.queryIntentServicesAsUser(
                new Intent(AccessibilityService.SERVICE_INTERFACE), flags, userId);

        for (int i = 0, count = installedServices.size(); i < count; i++) {
            ResolveInfo resolveInfo = installedServices.get(i);
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;

            if (!mSecurityPolicy.canRegisterService(serviceInfo)) {
                continue;
            }

            AccessibilityServiceInfo accessibilityServiceInfo;
            try {
                accessibilityServiceInfo = new AccessibilityServiceInfo(resolveInfo, mContext);
            } catch (XmlPullParserException | IOException xppe) {
                Slog.e(LOG_TAG, "Error while initializing AccessibilityServiceInfo", xppe);
                continue;
            }
            if (!accessibilityServiceInfo.isWithinParcelableSize()) {
                Slog.e(LOG_TAG, "Skipping service "
                        + accessibilityServiceInfo.getResolveInfo().getComponentInfo()
                        + " because service info size is larger than safe parcelable limits.");
                continue;
            }
            result.add(accessibilityServiceInfo);
        }
        return result;
    }

    private boolean readInstalledAccessibilityServiceLocked(AccessibilityUserState userState,
            @Nullable List<AccessibilityServiceInfo> parsedAccessibilityServiceInfos) {
        for (int i = 0, count = parsedAccessibilityServiceInfos.size(); i < count; i++) {
            AccessibilityServiceInfo accessibilityServiceInfo =
                    parsedAccessibilityServiceInfos.get(i);
            if (userState.mCrashedServices.contains(accessibilityServiceInfo.getComponentName())) {
                // Restore the crashed attribute.
                accessibilityServiceInfo.crashed = true;
            }
        }

        if (!parsedAccessibilityServiceInfos.equals(userState.mInstalledServices)) {
            userState.mInstalledServices.clear();
            userState.mInstalledServices.addAll(parsedAccessibilityServiceInfos);
            userState.updateTileServiceMapForAccessibilityServiceLocked();
            return true;
        }
        return false;
    }

    /**
     * Returns the {@link AccessibilityShortcutInfo}s of the installed
     * accessibility shortcut targets for the given user.
     *
     * <p>
     * <strong>Note:</strong> XML parsing is a resource-heavy operation that may
     * stall, so this method should not be called while holding a lock.
     * </p>
     */
    private List<AccessibilityShortcutInfo> parseAccessibilityShortcutInfos(int userId) {
        // TODO: b/297279151 - This should be implemented here, not by AccessibilityManager.
        return AccessibilityManager.getInstance(mContext)
                .getInstalledAccessibilityShortcutListAsUser(mContext, userId);
    }

    private boolean readInstalledAccessibilityShortcutLocked(AccessibilityUserState userState,
            List<AccessibilityShortcutInfo> parsedAccessibilityShortcutInfos) {
        if (!parsedAccessibilityShortcutInfos.equals(userState.mInstalledShortcuts)) {
            if (Flags.clearShortcutsWhenActivityUpdatesToService()) {
                List<String> componentNames = userState.mInstalledShortcuts.stream()
                        .filter(a11yActivity ->
                                !parsedAccessibilityShortcutInfos.contains(a11yActivity))
                        .map(a11yActivity -> a11yActivity.getComponentName().flattenToString())
                        .toList();
                if (!componentNames.isEmpty()) {
                    enableShortcutsForTargets(
                            /* enable= */ false, UserShortcutType.ALL,
                            componentNames, userState.mUserId);
                }
            }

            userState.mInstalledShortcuts.clear();
            userState.mInstalledShortcuts.addAll(parsedAccessibilityShortcutInfos);
            userState.updateTileServiceMapForAccessibilityActivityLocked();
            return true;
        }
        return false;
    }

    private boolean readEnabledAccessibilityServicesLocked(AccessibilityUserState userState) {
        mTempComponentNameSet.clear();
        readComponentNamesFromSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userState.mUserId, mTempComponentNameSet);
        if (!mTempComponentNameSet.equals(userState.mEnabledServices)) {
            userState.mEnabledServices.clear();
            userState.mEnabledServices.addAll(mTempComponentNameSet);
            mTempComponentNameSet.clear();
            return true;
        }
        mTempComponentNameSet.clear();
        return false;
    }

    private boolean readTouchExplorationGrantedAccessibilityServicesLocked(
            AccessibilityUserState userState) {
        mTempComponentNameSet.clear();
        readComponentNamesFromSettingLocked(
                Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                userState.mUserId, mTempComponentNameSet);
        if (!mTempComponentNameSet.equals(userState.mTouchExplorationGrantedServices)) {
            userState.mTouchExplorationGrantedServices.clear();
            userState.mTouchExplorationGrantedServices.addAll(mTempComponentNameSet);
            mTempComponentNameSet.clear();
            return true;
        }
        mTempComponentNameSet.clear();
        return false;
    }

    /**
     * Performs {@link AccessibilityService}s delayed notification. The delay is configurable
     * and denotes the period after the last event before notifying the service.
     *
     * @param event The event.
     * @param isDefault True to notify default listeners, not default services.
     */
    private void notifyAccessibilityServicesDelayedLocked(AccessibilityEvent event,
            boolean isDefault) {
        try {
            AccessibilityUserState state = getCurrentUserStateLocked();
            for (int i = 0, count = state.mBoundServices.size(); i < count; i++) {
                AccessibilityServiceConnection service = state.mBoundServices.get(i);

                if (service.mIsDefault == isDefault) {
                    service.notifyAccessibilityEvent(event);
                }
            }
        } catch (IndexOutOfBoundsException oobe) {
            // An out of bounds exception can happen if services are going away
            // as the for loop is running. If that happens, just bail because
            // there are no more services to notify.
        }
    }

    private void updateRelevantEventsLocked(AccessibilityUserState userState) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_SERVICE_CLIENT)) {
            mTraceManager.logTrace(LOG_TAG + ".updateRelevantEventsLocked",
                    FLAGS_ACCESSIBILITY_SERVICE_CLIENT, "userState=" + userState);
        }
        mMainHandler.post(() -> {
            broadcastToClients(userState, ignoreRemoteException(client -> {
                int relevantEventTypes;
                synchronized (mLock) {
                    relevantEventTypes = computeRelevantEventTypesLocked(userState, client);
                    if (!mProxyManager.isProxyedDeviceId(client.mDeviceId)) {
                        if (client.mLastSentRelevantEventTypes != relevantEventTypes) {
                            client.mLastSentRelevantEventTypes = relevantEventTypes;
                            client.mCallback.setRelevantEventTypes(relevantEventTypes);
                        }
                    }
                }
            }));
        });
    }

    private int computeRelevantEventTypesLocked(AccessibilityUserState userState, Client client) {
        int relevantEventTypes = 0;

        int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            relevantEventTypes |= isClientInPackageAllowlist(service.getServiceInfo(), client)
                    ? service.getRelevantEventTypes()
                    : 0;
        }

        relevantEventTypes |= isClientInPackageAllowlist(
                mUiAutomationManager.getServiceInfo(), client)
                ? mUiAutomationManager.getRelevantEventTypes()
                : 0;
        return relevantEventTypes;
    }

    private void updateMagnificationModeChangeSettingsLocked(AccessibilityUserState userState,
            int displayId) {
        if (userState.mUserId != mCurrentUserId) {
            return;
        }
        // New mode is invalid, so ignore and restore it.
        if (fallBackMagnificationModeSettingsLocked(userState, displayId)) {
            return;
        }
        mMagnificationController.transitionMagnificationModeLocked(
                displayId, userState.getMagnificationModeLocked(displayId),
                this::onMagnificationTransitionEndedLocked);
    }

    /**
     * Called when the magnification mode transition is completed. If the given display is default
     * display, we also need to fall back the mode in user settings.
     */
    void onMagnificationTransitionEndedLocked(int displayId, boolean success) {
        final AccessibilityUserState userState = getCurrentUserStateLocked();
        final int previousMode = userState.getMagnificationModeLocked(displayId)
                ^ Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
        if (!success && previousMode != 0) {
            userState.setMagnificationModeLocked(displayId, previousMode);
            if (displayId == Display.DEFAULT_DISPLAY) {
                persistMagnificationModeSettingsLocked(previousMode);
            }
        } else {
            mMainHandler.sendMessage(obtainMessage(
                    AccessibilityManagerService::notifyRefreshMagnificationModeToInputFilter,
                    this, displayId));
        }
    }

    private void notifyRefreshMagnificationModeToInputFilter(int displayId) {
        synchronized (mLock) {
            if (!mHasInputFilter) {
                return;
            }
            final ArrayList<Display> displays = getValidDisplayList();
            for (int i = 0; i < displays.size(); i++) {
                final Display display = displays.get(i);
                if (display != null && display.getDisplayId() == displayId) {
                    mInputFilter.refreshMagnificationMode(display);
                    return;
                }
            }
        }
    }

    static boolean isClientInPackageAllowlist(
            @Nullable AccessibilityServiceInfo serviceInfo, Client client) {
        if (serviceInfo == null) return false;

        String[] clientPackages = client.mPackageNames;
        boolean result = ArrayUtils.isEmpty(serviceInfo.packageNames);
        if (!result && clientPackages != null) {
            for (String packageName : clientPackages) {
                if (ArrayUtils.contains(serviceInfo.packageNames, packageName)) {
                    result = true;
                    break;
                }
            }
        }
        if (!result) {
            if (DEBUG) {
                Slog.d(LOG_TAG, "Dropping events: "
                        + Arrays.toString(clientPackages) + " -> "
                        + serviceInfo.getComponentName().flattenToShortString()
                        + " due to not being in package allowlist "
                        + Arrays.toString(serviceInfo.packageNames));
            }
        }

        return result;
    }

    private void broadcastToClients(
            AccessibilityUserState userState, Consumer<Client> clientAction) {
        mGlobalClients.broadcastForEachCookie(clientAction);
        userState.mUserClients.broadcastForEachCookie(clientAction);
    }

    /**
     * Populates a set with the {@link ComponentName}s stored in a colon
     * separated value setting for a given user.
     *
     * @param settingName The setting to parse.
     * @param userId The user id.
     * @param outComponentNames The output component names.
     */
    @VisibleForTesting
    void readComponentNamesFromSettingLocked(String settingName, int userId,
            Set<ComponentName> outComponentNames) {
        readColonDelimitedSettingToSet(settingName, userId,
                str -> ComponentName.unflattenFromString(str), outComponentNames);
    }

    /**
     * Populates a set with the {@link ComponentName}s contained in a colon-delimited string.
     *
     * @param names The colon-delimited string to parse.
     * @param outComponentNames The set of component names to be populated based on
     *    the contents of the <code>names</code> string.
     * @param doMerge If true, the parsed component names will be merged into the output
     *    set, rather than replacing the set's existing contents entirely.
     */
    private void readComponentNamesFromStringLocked(String names,
            Set<ComponentName> outComponentNames,
            boolean doMerge) {
        readColonDelimitedStringToSet(names, str -> ComponentName.unflattenFromString(str),
                outComponentNames, doMerge);
    }

    @Override
    public void persistComponentNamesToSettingLocked(String settingName,
            Set<ComponentName> componentNames, int userId) {
        persistColonDelimitedSetToSettingLocked(settingName, userId, componentNames,
                componentName -> componentName.flattenToShortString());
    }

    /**
     * Reads a colon delimited setting,
     * passes the values through a function,
     * then stores the values in a provided set.
     *
     * @param settingName Name of setting.
     * @param userId user id corresponding to setting.
     * @param toItem function mapping values to the output set.
     * @param outSet output set to write to.
     * @param <T> type of output set.
     */
    @VisibleForTesting
    <T> void readColonDelimitedSettingToSet(String settingName, int userId,
            Function<String, T> toItem, Set<T> outSet) {
        final String settingValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                settingName, userId);
        readColonDelimitedStringToSet(settingValue, toItem, outSet, false);
    }

    private <T> void readColonDelimitedStringToSet(String names, Function<String, T> toItem,
            Set<T> outSet, boolean doMerge) {
        if (!doMerge) {
            outSet.clear();
        }
        if (!TextUtils.isEmpty(names)) {
            final TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(names);
            while (splitter.hasNext()) {
                final String str = splitter.next();
                if (TextUtils.isEmpty(str)) {
                    continue;
                }
                final T item = toItem.apply(str);
                if (item != null) {
                    outSet.add(item);
                }
            }
        }
    }

    @VisibleForTesting
    <T> void persistColonDelimitedSetToSettingLocked(String settingName, int userId,
            Set<T> set, Function<T, String> toString) {
        persistColonDelimitedSetToSettingLocked(settingName, userId, set,
                toString, /* defaultEmptyString= */ null);
    }

    private <T> void persistColonDelimitedSetToSettingLocked(String settingName, int userId,
            Set<T> set, Function<T, String> toString, String defaultEmptyString) {
        final StringBuilder builder = new StringBuilder();
        for (T item : set) {
            final String str = (item != null ? toString.apply(item) : null);
            if (TextUtils.isEmpty(str)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(COMPONENT_NAME_SEPARATOR);
            }
            builder.append(str);
        }
        final String builderValue = builder.toString();
        final String settingValue = TextUtils.isEmpty(builderValue)
                ? defaultEmptyString : builderValue;
        if (android.view.accessibility.Flags.restoreA11yShortcutTargetService()) {
            final String currentValue = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(), settingName, userId);
            if (Objects.equals(settingValue, currentValue)) {
                // This logic exists to fix a bug where AccessibilityManagerService was writing
                // `null` to the ACCESSIBILITY_SHORTCUT_TARGET_SERVICE setting during early boot
                // during setup, due to a race condition in package scanning making A11yMS think
                // that the default service was not installed.
                //
                // Writing `null` was implicitly causing that Setting to have the default
                // `DEFAULT_OVERRIDEABLE_BY_RESTORE` property, which was preventing B&R for that
                // Setting altogether.
                //
                // The "quick fix" here is to not write `null` if the existing value is already
                // `null`. The ideal fix would be use the Settings.Secure#putStringForUser overload
                // that allows override-by-restore, but the full repercussions of using that here
                // have not yet been evaluated.
                // TODO: b/333457719 - Evaluate and fix AccessibilityManagerService's usage of
                //  "overridable by restore" when writing secure settings.
                return;
            }
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    settingName, settingValue, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void persistIntToSetting(int userId, String settingName, int settingValue) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(
                    mContext.getContentResolver(), settingName, settingValue, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void updateServicesLocked(AccessibilityUserState userState) {
        Map<ComponentName, AccessibilityServiceConnection> componentNameToServiceMap =
                userState.mComponentNameToServiceMap;
        boolean isUnlockingOrUnlocked = mUmi.isUserUnlockingOrUnlocked(userState.mUserId);

        // Store the list of installed services.
        mTempComponentNameSet.clear();
        for (int i = 0, count = userState.mInstalledServices.size(); i < count; i++) {
            AccessibilityServiceInfo installedService = userState.mInstalledServices.get(i);
            ComponentName componentName = ComponentName.unflattenFromString(
                    installedService.getId());
            mTempComponentNameSet.add(componentName);

            AccessibilityServiceConnection service = componentNameToServiceMap.get(componentName);

            // Ignore non-encryption-aware services until user is unlocked
            if (!isUnlockingOrUnlocked && !installedService.isDirectBootAware()) {
                Slog.d(LOG_TAG, "Ignoring non-encryption-aware service " + componentName);
                continue;
            }

            // Skip the component since it may be in process or crashed.
            if (userState.getBindingServicesLocked().contains(componentName)
                    || userState.getCrashedServicesLocked().contains(componentName)) {
                continue;
            }
            if (userState.mEnabledServices.contains(componentName)
                    && !mUiAutomationManager.suppressingAccessibilityServicesLocked()) {
                // Skip the enabling service disallowed by device admin policy.
                if (!isAccessibilityTargetAllowed(componentName.getPackageName(),
                        installedService.getResolveInfo().serviceInfo.applicationInfo.uid,
                        userState.mUserId)) {
                    Slog.d(LOG_TAG, "Skipping enabling service disallowed by device admin policy: "
                            + componentName);
                    disableAccessibilityServiceLocked(componentName, userState.mUserId);
                    continue;
                }
                if (service == null) {
                    service = new AccessibilityServiceConnection(userState, mContext, componentName,
                            installedService, sIdCounter++, mMainHandler, mLock, mSecurityPolicy,
                            this, getTraceManager(), mWindowManagerService,
                            getSystemActionPerformer(), mA11yWindowManager,
                            mActivityTaskManagerService);
                } else if (userState.mBoundServices.contains(service)) {
                    continue;
                }
                service.bindLocked();
            } else {
                if (service != null) {
                    service.unbindLocked();
                    removeShortcutTargetForUnboundServiceLocked(userState, service);
                }
            }
        }

        final int count = userState.mBoundServices.size();
        mTempIntArray.clear();
        for (int i = 0; i < count; i++) {
            final ResolveInfo resolveInfo =
                    userState.mBoundServices.get(i).mAccessibilityServiceInfo.getResolveInfo();
            if (resolveInfo != null) {
                mTempIntArray.add(resolveInfo.serviceInfo.applicationInfo.uid);
            }
        }
        // Calling out with lock held, but to lower-level services
        final AudioManagerInternal audioManager =
                LocalServices.getService(AudioManagerInternal.class);
        if (audioManager != null) {
            audioManager.setAccessibilityServiceUids(mTempIntArray);
        }
        mActivityTaskManagerService.setAccessibilityServiceUids(mTempIntArray);

        // If any services have been removed, remove them from the enabled list and the touch
        // exploration granted list.
        boolean anyServiceRemoved =
                userState.mEnabledServices.removeIf((comp) -> !mTempComponentNameSet.contains(comp))
                        || userState.mTouchExplorationGrantedServices.removeIf(
                                (comp) -> !mTempComponentNameSet.contains(comp));
        if (anyServiceRemoved) {
            // Update the enabled services setting.
            persistComponentNamesToSettingLocked(
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    userState.mEnabledServices,
                    userState.mUserId);
            // Update the touch exploration granted services setting.
            persistComponentNamesToSettingLocked(
                    Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                    userState.mTouchExplorationGrantedServices,
                    userState.mUserId);
        }
        updateAccessibilityEnabledSettingLocked(userState);
    }

    void scheduleUpdateClientsIfNeededLocked(AccessibilityUserState userState) {
        scheduleUpdateClientsIfNeededLocked(userState, false);
    }

    void scheduleUpdateClientsIfNeededLocked(AccessibilityUserState userState,
            boolean forceUpdate) {
        final int clientState = getClientStateLocked(userState);
        if (((userState.getLastSentClientStateLocked() != clientState || forceUpdate))
                && (mGlobalClients.getRegisteredCallbackCount() > 0
                || userState.mUserClients.getRegisteredCallbackCount() > 0)) {
            userState.setLastSentClientStateLocked(clientState);
            mMainHandler.sendMessage(obtainMessage(
                    AccessibilityManagerService::sendStateToAllClients,
                    this, clientState,
                    userState.mUserId));
        }
    }

    private void sendStateToAllClients(int clientState, int userId) {
        sendStateToClients(clientState, mGlobalClients);
        sendStateToClients(clientState, userId);
    }

    private void sendStateToClients(int clientState, int userId) {
        sendStateToClients(clientState, getUserState(userId).mUserClients);
    }

    private void sendStateToClients(int clientState,
            RemoteCallbackList<IAccessibilityManagerClient> clients) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER_CLIENT)) {
            mTraceManager.logTrace(LOG_TAG + ".sendStateToClients",
                    FLAGS_ACCESSIBILITY_MANAGER_CLIENT, "clientState=" + clientState);
        }
        clients.broadcastForEachCookie(ignoreRemoteException(
                client -> {
                    Client managerClient = ((Client) client);
                    if (!mProxyManager.isProxyedDeviceId(managerClient.mDeviceId)) {
                        managerClient.mCallback.setState(clientState);
                    }
                }));
    }

    private void scheduleNotifyClientsOfServicesStateChangeLocked(
            AccessibilityUserState userState) {
        updateRecommendedUiTimeoutLocked(userState);
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::sendServicesStateChanged,
                this, userState.mUserClients, getRecommendedTimeoutMillisLocked(userState)));
    }

    private void sendServicesStateChanged(
            RemoteCallbackList<IAccessibilityManagerClient> userClients, long uiTimeout) {
        notifyClientsOfServicesStateChange(mGlobalClients, uiTimeout);
        notifyClientsOfServicesStateChange(userClients, uiTimeout);
    }

    private void notifyClientsOfServicesStateChange(
            RemoteCallbackList<IAccessibilityManagerClient> clients, long uiTimeout) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER_CLIENT)) {
            mTraceManager.logTrace(LOG_TAG + ".notifyClientsOfServicesStateChange",
                    FLAGS_ACCESSIBILITY_MANAGER_CLIENT, "uiTimeout=" + uiTimeout);
        }

        clients.broadcastForEachCookie(ignoreRemoteException(
                client -> {
                    Client managerClient = ((Client) client);
                    if (!mProxyManager.isProxyedDeviceId(managerClient.mDeviceId)) {
                        managerClient.mCallback.notifyServicesStateChanged(uiTimeout);
                    }
                }));
    }

    private void scheduleUpdateInputFilter(AccessibilityUserState userState) {
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::updateInputFilter, this, userState));
    }

    private void scheduleUpdateFingerprintGestureHandling(AccessibilityUserState userState) {
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::updateFingerprintGestureHandling,
                this, userState));
    }

    private void updateInputFilter(AccessibilityUserState userState) {
        if (mUiAutomationManager.suppressingAccessibilityServicesLocked()) return;

        boolean setInputFilter = false;
        AccessibilityInputFilter inputFilter = null;
        synchronized (mLock) {
            int flags = 0;
            if (userState.isMagnificationSingleFingerTripleTapEnabledLocked()) {
                flags |= AccessibilityInputFilter
                        .FLAG_FEATURE_MAGNIFICATION_SINGLE_FINGER_TRIPLE_TAP;
            }
            if (Flags.enableMagnificationMultipleFingerMultipleTapGesture()) {
                if (userState.isMagnificationTwoFingerTripleTapEnabledLocked()) {
                    flags |= AccessibilityInputFilter
                            .FLAG_FEATURE_MAGNIFICATION_TWO_FINGER_TRIPLE_TAP;
                }
            }
            if (userState.isShortcutMagnificationEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_TRIGGERED_SCREEN_MAGNIFIER;
            }
            if (userHasMagnificationServicesLocked(userState)) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_CONTROL_SCREEN_MAGNIFIER;
            }
            // Touch exploration without accessibility makes no sense.
            if (userState.isHandlingAccessibilityEventsLocked()
                    && userState.isTouchExplorationEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_TOUCH_EXPLORATION;
                if (userState.isServiceHandlesDoubleTapEnabledLocked()) {
                    flags |= AccessibilityInputFilter.FLAG_SERVICE_HANDLES_DOUBLE_TAP;
                }
                if (userState.isMultiFingerGesturesEnabledLocked()) {
                    flags |= AccessibilityInputFilter.FLAG_REQUEST_MULTI_FINGER_GESTURES;
                }
                if (userState.isTwoFingerPassthroughEnabledLocked()) {
                    flags |= AccessibilityInputFilter.FLAG_REQUEST_2_FINGER_PASSTHROUGH;
                }
            }
            if (userState.isFilterKeyEventsEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_FILTER_KEY_EVENTS;
            }
            if (userState.isSendMotionEventsEnabled()) {
                flags |= AccessibilityInputFilter.FLAG_SEND_MOTION_EVENTS;
            }
            if (userState.isAutoclickEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_AUTOCLICK;
            }
            if (userState.isPerformGesturesEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_INJECT_MOTION_EVENTS;
            }
            int combinedGenericMotionEventSources = 0;
            int combinedMotionEventObservedSources = 0;
            for (AccessibilityServiceConnection connection : userState.mBoundServices) {
                combinedGenericMotionEventSources |= connection.mGenericMotionEventSources;
                combinedMotionEventObservedSources |= connection.mObservedMotionEventSources;
            }
            if (combinedGenericMotionEventSources != 0) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_INTERCEPT_GENERIC_MOTION_EVENTS;
            }
            if (userState.isMouseKeysEnabled()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_MOUSE_KEYS;
            }
            if (flags != 0) {
                if (!mHasInputFilter) {
                    mHasInputFilter = true;
                    if (mInputFilter == null) {
                        mInputFilter =
                                new AccessibilityInputFilter(
                                        mContext, AccessibilityManagerService.this);
                    }
                    inputFilter = mInputFilter;
                    setInputFilter = true;
                }
                mInputFilter.setUserAndEnabledFeatures(userState.mUserId, flags);
                mInputFilter.setCombinedGenericMotionEventSources(
                        combinedGenericMotionEventSources);
                mInputFilter.setCombinedMotionEventObservedSources(
                        combinedMotionEventObservedSources);
            } else {
                if (mHasInputFilter) {
                    mHasInputFilter = false;
                    mInputFilter.setUserAndEnabledFeatures(userState.mUserId, 0);
                    mInputFilter.resetServiceDetectsGestures();
                    if (userState.isTouchExplorationEnabledLocked()) {
                        //  Service gesture detection is turned on and off on a per-display
                        // basis.
                        final ArrayList<Display> displays = getValidDisplayList();
                        for (Display display : displays) {
                            int displayId = display.getDisplayId();
                            boolean mode = userState.isServiceDetectsGesturesEnabled(displayId);
                            mInputFilter.setServiceDetectsGesturesEnabled(displayId, mode);
                        }
                    }
                    inputFilter = null;
                    setInputFilter = true;
                }
            }
        }
        if (setInputFilter) {
            if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_WINDOW_MANAGER_INTERNAL
                    | FLAGS_INPUT_FILTER)) {
                mTraceManager.logTrace("WindowManagerInternal.setInputFilter",
                        FLAGS_WINDOW_MANAGER_INTERNAL | FLAGS_INPUT_FILTER,
                        "inputFilter=" + inputFilter);
            }
            mWindowManagerService.setInputFilter(inputFilter);
            mProxyManager.setAccessibilityInputFilter(inputFilter);
        }
    }

    private void showEnableTouchExplorationDialog(final AccessibilityServiceConnection service) {
        synchronized (mLock) {
            String label = service.getServiceInfo().getResolveInfo()
                    .loadLabel(mContext.getPackageManager()).toString();

            final AccessibilityUserState userState = getCurrentUserStateLocked();
            if (userState.isTouchExplorationEnabledLocked()) {
                return;
            }
            if (mEnableTouchExplorationDialog != null
                    && mEnableTouchExplorationDialog.isShowing()) {
                return;
            }
            mEnableTouchExplorationDialog = new AlertDialog.Builder(mContext)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setPositiveButton(android.R.string.ok, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // The user allowed the service to toggle touch exploration.
                        userState.mTouchExplorationGrantedServices.add(service.mComponentName);
                        persistComponentNamesToSettingLocked(
                                Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                                userState.mTouchExplorationGrantedServices, userState.mUserId);
                        // Enable touch exploration.
                        userState.setTouchExplorationEnabledLocked(true);
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                    Settings.Secure.TOUCH_EXPLORATION_ENABLED, 1,
                                    userState.mUserId);
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                        onUserStateChangedLocked(userState);
                    }
                })
                .setNegativeButton(android.R.string.cancel, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setTitle(R.string.enable_explore_by_touch_warning_title)
                .setMessage(mContext.getString(
                        R.string.enable_explore_by_touch_warning_message, label))
                .create();
             mEnableTouchExplorationDialog.getWindow().setType(
                     WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
             mEnableTouchExplorationDialog.getWindow().getAttributes().privateFlags
                     |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
             mEnableTouchExplorationDialog.setCanceledOnTouchOutside(true);
             mEnableTouchExplorationDialog.show();
        }
    }

    private void onUserVisibilityChanged(@UserIdInt int userId, boolean visible) {
        if (DEBUG) {
            Slogf.d(LOG_TAG, "onUserVisibilityChanged(): %d => %b", userId, visible);
        }
        synchronized (mLock) {
            if (visible) {
                mVisibleBgUserIds.put(userId, visible);
            } else {
                mVisibleBgUserIds.delete(userId);
            }
        }
    }

    /**
     * Called when any property of the user state has changed.
     *
     * @param userState the new user state
     */
    private void onUserStateChangedLocked(AccessibilityUserState userState) {
        onUserStateChangedLocked(userState, false);
    }

    /**
     * Called when any property of the user state has changed.
     *
     * @param userState the new user state
     * @param forceUpdate whether to force an update of the app Clients.
     */
    private void onUserStateChangedLocked(AccessibilityUserState userState, boolean forceUpdate) {
        if (DEBUG) {
            Slog.v(LOG_TAG, "onUserStateChangedLocked for user " + userState.mUserId + " with "
                    + "forceUpdate: " + forceUpdate);
        }
        // TODO: Remove this hack
        mInitialized = true;
        updateLegacyCapabilitiesLocked(userState);
        updateServicesLocked(userState);
        updateWindowsForAccessibilityCallbackLocked(userState);
        updateFilterKeyEventsLocked(userState);
        updateTouchExplorationLocked(userState);
        updatePerformGesturesLocked(userState);
        updateMagnificationLocked(userState);
        scheduleUpdateFingerprintGestureHandling(userState);
        scheduleUpdateInputFilter(userState);
        updateRelevantEventsLocked(userState);
        scheduleUpdateClientsIfNeededLocked(userState, forceUpdate);
        updateAccessibilityShortcutTargetsLocked(userState, HARDWARE);
        updateAccessibilityShortcutTargetsLocked(userState, SOFTWARE);
        updateAccessibilityShortcutTargetsLocked(userState, GESTURE);
        updateAccessibilityShortcutTargetsLocked(userState, QUICK_SETTINGS);
        // Update the capabilities before the mode because we will check the current mode is
        // invalid or not..
        updateMagnificationCapabilitiesSettingsChangeLocked(userState);
        updateMagnificationModeChangeSettingsForAllDisplaysLocked(userState);
        updateFocusAppearanceDataLocked(userState);
    }

    private void updateMagnificationModeChangeSettingsForAllDisplaysLocked(
            AccessibilityUserState userState) {
        final ArrayList<Display> displays = getValidDisplayList();
        for (int i = 0; i < displays.size(); i++) {
            final int displayId = displays.get(i).getDisplayId();
            updateMagnificationModeChangeSettingsLocked(userState, displayId);
        }
    }

    @GuardedBy("mLock")
    private void updateWindowsForAccessibilityCallbackLocked(AccessibilityUserState userState) {
        // We observe windows for accessibility only if there is at least
        // one bound service that can retrieve window content that specified
        // it is interested in accessing such windows. For services that are
        // binding we do an update pass after each bind event, so we run this
        // code and register the callback if needed.

        boolean observingWindows = mUiAutomationManager.canRetrieveInteractiveWindowsLocked()
                || mProxyManager.canRetrieveInteractiveWindowsLocked();
        List<AccessibilityServiceConnection> boundServices = userState.mBoundServices;
        final int boundServiceCount = boundServices.size();
        for (int i = 0; !observingWindows && (i < boundServiceCount); i++) {
            AccessibilityServiceConnection boundService = boundServices.get(i);
            if (boundService.canRetrieveInteractiveWindowsLocked()) {
                userState.setAccessibilityFocusOnlyInActiveWindow(false);
                observingWindows = true;
            }
        }
        userState.setAccessibilityFocusOnlyInActiveWindow(true);

        // Gets all valid displays and start tracking windows of each display if there is at least
        // one bound service that can retrieve window content.
        final ArrayList<Display> displays = getValidDisplayList();
        for (int i = 0; i < displays.size(); i++) {
            final Display display = displays.get(i);
            if (display != null) {
                // When supporting visible background users, only track windows on the display
                // assigned to the current user. The proxy displays are registered only to the
                // current user.
                if (UserManager.isVisibleBackgroundUsersEnabled()
                        && !mProxyManager.isProxyedDisplay(display.getDisplayId())
                        && !mUmi.isUserVisible(mCurrentUserId, display.getDisplayId())) {
                    continue;
                }
                if (observingWindows) {
                    mA11yWindowManager.startTrackingWindows(display.getDisplayId(),
                            mProxyManager.isProxyedDisplay(display.getDisplayId()));
                } else {
                    mA11yWindowManager.stopTrackingWindows(display.getDisplayId());
                }
            }
        }
    }

    private void updateLegacyCapabilitiesLocked(AccessibilityUserState userState) {
        // Up to JB-MR1 we had a allowlist with services that can enable touch
        // exploration. When a service is first started we show a dialog to the
        // use to get a permission to allowlist the service.
        final int installedServiceCount = userState.mInstalledServices.size();
        for (int i = 0; i < installedServiceCount; i++) {
            AccessibilityServiceInfo serviceInfo = userState.mInstalledServices.get(i);
            ResolveInfo resolveInfo = serviceInfo.getResolveInfo();
            if ((serviceInfo.getCapabilities()
                        & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION) == 0
                    && resolveInfo.serviceInfo.applicationInfo.targetSdkVersion
                        <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                ComponentName componentName = new ComponentName(
                        resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
                if (userState.mTouchExplorationGrantedServices.contains(componentName)) {
                    serviceInfo.setCapabilities(serviceInfo.getCapabilities()
                            | AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION);
                }
            }
        }
    }

    private void updatePerformGesturesLocked(AccessibilityUserState userState) {
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if ((service.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0) {
                userState.setPerformGesturesEnabledLocked(true);
                return;
            }
        }
        userState.setPerformGesturesEnabledLocked(false);
    }

    private void updateFilterKeyEventsLocked(AccessibilityUserState userState) {
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (service.mRequestFilterKeyEvents
                    && (service.getCapabilities()
                            & AccessibilityServiceInfo
                            .CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0) {
                userState.setFilterKeyEventsEnabledLocked(true);
                return;
            }
        }
        userState.setFilterKeyEventsEnabledLocked(false);
    }

    private boolean readConfigurationForUserStateLocked(
            AccessibilityUserState userState,
            List<AccessibilityServiceInfo> parsedAccessibilityServiceInfos,
            List<AccessibilityShortcutInfo> parsedAccessibilityShortcutInfos) {
        boolean somethingChanged = readInstalledAccessibilityServiceLocked(
                userState, parsedAccessibilityServiceInfos);
        somethingChanged |= readInstalledAccessibilityShortcutLocked(
                userState, parsedAccessibilityShortcutInfos);
        somethingChanged |= readEnabledAccessibilityServicesLocked(userState);
        somethingChanged |= readTouchExplorationGrantedAccessibilityServicesLocked(userState);
        somethingChanged |= readTouchExplorationEnabledSettingLocked(userState);
        somethingChanged |= readHighTextContrastEnabledSettingLocked(userState);
        somethingChanged |= readAudioDescriptionEnabledSettingLocked(userState);
        somethingChanged |= readMagnificationEnabledSettingsLocked(userState);
        somethingChanged |= readAutoclickEnabledSettingLocked(userState);
        somethingChanged |= readAccessibilityShortcutTargetsLocked(userState, HARDWARE);
        somethingChanged |= readAccessibilityShortcutTargetsLocked(userState, QUICK_SETTINGS);
        somethingChanged |= readAccessibilityShortcutTargetsLocked(userState, SOFTWARE);
        somethingChanged |= readAccessibilityShortcutTargetsLocked(userState, GESTURE);
        somethingChanged |= readAccessibilityButtonTargetComponentLocked(userState);
        somethingChanged |= readUserRecommendedUiTimeoutSettingsLocked(userState);
        somethingChanged |= readMagnificationModeForDefaultDisplayLocked(userState);
        somethingChanged |= readMagnificationCapabilitiesLocked(userState);
        somethingChanged |= readMagnificationFollowTypingLocked(userState);
        somethingChanged |= readAlwaysOnMagnificationLocked(userState);
        somethingChanged |= readMouseKeysEnabledLocked(userState);
        return somethingChanged;
    }

    private void updateAccessibilityEnabledSettingLocked(AccessibilityUserState userState) {
        final boolean isA11yEnabled = mUiAutomationManager.canIntrospect()
                || userState.isHandlingAccessibilityEventsLocked();
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    (isA11yEnabled) ? 1 : 0,
                    userState.mUserId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean readTouchExplorationEnabledSettingLocked(AccessibilityUserState userState) {
        final boolean touchExplorationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0, userState.mUserId) == 1;
        if (touchExplorationEnabled != userState.isTouchExplorationEnabledLocked()) {
            userState.setTouchExplorationEnabledLocked(touchExplorationEnabled);
            return true;
        }
        return false;
    }

    private boolean readMagnificationEnabledSettingsLocked(AccessibilityUserState userState) {
        final boolean magnificationSingleFingerTripleTapEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                0, userState.mUserId) == 1;
        if ((magnificationSingleFingerTripleTapEnabled
                != userState.isMagnificationSingleFingerTripleTapEnabledLocked())) {
            userState.setMagnificationSingleFingerTripleTapEnabledLocked(
                    magnificationSingleFingerTripleTapEnabled);
            return true;
        }
        return false;
    }

    private boolean readMagnificationTwoFingerTripleTapSettingsLocked(
            AccessibilityUserState userState) {
        final boolean magnificationTwoFingerTripleTapEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_TWO_FINGER_TRIPLE_TAP_ENABLED,
                0, userState.mUserId) == 1;
        if ((magnificationTwoFingerTripleTapEnabled
                != userState.isMagnificationTwoFingerTripleTapEnabledLocked())) {
            userState.setMagnificationTwoFingerTripleTapEnabledLocked(
                    magnificationTwoFingerTripleTapEnabled);
            return true;
        }
        return false;
    }

    private boolean readAutoclickEnabledSettingLocked(AccessibilityUserState userState) {
        final boolean autoclickEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_ENABLED,
                0, userState.mUserId) == 1;
        if (autoclickEnabled != userState.isAutoclickEnabledLocked()) {
            userState.setAutoclickEnabledLocked(autoclickEnabled);
            return true;
        }
        return false;
    }

    private boolean readHighTextContrastEnabledSettingLocked(AccessibilityUserState userState) {
        final boolean highTextContrastEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED, 0,
                userState.mUserId) == 1;
        if (highTextContrastEnabled != userState.isTextHighContrastEnabledLocked()) {
            userState.setTextHighContrastEnabledLocked(highTextContrastEnabled);
            return true;
        }
        return false;
    }

    private boolean readAudioDescriptionEnabledSettingLocked(AccessibilityUserState userState) {
        final boolean audioDescriptionByDefaultEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_AUDIO_DESCRIPTION_BY_DEFAULT, 0,
                userState.mUserId) == 1;
        if (audioDescriptionByDefaultEnabled
                    != userState.isAudioDescriptionByDefaultEnabledLocked()) {
            userState.setAudioDescriptionByDefaultEnabledLocked(audioDescriptionByDefaultEnabled);
            return true;
        }
        return false;
    }

    private void updateTouchExplorationLocked(AccessibilityUserState userState) {
        boolean touchExplorationEnabled = mUiAutomationManager.isTouchExplorationEnabledLocked();
        boolean serviceHandlesDoubleTapEnabled = false;
        boolean requestMultiFingerGestures = false;
        boolean requestTwoFingerPassthrough = false;
        boolean sendMotionEvents = false;
        final int serviceCount = userState.mBoundServices.size();
        for (int i = 0; i < serviceCount; i++) {
            AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (canRequestAndRequestsTouchExplorationLocked(service, userState)) {
                touchExplorationEnabled = true;
                serviceHandlesDoubleTapEnabled = service.isServiceHandlesDoubleTapEnabled();
                requestMultiFingerGestures = service.isMultiFingerGesturesEnabled();
                requestTwoFingerPassthrough = service.isTwoFingerPassthroughEnabled();
                sendMotionEvents = service.isSendMotionEventsEnabled();
                break;
            }
        }
        if (touchExplorationEnabled != userState.isTouchExplorationEnabledLocked()) {
            userState.setTouchExplorationEnabledLocked(touchExplorationEnabled);
            final long identity = Binder.clearCallingIdentity();
            try {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.TOUCH_EXPLORATION_ENABLED, touchExplorationEnabled ? 1 : 0,
                        userState.mUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        // Service gesture detection is turned on and off on a per-display
        // basis.
        userState.resetServiceDetectsGestures();
        final ArrayList<Display> displays = getValidDisplayList();
        for (AccessibilityServiceConnection service: userState.mBoundServices) {
            for (Display display : displays) {
                int displayId = display.getDisplayId();
                if (service.isServiceDetectsGesturesEnabled(displayId)) {
                    userState.setServiceDetectsGesturesEnabled(displayId, true);
                }
            }
        }
        userState.setServiceHandlesDoubleTapLocked(serviceHandlesDoubleTapEnabled);
        userState.setMultiFingerGesturesLocked(requestMultiFingerGestures);
        userState.setTwoFingerPassthroughLocked(requestTwoFingerPassthrough);
        userState.setSendMotionEventsEnabled(sendMotionEvents);
    }

    /**
     * Throws an exception for {@code TRIPLETAP} or {@code TWOFINGER_DOUBLETAP} types.
     */
    private boolean readAccessibilityShortcutTargetsLocked(AccessibilityUserState userState,
            @UserShortcutType int shortcutType) {
        assertNoTapShortcut(shortcutType);
        final String settingValue = getRawShortcutSetting(userState.mUserId, shortcutType);
        final Set<String> targetsFromSetting = new ArraySet<>();
        // If dealing with an empty hardware shortcut, fall back to the default value.
        if (shortcutType == HARDWARE && settingValue == null) {
            final String defaultService = mContext.getString(
                    R.string.config_defaultAccessibilityService);
            if (!TextUtils.isEmpty(defaultService)) {
                // Convert to component name to reformat the target if it has a relative path.
                ComponentName name = ComponentName.unflattenFromString(defaultService);
                if (name != null) {
                    targetsFromSetting.add(name.flattenToString());
                }
            }
        } else {
            readColonDelimitedStringToSet(settingValue, str -> str, targetsFromSetting, false);
        }

        if (userState.updateShortcutTargetsLocked(targetsFromSetting, shortcutType)) {
            scheduleNotifyClientsOfServicesStateChangeLocked(userState);
            return true;
        }
        return false;
    }

    private boolean readAccessibilityButtonTargetComponentLocked(AccessibilityUserState userState) {
        final String componentId = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT, userState.mUserId);
        if (TextUtils.isEmpty(componentId)) {
            if (userState.getTargetAssignedToAccessibilityButton() == null) {
                return false;
            }
            userState.setTargetAssignedToAccessibilityButton(null);
            return true;
        }
        if (componentId.equals(userState.getTargetAssignedToAccessibilityButton())) {
            return false;
        }
        userState.setTargetAssignedToAccessibilityButton(componentId);
        return true;
    }

    private boolean readUserRecommendedUiTimeoutSettingsLocked(AccessibilityUserState userState) {
        final int nonInteractiveUiTimeout = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_NON_INTERACTIVE_UI_TIMEOUT_MS, 0,
                userState.mUserId);
        final int interactiveUiTimeout = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_INTERACTIVE_UI_TIMEOUT_MS, 0,
                userState.mUserId);

        mProxyManager.updateTimeoutsIfNeeded(nonInteractiveUiTimeout, interactiveUiTimeout);
        if (nonInteractiveUiTimeout != userState.getUserNonInteractiveUiTimeoutLocked()
                || interactiveUiTimeout != userState.getUserInteractiveUiTimeoutLocked()) {
            userState.setUserNonInteractiveUiTimeoutLocked(nonInteractiveUiTimeout);
            userState.setUserInteractiveUiTimeoutLocked(interactiveUiTimeout);
            scheduleNotifyClientsOfServicesStateChangeLocked(userState);
            return true;
        }
        return false;
    }

    private boolean canRequestAndRequestsTouchExplorationLocked(
            AccessibilityServiceConnection service, AccessibilityUserState userState) {
        // Service not ready or cannot request the feature - well nothing to do.
        if (!service.canReceiveEventsLocked() || !service.mRequestTouchExplorationMode) {
            return false;
        }
        if (service.getServiceInfo().getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion
                <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // Up to JB-MR1 we had a allowlist with services that can enable touch
            // exploration. When a service is first started we show a dialog to the
            // use to get a permission to allowlist the service.
            if (userState.mTouchExplorationGrantedServices.contains(service.mComponentName)) {
                return true;
            } else if (mEnableTouchExplorationDialog == null
                    || !mEnableTouchExplorationDialog.isShowing()) {
                mMainHandler.sendMessage(obtainMessage(
                        AccessibilityManagerService::showEnableTouchExplorationDialog,
                        this, service));
            }
        } else {
            // Starting in JB-MR2 we request an accessibility service to declare
            // certain capabilities in its meta-data to allow it to enable the
            // corresponding features.
            if ((service.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION) != 0) {
                return true;
            }
        }
        return false;
    }

    private void updateMagnificationLocked(AccessibilityUserState userState) {
        if (userState.mUserId != mCurrentUserId) {
            return;
        }

        if (mUiAutomationManager.suppressingAccessibilityServicesLocked()
                && mMagnificationController.isFullScreenMagnificationControllerInitialized()) {
            getMagnificationController().getFullScreenMagnificationController().unregisterAll();
            return;
        }

        // Get all valid displays and register them if global magnification is enabled.
        // We would skip overlay display because it uses overlay window to simulate secondary
        // displays in one display. It's not a real display and there's no input events for it.
        final ArrayList<Display> displays = getValidDisplayList();
        if (userState.isMagnificationSingleFingerTripleTapEnabledLocked()
                || (Flags.enableMagnificationMultipleFingerMultipleTapGesture()
                && userState.isMagnificationTwoFingerTripleTapEnabledLocked())
                || userState.isShortcutMagnificationEnabledLocked()) {
            for (int i = 0; i < displays.size(); i++) {
                final Display display = displays.get(i);
                getMagnificationController().getFullScreenMagnificationController().register(
                        display.getDisplayId());
            }
            return;
        }

        // Register if display has listening magnification services.
        for (int i = 0; i < displays.size(); i++) {
            final Display display = displays.get(i);
            final int displayId = display.getDisplayId();
            if (userHasListeningMagnificationServicesLocked(userState, displayId)) {
                getMagnificationController().getFullScreenMagnificationController().register(
                        displayId);
            } else if (mMagnificationController.isFullScreenMagnificationControllerInitialized()) {
                getMagnificationController().getFullScreenMagnificationController().unregister(
                        displayId);
            }
        }
    }

    private void updateMagnificationConnectionIfNeeded(AccessibilityUserState userState) {
        if (!mMagnificationController.supportWindowMagnification()) {
            return;
        }

        // Magnification connection should not be requested for visible background users.
        // (b/332222893)
        if (mUmi.isVisibleBackgroundFullUser(userState.mUserId)) {
            return;
        }

        final boolean shortcutEnabled = (userState.isShortcutMagnificationEnabledLocked()
                || userState.isMagnificationSingleFingerTripleTapEnabledLocked()
                || (Flags.enableMagnificationMultipleFingerMultipleTapGesture()
                    && userState.isMagnificationTwoFingerTripleTapEnabledLocked()));

        final boolean createConnectionForCurrentCapability =
                com.android.window.flags.Flags.alwaysDrawMagnificationFullscreenBorder()
                        || (userState.getMagnificationCapabilitiesLocked()
                                != Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        final boolean connect = (shortcutEnabled && createConnectionForCurrentCapability)
                || userHasMagnificationServicesLocked(userState);

        getMagnificationConnectionManager().requestConnection(connect);
    }

    /**
     * Returns whether the specified user has any services that are capable of
     * controlling magnification.
     */
    private boolean userHasMagnificationServicesLocked(AccessibilityUserState userState) {
        final List<AccessibilityServiceConnection> services = userState.mBoundServices;
        for (int i = 0, count = services.size(); i < count; i++) {
            final AccessibilityServiceConnection service = services.get(i);
            if (mSecurityPolicy.canControlMagnification(service)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the specified user has any services that are capable of
     * controlling magnification and are actively listening for magnification updates.
     */
    private boolean userHasListeningMagnificationServicesLocked(AccessibilityUserState userState,
            int displayId) {
        final List<AccessibilityServiceConnection> services = userState.mBoundServices;
        for (int i = 0, count = services.size(); i < count; i++) {
            final AccessibilityServiceConnection service = services.get(i);
            if (mSecurityPolicy.canControlMagnification(service)
                    && service.isMagnificationCallbackEnabled(displayId)) {
                return true;
            }
        }
        return false;
    }

    private void updateFingerprintGestureHandling(AccessibilityUserState userState) {
        final List<AccessibilityServiceConnection> services;
        synchronized (mLock) {
            services = userState.mBoundServices;
            if ((mFingerprintGestureDispatcher == null)
                    &&  mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
                // Only create the controller when a service wants to use the feature
                int numServices = services.size();
                for (int i = 0; i < numServices; i++) {
                    if (services.get(i).isCapturingFingerprintGestures()) {
                        IFingerprintService service = null;
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            service = IFingerprintService.Stub.asInterface(
                                    ServiceManager.getService(Context.FINGERPRINT_SERVICE));
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                        if (service != null) {
                            mFingerprintGestureDispatcher = new FingerprintGestureDispatcher(
                                    service, mContext.getResources(), mLock);
                            break;
                        }
                    }
                }
            }
        }
        if (mFingerprintGestureDispatcher != null) {
            mFingerprintGestureDispatcher.updateClientList(services);
        }
    }

    /**
     * Adds or removes shortcut targets based on other attributes in the UserState.
     * <P></P>
     * Any target is removed if it is no longer installed on device.
     * For software shortcuts,
     * the function also updates the availability of the a11y button for services that request it.
     * For quick settings shortcuts,
     * targets are added if their service is enabled and has a corresponding QS panel.
     */
    private void updateAccessibilityShortcutTargetsLocked(
            AccessibilityUserState userState, @UserShortcutType int shortcutType) {
        if (shortcutType == QUICK_SETTINGS && !android.view.accessibility.Flags.a11yQsShortcut()) {
            return;
        }
        if (shortcutType == SOFTWARE) {
            // Update accessibility button availability.
            for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
                if (service.mRequestAccessibilityButton) {
                    service.notifyAccessibilityButtonAvailabilityChangedLocked(
                            service.isAccessibilityButtonAvailableLocked(userState));
                }
            }
        }

        // Remove targets that are no longer installed on device.
        final Set<String> currentTargets =
                userState.getShortcutTargetsLocked(shortcutType);
        currentTargets.removeIf(
                name -> !userState.isShortcutTargetInstalledLocked(name));

        if (shortcutType == QUICK_SETTINGS) {
            // Add the target if the a11y service is enabled and the tile exist in QS panel
            Set<ComponentName> enabledServices = userState.getEnabledServicesLocked();
            Map<ComponentName, ComponentName> a11yFeatureToTileService =
                    userState.getA11yFeatureToTileService();
            Set<ComponentName> currentA11yTilesInQsPanel = userState.getA11yQsTilesInQsPanel();
            for (ComponentName enabledService : enabledServices) {
                ComponentName tileService =
                        a11yFeatureToTileService.getOrDefault(enabledService, null);
                if (tileService != null && currentA11yTilesInQsPanel.contains(tileService)) {
                    currentTargets.add(enabledService.flattenToString());
                }
            }
        }
        // Update targets in userState
        if (!userState.updateShortcutTargetsLocked(currentTargets, shortcutType)) {
            return;
        }
        // If there was a change to the targets in userState, save the new targets to Settings.
        persistColonDelimitedSetToSettingLocked(ShortcutUtils.convertToKey(shortcutType),
                userState.mUserId, currentTargets, str -> str);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
    }

    private void updateShortcutTargetSets(AccessibilityUserState userState,
            Set<Pair<Set<String>, Integer>> targetSets) {
        boolean somethingChanged = false;
        for (Pair<Set<String>, Integer> pair : targetSets) {
            Set<String> targets = pair.first;
            int type = pair.second;
            if (userState.updateShortcutTargetsLocked(targets, type)) {
                somethingChanged = true;
                persistColonDelimitedSetToSettingLocked(ShortcutUtils.convertToKey(type),
                        userState.mUserId, targets, str -> str);
            }
        }
        if (somethingChanged) {
            scheduleNotifyClientsOfServicesStateChangeLocked(userState);
        }
    }

    /**
     * 1) Check if the service assigned to accessibility button target sdk version > Q.
     *    If it isn't, remove it from the list and associated setting.
     *    (It happens when an accessibility service package is downgraded.)
     * 2) For a service targeting sdk version > Q and requesting a11y button, it should be in the
     *    enabled list if's assigned to a11y button.
     *    (It happens when an accessibility service package is same graded, and updated requesting
     *     a11y button flag)
     * 3) Check if an enabled service targeting sdk version > Q and requesting a11y button is
     *    assigned to a shortcut. If it isn't, assigns it to the accessibility button.
     *    (It happens when an enabled accessibility service package is upgraded.)
     *
     * @param packageName The package name to check, or {@code null} to check all services.
     * @param restoreFromSdkInt The target sdk version of the restored source device, or {@code 0}
     *                          if the caller is not related to the restore.
     */
    private void migrateAccessibilityButtonSettingsIfNecessaryLocked(
            AccessibilityUserState userState, @Nullable String packageName, int restoreFromSdkInt) {
        // No need to migrate settings if they are restored from a version after Q.
        if (restoreFromSdkInt > Build.VERSION_CODES.Q) {
            return;
        }
        final Set<String> buttonTargets =
                userState.getShortcutTargetsLocked(SOFTWARE);
        buttonTargets.removeIf(name -> {
            if (packageName != null && name != null && !name.contains(packageName)) {
                return false;
            }
            final ComponentName componentName = ComponentName.unflattenFromString(name);
            if (componentName == null) {
                return false;
            }
            final AccessibilityServiceInfo serviceInfo =
                    userState.getInstalledServiceInfoLocked(componentName);
            if (serviceInfo == null) {
                return false;
            }
            if (serviceInfo.getResolveInfo().serviceInfo.applicationInfo
                    .targetSdkVersion <= Build.VERSION_CODES.Q) {
                // A11y services targeting sdk version <= Q should not be in the list.
                Slog.v(LOG_TAG, "Legacy service " + componentName
                        + " should not in the button");
                return true;
            }
            final boolean requestA11yButton = (serviceInfo.flags
                    & FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
            if (requestA11yButton && !userState.mEnabledServices.contains(componentName)) {
                // An a11y service targeting sdk version > Q and request A11y button and is assigned
                // to a11y btn should be in the enabled list.
                Slog.v(LOG_TAG, "Service requesting a11y button and be assigned to the button"
                        + componentName + " should be enabled state");
                return true;
            }
            return false;
        });

        final Set<String> shortcutKeyTargets =
                userState.getShortcutTargetsLocked(HARDWARE);
        final Set<String> qsShortcutTargets =
                userState.getShortcutTargetsLocked(QUICK_SETTINGS);
        final Set<String> shortcutTargets = userState.getShortcutTargetsLocked(ALL);
        userState.mEnabledServices.forEach(componentName -> {
            if (packageName != null && componentName != null
                    && !packageName.equals(componentName.getPackageName())) {
                return;
            }
            final AccessibilityServiceInfo serviceInfo =
                    userState.getInstalledServiceInfoLocked(componentName);
            if (serviceInfo == null) {
                return;
            }
            final boolean requestA11yButton = (serviceInfo.flags
                    & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
            if (!(serviceInfo.getResolveInfo().serviceInfo.applicationInfo
                    .targetSdkVersion > Build.VERSION_CODES.Q && requestA11yButton)) {
                return;
            }
            final String serviceName = componentName.flattenToString();
            if (TextUtils.isEmpty(serviceName)) {
                return;
            }
            if (android.provider.Flags.a11yStandaloneGestureEnabled()) {
                if (doesShortcutTargetsStringContain(shortcutTargets, serviceName)) {
                    return;
                }
            } else if (doesShortcutTargetsStringContain(buttonTargets, serviceName)
                    || doesShortcutTargetsStringContain(shortcutKeyTargets, serviceName)
                    || doesShortcutTargetsStringContain(qsShortcutTargets, serviceName)) {
                return;
            }
            // For enabled a11y services targeting sdk version > Q and requesting a11y button should
            // be assigned to a shortcut.
            Slog.v(LOG_TAG, "A enabled service requesting a11y button " + componentName
                    + " should be assign to the button or shortcut.");
            buttonTargets.add(serviceName);
        });
        if (!userState.updateShortcutTargetsLocked(buttonTargets, SOFTWARE)) {
            return;
        }

        // Update setting key with new value.
        persistColonDelimitedSetToSettingLocked(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                userState.mUserId, buttonTargets, str -> str);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
    }

    /**
     * Remove the shortcut target for the unbound service which is requesting accessibility button
     * and targeting sdk > Q from the accessibility button and shortcut.
     *
     * @param userState The accessibility user state.
     * @param service The unbound service.
     */
    private void removeShortcutTargetForUnboundServiceLocked(AccessibilityUserState userState,
            AccessibilityServiceConnection service) {
        if (!service.mRequestAccessibilityButton
                || service.getServiceInfo().getResolveInfo().serviceInfo.applicationInfo
                .targetSdkVersion <= Build.VERSION_CODES.Q) {
            return;
        }

        final List<Integer> shortcutTypes = new ArrayList<>(4);
        shortcutTypes.add(HARDWARE);
        shortcutTypes.add(SOFTWARE);
        if (android.view.accessibility.Flags.a11yQsShortcut()) {
            shortcutTypes.add(QUICK_SETTINGS);
        }
        if (android.provider.Flags.a11yStandaloneGestureEnabled()) {
            shortcutTypes.add(GESTURE);
        }

        final ComponentName serviceName = service.getComponentName();
        for (Integer shortcutType: shortcutTypes) {
            String shortcutSettingName = ShortcutUtils.convertToKey(shortcutType);
            if (userState.removeShortcutTargetLocked(shortcutType, serviceName)) {
                final Set<String> currentTargets = userState.getShortcutTargetsLocked(shortcutType);
                persistColonDelimitedSetToSettingLocked(
                        shortcutSettingName,
                        userState.mUserId, currentTargets, str -> str);

                if (shortcutType != QUICK_SETTINGS) {
                    continue;
                }

                ComponentName tileService =
                        userState.getA11yFeatureToTileService().getOrDefault(serviceName, null);

                final StatusBarManagerInternal statusBarManagerInternal =
                        LocalServices.getService(StatusBarManagerInternal.class);
                // In case it's not initialized yet
                if (statusBarManagerInternal == null || tileService == null) {
                    continue;
                }
                mMainHandler.sendMessage(obtainMessage(StatusBarManagerInternal::removeQsTile,
                        statusBarManagerInternal, tileService));
            }
        }
    }

    private void updateRecommendedUiTimeoutLocked(AccessibilityUserState userState) {
        int newNonInteractiveUiTimeout = userState.getUserNonInteractiveUiTimeoutLocked();
        int newInteractiveUiTimeout = userState.getUserInteractiveUiTimeoutLocked();
        // read from a11y services if user does not specify value
        if (newNonInteractiveUiTimeout == 0 || newInteractiveUiTimeout == 0) {
            int serviceNonInteractiveUiTimeout = 0;
            int serviceInteractiveUiTimeout = 0;
            final List<AccessibilityServiceConnection> services = userState.mBoundServices;
            for (int i = 0; i < services.size(); i++) {
                int timeout = services.get(i).getServiceInfo().getInteractiveUiTimeoutMillis();
                if (serviceInteractiveUiTimeout < timeout) {
                    serviceInteractiveUiTimeout = timeout;
                }
                timeout = services.get(i).getServiceInfo().getNonInteractiveUiTimeoutMillis();
                if (serviceNonInteractiveUiTimeout < timeout) {
                    serviceNonInteractiveUiTimeout = timeout;
                }
            }
            if (newNonInteractiveUiTimeout == 0) {
                newNonInteractiveUiTimeout = serviceNonInteractiveUiTimeout;
            }
            if (newInteractiveUiTimeout == 0) {
                newInteractiveUiTimeout = serviceInteractiveUiTimeout;
            }
        }
        userState.setNonInteractiveUiTimeoutLocked(newNonInteractiveUiTimeout);
        userState.setInteractiveUiTimeoutLocked(newInteractiveUiTimeout);
    }

    @Override
    public KeyEventDispatcher getKeyEventDispatcher() {
        if (mKeyEventDispatcher == null) {
            mKeyEventDispatcher = new KeyEventDispatcher(
                    mMainHandler, MainHandler.MSG_SEND_KEY_EVENT_TO_INPUT_FILTER, mLock,
                    mPowerManager);
        }
        return mKeyEventDispatcher;
    }

    @Override
    @SuppressWarnings("AndroidFrameworkPendingIntentMutability")
    public PendingIntent getPendingIntentActivity(Context context, int requestCode, Intent intent,
            int flags) {
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }

    /**
     * AIDL-exposed method to be called when the accessibility shortcut key is enabled. Requires
     * permission to write secure settings, since someone with that permission can enable
     * accessibility services themselves.
     *
     * @param targetName The flattened {@link ComponentName} string or the class name of a system
     *        class implementing a supported accessibility feature, or {@code null} if there's no
     *        specified target.
     */
    @EnforcePermission(MANAGE_ACCESSIBILITY)
    @Override
    public void performAccessibilityShortcut(
            int displayId, @UserShortcutType int shortcutType, String targetName) {
        performAccessibilityShortcut_enforcePermission();
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".performAccessibilityShortcut",
                    FLAGS_ACCESSIBILITY_MANAGER, "targetName=" + targetName);
        }

        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::performAccessibilityShortcutInternal, this,
                displayId, shortcutType, targetName));
    }

    /**
     * Perform the accessibility shortcut action.
     *
     * @param shortcutType The shortcut type.
     * @param displayId The display id the shortcut is being performed from.
     * @param targetName The flattened {@link ComponentName} string or the class name of a system
     *        class implementing a supported accessibility feature, or {@code null} if there's no
     *        specified target.
     */
    private void performAccessibilityShortcutInternal(int displayId,
            @UserShortcutType int shortcutType, @Nullable String targetName) {
        final List<String> shortcutTargets = getAccessibilityShortcutTargetsInternal(shortcutType);
        if (shortcutTargets.isEmpty()) {
            Slog.d(LOG_TAG, "No target to perform shortcut, shortcutType=" + shortcutType);
            return;
        }
        // In case the caller specified a target name
        if (targetName != null && !doesShortcutTargetsStringContain(shortcutTargets, targetName)) {
            Slog.v(LOG_TAG, "Perform shortcut failed, invalid target name:" + targetName);
            targetName = null;
        }
        if (targetName == null) {
            // In case there are many targets assigned to the given shortcut.
            if (shortcutTargets.size() > 1) {
                showAccessibilityTargetsSelection(
                        displayId, shortcutType, getCurrentUserState().mUserId);
                return;
            }
            targetName = shortcutTargets.get(0);
        }
        // In case user assigned magnification to the given shortcut.
        if (targetName.equals(MAGNIFICATION_CONTROLLER_NAME)) {
            final boolean enabled =
                    !getMagnificationController().getFullScreenMagnificationController()
                            .isActivated(displayId);
            logAccessibilityShortcutActivated(mContext, MAGNIFICATION_COMPONENT_NAME, shortcutType,
                    enabled);
            notifyMagnificationShortcutTriggered(displayId);
            return;
        }
        final ComponentName targetComponentName = ComponentName.unflattenFromString(targetName);
        if (targetComponentName == null) {
            Slog.d(LOG_TAG, "Perform shortcut failed, invalid target name:" + targetName);
            return;
        }

        // In case user assigned an accessibility framework feature to the given shortcut.
        if (performAccessibilityFrameworkFeature(displayId, targetComponentName, shortcutType)) {
            return;
        }
        // In case user assigned an accessibility shortcut target to the given shortcut.
        if (performAccessibilityShortcutTargetActivity(displayId, targetComponentName)) {
            logAccessibilityShortcutActivated(mContext, targetComponentName, shortcutType);
            return;
        }
        // in case user assigned an accessibility service to the given shortcut.
        if (performAccessibilityShortcutTargetService(
                displayId, shortcutType, targetComponentName)) {
            return;
        }
    }

    private boolean performAccessibilityFrameworkFeature(int displayId,
            ComponentName assignedTarget, @UserShortcutType int shortcutType) {
        final Map<ComponentName, FrameworkFeatureInfo> frameworkFeatureMap =
                AccessibilityShortcutController.getFrameworkShortcutFeaturesMap();
        if (!frameworkFeatureMap.containsKey(assignedTarget)) {
            return false;
        }
        final int userId;
        synchronized (mLock) {
            userId = mCurrentUserId;
        }
        final FrameworkFeatureInfo featureInfo = frameworkFeatureMap.get(assignedTarget);
        final SettingStringHelper setting = new SettingStringHelper(mContext.getContentResolver(),
                featureInfo.getSettingKey(), mCurrentUserId);

        if (featureInfo instanceof LaunchableFrameworkFeatureInfo) {
            logAccessibilityShortcutActivated(mContext, assignedTarget, shortcutType,
                    /* serviceEnabled= */ true);
            launchAccessibilityFrameworkFeature(displayId, assignedTarget);
            return true;
        }

        if (featureInfo instanceof ExtraDimFrameworkFeatureInfo) {
            boolean serviceEnabled =
                    ((ExtraDimFrameworkFeatureInfo) featureInfo)
                            .activateShortcut(mContext, userId);
            logAccessibilityShortcutActivated(mContext, assignedTarget, shortcutType,
                    serviceEnabled);
            return true;
        }

        // Assuming that the default state will be to have the feature off
        if (!TextUtils.equals(featureInfo.getSettingOnValue(), setting.read())) {
            logAccessibilityShortcutActivated(mContext, assignedTarget, shortcutType,
                    /* serviceEnabled= */ true);
            setting.write(featureInfo.getSettingOnValue());
        } else {
            logAccessibilityShortcutActivated(mContext, assignedTarget, shortcutType,
                    /* serviceEnabled= */ false);
            setting.write(featureInfo.getSettingOffValue());
        }
        return true;
    }

    private boolean performAccessibilityShortcutTargetActivity(int displayId,
            ComponentName assignedTarget) {
        synchronized (mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();
            for (int i = 0; i < userState.mInstalledShortcuts.size(); i++) {
                final AccessibilityShortcutInfo shortcutInfo = userState.mInstalledShortcuts.get(i);
                if (!shortcutInfo.getComponentName().equals(assignedTarget)) {
                    continue;
                }
                launchShortcutTargetActivity(displayId, assignedTarget);
                return true;
            }
        }
        return false;
    }

    /**
     * Perform accessibility service shortcut action.
     *
     * 1) For {@link UserShortcutType#SOFTWARE} type and services targeting sdk
     *    version <= Q: callbacks to accessibility service if service is bounded and requests
     *    accessibility button.
     * 2) For {@link UserShortcutType#HARDWARE} type and service targeting sdk
     *    version <= Q: turns on / off the accessibility service.
     * 3) For {@link UserShortcutType#HARDWARE} type and service targeting sdk
     *    version > Q and request accessibility button: turn on the accessibility service if it's
     *    not in the enabled state.
     *    (It'll happen when a service is disabled and assigned to shortcut then upgraded.)
     * 4) For services targeting sdk version > Q:
     *    a) Turns on / off the accessibility service, if service does not request accessibility
     *       button.
     *    b) Callbacks to accessibility service if service is bounded and requests accessibility
     *       button.
     */
    private boolean performAccessibilityShortcutTargetService(int displayId,
            @UserShortcutType int shortcutType, ComponentName assignedTarget) {
        synchronized (mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();
            final AccessibilityServiceInfo installedServiceInfo =
                    userState.getInstalledServiceInfoLocked(assignedTarget);
            if (installedServiceInfo == null) {
                Slog.d(LOG_TAG, "Perform shortcut failed, invalid component name:"
                        + assignedTarget);
                return false;
            }

            final AccessibilityServiceConnection serviceConnection =
                    userState.getServiceConnectionLocked(assignedTarget);
            final int targetSdk = installedServiceInfo.getResolveInfo()
                    .serviceInfo.applicationInfo.targetSdkVersion;
            final boolean requestA11yButton = (installedServiceInfo.flags
                    & FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
            // Turns on / off the accessibility service
            if ((targetSdk <= Build.VERSION_CODES.Q && shortcutType == HARDWARE)
                    || (targetSdk > Build.VERSION_CODES.Q && !requestA11yButton)) {
                if (serviceConnection == null) {
                    logAccessibilityShortcutActivated(mContext, assignedTarget, shortcutType,
                            /* serviceEnabled= */ true);
                    enableAccessibilityServiceLocked(assignedTarget, mCurrentUserId);

                } else {
                    logAccessibilityShortcutActivated(mContext, assignedTarget, shortcutType,
                            /* serviceEnabled= */ false);
                    disableAccessibilityServiceLocked(assignedTarget, mCurrentUserId);
                }
                return true;
            }
            if (shortcutType == HARDWARE && targetSdk > Build.VERSION_CODES.Q
                    && requestA11yButton) {
                if (!userState.getEnabledServicesLocked().contains(assignedTarget)) {
                    enableAccessibilityServiceLocked(assignedTarget, mCurrentUserId);
                    return true;
                }
            }
            // Callbacks to a11y service if it's bounded and requests a11y button.
            if (serviceConnection == null
                    || !userState.mBoundServices.contains(serviceConnection)
                    || !serviceConnection.mRequestAccessibilityButton) {
                Slog.d(LOG_TAG, "Perform shortcut failed, service is not ready:"
                        + assignedTarget);
                return false;
            }
            // ServiceConnection means service enabled.
            logAccessibilityShortcutActivated(mContext, assignedTarget, shortcutType,
                    /* serviceEnabled= */ true);
            serviceConnection.notifyAccessibilityButtonClickedLocked(displayId);
            return true;
        }
    }

    private void launchAccessibilityFrameworkFeature(int displayId, ComponentName assignedTarget) {
        if (assignedTarget.equals(ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME)) {
            //import com.android.systemui.Flags;
            if (com.android.systemui.Flags.hearingAidsQsTileDialog()) {
                launchHearingDevicesDialog();
            } else {
                launchAccessibilitySubSettings(displayId,
                        ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME);
            }
        }
    }

    /**
     * Turns on or off a shortcut type of the accessibility features. The {@code shortcutTypes} is a
     * flag that contains values defined in the
     * {@link ShortcutConstants.USER_SHORTCUT_TYPES}.
     *
     * @hide
     */
    @Override
    @EnforcePermission(MANAGE_ACCESSIBILITY)
    public void enableShortcutsForTargets(
            boolean enable, @UserShortcutType int shortcutTypes,
            @NonNull List<String> shortcutTargets, @UserIdInt int userId) {
        enableShortcutsForTargets_enforcePermission();

        for (int shortcutType : USER_SHORTCUT_TYPES) {
            if ((shortcutTypes & shortcutType) == shortcutType) {
                enableShortcutForTargets(enable, shortcutType, shortcutTargets, userId);
            }
        }
    }

    private void enableShortcutForTargets(
            boolean enable, @UserShortcutType int shortcutType,
            @NonNull List<String> shortcutTargets, @UserIdInt int userId) {
        if (shortcutType == UserShortcutType.GESTURE
                && !android.provider.Flags.a11yStandaloneGestureEnabled()) {
            Slog.w(LOG_TAG,
                    "GESTURE type shortcuts are disabled by feature flag");
            return;
        }

        final String shortcutTypeSettingKey = ShortcutUtils.convertToKey(shortcutType);
        if (shortcutType == UserShortcutType.TRIPLETAP
                || shortcutType == UserShortcutType.TWOFINGER_DOUBLETAP) {
            for (String target : shortcutTargets) {
                if (MAGNIFICATION_CONTROLLER_NAME.equals(target)) {
                    persistIntToSetting(
                            userId,
                            shortcutTypeSettingKey,
                            enable ? AccessibilityUtils.State.ON : AccessibilityUtils.State.OFF);
                } else {
                    Slog.w(LOG_TAG,
                            "Triple tap or two-fingers double-tap is not supported for " + target);
                }
            }
            return;
        }
        Set<String> validNewTargets;
        Set<String> currentTargets;

        Map<ComponentName, ComponentName> featureToTileMap =
                getA11yFeatureToTileMapInternal(userId);
        synchronized (mLock) {
            AccessibilityUserState userState = getUserStateLocked(userId);
            currentTargets =
                    ShortcutUtils.getShortcutTargetsFromSettings(mContext, shortcutType, userId);

            Set<String> newTargets = new ArraySet<>(currentTargets);
            if (enable) {
                newTargets.addAll(shortcutTargets);
            } else {
                newTargets.removeAll(shortcutTargets);
            }
            validNewTargets = newTargets;

            // filter out targets that doesn't have qs shortcut
            if (shortcutType == QUICK_SETTINGS) {
                validNewTargets = newTargets.stream().filter(target -> {
                    ComponentName targetComponent = ComponentName.unflattenFromString(target);
                    return featureToTileMap.containsKey(targetComponent);
                }).collect(Collectors.toUnmodifiableSet());
            }

            if (currentTargets.equals(validNewTargets)) {
                return;
            }
            persistColonDelimitedSetToSettingLocked(
                    shortcutTypeSettingKey,
                    userId,
                    validNewTargets,
                    str -> str,
                    /* defaultEmptyString= */ ""
            );

            if (shortcutType == QUICK_SETTINGS) {
                int numOfFeatureChanged = Math.abs(currentTargets.size() - validNewTargets.size());
                logMetricForQsShortcutConfiguration(enable, numOfFeatureChanged);
                userState.updateShortcutTargetsLocked(validNewTargets, QUICK_SETTINGS);
                scheduleNotifyClientsOfServicesStateChangeLocked(userState);
                onUserStateChangedLocked(userState);
            }
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            ShortcutUtils.updateInvisibleToggleAccessibilityServiceEnableState(
                    mContext, new ArraySet<>(shortcutTargets), userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        // Add or Remove tile in QS Panel
        if (shortcutType == QUICK_SETTINGS) {
            mMainHandler.sendMessage(obtainMessage(
                    AccessibilityManagerService::updateA11yTileServicesInQuickSettingsPanel,
                    this, validNewTargets, currentTargets, userId));
        }

        if (!enable) {
            return;
        }
        if (shortcutType == HARDWARE) {
            skipVolumeShortcutDialogTimeoutRestriction(userId);
            if (com.android.server.accessibility.Flags.enableHardwareShortcutDisablesWarning()) {
                persistIntToSetting(
                        userId,
                        Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN,
                        AccessibilityShortcutController.DialogStatus.SHOWN
                );
            }
        } else if (shortcutType == SOFTWARE) {
            // Update the A11y FAB size to large when the Magnification shortcut is
            // enabled and the user hasn't changed the floating button size
            if (shortcutTargets.contains(MAGNIFICATION_CONTROLLER_NAME)
                    && Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE,
                    FloatingMenuSize.UNKNOWN, userId) == FloatingMenuSize.UNKNOWN) {
                persistIntToSetting(
                        userId,
                        Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE,
                        FloatingMenuSize.LARGE);
            }
        }
    }

    /**
     * Add or remove the TileServices of the a11y features in the Quick Settings panel based on the
     * changes in the new targets and current targets.
     *
     * <p>
     * The framework tiles implemented in the SysUi are automatically added/removed via the
     * SystemUI's AutoAddable framework. This method only handles updating the TileServices
     * provided by AccessibilityService or Accessibility Activity.
     * </p>
     *
     * @see com.android.systemui.qs.pipeline.domain.model.AutoAddable AutoAddable QS Tiles
     */
    private void updateA11yTileServicesInQuickSettingsPanel(
            Set<String> newQsTargets,
            Set<String> currentQsTargets, @UserIdInt int userId) {
        // Call StatusBarManager to add/remove tiles
        final StatusBarManagerInternal statusBarManagerInternal =
                LocalServices.getService(StatusBarManagerInternal.class);
        // In case it's not initialized yet
        if (statusBarManagerInternal == null) {
            return;
        }

        Map<ComponentName, ComponentName> a11yFeatureToTileMap =
                getA11yFeatureToTileMapInternal(userId);
        Set<String> targetWithNoTile = new ArraySet<>();

        // Add TileServices to QS Panel that are added to the new targets
        newQsTargets.stream()
                .filter(target -> !currentQsTargets.contains(target))
                .forEach(
                        target -> {
                            ComponentName targetComponent =
                                    ComponentName.unflattenFromString(target);
                            if (targetComponent == null
                                    || !a11yFeatureToTileMap.containsKey(targetComponent)) {
                                targetWithNoTile.add(target);
                                return;
                            }

                            if (ShortcutConstants.A11Y_FEATURE_TO_FRAMEWORK_TILE.containsKey(
                                    targetComponent)) {
                                // a11y framework tile is handled by the SysUi autoaddable framework
                                return;
                            }
                            statusBarManagerInternal.addQsTileToFrontOrEnd(
                                    a11yFeatureToTileMap.get(targetComponent), /* end= */ true);
                        }
                );

        // Remove TileServices from QS Panel that are no longer in the new targets.
        currentQsTargets.stream()
                .filter(target -> !newQsTargets.contains(target))
                .forEach(
                        target -> {
                            ComponentName targetComponent =
                                    ComponentName.unflattenFromString(target);
                            if (targetComponent == null
                                    || !a11yFeatureToTileMap.containsKey(targetComponent)) {
                                targetWithNoTile.add(target);
                                return;
                            }

                            if (ShortcutConstants.A11Y_FEATURE_TO_FRAMEWORK_TILE.containsKey(
                                    targetComponent)) {
                                // a11y framework tile is handled by the SysUi autoaddable framework
                                return;
                            }
                            statusBarManagerInternal.removeQsTile(
                                    a11yFeatureToTileMap.get(targetComponent));
                        }
                );

        if (!targetWithNoTile.isEmpty()) {
            Slog.e(LOG_TAG,
                    "Unable to add/remove Tiles for a11y features: " + targetWithNoTile
                            + "as the Tiles aren't provided");
        }
    }

    @Override
    @EnforcePermission(MANAGE_ACCESSIBILITY)
    public Bundle getA11yFeatureToTileMap(@UserIdInt int userId) {
        getA11yFeatureToTileMap_enforcePermission();
        Bundle bundle = new Bundle();
        Map<ComponentName, ComponentName> a11yFeatureToTile =
                getA11yFeatureToTileMapInternal(userId);
        for (Map.Entry<ComponentName, ComponentName> entry : a11yFeatureToTile.entrySet()) {
            bundle.putParcelable(entry.getKey().flattenToString(), entry.getValue());
        }
        return bundle;
    }



    /**
     * Returns accessibility feature's component and the provided tile map. This includes the
     * TileService provided by the AccessibilityService or Accessibility Activity and the tile
     * component provided by the framework's feature.
     *
     * @return a map of a feature's component name, and its provided tile's component name. The
     * returned map's keys and values are not null. If a feature doesn't provide a tile, it won't
     * have an entry in this map.
     *
     * @see ShortcutConstants.A11Y_FEATURE_TO_FRAMEWORK_TILE
     */
    @NonNull
    private Map<ComponentName, ComponentName> getA11yFeatureToTileMapInternal(
            @UserIdInt int userId) {
        final Map<ComponentName, ComponentName> a11yFeatureToTileService;
        Map<ComponentName, ComponentName> a11yFeatureToTile = new ArrayMap<>();
        final int resolvedUserId;

        synchronized (mLock) {
            resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            AccessibilityUserState userState = getUserStateLocked(resolvedUserId);
            a11yFeatureToTileService = userState.getA11yFeatureToTileService();
        }
        final boolean shouldFilterAppAccess = Binder.getCallingPid() != OWN_PROCESS_ID;
        final int callingUid = Binder.getCallingUid();
        final PackageManagerInternal pm = LocalServices.getService(
                PackageManagerInternal.class);

        for (Map.Entry<ComponentName, ComponentName> entry :
                a11yFeatureToTileService.entrySet()) {
            if (shouldFilterAppAccess
                    && pm.filterAppAccess(
                    entry.getKey().getPackageName(), callingUid, resolvedUserId)) {
                continue;
            }
            a11yFeatureToTile.put(entry.getKey(), entry.getValue());
        }

        a11yFeatureToTile.putAll(ShortcutConstants.A11Y_FEATURE_TO_FRAMEWORK_TILE);
        return a11yFeatureToTile;
    }

    @Override
    @EnforcePermission(MANAGE_ACCESSIBILITY)
    public List<String> getAccessibilityShortcutTargets(@UserShortcutType int shortcutType) {
        getAccessibilityShortcutTargets_enforcePermission();
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getAccessibilityShortcutTargets",
                    FLAGS_ACCESSIBILITY_MANAGER, "shortcutType=" + shortcutType);
        }
        return getAccessibilityShortcutTargetsInternal(shortcutType);
    }

    private List<String> getAccessibilityShortcutTargetsInternal(
            @UserShortcutType int shortcutType) {
        synchronized (mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();
            final ArrayList<String> shortcutTargets = new ArrayList<>(
                    userState.getShortcutTargetsLocked(shortcutType));
            if (shortcutType != SOFTWARE) {
                return shortcutTargets;
            }
            // Adds legacy a11y services requesting a11y button into the list.
            for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
                if (!service.mRequestAccessibilityButton
                        || service.getServiceInfo().getResolveInfo().serviceInfo.applicationInfo
                        .targetSdkVersion > Build.VERSION_CODES.Q) {
                    continue;
                }
                final String serviceName = service.getComponentName().flattenToString();
                if (!TextUtils.isEmpty(serviceName)) {
                    shortcutTargets.add(serviceName);
                }
            }
            userState.updateShortcutTargetsLocked(Set.copyOf(shortcutTargets), shortcutType);
            return shortcutTargets;
        }
    }

    /**
     * Enables accessibility service specified by {@param componentName} for the {@param userId}.
     */
    private void enableAccessibilityServiceLocked(ComponentName componentName, int userId) {
        mTempComponentNameSet.clear();
        readComponentNamesFromSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userId, mTempComponentNameSet);
        mTempComponentNameSet.add(componentName);
        persistComponentNamesToSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                mTempComponentNameSet, userId);

        AccessibilityUserState userState = getUserStateLocked(userId);
        if (userState.mEnabledServices.add(componentName)) {
            onUserStateChangedLocked(userState);
        }
    }

    /**
     * Disables accessibility service specified by {@param componentName} for the {@param userId}.
     */
    private void disableAccessibilityServiceLocked(ComponentName componentName, int userId) {
        mTempComponentNameSet.clear();
        readComponentNamesFromSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userId, mTempComponentNameSet);
        mTempComponentNameSet.remove(componentName);
        persistComponentNamesToSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                mTempComponentNameSet, userId);

        AccessibilityUserState userState = getUserStateLocked(userId);
        if (userState.mEnabledServices.remove(componentName)) {
            onUserStateChangedLocked(userState);
        }
    }

    @Override
    public void sendAccessibilityEventForCurrentUserLocked(AccessibilityEvent event) {
        if (event.getWindowChanges() == AccessibilityEvent.WINDOWS_CHANGE_ADDED) {
            // We need to ensure the window is available before sending pending
            // window_state_changed events.
            sendPendingWindowStateChangedEventsForAvailableWindowLocked(event.getWindowId());
        }
        sendAccessibilityEventLocked(event, mCurrentUserId);
    }

    private void sendAccessibilityEventLocked(AccessibilityEvent event, int userId) {
        // Resync to avoid calling out with the lock held
        event.setEventTime(SystemClock.uptimeMillis());
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::sendAccessibilityEvent,
                this, event, userId));
    }

    /**
     * AIDL-exposed method. System only.
     * Inform accessibility that a fingerprint gesture was performed
     *
     * @param gestureKeyCode The key code corresponding to the fingerprint gesture.
     * @return {@code true} if accessibility consumes the fingerprint gesture, {@code false} if it
     * doesn't.
     */
    @Override
    @RequiresNoPermission
    public boolean sendFingerprintGesture(int gestureKeyCode) {
        if (mTraceManager.isA11yTracingEnabledForTypes(
                FLAGS_ACCESSIBILITY_MANAGER | FLAGS_FINGERPRINT)) {
            mTraceManager.logTrace(LOG_TAG + ".sendFingerprintGesture",
                    FLAGS_ACCESSIBILITY_MANAGER | FLAGS_FINGERPRINT,
                    "gestureKeyCode=" + gestureKeyCode);
        }

        synchronized(mLock) {
            if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
                // TODO(b/333547153) remove the AIDL definitions for these functions that are
                // restricted to system server and move them to AccessibilityManagerInternal.
                throw new SecurityException("Only SYSTEM can call sendFingerprintGesture");
            }
        }
        if (mFingerprintGestureDispatcher == null) {
            return false;
        }
        return mFingerprintGestureDispatcher.onFingerprintGesture(gestureKeyCode);
    }

    /**
     * AIDL-exposed method. System only.
     * Gets accessibility window id from window token.
     *
     * @param windowToken Window token to get accessibility window id.
     * @return Accessibility window id for the window token. Returns -1 if no such token is
     *   registered.
     */
    @Override
    @RequiresNoPermission
    public int getAccessibilityWindowId(@Nullable IBinder windowToken) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getAccessibilityWindowId",
                    FLAGS_ACCESSIBILITY_MANAGER, "windowToken=" + windowToken);
        }

        synchronized (mLock) {
            if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
                throw new SecurityException("Only SYSTEM can call getAccessibilityWindowId");
            }

            return mA11yWindowManager.findWindowIdLocked(mCurrentUserId, windowToken);
        }
    }

    /**
     * Get the recommended timeout of interactive controls and non-interactive controls.
     *
     * @return A long for pair of {@code int}s. First integer for interactive one, and second
     * integer for non-interactive one.
     */
    @Override
    @RequiresNoPermission
    public long getRecommendedTimeoutMillis() {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(
                    LOG_TAG + ".getRecommendedTimeoutMillis", FLAGS_ACCESSIBILITY_MANAGER);
        }

        synchronized(mLock) {
            final int deviceId = mProxyManager.getFirstDeviceIdForUidLocked(
                    Binder.getCallingUid());
            if (mProxyManager.isProxyedDeviceId(deviceId)) {
                return mProxyManager.getRecommendedTimeoutMillisLocked(deviceId);
            } else {
                final AccessibilityUserState userState = getCurrentUserStateLocked();
                return getRecommendedTimeoutMillisLocked(userState);
            }
        }
    }

    private long getRecommendedTimeoutMillisLocked(AccessibilityUserState userState) {
        return IntPair.of(userState.getInteractiveUiTimeoutLocked(),
                userState.getNonInteractiveUiTimeoutLocked());
    }

    @Override
    @EnforcePermission(STATUS_BAR_SERVICE)
    public void setMagnificationConnection(
            IMagnificationConnection connection) throws RemoteException {
        setMagnificationConnection_enforcePermission();
        if (mTraceManager.isA11yTracingEnabledForTypes(
                FLAGS_ACCESSIBILITY_MANAGER | FLAGS_MAGNIFICATION_CONNECTION)) {
            mTraceManager.logTrace(LOG_TAG + ".setMagnificationConnection",
                    FLAGS_ACCESSIBILITY_MANAGER | FLAGS_MAGNIFICATION_CONNECTION,
                    "connection=" + connection);
        }

        getMagnificationConnectionManager().setConnection(connection);

        if (com.android.window.flags.Flags.alwaysDrawMagnificationFullscreenBorder()
                && connection == null
                && mMagnificationController.isFullScreenMagnificationControllerInitialized()) {
            // Since the connection does not exist, the system ui cannot provide the border
            // implementation for fullscreen magnification. So we call reset to deactivate the
            // fullscreen magnification to prevent the magnified but no border situation.
            final ArrayList<Display> displays = getValidDisplayList();
            for (int i = 0; i < displays.size(); i++) {
                final Display display = displays.get(i);
                getMagnificationController().getFullScreenMagnificationController()
                        .reset(display.getDisplayId(), false);
            }
        }
    }

    /**
     * Getter of {@link MagnificationConnectionManager}.
     *
     * @return MagnificationManager
     */
    public MagnificationConnectionManager getMagnificationConnectionManager() {
        synchronized (mLock) {
            return mMagnificationController.getMagnificationConnectionManager();
        }
    }

    /**
     * Getter of {@link MagnificationController}.
     *
     * @return MagnificationController
     */
    MagnificationController getMagnificationController() {
        return mMagnificationController;
    }

    @Override
    @RequiresNoPermission
    public void associateEmbeddedHierarchy(@NonNull IBinder host, @NonNull IBinder embedded) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".associateEmbeddedHierarchy",
                    FLAGS_ACCESSIBILITY_MANAGER, "host=" + host + ";embedded=" + embedded);
        }

        synchronized (mLock) {
            mA11yWindowManager.associateEmbeddedHierarchyLocked(host, embedded);
        }
    }

    @Override
    @RequiresNoPermission
    public void disassociateEmbeddedHierarchy(@NonNull IBinder token) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".disassociateEmbeddedHierarchy",
                    FLAGS_ACCESSIBILITY_MANAGER, "token=" + token);
        }

        synchronized (mLock) {
            mA11yWindowManager.disassociateEmbeddedHierarchyLocked(token);
        }
    }

    /**
     * Gets the stroke width of the focus rectangle.
     * @return The stroke width.
     */
    @Override
    @RequiresNoPermission
    public int getFocusStrokeWidth() {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getFocusStrokeWidth", FLAGS_ACCESSIBILITY_MANAGER);
        }
        synchronized (mLock) {
            final int deviceId = mProxyManager.getFirstDeviceIdForUidLocked(
                    Binder.getCallingUid());
            if (mProxyManager.isProxyedDeviceId(deviceId)) {
                return mProxyManager.getFocusStrokeWidthLocked(deviceId);
            }
            final AccessibilityUserState userState = getCurrentUserStateLocked();

            return userState.getFocusStrokeWidthLocked();
        }
    }

    /**
     * Gets the color of the focus rectangle.
     * @return The color.
     */
    @Override
    @RequiresNoPermission
    public int getFocusColor() {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getFocusColor", FLAGS_ACCESSIBILITY_MANAGER);
        }
        synchronized (mLock) {
            final int deviceId = mProxyManager.getFirstDeviceIdForUidLocked(
                    Binder.getCallingUid());
            if (mProxyManager.isProxyedDeviceId(deviceId)) {
                return mProxyManager.getFocusColorLocked(deviceId);
            }
            final AccessibilityUserState userState = getCurrentUserStateLocked();

            return userState.getFocusColorLocked();
        }
    }

    /**
     * Gets the status of the audio description preference.
     * @return {@code true} if the audio description is enabled, {@code false} otherwise.
     */
    @Override
    @RequiresNoPermission
    public boolean isAudioDescriptionByDefaultEnabled() {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".isAudioDescriptionByDefaultEnabled",
                    FLAGS_ACCESSIBILITY_MANAGER);
        }
        synchronized (mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();

            return userState.isAudioDescriptionByDefaultEnabledLocked();
        }
    }

    /**
     * Sets the {@link AccessibilityWindowAttributes} to the window associated with the given
     * window id.
     *
     * @param displayId The display id of the window.
     * @param windowId The id of the window
     * @param userId The user id.
     * @param attributes The accessibility window attributes.
     */
    @Override
    @RequiresNoPermission
    public void setAccessibilityWindowAttributes(int displayId, int windowId, int userId,
            AccessibilityWindowAttributes attributes) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".setAccessibilityWindowAttributes",
                    FLAGS_ACCESSIBILITY_MANAGER);
        }
        mA11yWindowManager.setAccessibilityWindowAttributes(displayId, windowId, userId,
                attributes);
    }

    @Override
    @EnforcePermission(SET_SYSTEM_AUDIO_CAPTION)
    public void setSystemAudioCaptioningEnabled(boolean isEnabled, int userId) {
        setSystemAudioCaptioningEnabled_enforcePermission();
        mCaptioningManagerImpl.setSystemAudioCaptioningEnabled(isEnabled, userId);
    }

    @Override
    @RequiresNoPermission
    public boolean isSystemAudioCaptioningUiEnabled(int userId) {
        return mCaptioningManagerImpl.isSystemAudioCaptioningUiEnabled(userId);
    }

    @Override
    @EnforcePermission(SET_SYSTEM_AUDIO_CAPTION)
    public void setSystemAudioCaptioningUiEnabled(boolean isEnabled, int userId) {
        setSystemAudioCaptioningUiEnabled_enforcePermission();
        mCaptioningManagerImpl.setSystemAudioCaptioningUiEnabled(isEnabled, userId);
    }

    @Override
    @EnforcePermission(CREATE_VIRTUAL_DEVICE)
    public boolean registerProxyForDisplay(IAccessibilityServiceClient client, int displayId)
            throws RemoteException {
        registerProxyForDisplay_enforcePermission();
        mSecurityPolicy.checkForAccessibilityPermissionOrRole();
        int currentUserId;
        synchronized (mLock) {
            currentUserId = mCurrentUserId;
        }
        enforceCurrentUserIfVisibleBackgroundEnabled(currentUserId);
        if (client == null) {
            return false;
        }
        if (displayId < 0) {
            throw new IllegalArgumentException("The display id " + displayId + " is invalid.");
        }
        if (!isTrackedDisplay(displayId)) {
            throw new IllegalArgumentException("The display " + displayId + " does not exist or is"
                    + " not tracked by accessibility.");
        }
        if (mProxyManager.isProxyedDisplay(displayId)) {
            throw new IllegalArgumentException("The display " + displayId + " is already being"
                    + " proxy-ed");
        }
        if (!mProxyManager.displayBelongsToCaller(Binder.getCallingUid(), displayId)) {
            throw new SecurityException("The display " + displayId + " does not belong to"
                    + " the caller.");
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            mProxyManager.registerProxy(client, displayId, sIdCounter++, mSecurityPolicy,
                    this, getTraceManager(), mWindowManagerService);

            synchronized (mLock) {
                notifyClearAccessibilityCacheLocked();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return true;
    }

    @Override
    @EnforcePermission(CREATE_VIRTUAL_DEVICE)
    public boolean unregisterProxyForDisplay(int displayId) {
        unregisterProxyForDisplay_enforcePermission();
        mSecurityPolicy.checkForAccessibilityPermissionOrRole();
        final long identity = Binder.clearCallingIdentity();
        try {
            return mProxyManager.unregisterProxy(displayId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    boolean isDisplayProxyed(int displayId) {
        return mProxyManager.isProxyedDisplay(displayId);
    }

    @Override
    @EnforcePermission(MANAGE_ACCESSIBILITY)
    public boolean startFlashNotificationSequence(String opPkg, @FlashNotificationReason int reason,
            IBinder token) {
        startFlashNotificationSequence_enforcePermission();
        return mFlashNotificationsController.startFlashNotificationSequence(opPkg, reason, token);
    }

    @Override
    @EnforcePermission(MANAGE_ACCESSIBILITY)
    public boolean stopFlashNotificationSequence(String opPkg) {
        stopFlashNotificationSequence_enforcePermission();
        return mFlashNotificationsController.stopFlashNotificationSequence(opPkg);
    }

    @Override
    @EnforcePermission(MANAGE_ACCESSIBILITY)
    public boolean startFlashNotificationEvent(String opPkg, @FlashNotificationReason int reason,
            String reasonPkg) {
        startFlashNotificationEvent_enforcePermission();
        return mFlashNotificationsController.startFlashNotificationEvent(opPkg, reason, reasonPkg);
    }

    @Override
    @RequiresNoPermission
    public boolean isAccessibilityTargetAllowed(String packageName, int uid, int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            final DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
            final List<String> permittedServices = dpm.getPermittedAccessibilityServices(userId);

            // permittedServices null means all accessibility services are allowed.
            boolean allowed = permittedServices == null || permittedServices.contains(packageName);
            if (allowed) {
                if (android.permission.flags.Flags.enhancedConfirmationModeApisEnabled()
                        && android.security.Flags.extendEcmToAllSettings()) {
                    try {
                        final EnhancedConfirmationManager userContextEcm =
                                mContext.createContextAsUser(UserHandle.of(userId), /* flags = */ 0)
                                        .getSystemService(EnhancedConfirmationManager.class);
                        if (userContextEcm != null) {
                            return !userContextEcm.isRestricted(packageName,
                                    AppOpsManager.OPSTR_BIND_ACCESSIBILITY_SERVICE);
                        }
                        return false;
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(LOG_TAG, "Exception when retrieving package:" + packageName, e);
                        return false;
                    }
                } else {
                    try {
                        final int mode = mContext.getSystemService(AppOpsManager.class)
                                .noteOpNoThrow(AppOpsManager.OP_ACCESS_RESTRICTED_SETTINGS,
                                        uid, packageName);
                        final boolean ecmEnabled = mContext.getResources().getBoolean(
                                com.android.internal.R.bool.config_enhancedConfirmationModeEnabled);
                        return !ecmEnabled || mode == AppOpsManager.MODE_ALLOWED
                                || mode == AppOpsManager.MODE_DEFAULT;
                    } catch (Exception e) {
                        // Fallback in case if app ops is not available in testing.
                        return false;
                    }
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    @RequiresNoPermission
    public boolean sendRestrictedDialogIntent(String packageName, int uid, int userId) {
        // The accessibility service is allowed. Don't show the restricted dialog.
        if (isAccessibilityTargetAllowed(packageName, uid, userId)) {
            return false;
        }

        final EnforcedAdmin admin =
                RestrictedLockUtilsInternal.checkIfAccessibilityServiceDisallowed(
                        mContext, packageName, userId);
        if (admin != null) {
            RestrictedLockUtils.sendShowAdminSupportDetailsIntent(mContext, admin);
            return true;
        }

        if (android.permission.flags.Flags.enhancedConfirmationModeApisEnabled()
                && android.security.Flags.extendEcmToAllSettings()) {
            try {
                Intent settingDialogIntent = mContext
                        .getSystemService(EnhancedConfirmationManager.class)
                        .createRestrictedSettingDialogIntent(packageName,
                                AppOpsManager.OPSTR_BIND_ACCESSIBILITY_SERVICE);
                mContext.startActivity(settingDialogIntent);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(LOG_TAG, "Exception when retrieving package:" + packageName, e);
            }
        } else {
            RestrictedLockUtils.sendShowRestrictedSettingDialogIntent(mContext,
                    packageName, uid);
        }
        return true;
    }

    @Override
    @EnforcePermission(MANAGE_ACCESSIBILITY)
    public boolean isAccessibilityServiceWarningRequired(AccessibilityServiceInfo info) {
        isAccessibilityServiceWarningRequired_enforcePermission();
        final ComponentName componentName = info.getComponentName();

        // Warning is not required if the service is already enabled.
        synchronized (mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();
            if (userState.getEnabledServicesLocked().contains(componentName)) {
                return false;
            }
        }
        // Warning is not required if the service is already assigned to a shortcut.
        for (int shortcutType : ShortcutConstants.USER_SHORTCUT_TYPES) {
            if (getAccessibilityShortcutTargets(shortcutType).contains(
                    componentName.flattenToString())) {
                return false;
            }
        }
        // Warning is not required if the service is preinstalled and in the
        // trustedAccessibilityServices allowlist.
        if (android.view.accessibility.Flags.skipAccessibilityWarningDialogForTrustedServices()
                && isAccessibilityServicePreinstalledAndTrusted(info)) {
            return false;
        }

        // Warning is required by default.
        return true;
    }

    private boolean isAccessibilityServicePreinstalledAndTrusted(AccessibilityServiceInfo info) {
        final ComponentName componentName = info.getComponentName();
        final boolean isPreinstalled =
                info.getResolveInfo().serviceInfo.applicationInfo.isSystemApp();
        if (isPreinstalled) {
            final String[] trustedAccessibilityServices =
                    mContext.getResources().getStringArray(
                            R.array.config_trustedAccessibilityServices);
            if (Arrays.stream(trustedAccessibilityServices)
                    .map(ComponentName::unflattenFromString)
                    .anyMatch(componentName::equals)) {
                return true;
            }
        }
        return false;
    }

    @Override
    @PermissionManuallyEnforced // DUMP
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;
        synchronized (mLock) {
            pw.println("ACCESSIBILITY MANAGER (dumpsys accessibility)");
            pw.println();
            pw.append("currentUserId=").append(String.valueOf(mCurrentUserId));
            if (mRealCurrentUserId != UserHandle.USER_CURRENT
                    && mCurrentUserId != mRealCurrentUserId) {
                pw.append(" (set for UiAutomation purposes; \"real\" current user is ")
                        .append(String.valueOf(mRealCurrentUserId)).append(")");
            }
            pw.println();
            if (mVisibleBgUserIds != null) {
                pw.append("visibleBgUserIds=").append(mVisibleBgUserIds.toString());
                pw.println();
            }
            pw.append("hasMagnificationConnection=").append(
                    String.valueOf(getMagnificationConnectionManager().isConnected()));
            pw.println();
            mMagnificationProcessor.dump(pw, getValidDisplayList());
            final int userCount = mUserStates.size();
            for (int i = 0; i < userCount; i++) {
                mUserStates.valueAt(i).dump(fd, pw, args);
            }
            if (mUiAutomationManager.isUiAutomationRunningLocked()) {
                mUiAutomationManager.dumpUiAutomationService(fd, pw, args);
                pw.println();
            }
            mA11yWindowManager.dump(fd, pw, args);
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.dump(fd, pw, args);
            }
            pw.println("Global client list info:{");
            mGlobalClients.dump(pw, "    Client list ");
            pw.println("    Registered clients:{");
            for (int i = 0; i < mGlobalClients.getRegisteredCallbackCount(); i++) {
                AccessibilityManagerService.Client client = (AccessibilityManagerService.Client)
                        mGlobalClients.getRegisteredCallbackCookie(i);
                pw.append(Arrays.toString(client.mPackageNames));
            }
            pw.println();
            mProxyManager.dump(fd, pw, args);
            mA11yDisplayListener.dump(fd, pw, args);
        }
    }

    //TODO remove after refactoring KeyEventDispatcherTest
    final class MainHandler extends Handler {
        public static final int MSG_SEND_KEY_EVENT_TO_INPUT_FILTER = 8;

        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_SEND_KEY_EVENT_TO_INPUT_FILTER) {
                KeyEvent event = (KeyEvent) msg.obj;
                final int policyFlags = msg.arg1;
                synchronized (mLock) {
                    if (mHasInputFilter && mInputFilter != null) {
                        mInputFilter.sendInputEvent(event, policyFlags);
                    }
                }
                event.recycle();
            }
        }
    }

    @Override
    public MagnificationProcessor getMagnificationProcessor() {
        return mMagnificationProcessor;
    }

    @Override
    public void onClientChangeLocked(boolean serviceInfoChanged) {
        onClientChangeLocked(serviceInfoChanged, false);
    }

    /**
     * Called when the state of a service or proxy has changed
     * @param serviceInfoChanged if the service info has changed
     * @param forceUpdate whether to force an update of state for app clients
     */
    public void onClientChangeLocked(boolean serviceInfoChanged, boolean forceUpdate) {
        AccessibilityUserState userState = getUserStateLocked(mCurrentUserId);
        onUserStateChangedLocked(userState, forceUpdate);
        if (serviceInfoChanged) {
            scheduleNotifyClientsOfServicesStateChangeLocked(userState);
        }
    }


    @Override
    public void onProxyChanged(int deviceId) {
        mProxyManager.onProxyChanged(deviceId);
    }

    /**
     * Removes the device from tracking. This will reset any AccessibilityManagerClients to be
     * associated with the default user id.
     */
    @Override
    public void removeDeviceIdLocked(int deviceId) {
        resetClientsLocked(deviceId, getCurrentUserStateLocked().mUserClients);
        resetClientsLocked(deviceId, mGlobalClients);
        // Force an update of A11yManagers if the state was previously a proxy state and needs to be
        // returned to the default device state.
        onClientChangeLocked(true, true);
    }

    private void resetClientsLocked(int deviceId,
            RemoteCallbackList<IAccessibilityManagerClient> clients) {
        if (clients == null || clients.getRegisteredCallbackCount() == 0) {
            return;
        }
        synchronized (mLock) {
            for (int i = 0; i < clients.getRegisteredCallbackCount(); i++) {
                final Client appClient = ((Client) clients.getRegisteredCallbackCookie(i));
                if (appClient.mDeviceId == deviceId) {
                    appClient.mDeviceId = DEVICE_ID_DEFAULT;
                }
            }
        }
    }

    @Override
    public void updateWindowsForAccessibilityCallbackLocked() {
        updateWindowsForAccessibilityCallbackLocked(getUserStateLocked(mCurrentUserId));
    }

    @Override
    public RemoteCallbackList<IAccessibilityManagerClient> getGlobalClientsLocked() {
        return mGlobalClients;
    }

    @Override
    public RemoteCallbackList<IAccessibilityManagerClient> getCurrentUserClientsLocked() {
        return getCurrentUserState().mUserClients;
    }

    @Override
    @RequiresNoPermission
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        new AccessibilityShellCommand(mContext, this, mSystemActionPerformer)
                .exec(this, in, out, err, args, callback, resultReceiver);
    }

    private final class InteractionBridge {
        private final ComponentName COMPONENT_NAME =
                new ComponentName("com.android.server.accessibility", "InteractionBridge");

        private final Display mDefaultDisplay;
        private final int mConnectionId;
        private final AccessibilityInteractionClient mClient;

        public InteractionBridge() {
            final AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.setCapabilities(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.setAccessibilityTool(true);
            final AccessibilityUserState userState;
            synchronized (mLock) {
                userState = getCurrentUserStateLocked();
            }
            AccessibilityServiceConnection service = new AccessibilityServiceConnection(
                    userState, mContext,
                    COMPONENT_NAME, info, sIdCounter++, mMainHandler, mLock, mSecurityPolicy,
                    AccessibilityManagerService.this,
                    AccessibilityManagerService.this.getTraceManager(), mWindowManagerService,
                    getSystemActionPerformer(), mA11yWindowManager, mActivityTaskManagerService) {
                @Override
                public boolean supportsFlagForNotImportantViews(AccessibilityServiceInfo info) {
                    return true;
                }
            };

            mConnectionId = service.mId;

            mClient = AccessibilityInteractionClient.getInstance(mContext);
            mClient.addConnection(mConnectionId, service, /*initializeCache=*/false);

            //TODO: (multi-display) We need to support multiple displays.
            DisplayManager displayManager = (DisplayManager)
                    mContext.getSystemService(Context.DISPLAY_SERVICE);
            mDefaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        }

        /**
         * Gets a point within the accessibility focused node where we can send down and up events
         * to perform a click.
         *
         * @param outPoint The click point to populate.
         * @return Whether accessibility a click point was found and set.
         */
        // TODO: (multi-display) Make sure this works for multiple displays.
        boolean getAccessibilityFocusClickPointInScreen(Point outPoint) {
            return getInteractionBridge()
                    .getAccessibilityFocusClickPointInScreenNotLocked(outPoint);
        }

    /**
         * Perform an accessibility action on the view that currently has accessibility focus.
         * Has no effect if no item has accessibility focus, if the item with accessibility
         * focus does not expose the specified action, or if the action fails.
         *
         * @param action The action to perform.
         *
         * @return {@code true} if the action was performed. {@code false} if it was not.
         */
        public boolean performActionOnAccessibilityFocusedItemNotLocked(
                AccessibilityNodeInfo.AccessibilityAction action) {
            AccessibilityNodeInfo focus = getAccessibilityFocusNotLocked();
            if ((focus == null) || !focus.getActionList().contains(action)) {
                return false;
            }
            return focus.performAction(action.getId());
        }

        public boolean getAccessibilityFocusClickPointInScreenNotLocked(Point outPoint) {
            AccessibilityNodeInfo focus = getAccessibilityFocusNotLocked();
            if (focus == null) {
                return false;
            }

            synchronized (mLock) {
                Rect boundsInScreenBeforeMagnification = mTempRect;

                focus.getBoundsInScreen(boundsInScreenBeforeMagnification);
                final Point nodeCenter = new Point(boundsInScreenBeforeMagnification.centerX(),
                        boundsInScreenBeforeMagnification.centerY());

                // Invert magnification if needed.
                final Pair<float[], MagnificationSpec> pair =
                        getWindowTransformationMatrixAndMagnificationSpec(focus.getWindowId());
                MagnificationSpec spec = null;
                if (pair != null && pair.second != null) {
                    spec = new MagnificationSpec();
                    spec.setTo(pair.second);
                }

                if (spec != null && !spec.isNop()) {
                    boundsInScreenBeforeMagnification.offset((int) -spec.offsetX,
                            (int) -spec.offsetY);
                    boundsInScreenBeforeMagnification.scale(1 / spec.scale);
                }

                //Clip to the window bounds.
                Rect windowBounds = mTempRect1;
                AccessibilityWindowInfo window = focus.getWindow();
                if (window != null) {
                    window.getBoundsInScreen(windowBounds);
                }
                if (!boundsInScreenBeforeMagnification.intersect(windowBounds)) {
                    return false;
                }

                //Clip to the screen bounds.
                Point screenSize = mTempPoint;
                mDefaultDisplay.getRealSize(screenSize);
                if (!boundsInScreenBeforeMagnification.intersect(0, 0, screenSize.x,
                        screenSize.y)) {
                    return false;
                }

                outPoint.set(nodeCenter.x, nodeCenter.y);
            }

            return true;
        }

        private AccessibilityNodeInfo getAccessibilityFocusNotLocked() {
            final int focusedWindowId;
            synchronized (mLock) {
                focusedWindowId = mA11yWindowManager.getFocusedWindowId(
                        AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                if (focusedWindowId == AccessibilityWindowInfo.UNDEFINED_WINDOW_ID) {
                    return null;
                }
            }
            return getAccessibilityFocusNotLocked(focusedWindowId);
        }

        private AccessibilityNodeInfo getAccessibilityFocusNotLocked(int windowId) {
            return mClient.findFocus(mConnectionId,
                    windowId, AccessibilityNodeInfo.ROOT_NODE_ID,
                    AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        }
    }

    /**
     * Gets all currently valid logical displays.
     *
     * @return An array list containing all valid logical displays.
     */
    public ArrayList<Display> getValidDisplayList() {
        return mA11yDisplayListener.getValidDisplayList();
    }


    /**
     *  Returns {@code true} if the display id is in the list of currently valid logical displays
     *  being tracked by a11y.
     */
    private boolean isTrackedDisplay(int displayId) {
        final ArrayList<Display> displays = getValidDisplayList();
        for (Display display : displays) {
            if (display.getDisplayId() == displayId) {
                return true;
            }
        }
        return false;
    }

    /**
     * A Utility class to handle display state.
     */
    public class AccessibilityDisplayListener implements DisplayManager.DisplayListener {
        private final DisplayManager mDisplayManager;
        private final ArrayList<Display> mDisplaysList = new ArrayList<>();
        private int mSystemUiUid = 0;

        AccessibilityDisplayListener(Context context, Handler handler) {
            // Avoid concerns about one thread adding displays while another thread removes
            // them by ensuring the looper is the main looper and the DisplayListener
            // callbacks are always executed on the one main thread.
            final boolean isMainHandler = handler.getLooper() == Looper.getMainLooper();
            final String errorMessage =
                    "AccessibilityDisplayListener must use the main handler";
            if (Build.IS_USERDEBUG || Build.IS_ENG) {
                Preconditions.checkArgument(isMainHandler, errorMessage);
            } else if (!isMainHandler) {
                Slog.e(LOG_TAG, errorMessage);
            }

            mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            mDisplayManager.registerDisplayListener(this, handler);
            initializeDisplayList();

            final PackageManagerInternal pm =
                    LocalServices.getService(PackageManagerInternal.class);
            if (pm != null) {
                mSystemUiUid = pm.getPackageUid(pm.getSystemUiServiceComponent().getPackageName(),
                        PackageManager.MATCH_SYSTEM_ONLY, mCurrentUserId);
            }
        }

        /**
         * Gets all currently valid logical displays.
         *
         * @return An array list containing all valid logical displays.
         */
        public ArrayList<Display> getValidDisplayList() {
            synchronized (mLock) {
                return mDisplaysList;
            }
        }

        private void initializeDisplayList() {
            final Display[] displays = mDisplayManager.getDisplays();
            synchronized (mLock) {
                mDisplaysList.clear();
                for (int i = 0; i < displays.length; i++) {
                    // Exclude overlay virtual displays. The display list is for A11yInputFilter
                    // to create event handler per display. The events should be handled by the
                    // display which is overlaid by it.
                    final Display display = displays[i];
                    if (isValidDisplay(display)) {
                        mDisplaysList.add(display);
                    }
                }
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
            final boolean isMainThread = Looper.getMainLooper().isCurrentThread();
            final String errorMessage = "onDisplayAdded must be called from the main thread";
            if (Build.IS_USERDEBUG || Build.IS_ENG) {
                Preconditions.checkArgument(isMainThread, errorMessage);
            } else if (!isMainThread) {
                Slog.e(LOG_TAG, errorMessage);
            }
            final Display display = mDisplayManager.getDisplay(displayId);
            if (!isValidDisplay(display)) {
                return;
            }

            final List<AccessibilityServiceConnection> services;
            synchronized (mLock) {
                mDisplaysList.add(display);
                mA11yOverlayLayers.put(
                        displayId, mWindowManagerService.getA11yOverlayLayer(displayId));
                if (mInputFilter != null) {
                    mInputFilter.onDisplayAdded(display);
                }
                AccessibilityUserState userState = getCurrentUserStateLocked();
                services = new ArrayList<>(userState.mBoundServices);
                updateMagnificationLocked(userState);
                updateWindowsForAccessibilityCallbackLocked(userState);
                notifyClearAccessibilityCacheLocked();
            }
            if (displayId != Display.DEFAULT_DISPLAY) {
                for (int i = 0; i < services.size(); i++) {
                    AccessibilityServiceConnection boundClient = services.get(i);
                    boundClient.addWindowTokenForDisplay(displayId);
                }
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            final boolean isMainThread = Looper.getMainLooper().isCurrentThread();
            final String errorMessage = "onDisplayRemoved must be called from the main thread";
            if (Build.IS_USERDEBUG || Build.IS_ENG) {
                Preconditions.checkArgument(isMainThread, errorMessage);
            } else if (!isMainThread) {
                Slog.e(LOG_TAG, errorMessage);
            }
            synchronized (mLock) {
                if (!removeDisplayFromList(displayId)) {
                    return;
                }
                mA11yOverlayLayers.remove(displayId);
                if (mInputFilter != null) {
                    mInputFilter.onDisplayRemoved(displayId);
                }
                AccessibilityUserState userState = getCurrentUserStateLocked();
                if (displayId != Display.DEFAULT_DISPLAY) {
                    final List<AccessibilityServiceConnection> services = userState.mBoundServices;
                    for (int i = 0; i < services.size(); i++) {
                        AccessibilityServiceConnection boundClient = services.get(i);
                        boundClient.onDisplayRemoved(displayId);
                    }
                }
            }
            mMagnificationController.onDisplayRemoved(displayId);
            mA11yWindowManager.stopTrackingWindows(displayId);
        }

        @GuardedBy("mLock")
        private boolean removeDisplayFromList(int displayId) {
            for (int i = 0; i < mDisplaysList.size(); i++) {
                if (mDisplaysList.get(i).getDisplayId() == displayId) {
                    mDisplaysList.remove(i);
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onDisplayChanged(int displayId) {
            /* do nothing */
        }

        void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("Accessibility Display Listener:");
            pw.println("    SystemUI uid: " + mSystemUiUid);
            int size = mDisplaysList.size();
            pw.printf("    %d valid display%s: ", size, (size == 1 ? "" : "s"));
            for (int i = 0; i < size; i++) {
                pw.print(mDisplaysList.get(i).getDisplayId());
                if (i < size - 1) {
                    pw.print(", ");
                }
            }
            pw.println();
        }

        private boolean isValidDisplay(@Nullable Display display) {
            if (display == null || display.getType() == Display.TYPE_OVERLAY) {
                return false;
            }
            // Private virtual displays are created by the ap and is not allowed to access by other
            // aps. We assume we could ignore them.
            // The exceptional case is for bubbles. Because the bubbles use the activityView, and
            // the virtual display of the activityView is private, so if the owner UID of the
            // private virtual display is the one of system ui which creates the virtual display of
            // bubbles, then this private virtual display should track the windows.
            if (display.getType() == Display.TYPE_VIRTUAL
                    && (display.getFlags() & Display.FLAG_PRIVATE) != 0
                    && display.getOwnerUid() != mSystemUiUid) {
                return false;
            }
            return true;
        }
    }

    /** Represents an {@link AccessibilityManager} */
    class Client {
        final IAccessibilityManagerClient mCallback;
        final String[] mPackageNames;
        int mLastSentRelevantEventTypes;
        int mUid;
        int mDeviceId = DEVICE_ID_DEFAULT;

        private Client(IAccessibilityManagerClient callback, int clientUid,
                AccessibilityUserState userState, int deviceId) {
            mCallback = callback;
            mPackageNames = mPackageManager.getPackagesForUid(clientUid);
            mUid = clientUid;
            mDeviceId = deviceId;
            synchronized (mLock) {
                if (mProxyManager.isProxyedDeviceId(deviceId)) {
                    mLastSentRelevantEventTypes =
                            mProxyManager.computeRelevantEventTypesLocked(this);
                } else {
                    mLastSentRelevantEventTypes = computeRelevantEventTypesLocked(userState, this);
                }
            }
        }
    }

    private final class AccessibilityContentObserver extends ContentObserver {

        private final Uri mTouchExplorationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.TOUCH_EXPLORATION_ENABLED);

        private final Uri mMagnificationmSingleFingerTripleTapEnabledUri = Settings.Secure
                .getUriFor(Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED);

        private final Uri mMagnificationTwoFingerTripleTapEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_TWO_FINGER_TRIPLE_TAP_ENABLED);

        private final Uri mAutoclickEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_ENABLED);

        private final Uri mEnabledAccessibilityServicesUri = Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        private final Uri mTouchExplorationGrantedAccessibilityServicesUri = Settings.Secure
                .getUriFor(Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES);

        private final Uri mHighTextContrastUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED);

        private final Uri mAudioDescriptionByDefaultUri = Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_ACCESSIBILITY_AUDIO_DESCRIPTION_BY_DEFAULT);

        private final Uri mAccessibilitySoftKeyboardModeUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE);

        private final Uri mShowImeWithHardKeyboardUri = Settings.Secure.getUriFor(
                Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD);

        private final Uri mAccessibilityShortcutServiceIdUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE);

        private final Uri mAccessibilityButtonComponentIdUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT);

        private final Uri mAccessibilityButtonTargetsUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS);

        private final Uri mAccessibilityGestureTargetsUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_GESTURE_TARGETS);

        private final Uri mUserNonInteractiveUiTimeoutUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_NON_INTERACTIVE_UI_TIMEOUT_MS);

        private final Uri mUserInteractiveUiTimeoutUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_INTERACTIVE_UI_TIMEOUT_MS);

        private final Uri mMagnificationModeUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE);

        private final Uri mMagnificationCapabilityUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY);

        private final Uri mMagnificationFollowTypingUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_FOLLOW_TYPING_ENABLED);

        private final Uri mAlwaysOnMagnificationUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_ALWAYS_ON_ENABLED);

        private final Uri mMouseKeysUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_MOUSE_KEYS_ENABLED);

        private final Uri mNavigationModeUri = Settings.Secure.getUriFor(
                Settings.Secure.NAVIGATION_MODE);

        private final Uri mUserSetupCompleteUri = Settings.Secure.getUriFor(
                Settings.Secure.USER_SETUP_COMPLETE);

        public AccessibilityContentObserver(Handler handler) {
            super(handler);
        }

        public void register(ContentResolver contentResolver) {
            contentResolver.registerContentObserver(mTouchExplorationEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mMagnificationmSingleFingerTripleTapEnabledUri,
                    false, this, UserHandle.USER_ALL);
            if (Flags.enableMagnificationMultipleFingerMultipleTapGesture()) {
                contentResolver.registerContentObserver(mMagnificationTwoFingerTripleTapEnabledUri,
                        false, this, UserHandle.USER_ALL);
            }
            contentResolver.registerContentObserver(mAutoclickEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mEnabledAccessibilityServicesUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mTouchExplorationGrantedAccessibilityServicesUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mHighTextContrastUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAudioDescriptionByDefaultUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilitySoftKeyboardModeUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mShowImeWithHardKeyboardUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilityShortcutServiceIdUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilityButtonComponentIdUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilityButtonTargetsUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAccessibilityGestureTargetsUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mUserNonInteractiveUiTimeoutUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mUserInteractiveUiTimeoutUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mMagnificationModeUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mMagnificationCapabilityUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mMagnificationFollowTypingUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mAlwaysOnMagnificationUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mMouseKeysUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mNavigationModeUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mUserSetupCompleteUri, false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                // Profiles share the accessibility state of the parent. Therefore,
                // we are checking for changes only the parent settings.
                AccessibilityUserState userState = getCurrentUserStateLocked();

                if (mTouchExplorationEnabledUri.equals(uri)) {
                    if (readTouchExplorationEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mMagnificationmSingleFingerTripleTapEnabledUri.equals(uri)) {
                    if (readMagnificationEnabledSettingsLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (Flags.enableMagnificationMultipleFingerMultipleTapGesture()
                        && mMagnificationTwoFingerTripleTapEnabledUri.equals(uri)) {
                    if (readMagnificationTwoFingerTripleTapSettingsLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAutoclickEnabledUri.equals(uri)) {
                    if (readAutoclickEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mEnabledAccessibilityServicesUri.equals(uri)) {
                    if (readEnabledAccessibilityServicesLocked(userState)) {
                        mSecurityPolicy.onEnabledServicesChangedLocked(userState.mUserId,
                                userState.mEnabledServices);
                        userState.removeDisabledServicesFromTemporaryStatesLocked();
                        onUserStateChangedLocked(userState);
                    }
                } else if (mTouchExplorationGrantedAccessibilityServicesUri.equals(uri)) {
                    if (readTouchExplorationGrantedAccessibilityServicesLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mHighTextContrastUri.equals(uri)) {
                    if (readHighTextContrastEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAudioDescriptionByDefaultUri.equals(uri)) {
                    if (readAudioDescriptionEnabledSettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilitySoftKeyboardModeUri.equals(uri)
                        || mShowImeWithHardKeyboardUri.equals(uri)) {
                    userState.reconcileSoftKeyboardModeWithSettingsLocked();
                } else if (mAccessibilityShortcutServiceIdUri.equals(uri)) {
                    if (readAccessibilityShortcutTargetsLocked(userState, HARDWARE)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilityButtonComponentIdUri.equals(uri)) {
                    if (readAccessibilityButtonTargetComponentLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilityButtonTargetsUri.equals(uri)) {
                    if (readAccessibilityShortcutTargetsLocked(userState, SOFTWARE)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilityGestureTargetsUri.equals(uri)) {
                    if (readAccessibilityShortcutTargetsLocked(userState, GESTURE)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mUserNonInteractiveUiTimeoutUri.equals(uri)
                        || mUserInteractiveUiTimeoutUri.equals(uri)) {
                    readUserRecommendedUiTimeoutSettingsLocked(userState);
                } else if (mMagnificationModeUri.equals(uri)) {
                    if (readMagnificationModeForDefaultDisplayLocked(userState)) {
                        updateMagnificationModeChangeSettingsLocked(userState,
                                Display.DEFAULT_DISPLAY);
                    }
                } else if (mMagnificationCapabilityUri.equals(uri)) {
                    if (readMagnificationCapabilitiesLocked(userState)) {
                        updateMagnificationCapabilitiesSettingsChangeLocked(userState);
                    }
                } else if (mMagnificationFollowTypingUri.equals(uri)) {
                    readMagnificationFollowTypingLocked(userState);
                } else if (mAlwaysOnMagnificationUri.equals(uri)) {
                    readAlwaysOnMagnificationLocked(userState);
                } else if (mMouseKeysUri.equals(uri)) {
                    if (readMouseKeysEnabledLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mNavigationModeUri.equals(uri) || mUserSetupCompleteUri.equals(uri)) {
                    updateShortcutsForCurrentNavigationMode();
                }
            }
        }
    }

    private void updateMagnificationCapabilitiesSettingsChangeLocked(
            AccessibilityUserState userState) {
        final ArrayList<Display> displays = getValidDisplayList();
        for (int i = 0; i < displays.size(); i++) {
            final int displayId = displays.get(i).getDisplayId();
            if (fallBackMagnificationModeSettingsLocked(userState, displayId)) {
                updateMagnificationModeChangeSettingsLocked(userState, displayId);
            }
        }
        updateMagnificationConnectionIfNeeded(userState);
        // Remove magnification button UI when the magnification capability is not all mode or
        // magnification is disabled.
        if (!(userState.isMagnificationSingleFingerTripleTapEnabledLocked()
                || (Flags.enableMagnificationMultipleFingerMultipleTapGesture()
                && userState.isMagnificationTwoFingerTripleTapEnabledLocked())
                || userState.isShortcutMagnificationEnabledLocked())
                || userState.getMagnificationCapabilitiesLocked()
                != Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL) {

            for (int i = 0; i < displays.size(); i++) {
                final int displayId = displays.get(i).getDisplayId();
                getMagnificationConnectionManager().removeMagnificationButton(displayId);
            }
        }
    }

    private boolean fallBackMagnificationModeSettingsLocked(AccessibilityUserState userState,
            int displayId) {
        if (userState.isValidMagnificationModeLocked(displayId)) {
            return false;
        }
        Slog.w(LOG_TAG, "displayId " + displayId + ", invalid magnification mode:"
                + userState.getMagnificationModeLocked(displayId));
        final int capabilities = userState.getMagnificationCapabilitiesLocked();
        userState.setMagnificationModeLocked(displayId, capabilities);
        if (displayId == Display.DEFAULT_DISPLAY) {
            persistMagnificationModeSettingsLocked(capabilities);
        }
        return true;
    }

    private void persistMagnificationModeSettingsLocked(int mode) {
        BackgroundThread.getHandler().post(() -> {
            final long identity = Binder.clearCallingIdentity();
            try {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, mode, mCurrentUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        });
    }

    /**
     * Gets the magnification mode of the specified display.
     *
     * @param displayId The logical displayId.
     * @return magnification mode. It's either ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN or
     * ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW.
     */
    public int getMagnificationMode(int displayId) {
        synchronized (mLock) {
            return getCurrentUserStateLocked().getMagnificationModeLocked(displayId);
        }
    }

    // Only the value of the default display is from user settings because not each of displays has
    // a unique id.
    private boolean readMagnificationModeForDefaultDisplayLocked(AccessibilityUserState userState) {
        final int magnificationMode = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN, userState.mUserId);
        if (magnificationMode != userState.getMagnificationModeLocked(Display.DEFAULT_DISPLAY)) {
            userState.setMagnificationModeLocked(Display.DEFAULT_DISPLAY, magnificationMode);
            return true;
        }
        return false;
    }

    private boolean readMagnificationCapabilitiesLocked(AccessibilityUserState userState) {
        final int capabilities = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN, userState.mUserId);
        if (capabilities != userState.getMagnificationCapabilitiesLocked()) {
            userState.setMagnificationCapabilitiesLocked(capabilities);
            mMagnificationController.setMagnificationCapabilities(capabilities);
            return true;
        }
        return false;
    }

    boolean readMagnificationFollowTypingLocked(AccessibilityUserState userState) {
        final boolean followTypeEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_FOLLOW_TYPING_ENABLED,
                1, userState.mUserId) == 1;
        if (followTypeEnabled != userState.isMagnificationFollowTypingEnabled()) {
            userState.setMagnificationFollowTypingEnabled(followTypeEnabled);
            mMagnificationController.setMagnificationFollowTypingEnabled(followTypeEnabled);
            return true;
        }
        return false;
    }

    /**
     * Called when always on magnification feature flag flips to check if the feature should be
     * enabled for current user state.
     */
    public void updateAlwaysOnMagnification() {
        synchronized (mLock) {
            readAlwaysOnMagnificationLocked(getCurrentUserState());
        }
    }

    @GuardedBy("mLock")
    boolean readAlwaysOnMagnificationLocked(AccessibilityUserState userState) {
        final boolean isSettingsAlwaysOnEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_ALWAYS_ON_ENABLED,
                1, userState.mUserId) == 1;
        final boolean isAlwaysOnFeatureFlagEnabled = mMagnificationController
                .isAlwaysOnMagnificationFeatureFlagEnabled();
        final boolean isAlwaysOnEnabled = isAlwaysOnFeatureFlagEnabled && isSettingsAlwaysOnEnabled;
        if (isAlwaysOnEnabled != userState.isAlwaysOnMagnificationEnabled()) {
            userState.setAlwaysOnMagnificationEnabled(isAlwaysOnEnabled);
            mMagnificationController.setAlwaysOnMagnificationEnabled(isAlwaysOnEnabled);
            return true;
        }
        return false;
    }

    boolean readMouseKeysEnabledLocked(AccessibilityUserState userState) {
        if (!keyboardA11yMouseKeys()) {
            return false;
        }
        final boolean isMouseKeysEnabled =
                Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MOUSE_KEYS_ENABLED, 0, userState.mUserId) == 1;
        if (isMouseKeysEnabled != userState.isMouseKeysEnabled()) {
            userState.setMouseKeysEnabled(isMouseKeysEnabled);
            return true;
        }
        return false;
    }

    @Override
    public void setGestureDetectionPassthroughRegion(int displayId, Region region) {
        mMainHandler.sendMessage(
                obtainMessage(
                        AccessibilityManagerService::setGestureDetectionPassthroughRegionInternal,
                        this,
                        displayId,
                        region));
    }

    @Override
    public void setTouchExplorationPassthroughRegion(int displayId, Region region) {
        mMainHandler.sendMessage(
                obtainMessage(
                        AccessibilityManagerService::setTouchExplorationPassthroughRegionInternal,
                        this,
                        displayId,
                        region));
    }

    private void setTouchExplorationPassthroughRegionInternal(int displayId, Region region) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.setTouchExplorationPassthroughRegion(displayId, region);
            }
        }
    }

    private void setGestureDetectionPassthroughRegionInternal(int displayId, Region region) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.setGestureDetectionPassthroughRegion(displayId, region);
            }
        }
    }

    @Override
    public void setServiceDetectsGesturesEnabled(int displayId, boolean mode) {
        mMainHandler.sendMessage(
                obtainMessage(AccessibilityManagerService::setServiceDetectsGesturesInternal, this,
                        displayId, mode));
    }

    private void setServiceDetectsGesturesInternal(int displayId, boolean mode) {
        synchronized (mLock) {
            getCurrentUserStateLocked().setServiceDetectsGesturesEnabled(displayId, mode);
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.setServiceDetectsGesturesEnabled(displayId, mode);
            }
        }
    }

    @Override
    public void requestTouchExploration(int displayId) {
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::requestTouchExplorationInternal, this, displayId));
    }

    private void requestTouchExplorationInternal(int displayId) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.requestTouchExploration(displayId);
            }
        }
    }

    @Override
    public void requestDragging(int displayId, int pointerId) {
        mMainHandler.sendMessage(obtainMessage(AccessibilityManagerService::requestDraggingInternal,
                this, displayId, pointerId));
    }

    private void requestDraggingInternal(int displayId, int pointerId) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.requestDragging(displayId, pointerId);
            }
        }
    }

    @Override
    public void requestDelegating(int displayId) {
        mMainHandler.sendMessage(
                obtainMessage(
                        AccessibilityManagerService::requestDelegatingInternal, this, displayId));
    }

    private void requestDelegatingInternal(int displayId) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.requestDelegating(displayId);
            }
        }
    }

    @Override
    public void onDoubleTap(int displayId) {
        mMainHandler.sendMessage(obtainMessage(AccessibilityManagerService::onDoubleTapInternal,
                this, displayId));
    }

    private void onDoubleTapInternal(int displayId) {
        AccessibilityInputFilter inputFilter = null;
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                inputFilter = mInputFilter;
            }
        }
        if (inputFilter != null) {
            inputFilter.onDoubleTap(displayId);
        }
    }

    @Override
    public void onDoubleTapAndHold(int displayId) {
        mMainHandler
                .sendMessage(obtainMessage(AccessibilityManagerService::onDoubleTapAndHoldInternal,
                        this, displayId));
    }

    @Override
    public void requestImeLocked(AbstractAccessibilityServiceConnection connection) {
        if (!(connection instanceof AccessibilityServiceConnection)
                || (connection instanceof ProxyAccessibilityServiceConnection)) {
            if (DEBUG) {
                Slog.d(LOG_TAG, "The connection should be a real connection but was "
                        + connection);
            }
            return;
        }
        AccessibilityServiceConnection realConnection = (AccessibilityServiceConnection) connection;
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::createSessionForConnection, this, realConnection));
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::bindAndStartInputForConnection, this, realConnection));
    }

    @Override
    public void unbindImeLocked(AbstractAccessibilityServiceConnection connection) {
        if (!(connection instanceof AccessibilityServiceConnection)
                || (connection instanceof ProxyAccessibilityServiceConnection)) {
            if (DEBUG) {
                Slog.d(LOG_TAG, "The connection should be a real connection but was "
                        + connection);
            }
            return;
        }
        AccessibilityServiceConnection realConnection = (AccessibilityServiceConnection) connection;
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::unbindInputForConnection, this, realConnection));
    }

    private void createSessionForConnection(AccessibilityServiceConnection connection) {
        synchronized (mLock) {
            if (mInputSessionRequested) {
                connection.createImeSessionLocked();
            }
        }
    }

    private void bindAndStartInputForConnection(AccessibilityServiceConnection connection) {
        synchronized (mLock) {
            if (mInputBound) {
                connection.bindInputLocked();
                connection.startInputLocked(mRemoteInputConnection, mEditorInfo, mRestarting);
            }
        }
    }

    private void unbindInputForConnection(AccessibilityServiceConnection connection) {
        InputMethodManagerInternal.get()
                .unbindAccessibilityFromCurrentClient(connection.mId, connection.mUserId);
        synchronized (mLock) {
            connection.unbindInputLocked();
        }
    }

    private void onDoubleTapAndHoldInternal(int displayId) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.onDoubleTapAndHold(displayId);
            }
        }
    }

    private void updateFocusAppearanceDataLocked(AccessibilityUserState userState) {
        if (userState.mUserId != mCurrentUserId) {
            return;
        }
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_SERVICE_CLIENT)) {
            mTraceManager.logTrace(LOG_TAG + ".updateFocusAppearanceDataLocked",
                    FLAGS_ACCESSIBILITY_SERVICE_CLIENT, "userState=" + userState);
        }
        mMainHandler.post(() -> {
            broadcastToClients(userState, ignoreRemoteException(client -> {
                if (!mProxyManager.isProxyedDeviceId(client.mDeviceId)) {
                    client.mCallback.setFocusAppearance(userState.getFocusStrokeWidthLocked(),
                            userState.getFocusColorLocked());
                }
            }));
        });

    }

    public AccessibilityTraceManager getTraceManager() {
        return mTraceManager;
    }

    /**
     * Bind input for accessibility services which request ime capabilities.
     */
    public void scheduleBindInput() {
        mMainHandler.sendMessage(obtainMessage(AccessibilityManagerService::bindInput, this));
    }

    private void bindInput() {
        synchronized (mLock) {
            // Keep records of these in case new Accessibility Services are enabled.
            mInputBound = true;
            AccessibilityUserState userState = getCurrentUserStateLocked();
            for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
                if (service.requestImeApis()) {
                    service.bindInputLocked();
                }
            }
        }
    }

    /**
     * Unbind input for accessibility services which request ime capabilities.
     */
    public void scheduleUnbindInput() {
        mMainHandler.sendMessage(obtainMessage(AccessibilityManagerService::unbindInput, this));
    }

    private void unbindInput() {
        synchronized (mLock) {
            mInputBound = false;
            AccessibilityUserState userState = getCurrentUserStateLocked();
            for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
                if (service.requestImeApis()) {
                    service.unbindInputLocked();
                }
            }
        }
    }

    /**
     * Start input for accessibility services which request ime capabilities.
     */
    public void scheduleStartInput(IRemoteAccessibilityInputConnection connection,
            EditorInfo editorInfo, boolean restarting) {
        mMainHandler.sendMessage(obtainMessage(AccessibilityManagerService::startInput, this,
                connection, editorInfo, restarting));
    }

    private void startInput(IRemoteAccessibilityInputConnection connection, EditorInfo editorInfo,
            boolean restarting) {
        synchronized (mLock) {
            // Keep records of these in case new Accessibility Services are enabled.
            mRemoteInputConnection = connection;
            mEditorInfo = editorInfo;
            mRestarting = restarting;
            AccessibilityUserState userState = getCurrentUserStateLocked();
            for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
                if (service.requestImeApis()) {
                    service.startInputLocked(connection, editorInfo, restarting);
                }
            }
        }
    }

    /**
     * Request input sessions from all accessibility services which request ime capabilities and
     * whose id is not in the ignoreSet
     */
    public void scheduleCreateImeSession(ArraySet<Integer> ignoreSet) {
        mMainHandler.sendMessage(obtainMessage(AccessibilityManagerService::createImeSession,
                this, ignoreSet));
    }

    private void createImeSession(ArraySet<Integer> ignoreSet) {
        synchronized (mLock) {
            mInputSessionRequested = true;
            AccessibilityUserState userState = getCurrentUserStateLocked();
            for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
                if ((!ignoreSet.contains(service.mId)) && service.requestImeApis()) {
                    service.createImeSessionLocked();
                }
            }
        }
    }

    /**
     * Enable or disable the sessions.
     *
     * @param sessions Sessions to enable or disable.
     * @param enabled True if enable the sessions or false if disable the sessions.
     */
    public void scheduleSetImeSessionEnabled(SparseArray<IAccessibilityInputMethodSession> sessions,
            boolean enabled) {
        mMainHandler.sendMessage(obtainMessage(AccessibilityManagerService::setImeSessionEnabled,
                this, sessions, enabled));
    }

    private void setImeSessionEnabled(SparseArray<IAccessibilityInputMethodSession> sessions,
            boolean enabled) {
        synchronized (mLock) {
            AccessibilityUserState userState = getCurrentUserStateLocked();
            for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
                if (sessions.contains(service.mId) && service.requestImeApis()) {
                    service.setImeSessionEnabledLocked(sessions.get(service.mId), enabled);
                }
            }
        }
    }

    @Override
    @RequiresNoPermission
    public void registerUserInitializationCompleteCallback(
            IUserInitializationCompleteCallback callback) {
        synchronized (mLock) {
            mUserInitializationCompleteCallbacks.add(callback);
        }
    }

    @Override
    @RequiresNoPermission
    public void unregisterUserInitializationCompleteCallback(
            IUserInitializationCompleteCallback callback) {
        synchronized (mLock) {
            mUserInitializationCompleteCallbacks.remove(callback);
        }
    }

    @Override
    @EnforcePermission(INJECT_EVENTS)
    public void injectInputEventToInputFilter(InputEvent event) {
        injectInputEventToInputFilter_enforcePermission();
        synchronized (mLock) {
            final long endMillis =
                    SystemClock.uptimeMillis() + WAIT_INPUT_FILTER_INSTALL_TIMEOUT_MS;
            while (!mInputFilterInstalled && (SystemClock.uptimeMillis() < endMillis)) {
                try {
                    mLock.wait(endMillis - SystemClock.uptimeMillis());
                } catch (InterruptedException ie) {
                    /* ignore */
                }
            }
        }

        if (mInputFilterInstalled && mInputFilter != null) {
            mInputFilter.onInputEvent(event,
                    WindowManagerPolicy.FLAG_PASS_TO_USER | WindowManagerPolicy.FLAG_INJECTED);
        } else {
            Slog.w(LOG_TAG, "Cannot injectInputEventToInputFilter because the "
                    + "AccessibilityInputFilter is not installed.");
        }
    }

    private final class SendWindowStateChangedEventRunnable implements Runnable {

        private final AccessibilityEvent mPendingEvent;
        private final int mWindowId;

        SendWindowStateChangedEventRunnable(@NonNull AccessibilityEvent event) {
            mPendingEvent = event;
            mWindowId = event.getWindowId();
        }

        @Override
        public void run() {
            synchronized (mLock) {
                Slog.w(LOG_TAG, " wait for adding window timeout: " + mWindowId);
                sendPendingEventLocked();
            }
        }

        private void sendPendingEventLocked() {
            mSendWindowStateChangedEventRunnables.remove(this);
            dispatchAccessibilityEventLocked(mPendingEvent);
        }

        private int getWindowId() {
            return mWindowId;
        }
    }

    @VisibleForTesting
    public static class ManagerPackageMonitor extends PackageMonitor {
        private final AccessibilityManagerService mManagerService;
        public ManagerPackageMonitor(AccessibilityManagerService managerService) {
            super(/* supportsPackageRestartQuery = */ true);
            mManagerService = managerService;
        }

        @Override
        public void onSomePackagesChanged() {
            if (mManagerService.mTraceManager.isA11yTracingEnabledForTypes(
                    FLAGS_PACKAGE_BROADCAST_RECEIVER)) {
                mManagerService.mTraceManager.logTrace(LOG_TAG + ".PM.onSomePackagesChanged",
                        FLAGS_PACKAGE_BROADCAST_RECEIVER);
            }

            final int userId = getChangingUserId();
            List<AccessibilityServiceInfo> parsedAccessibilityServiceInfos = mManagerService
                    .parseAccessibilityServiceInfos(userId);
            List<AccessibilityShortcutInfo> parsedAccessibilityShortcutInfos = mManagerService
                    .parseAccessibilityShortcutInfos(userId);
            synchronized (mManagerService.getLock()) {
                // Only the profile parent can install accessibility services.
                // Therefore we ignore packages from linked profiles.
                if (userId != mManagerService.getCurrentUserIdLocked()) {
                    return;
                }

                // Only continue setting up the packages if the service has been initialized.
                // See: b/340927041
                if (Flags.skipPackageChangeBeforeUserSwitch()
                        && !mManagerService.isServiceInitializedLocked()) {
                    Slog.w(LOG_TAG,
                            "onSomePackagesChanged: service not initialized, skip the callback.");
                    return;
                }
                mManagerService.onSomePackagesChangedLocked(parsedAccessibilityServiceInfos,
                        parsedAccessibilityShortcutInfos);
            }
        }

        @Override
        public void onPackageUpdateFinished(String packageName, int uid) {
            // The package should already be removed from mBoundServices, and added into
            // mBindingServices in binderDied() during updating. Remove services from  this
            // package from mBindingServices, and then update the user state to re-bind new
            // versions of them.
            if (mManagerService.mTraceManager.isA11yTracingEnabledForTypes(
                    FLAGS_PACKAGE_BROADCAST_RECEIVER)) {
                mManagerService.mTraceManager.logTrace(
                        LOG_TAG + ".PM.onPackageUpdateFinished",
                        FLAGS_PACKAGE_BROADCAST_RECEIVER,
                        "packageName=" + packageName + ";uid=" + uid);
            }
            final int userId = getChangingUserId();
            List<AccessibilityServiceInfo> parsedAccessibilityServiceInfos = mManagerService
                    .parseAccessibilityServiceInfos(userId);
            List<AccessibilityShortcutInfo> parsedAccessibilityShortcutInfos =
                    mManagerService.parseAccessibilityShortcutInfos(userId);
            synchronized (mManagerService.getLock()) {
                if (userId != mManagerService.getCurrentUserIdLocked()) {
                    return;
                }
                final AccessibilityUserState userState = mManagerService.getUserStateLocked(userId);
                final boolean reboundAService = userState.getBindingServicesLocked().removeIf(
                        component -> component != null
                                && component.getPackageName().equals(packageName))
                        || userState.mCrashedServices.removeIf(component -> component != null
                        && component.getPackageName().equals(packageName));
                // Reloads the installed services info to make sure the rebound service could
                // get a new one.
                userState.mInstalledServices.clear();
                final boolean configurationChanged;
                configurationChanged = mManagerService.readConfigurationForUserStateLocked(
                        userState, parsedAccessibilityServiceInfos,
                        parsedAccessibilityShortcutInfos);
                if (reboundAService || configurationChanged) {
                    mManagerService.onUserStateChangedLocked(userState);
                }
                // Passing 0 for restoreFromSdkInt to have this migration check execute each
                // time. It can make sure a11y button settings are correctly if there's an a11y
                // service updated and modifies the a11y button configuration.
                mManagerService.migrateAccessibilityButtonSettingsIfNecessaryLocked(
                        userState, packageName, /* restoreFromSdkInt = */0);
            }
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            if (mManagerService.mTraceManager.isA11yTracingEnabledForTypes(
                    FLAGS_PACKAGE_BROADCAST_RECEIVER)) {
                mManagerService.mTraceManager.logTrace(LOG_TAG + ".PM.onPackageRemoved",
                        FLAGS_PACKAGE_BROADCAST_RECEIVER,
                        "packageName=" + packageName + ";uid=" + uid);
            }

            synchronized (mManagerService.getLock()) {
                final int userId = getChangingUserId();
                // Only the profile parent can install accessibility services.
                // Therefore we ignore packages from linked profiles.
                if (userId != mManagerService.getCurrentUserIdLocked()) {
                    return;
                }
                mManagerService.onPackageRemovedLocked(packageName);
            }
        }

        /**
         * Handles instances in which a package or packages have forcibly stopped.
         *
         * @param intent intent containing package event information.
         * @param uid linux process user id (different from Android user id).
         * @param packages array of package names that have stopped.
         * @param doit whether to try and handle the stop or just log the trace.
         *
         * @return {@code true} if doit == {@code false}
         * and at least one of the provided packages is enabled.
         * In any other case, returns {@code false}.
         * This is to indicate whether further action is necessary.
         */
        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages,
                int uid, boolean doit) {
            if (mManagerService.mTraceManager.isA11yTracingEnabledForTypes(
                    FLAGS_PACKAGE_BROADCAST_RECEIVER)) {
                mManagerService.mTraceManager.logTrace(LOG_TAG + ".PM.onHandleForceStop",
                        FLAGS_PACKAGE_BROADCAST_RECEIVER,
                        "intent=" + intent + ";packages=" + Arrays.toString(packages)
                                + ";uid=" + uid + ";doit=" + doit);
            }
            synchronized (mManagerService.getLock()) {
                final int userId = getChangingUserId();
                // Only the profile parent can install accessibility services.
                // Therefore we ignore packages from linked profiles.
                if (userId != mManagerService.getCurrentUserIdLocked()) {
                    return false;
                }
                final AccessibilityUserState userState = mManagerService.getUserStateLocked(userId);

                if (Flags.managerPackageMonitorLogicFix()) {
                    if (!doit) {
                        // if we're not handling the stop here, then we only need to know
                        // if any of the force-stopped packages are currently enabled.
                        return userState.mEnabledServices.stream().anyMatch(
                                (comp) -> Arrays.stream(packages).anyMatch(
                                        (pkg) -> pkg.equals(comp.getPackageName()))
                        );
                    } else if (mManagerService.onPackagesForceStoppedLocked(packages, userState)) {
                        mManagerService.onUserStateChangedLocked(userState);
                    }
                    return false;
                } else {
                    // this old logic did not properly indicate when base packageMonitor's routine
                    // should handle stopping the package.
                    if (doit && mManagerService.onPackagesForceStoppedLocked(packages, userState)) {
                        mManagerService.onUserStateChangedLocked(userState);
                        return false;
                    } else {
                        return true;
                    }
                }
            }
        }

        @Override
        public boolean onPackageChanged(String packageName, int uid, String[] components) {
            // We care about all package changes, not just the whole package itself which is
            // default behavior.
            return true;
        }
    }

    void sendPendingWindowStateChangedEventsForAvailableWindowLocked(int windowId) {
        final int eventSize =  mSendWindowStateChangedEventRunnables.size();
        for (int i = eventSize - 1; i >= 0; i--) {
            final SendWindowStateChangedEventRunnable runnable =
                    mSendWindowStateChangedEventRunnables.get(i);
            if (runnable.getWindowId() == windowId) {
                mMainHandler.removeCallbacks(runnable);
                runnable.sendPendingEventLocked();
            }
        }
    }

    /**
     * Postpones the {@link AccessibilityEvent} with
     * {@link AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED}
     * which doesn't have the corresponding window until the window is added or timeout.
     *
     * @return {@code true} if the event is postponed.
     */
    private boolean postponeWindowStateEvent(AccessibilityEvent event) {
        synchronized (mLock) {
            final int resolvedWindowId = mA11yWindowManager.resolveParentWindowIdLocked(
                    event.getWindowId());
            if (mA11yWindowManager.findWindowInfoByIdLocked(resolvedWindowId) != null) {
                return false;
            }
            final SendWindowStateChangedEventRunnable pendingRunnable =
                    new SendWindowStateChangedEventRunnable(new AccessibilityEvent(event));
            mMainHandler.postDelayed(pendingRunnable,
                    POSTPONE_WINDOW_STATE_CHANGED_EVENT_TIMEOUT_MILLIS);
            mSendWindowStateChangedEventRunnables.add(pendingRunnable);
            return true;
        }
    }

    /** Used to attach accessibility overlays from the system itself i.e. magnification. */
    @EnforcePermission(INTERNAL_SYSTEM_WINDOW)
    @Override
    public void attachAccessibilityOverlayToDisplay(int displayId, SurfaceControl sc) {
        attachAccessibilityOverlayToDisplay_enforcePermission();
        mMainHandler.sendMessage(
                obtainMessage(
                        AccessibilityManagerService::attachAccessibilityOverlayToDisplayInternal,
                        this,
                        -1,
                        displayId,
                        sc,
                        null));
    }

    /** Called by services to attach accessibility overlays. */
    @Override
    public void attachAccessibilityOverlayToDisplay(
            int interactionId,
            int displayId,
            SurfaceControl sc,
            IAccessibilityInteractionConnectionCallback callback) {
        mMainHandler.sendMessage(
                obtainMessage(
                        AccessibilityManagerService::attachAccessibilityOverlayToDisplayInternal,
                        this,
                        interactionId,
                        displayId,
                        sc,
                        callback));
    }

    @VisibleForTesting
    int getShortcutTypeForGenericShortcutCalls(int userId) {
        int navigationMode = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.NAVIGATION_MODE, -1, userId);
        if (android.provider.Flags.a11yStandaloneGestureEnabled()) {
            return (navigationMode == NAV_BAR_MODE_GESTURAL) ? GESTURE : SOFTWARE;
        } else {
            return SOFTWARE;
        }
    }

    void attachAccessibilityOverlayToDisplayInternal(
            int interactionId,
            int displayId,
            SurfaceControl sc,
            IAccessibilityInteractionConnectionCallback callback) {
        int result;
        if (!mA11yOverlayLayers.contains(displayId)) {
            mA11yOverlayLayers.put(displayId, mWindowManagerService.getA11yOverlayLayer(displayId));
        }
        SurfaceControl parent = mA11yOverlayLayers.get(displayId);
        if (parent == null) {
            Slog.e(LOG_TAG, "Unable to get accessibility overlay SurfaceControl.");
            mA11yOverlayLayers.remove(displayId);
            result = AccessibilityService.OVERLAY_RESULT_INVALID;
        } else {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.reparent(sc, parent).setTrustedOverlay(sc, true).apply();
            t.close();
            result = AccessibilityService.OVERLAY_RESULT_SUCCESS;
        }

        if (callback != null) {
            // Send the result back to the service.
            try {
                callback.sendAttachOverlayResult(result, interactionId);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Exception while attaching overlay.", re);
                // the other side will time out
            }
        }
    }

    /**
     * Bypasses the timeout restriction if volume key shortcut assigned.
     */
    private void skipVolumeShortcutDialogTimeoutRestriction(int userId) {
        persistIntToSetting(
                userId,
                Settings.Secure.SKIP_ACCESSIBILITY_SHORTCUT_DIALOG_TIMEOUT_RESTRICTION,
                /* true */ 1);
    }

    /**
     * Log the metric when the user add/remove qs shortcut for accessibility features. Use the
     * callingUid to know where the users configure the a11y qs shortcuts.
     */
    private void logMetricForQsShortcutConfiguration(boolean enable, int numOfFeatures) {
        if (numOfFeatures <= 0) {
            // Skip logging metric if no a11y features are configured
            return;
        }
        String metricId = enable ? METRIC_ID_QS_SHORTCUT_ADD : METRIC_ID_QS_SHORTCUT_REMOVE;
        Counter.logIncrementWithUid(metricId, Binder.getCallingUid(), numOfFeatures);
    }

    private void assertNoTapShortcut(@UserShortcutType int shortcutType) {
        if ((shortcutType & (TRIPLETAP | TWOFINGER_DOUBLETAP)) != 0) {
            throw new IllegalArgumentException("Tap shortcuts are not supported.");
        }
    }
}
