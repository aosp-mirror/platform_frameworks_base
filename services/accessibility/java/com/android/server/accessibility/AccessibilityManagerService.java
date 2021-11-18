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

import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_MANAGER;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_MANAGER_CLIENT;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_SERVICE_CLIENT;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_FINGERPRINT;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_INPUT_FILTER;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_PACKAGE_BROADCAST_RECEIVER;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_USER_BROADCAST_RECEIVER;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_WINDOW_MAGNIFICATION_CONNECTION;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_WINDOW_MANAGER_INTERNAL;
import static android.provider.Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_SHORTCUT_KEY;
import static android.view.accessibility.AccessibilityManager.ShortcutType;

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.internal.accessibility.common.ShortcutConstants.CHOOSER_PACKAGE_NAME;
import static com.android.internal.accessibility.util.AccessibilityStatsLogUtils.logAccessibilityShortcutActivated;
import static com.android.internal.util.FunctionalUtils.ignoreRemoteException;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.accessibility.AccessibilityUserState.doesShortcutTargetsStringContain;

import android.Manifest;
import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.MagnificationConfig;
import android.accessibilityservice.TouchInteractionController;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RemoteAction;
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
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
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
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
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
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindow;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;

import com.android.internal.R;
import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.internal.accessibility.AccessibilityShortcutController.ToggleableFrameworkFeatureInfo;
import com.android.internal.accessibility.dialog.AccessibilityButtonChooserActivity;
import com.android.internal.accessibility.dialog.AccessibilityShortcutChooserActivity;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IntPair;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodSession;
import com.android.server.AccessibilityManagerInternal;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.accessibility.magnification.MagnificationController;
import com.android.server.accessibility.magnification.MagnificationProcessor;
import com.android.server.accessibility.magnification.MagnificationScaleProvider;
import com.android.server.accessibility.magnification.WindowMagnificationManager;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

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
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

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
        SystemActionPerformer.SystemActionsChangedListener {

    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "AccessibilityManagerService";

    // TODO: This is arbitrary. When there is time implement this by watching
    //       when that accessibility services are bound.
    private static final int WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS = 3000;

    // TODO: Restructure service initialization so services aren't connected before all of
    //       their capabilities are ready.
    private static final int WAIT_MOTION_INJECTOR_TIMEOUT_MILLIS = 1000;

    private static final String FUNCTION_REGISTER_UI_TEST_AUTOMATION_SERVICE =
        "registerUiTestAutomationService";

    private static final String TEMPORARY_ENABLE_ACCESSIBILITY_UNTIL_KEYGUARD_REMOVED =
            "temporaryEnableAccessibilityStateUntilKeyguardRemoved";

    private static final String GET_WINDOW_TOKEN = "getWindowToken";

    private static final String SET_PIP_ACTION_REPLACEMENT =
            "setPictureInPictureActionReplacingConnection";

    private static final char COMPONENT_NAME_SEPARATOR = ':';

    private static final int OWN_PROCESS_ID = android.os.Process.myPid();

    public static final int INVALID_SERVICE_ID = -1;

    // Each service has an ID. Also provide one for magnification gesture handling
    public static final int MAGNIFICATION_GESTURE_HANDLER_ID = 0;

    private static int sIdCounter = MAGNIFICATION_GESTURE_HANDLER_ID + 1;

    private final Context mContext;

    private final Object mLock = new Object();

    private final SimpleStringSplitter mStringColonSplitter =
            new SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);

    private final Rect mTempRect = new Rect();
    private final Rect mTempRect1 = new Rect();

    private final PackageManager mPackageManager;

    private final PowerManager mPowerManager;

    private final WindowManagerInternal mWindowManagerService;

    private final AccessibilitySecurityPolicy mSecurityPolicy;

    private final AccessibilityWindowManager mA11yWindowManager;

    private final AccessibilityDisplayListener mA11yDisplayListener;

    private final ActivityTaskManagerInternal mActivityTaskManagerService;

    private final MagnificationController mMagnificationController;
    private final MagnificationProcessor mMagnificationProcessor;

    private final MainHandler mMainHandler;

    // Lazily initialized - access through getSystemActionPerformer()
    private SystemActionPerformer mSystemActionPerformer;

    private InteractionBridge mInteractionBridge;

    private AlertDialog mEnableTouchExplorationDialog;

    private AccessibilityInputFilter mInputFilter;

    private boolean mHasInputFilter;

    private KeyEventDispatcher mKeyEventDispatcher;

    private SparseArray<MotionEventInjector> mMotionEventInjectors;

    private FingerprintGestureDispatcher mFingerprintGestureDispatcher;

    private final Set<ComponentName> mTempComponentNameSet = new HashSet<>();

    private final List<AccessibilityServiceInfo> mTempAccessibilityServiceInfoList =
            new ArrayList<>();

    private final IntArray mTempIntArray = new IntArray(0);

    private final RemoteCallbackList<IAccessibilityManagerClient> mGlobalClients =
            new RemoteCallbackList<>();

    @VisibleForTesting
    final SparseArray<AccessibilityUserState> mUserStates = new SparseArray<>();

    private final UiAutomationManager mUiAutomationManager = new UiAutomationManager(mLock);
    private final AccessibilityTraceManager mTraceManager;
    private final CaptioningManagerImpl mCaptioningManagerImpl;

    private int mCurrentUserId = UserHandle.USER_SYSTEM;

    //TODO: Remove this hack
    private boolean mInitialized;

    private Point mTempPoint = new Point();
    private boolean mIsAccessibilityButtonShown;

    private InputBinding mInputBinding;
    IBinder mStartInputToken;
    IInputContext mInputContext;
    EditorInfo mEditorInfo;
    boolean mRestarting;
    boolean mInputSessionRequested;

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
        public void setImeSessionEnabled(SparseArray<IInputMethodSession> sessions,
                boolean enabled) {
            mService.scheduleSetImeSessionEnabled(sessions, enabled);
        }

        @Override
        public void unbindInput() {
            mService.scheduleUnbindInput();
        }

        @Override
        public void bindInput(InputBinding binding) {
            mService.scheduleBindInput(binding);
        }

        @Override
        public void createImeSession(ArraySet<Integer> ignoreSet) {
            mService.scheduleCreateImeSession(ignoreSet);
        }

        @Override
        public void startInput(IBinder startInputToken, IInputContext inputContext,
                EditorInfo editorInfo, boolean restarting) {
            mService.scheduleStartInput(startInputToken, inputContext, editorInfo, restarting);
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
            PackageManager packageManager,
            AccessibilitySecurityPolicy securityPolicy,
            SystemActionPerformer systemActionPerformer,
            AccessibilityWindowManager a11yWindowManager,
            AccessibilityDisplayListener a11yDisplayListener,
            MagnificationController magnificationController,
            @Nullable AccessibilityInputFilter inputFilter) {
        mContext = context;
        mPowerManager =  (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWindowManagerService = LocalServices.getService(WindowManagerInternal.class);
        mTraceManager = AccessibilityTraceManager.getInstance(
                mWindowManagerService.getAccessibilityController(), this, mLock);
        mMainHandler = new MainHandler(mContext.getMainLooper());
        mActivityTaskManagerService = LocalServices.getService(ActivityTaskManagerInternal.class);
        mPackageManager = packageManager;
        mSecurityPolicy = securityPolicy;
        mSystemActionPerformer = systemActionPerformer;
        mA11yWindowManager = a11yWindowManager;
        mA11yDisplayListener = a11yDisplayListener;
        mMagnificationController = magnificationController;
        mMagnificationProcessor = new MagnificationProcessor(mMagnificationController);
        mCaptioningManagerImpl = new CaptioningManagerImpl(mContext);
        if (inputFilter != null) {
            mInputFilter = inputFilter;
            mHasInputFilter = true;
        }
        init();
    }

    /**
     * Creates a new instance.
     *
     * @param context A {@link Context} instance.
     */
    public AccessibilityManagerService(Context context) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
        mWindowManagerService = LocalServices.getService(WindowManagerInternal.class);
        mTraceManager = AccessibilityTraceManager.getInstance(
                mWindowManagerService.getAccessibilityController(), this, mLock);
        mMainHandler = new MainHandler(mContext.getMainLooper());
        mActivityTaskManagerService = LocalServices.getService(ActivityTaskManagerInternal.class);
        mPackageManager = mContext.getPackageManager();
        PolicyWarningUIController policyWarningUIController;
        if (AccessibilitySecurityPolicy.POLICY_WARNING_ENABLED) {
            policyWarningUIController = new PolicyWarningUIController(mMainHandler, context,
                    new PolicyWarningUIController.NotificationController(context));
        }
        mSecurityPolicy = new AccessibilitySecurityPolicy(policyWarningUIController, mContext,
                this);
        mA11yWindowManager = new AccessibilityWindowManager(mLock, mMainHandler,
                mWindowManagerService, this, mSecurityPolicy, this, mTraceManager);
        mA11yDisplayListener = new AccessibilityDisplayListener(mContext, mMainHandler);
        mMagnificationController = new MagnificationController(this, mLock, mContext,
                new MagnificationScaleProvider(mContext));
        mMagnificationProcessor = new MagnificationProcessor(mMagnificationController);
        mCaptioningManagerImpl = new CaptioningManagerImpl(mContext);
        init();
    }

    private void init() {
        mSecurityPolicy.setAccessibilityWindowManager(mA11yWindowManager);
        registerBroadcastReceivers();
        new AccessibilityContentObserver(mMainHandler).register(
                mContext.getContentResolver());
    }

    @Override
    public int getCurrentUserIdLocked() {
        return mCurrentUserId;
    }

    @Override
    public boolean isAccessibilityButtonShown() {
        return mIsAccessibilityButtonShown;
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

    private void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)) {
                mSecurityPolicy.setAppWidgetManager(
                        LocalServices.getService(AppWidgetManagerInternal.class));
            }
        }
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
                Manifest.permission.MANAGE_BIND_INSTANT_SERVICE,
                "setBindInstantServiceAllowed");
        synchronized (mLock) {
            final AccessibilityUserState userState = getUserStateLocked(userId);
            if (allowed != userState.getBindInstantServiceAllowedLocked()) {
                userState.setBindInstantServiceAllowedLocked(allowed);
                onUserStateChangedLocked(userState);
            }
        }
    }

    private void registerBroadcastReceivers() {
        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_PACKAGE_BROADCAST_RECEIVER)) {
                    mTraceManager.logTrace(LOG_TAG + ".PM.onSomePackagesChanged",
                            FLAGS_PACKAGE_BROADCAST_RECEIVER);
                }

                synchronized (mLock) {
                    // Only the profile parent can install accessibility services.
                    // Therefore we ignore packages from linked profiles.
                    if (getChangingUserId() != mCurrentUserId) {
                        return;
                    }
                    // We will update when the automation service dies.
                    final AccessibilityUserState userState = getCurrentUserStateLocked();
                    // We have to reload the installed services since some services may
                    // have different attributes, resolve info (does not support equals),
                    // etc. Remove them then to force reload.
                    userState.mInstalledServices.clear();
                    if (readConfigurationForUserStateLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                }
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                // The package should already be removed from mBoundServices, and added into
                // mBindingServices in binderDied() during updating. Remove services from  this
                // package from mBindingServices, and then update the user state to re-bind new
                // versions of them.
                if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_PACKAGE_BROADCAST_RECEIVER)) {
                    mTraceManager.logTrace(LOG_TAG + ".PM.onPackageUpdateFinished",
                            FLAGS_PACKAGE_BROADCAST_RECEIVER,
                            "packageName=" + packageName + ";uid=" + uid);
                }
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    if (userId != mCurrentUserId) {
                        return;
                    }
                    final AccessibilityUserState userState = getUserStateLocked(userId);
                    final boolean reboundAService = userState.getBindingServicesLocked().removeIf(
                            component -> component != null
                                    && component.getPackageName().equals(packageName))
                            || userState.mCrashedServices.removeIf(component -> component != null
                                    && component.getPackageName().equals(packageName));
                    // Reloads the installed services info to make sure the rebound service could
                    // get a new one.
                    userState.mInstalledServices.clear();
                    final boolean configurationChanged =
                            readConfigurationForUserStateLocked(userState);
                    if (reboundAService || configurationChanged) {
                        onUserStateChangedLocked(userState);
                    }
                    // Passing 0 for restoreFromSdkInt to have this migration check execute each
                    // time. It can make sure a11y button settings are correctly if there's an a11y
                    // service updated and modifies the a11y button configuration.
                    migrateAccessibilityButtonSettingsIfNecessaryLocked(userState, packageName,
                            /* restoreFromSdkInt = */0);
                }
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_PACKAGE_BROADCAST_RECEIVER)) {
                    mTraceManager.logTrace(LOG_TAG + ".PM.onPackageRemoved",
                            FLAGS_PACKAGE_BROADCAST_RECEIVER,
                            "packageName=" + packageName + ";uid=" + uid);
                }

                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    // Only the profile parent can install accessibility services.
                    // Therefore we ignore packages from linked profiles.
                    if (userId != mCurrentUserId) {
                        return;
                    }
                    final AccessibilityUserState userState = getUserStateLocked(userId);
                    final Predicate<ComponentName> filter =
                            component -> component != null && component.getPackageName().equals(
                                    packageName);
                    userState.mBindingServices.removeIf(filter);
                    userState.mCrashedServices.removeIf(filter);
                    final Iterator<ComponentName> it = userState.mEnabledServices.iterator();
                    while (it.hasNext()) {
                        final ComponentName comp = it.next();
                        final String compPkg = comp.getPackageName();
                        if (compPkg.equals(packageName)) {
                            it.remove();
                            // Update the enabled services setting.
                            persistComponentNamesToSettingLocked(
                                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                    userState.mEnabledServices, userId);
                            // Update the touch exploration granted services setting.
                            userState.mTouchExplorationGrantedServices.remove(comp);
                            persistComponentNamesToSettingLocked(
                                    Settings.Secure.
                                    TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                                    userState.mTouchExplorationGrantedServices, userId);
                            onUserStateChangedLocked(userState);
                            return;
                        }
                    }
                }
            }

            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages,
                    int uid, boolean doit) {
                if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_PACKAGE_BROADCAST_RECEIVER)) {
                    mTraceManager.logTrace(LOG_TAG + ".PM.onHandleForceStop",
                            FLAGS_PACKAGE_BROADCAST_RECEIVER,
                            "intent=" + intent + ";packages=" + packages + ";uid=" + uid
                            + ";doit=" + doit);
                }
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    // Only the profile parent can install accessibility services.
                    // Therefore we ignore packages from linked profiles.
                    if (userId != mCurrentUserId) {
                        return false;
                    }
                    final AccessibilityUserState userState = getUserStateLocked(userId);
                    final Iterator<ComponentName> it = userState.mEnabledServices.iterator();
                    while (it.hasNext()) {
                        final ComponentName comp = it.next();
                        final String compPkg = comp.getPackageName();
                        for (String pkg : packages) {
                            if (compPkg.equals(pkg)) {
                                if (!doit) {
                                    return true;
                                }
                                it.remove();
                                userState.getBindingServicesLocked().remove(comp);
                                userState.getCrashedServicesLocked().remove(comp);
                                persistComponentNamesToSettingLocked(
                                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                        userState.mEnabledServices, userId);
                                onUserStateChangedLocked(userState);
                            }
                        }
                    }
                    return false;
                }
            }
        };

        // package changes
        monitor.register(mContext, null,  UserHandle.ALL, true);

        // user change and unlock
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
        intentFilter.addAction(Intent.ACTION_SETTING_RESTORED);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_USER_BROADCAST_RECEIVER)) {
                    mTraceManager.logTrace(LOG_TAG + ".BR.onReceive", FLAGS_USER_BROADCAST_RECEIVER,
                            "context=" + context + ";intent=" + intent);
                }

                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                    unlockUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    removeUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    // We will update when the automation service dies.
                    synchronized (mLock) {
                        AccessibilityUserState userState = getCurrentUserStateLocked();
                        if (readConfigurationForUserStateLocked(userState)) {
                            onUserStateChangedLocked(userState);
                        }
                    }
                } else if (Intent.ACTION_SETTING_RESTORED.equals(action)) {
                    final String which = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                    if (Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.equals(which)) {
                        synchronized (mLock) {
                            restoreEnabledAccessibilityServicesLocked(
                                    intent.getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE),
                                    intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE),
                                    intent.getIntExtra(Intent.EXTRA_SETTING_RESTORED_FROM_SDK_INT,
                                            0));
                        }
                    } else if (ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED.equals(which)) {
                        synchronized (mLock) {
                            restoreLegacyDisplayMagnificationNavBarIfNeededLocked(
                                    intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE),
                                    intent.getIntExtra(Intent.EXTRA_SETTING_RESTORED_FROM_SDK_INT,
                                            0));
                        }
                    } else if (Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS.equals(which)) {
                        synchronized (mLock) {
                            restoreAccessibilityButtonTargetsLocked(
                                    intent.getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE),
                                    intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE));
                        }
                    }
                }
            }
        }, UserHandle.ALL, intentFilter, null, null);
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
        readAccessibilityButtonTargetsLocked(userState);
        onUserStateChangedLocked(userState);
    }

    @Override
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

            // If the client is from a process that runs across users such as
            // the system UI or the system we add it to the global state that
            // is shared across users.
            AccessibilityUserState userState = getUserStateLocked(resolvedUserId);
            Client client = new Client(callback, Binder.getCallingUid(), userState);
            if (mSecurityPolicy.isCallerInteractingAcrossUsers(userId)) {
                mGlobalClients.register(callback, client);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added global client for pid:" + Binder.getCallingPid());
                }
                return IntPair.of(
                        getClientStateLocked(userState),
                        client.mLastSentRelevantEventTypes);
            } else {
                userState.mUserClients.register(callback, client);
                // If this client is not for the current user we do not
                // return a state since it is not for the foreground user.
                // We will send the state to the client on a user switch.
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Added user client for pid:" + Binder.getCallingPid()
                            + " and userId:" + mCurrentUserId);
                }
                return IntPair.of(
                        (resolvedUserId == mCurrentUserId) ? getClientStateLocked(userState) : 0,
                        client.mLastSentRelevantEventTypes);
            }
        }
    }

    @Override
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
            int displayId = Display.INVALID_DISPLAY;
            synchronized (mLock) {
                final int windowId = event.getWindowId();
                if (windowId != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID) {
                    displayId = mA11yWindowManager.getDisplayIdByUserIdAndWindowIdLocked(
                            resolvedUserId, windowId);
                    event.setDisplayId(displayId);
                }

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
            }
            synchronized (mLock) {
                notifyAccessibilityServicesDelayedLocked(event, false);
                notifyAccessibilityServicesDelayedLocked(event, true);
                mUiAutomationManager.sendAccessibilityEventLocked(event);
            }
        }

        if (OWN_PROCESS_ID != Binder.getCallingPid()) {
            event.recycle();
        }
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
    public void registerSystemAction(RemoteAction action, int actionId) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".registerSystemAction",
                    FLAGS_ACCESSIBILITY_MANAGER, "action=" + action + ";actionId=" + actionId);
        }
        mSecurityPolicy.enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ACCESSIBILITY);
        getSystemActionPerformer().registerSystemAction(actionId, action);
    }

    /**
     * This is the implementation of AccessibilityManager system API.
     * System UI calls into this method through AccessibilityManager system API to unregister a
     * system action.
     */
    @Override
    public void unregisterSystemAction(int actionId) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".unregisterSystemAction",
                    FLAGS_ACCESSIBILITY_MANAGER, "actionId=" + actionId);
        }
        mSecurityPolicy.enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ACCESSIBILITY);
        getSystemActionPerformer().unregisterSystemAction(actionId);
    }

    private SystemActionPerformer getSystemActionPerformer() {
        if (mSystemActionPerformer == null) {
            mSystemActionPerformer =
                    new SystemActionPerformer(mContext, mWindowManagerService, null, this);
        }
        return mSystemActionPerformer;
    }

    @Override
    public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(int userId) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getInstalledAccessibilityServiceList",
                    FLAGS_ACCESSIBILITY_MANAGER, "userId=" + userId);
        }

        final int resolvedUserId;
        final List<AccessibilityServiceInfo> serviceInfos;
        synchronized (mLock) {
            // We treat calls from a profile as if made by its parent as profiles
            // share the accessibility state of the parent. The call below
            // performs the current profile parent resolution.
            resolvedUserId = mSecurityPolicy
                    .resolveCallingUserIdEnforcingPermissionsLocked(userId);
            serviceInfos = new ArrayList<>(
                    getUserStateLocked(resolvedUserId).mInstalledServices);
        }

        if (Binder.getCallingPid() == OWN_PROCESS_ID) {
            return serviceInfos;
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
        return serviceInfos;
    }

    @Override
    public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(int feedbackType,
            int userId) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getEnabledAccessibilityServiceList",
                    FLAGS_ACCESSIBILITY_MANAGER,
                    "feedbackType=" + feedbackType + ";userId=" + userId);
        }

        synchronized (mLock) {
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
                if ((service.mFeedbackType & feedbackType) != 0) {
                    result.add(service.getServiceInfo());
                }
            }
            return result;
        }
    }

    @Override
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
            List<AccessibilityServiceConnection> services =
                    getUserStateLocked(resolvedUserId).mBoundServices;
            int numServices = services.size();
            interfacesToInterrupt = new ArrayList<>(numServices);
            for (int i = 0; i < numServices; i++) {
                AccessibilityServiceConnection service = services.get(i);
                IBinder a11yServiceBinder = service.mService;
                IAccessibilityServiceClient a11yServiceInterface = service.mServiceInterface;
                if ((a11yServiceBinder != null) && (a11yServiceInterface != null)) {
                    interfacesToInterrupt.add(a11yServiceInterface);
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
    public void removeAccessibilityInteractionConnection(IWindow window) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".removeAccessibilityInteractionConnection",
                    FLAGS_ACCESSIBILITY_MANAGER, "window=" + window);
        }
        mA11yWindowManager.removeAccessibilityInteractionConnection(window);
    }

    @Override
    public void setPictureInPictureActionReplacingConnection(
            IAccessibilityInteractionConnection connection) throws RemoteException {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".setPictureInPictureActionReplacingConnection",
                    FLAGS_ACCESSIBILITY_MANAGER, "connection=" + connection);
        }
        mSecurityPolicy.enforceCallingPermission(Manifest.permission.MODIFY_ACCESSIBILITY_DATA,
                SET_PIP_ACTION_REPLACEMENT);
        mA11yWindowManager.setPictureInPictureActionReplacingConnection(connection);
    }

    @Override
    public void registerUiTestAutomationService(IBinder owner,
            IAccessibilityServiceClient serviceClient,
            AccessibilityServiceInfo accessibilityServiceInfo,
            int flags) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".registerUiTestAutomationService",
                    FLAGS_ACCESSIBILITY_MANAGER,
                    "owner=" + owner + ";serviceClient=" + serviceClient
                    + ";accessibilityServiceInfo=" + accessibilityServiceInfo + ";flags=" + flags);
        }

        mSecurityPolicy.enforceCallingPermission(Manifest.permission.RETRIEVE_WINDOW_CONTENT,
                FUNCTION_REGISTER_UI_TEST_AUTOMATION_SERVICE);

        synchronized (mLock) {
            mUiAutomationManager.registerUiTestAutomationServiceLocked(owner, serviceClient,
                    mContext, accessibilityServiceInfo, sIdCounter++, mMainHandler,
                    mSecurityPolicy, this, getTraceManager(), mWindowManagerService,
                    getSystemActionPerformer(), mA11yWindowManager, flags);
            onUserStateChangedLocked(getCurrentUserStateLocked());
        }
    }

    @Override
    public void unregisterUiTestAutomationService(IAccessibilityServiceClient serviceClient) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".unregisterUiTestAutomationService",
                    FLAGS_ACCESSIBILITY_MANAGER, "serviceClient=" + serviceClient);
        }
        synchronized (mLock) {
            mUiAutomationManager.unregisterUiTestAutomationServiceLocked(serviceClient);
        }
    }

    @Override
    public void temporaryEnableAccessibilityStateUntilKeyguardRemoved(
            ComponentName service, boolean touchExplorationEnabled) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(
                    LOG_TAG + ".temporaryEnableAccessibilityStateUntilKeyguardRemoved",
                    FLAGS_ACCESSIBILITY_MANAGER,
                    "service=" + service + ";touchExplorationEnabled=" + touchExplorationEnabled);
        }

        mSecurityPolicy.enforceCallingPermission(
                Manifest.permission.TEMPORARY_ENABLE_ACCESSIBILITY,
                TEMPORARY_ENABLE_ACCESSIBILITY_UNTIL_KEYGUARD_REMOVED);
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_WINDOW_MANAGER_INTERNAL)) {
            mTraceManager.logTrace("WindowManagerInternal.isKeyguardLocked",
                    FLAGS_WINDOW_MANAGER_INTERNAL);
        }
        if (!mWindowManagerService.isKeyguardLocked()) {
            return;
        }
        synchronized (mLock) {
            // Set the temporary state.
            AccessibilityUserState userState = getCurrentUserStateLocked();

            userState.setTouchExplorationEnabledLocked(touchExplorationEnabled);
            userState.setDisplayMagnificationEnabledLocked(false);
            userState.disableShortcutMagnificationLocked();
            userState.setAutoclickEnabledLocked(false);
            userState.mEnabledServices.clear();
            userState.mEnabledServices.add(service);
            userState.getBindingServicesLocked().clear();
            userState.getCrashedServicesLocked().clear();
            userState.mTouchExplorationGrantedServices.clear();
            userState.mTouchExplorationGrantedServices.add(service);

            // User the current state instead settings.
            onUserStateChangedLocked(userState);
        }
    }

    @Override
    public IBinder getWindowToken(int windowId, int userId) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getWindowToken",
                    FLAGS_ACCESSIBILITY_MANAGER, "windowId=" + windowId + ";userId=" + userId);
        }

        mSecurityPolicy.enforceCallingPermission(
                Manifest.permission.RETRIEVE_WINDOW_TOKEN,
                GET_WINDOW_TOKEN);
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
     * navigation area has been clicked.
     *
     * @param displayId The logical display id.
     * @param targetName The flattened {@link ComponentName} string or the class name of a system
     *        class implementing a supported accessibility feature, or {@code null} if there's no
     *        specified target.
     */
    @Override
    public void notifyAccessibilityButtonClicked(int displayId, String targetName) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".notifyAccessibilityButtonClicked",
                    FLAGS_ACCESSIBILITY_MANAGER,
                    "displayId=" + displayId + ";targetName=" + targetName);
        }

        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold permission "
                    + android.Manifest.permission.STATUS_BAR_SERVICE);
        }
        if (targetName == null) {
            synchronized (mLock) {
                final AccessibilityUserState userState = getCurrentUserStateLocked();
                targetName = userState.getTargetAssignedToAccessibilityButton();
            }
        }
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::performAccessibilityShortcutInternal, this,
                displayId, ACCESSIBILITY_BUTTON, targetName));
    }

    /**
     * Invoked remotely over AIDL by SysUi when the visibility of the accessibility
     * button within the system's navigation area has changed.
     *
     * @param shown {@code true} if the accessibility button is shown to the
     *                  user, {@code false} otherwise
     */
    @Override
    public void notifyAccessibilityButtonVisibilityChanged(boolean shown) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".notifyAccessibilityButtonVisibilityChanged",
                    FLAGS_ACCESSIBILITY_MANAGER, "shown=" + shown);
        }

        mSecurityPolicy.enforceCallingOrSelfPermission(
                android.Manifest.permission.STATUS_BAR_SERVICE);
        synchronized (mLock) {
            notifyAccessibilityButtonVisibilityChangedLocked(shown);
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

    /** Send a motion event to the service to allow it to perform gesture detection. */
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
        final long endMillis = SystemClock.uptimeMillis() + WAIT_MOTION_INJECTOR_TIMEOUT_MILLIS;
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

    private void switchUser(int userId) {
        mMagnificationController.updateUserIdIfNeeded(userId);
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

            // Announce user changes only if more that one exist.
            UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            final boolean announceNewUser = userManager.getUsers().size() > 1;

            // The user changed.
            mCurrentUserId = userId;
            AccessibilityUserState userState = getCurrentUserStateLocked();

            readConfigurationForUserStateLocked(userState);
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

            if (announceNewUser) {
                // Schedule announcement of the current user if needed.
                mMainHandler.sendMessageDelayed(
                        obtainMessage(AccessibilityManagerService::announceNewUserIfNeeded, this),
                        WAIT_FOR_USER_STATE_FULLY_INITIALIZED_MILLIS);
            }
        }
    }

    private void announceNewUserIfNeeded() {
        synchronized (mLock) {
            AccessibilityUserState userState = getCurrentUserStateLocked();
            if (userState.isHandlingAccessibilityEventsLocked()) {
                UserManager userManager = (UserManager) mContext.getSystemService(
                        Context.USER_SERVICE);
                String message = mContext.getString(R.string.user_switched,
                        userManager.getUserInfo(mCurrentUserId).name);
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
     * User could enable accessibility services and configure accessibility button during the SUW.
     * Merges current value of accessibility button settings into the restored one to make sure
     * user's preferences of accessibility button updated in SUW are not lost.
     *
     * Called only during settings restore; currently supports only the owner user
     * TODO: http://b/22388012
     */
    void restoreAccessibilityButtonTargetsLocked(String oldSetting, String newSetting) {
        final Set<String> targetsFromSetting = new ArraySet<>();
        readColonDelimitedStringToSet(oldSetting, str -> str, targetsFromSetting,
                /* doMerge = */false);
        readColonDelimitedStringToSet(newSetting, str -> str, targetsFromSetting,
                /* doMerge = */true);

        final AccessibilityUserState userState = getUserStateLocked(UserHandle.USER_SYSTEM);
        userState.mAccessibilityButtonTargets.clear();
        userState.mAccessibilityButtonTargets.addAll(targetsFromSetting);
        persistColonDelimitedSetToSettingLocked(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                UserHandle.USER_SYSTEM, userState.mAccessibilityButtonTargets, str -> str);

        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
        onUserStateChangedLocked(userState);
    }

    private int getClientStateLocked(AccessibilityUserState userState) {
        return userState.getClientStateLocked(
            mUiAutomationManager.isUiAutomationRunningLocked(),
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
        synchronized (mLock) {
            AccessibilityUserState state = getCurrentUserStateLocked();
            for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
                AccessibilityServiceConnection service = state.mBoundServices.get(i);
                if (service.mRequestTouchExplorationMode) {
                    service.notifyMotionEvent(event);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean scheduleNotifyTouchState(int displayId, int touchState) {
        synchronized (mLock) {
            AccessibilityUserState state = getCurrentUserStateLocked();
            for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
                AccessibilityServiceConnection service = state.mBoundServices.get(i);
                if (service.mRequestTouchExplorationMode) {
                    service.notifyTouchState(displayId, touchState);
                    return true;
                }
            }
        }
        return false;
    }

    private void notifyClearAccessibilityCacheLocked() {
        AccessibilityUserState state = getCurrentUserStateLocked();
        for (int i = state.mBoundServices.size() - 1; i >= 0; i--) {
            AccessibilityServiceConnection service = state.mBoundServices.get(i);
            service.notifyClearAccessibilityNodeInfoCache();
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

    private void sendAccessibilityButtonToInputFilter(int displayId) {
        synchronized (mLock) {
            if (mHasInputFilter && mInputFilter != null) {
                mInputFilter.notifyAccessibilityButtonClicked(displayId);
            }
        }
    }

    private void showAccessibilityTargetsSelection(int displayId,
            @ShortcutType int shortcutType) {
        final Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
        final String chooserClassName = (shortcutType == ACCESSIBILITY_SHORTCUT_KEY)
                ? AccessibilityShortcutChooserActivity.class.getName()
                : AccessibilityButtonChooserActivity.class.getName();
        intent.setClassName(CHOOSER_PACKAGE_NAME, chooserClassName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        final Bundle bundle = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        mContext.startActivityAsUser(intent, bundle, UserHandle.of(mCurrentUserId));
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

    private boolean readInstalledAccessibilityServiceLocked(AccessibilityUserState userState) {
        mTempAccessibilityServiceInfoList.clear();

        int flags = PackageManager.GET_SERVICES
                | PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

        if (userState.getBindInstantServiceAllowedLocked()) {
            flags |= PackageManager.MATCH_INSTANT;
        }

        List<ResolveInfo> installedServices = mPackageManager.queryIntentServicesAsUser(
                new Intent(AccessibilityService.SERVICE_INTERFACE), flags, mCurrentUserId);

        for (int i = 0, count = installedServices.size(); i < count; i++) {
            ResolveInfo resolveInfo = installedServices.get(i);
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;

            if (!mSecurityPolicy.canRegisterService(serviceInfo)) {
                continue;
            }

            AccessibilityServiceInfo accessibilityServiceInfo;
            try {
                accessibilityServiceInfo = new AccessibilityServiceInfo(resolveInfo, mContext);
                if (userState.mCrashedServices.contains(serviceInfo.getComponentName())) {
                    // Restore the crashed attribute.
                    accessibilityServiceInfo.crashed = true;
                }
                mTempAccessibilityServiceInfoList.add(accessibilityServiceInfo);
            } catch (XmlPullParserException | IOException xppe) {
                Slog.e(LOG_TAG, "Error while initializing AccessibilityServiceInfo", xppe);
            }
        }

        if (!mTempAccessibilityServiceInfoList.equals(userState.mInstalledServices)) {
            userState.mInstalledServices.clear();
            userState.mInstalledServices.addAll(mTempAccessibilityServiceInfoList);
            mTempAccessibilityServiceInfoList.clear();
            return true;
        }

        mTempAccessibilityServiceInfoList.clear();
        return false;
    }

    private boolean readInstalledAccessibilityShortcutLocked(AccessibilityUserState userState) {
        final List<AccessibilityShortcutInfo> shortcutInfos = AccessibilityManager
                .getInstance(mContext).getInstalledAccessibilityShortcutListAsUser(
                        mContext, mCurrentUserId);
        if (!shortcutInfos.equals(userState.mInstalledShortcuts)) {
            userState.mInstalledShortcuts.clear();
            userState.mInstalledShortcuts.addAll(shortcutInfos);
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
                boolean changed = false;
                synchronized (mLock) {
                    relevantEventTypes = computeRelevantEventTypesLocked(userState, client);

                    if (client.mLastSentRelevantEventTypes != relevantEventTypes) {
                        client.mLastSentRelevantEventTypes = relevantEventTypes;
                        changed = true;
                    }
                }
                if (changed) {
                    client.mCallback.setRelevantEventTypes(relevantEventTypes);
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

    private static boolean isClientInPackageAllowlist(
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
    private void readComponentNamesFromSettingLocked(String settingName, int userId,
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

    private <T> void readColonDelimitedSettingToSet(String settingName, int userId,
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

    private <T> void persistColonDelimitedSetToSettingLocked(String settingName, int userId,
            Set<T> set, Function<T, String> toString) {
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
        final long identity = Binder.clearCallingIdentity();
        try {
            final String settingValue = builder.toString();
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    settingName, TextUtils.isEmpty(settingValue) ? null : settingValue, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void updateServicesLocked(AccessibilityUserState userState) {
        Map<ComponentName, AccessibilityServiceConnection> componentNameToServiceMap =
                userState.mComponentNameToServiceMap;
        boolean isUnlockingOrUnlocked = LocalServices.getService(UserManagerInternal.class)
                    .isUserUnlockingOrUnlocked(userState.mUserId);

        for (int i = 0, count = userState.mInstalledServices.size(); i < count; i++) {
            AccessibilityServiceInfo installedService = userState.mInstalledServices.get(i);
            ComponentName componentName = ComponentName.unflattenFromString(
                    installedService.getId());

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
        updateAccessibilityEnabledSettingLocked(userState);
    }

    void scheduleUpdateClientsIfNeeded(AccessibilityUserState userState) {
        synchronized (mLock) {
            scheduleUpdateClientsIfNeededLocked(userState);
        }
    }

    void scheduleUpdateClientsIfNeededLocked(AccessibilityUserState userState) {
        final int clientState = getClientStateLocked(userState);
        if (userState.getLastSentClientStateLocked() != clientState
                && (mGlobalClients.getRegisteredCallbackCount() > 0
                        || userState.mUserClients.getRegisteredCallbackCount() > 0)) {
            userState.setLastSentClientStateLocked(clientState);
            mMainHandler.sendMessage(obtainMessage(
                    AccessibilityManagerService::sendStateToAllClients,
                    this, clientState, userState.mUserId));
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
        clients.broadcast(ignoreRemoteException(
                client -> client.setState(clientState)));
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
        clients.broadcast(ignoreRemoteException(
                client -> client.notifyServicesStateChanged(uiTimeout)));
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
            if (userState.isDisplayMagnificationEnabledLocked()) {
                flags |= AccessibilityInputFilter.FLAG_FEATURE_SCREEN_MAGNIFIER;
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
            if (flags != 0) {
                if (!mHasInputFilter) {
                    mHasInputFilter = true;
                    if (mInputFilter == null) {
                        mInputFilter = new AccessibilityInputFilter(mContext,
                                AccessibilityManagerService.this);
                    }
                    inputFilter = mInputFilter;
                    setInputFilter = true;
                }
                mInputFilter.setUserAndEnabledFeatures(userState.mUserId, flags);
            } else {
                if (mHasInputFilter) {
                    mHasInputFilter = false;
                    mInputFilter.setUserAndEnabledFeatures(userState.mUserId, 0);
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

    /**
     * Called when any property of the user state has changed.
     *
     * @param userState the new user state
     */
    private void onUserStateChangedLocked(AccessibilityUserState userState) {
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
        scheduleUpdateClientsIfNeededLocked(userState);
        updateAccessibilityShortcutKeyTargetsLocked(userState);
        updateAccessibilityButtonTargetsLocked(userState);
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

    private void updateWindowsForAccessibilityCallbackLocked(AccessibilityUserState userState) {
        // We observe windows for accessibility only if there is at least
        // one bound service that can retrieve window content that specified
        // it is interested in accessing such windows. For services that are
        // binding we do an update pass after each bind event, so we run this
        // code and register the callback if needed.

        boolean observingWindows = mUiAutomationManager.canRetrieveInteractiveWindowsLocked();
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
                if (observingWindows) {
                    mA11yWindowManager.startTrackingWindows(display.getDisplayId());
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

    private boolean readConfigurationForUserStateLocked(AccessibilityUserState userState) {
        boolean somethingChanged = readInstalledAccessibilityServiceLocked(userState);
        somethingChanged |= readInstalledAccessibilityShortcutLocked(userState);
        somethingChanged |= readEnabledAccessibilityServicesLocked(userState);
        somethingChanged |= readTouchExplorationGrantedAccessibilityServicesLocked(userState);
        somethingChanged |= readTouchExplorationEnabledSettingLocked(userState);
        somethingChanged |= readHighTextContrastEnabledSettingLocked(userState);
        somethingChanged |= readAudioDescriptionEnabledSettingLocked(userState);
        somethingChanged |= readMagnificationEnabledSettingsLocked(userState);
        somethingChanged |= readAutoclickEnabledSettingLocked(userState);
        somethingChanged |= readAccessibilityShortcutKeySettingLocked(userState);
        somethingChanged |= readAccessibilityButtonTargetsLocked(userState);
        somethingChanged |= readAccessibilityButtonTargetComponentLocked(userState);
        somethingChanged |= readUserRecommendedUiTimeoutSettingsLocked(userState);
        somethingChanged |= readMagnificationModeForDefaultDisplayLocked(userState);
        somethingChanged |= readMagnificationCapabilitiesLocked(userState);
        somethingChanged |= readMagnificationFollowTypingLocked(userState);
        return somethingChanged;
    }

    private void updateAccessibilityEnabledSettingLocked(AccessibilityUserState userState) {
        final boolean isA11yEnabled = mUiAutomationManager.isUiAutomationRunningLocked()
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
        final boolean displayMagnificationEnabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                0, userState.mUserId) == 1;
        if ((displayMagnificationEnabled != userState.isDisplayMagnificationEnabledLocked())) {
            userState.setDisplayMagnificationEnabledLocked(displayMagnificationEnabled);
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
        userState.setServiceHandlesDoubleTapLocked(serviceHandlesDoubleTapEnabled);
        userState.setMultiFingerGesturesLocked(requestMultiFingerGestures);
        userState.setTwoFingerPassthroughLocked(requestTwoFingerPassthrough);
        userState.setSendMotionEventsEnabled(sendMotionEvents);
    }

    private boolean readAccessibilityShortcutKeySettingLocked(AccessibilityUserState userState) {
        final String settingValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, userState.mUserId);
        final Set<String> targetsFromSetting = new ArraySet<>();
        readColonDelimitedStringToSet(settingValue, str -> str, targetsFromSetting, false);
        // Fall back to device's default a11y service, only when setting is never updated.
        if (settingValue == null) {
            final String defaultService = mContext.getString(
                    R.string.config_defaultAccessibilityService);
            if (!TextUtils.isEmpty(defaultService)) {
                targetsFromSetting.add(defaultService);
            }
        }

        final Set<String> currentTargets =
                userState.getShortcutTargetsLocked(ACCESSIBILITY_SHORTCUT_KEY);
        if (targetsFromSetting.equals(currentTargets)) {
            return false;
        }
        currentTargets.clear();
        currentTargets.addAll(targetsFromSetting);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
        return true;
    }

    private boolean readAccessibilityButtonTargetsLocked(AccessibilityUserState userState) {
        final Set<String> targetsFromSetting = new ArraySet<>();
        readColonDelimitedSettingToSet(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                userState.mUserId, str -> str, targetsFromSetting);

        final Set<String> currentTargets =
                userState.getShortcutTargetsLocked(ACCESSIBILITY_BUTTON);
        if (targetsFromSetting.equals(currentTargets)) {
            return false;
        }
        currentTargets.clear();
        currentTargets.addAll(targetsFromSetting);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
        return true;
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
        if (nonInteractiveUiTimeout != userState.getUserNonInteractiveUiTimeoutLocked()
                || interactiveUiTimeout != userState.getUserInteractiveUiTimeoutLocked()) {
            userState.setUserNonInteractiveUiTimeoutLocked(nonInteractiveUiTimeout);
            userState.setUserInteractiveUiTimeoutLocked(interactiveUiTimeout);
            scheduleNotifyClientsOfServicesStateChangeLocked(userState);
            return true;
        }
        return false;
    }

    /**
     * Check if the target that will be enabled by the accessibility shortcut key is installed.
     * If it isn't, remove it from the list and associated setting so a side loaded service can't
     * spoof the package name of the default service.
     */
    private void updateAccessibilityShortcutKeyTargetsLocked(AccessibilityUserState userState) {
        final Set<String> currentTargets =
                userState.getShortcutTargetsLocked(ACCESSIBILITY_SHORTCUT_KEY);
        final int lastSize = currentTargets.size();
        if (lastSize == 0) {
            return;
        }
        currentTargets.removeIf(
                name -> !userState.isShortcutTargetInstalledLocked(name));
        if (lastSize == currentTargets.size()) {
            return;
        }

        // Update setting key with new value.
        persistColonDelimitedSetToSettingLocked(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                userState.mUserId, currentTargets, str -> str);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
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
        if (userState.isDisplayMagnificationEnabledLocked()
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

    private void updateWindowMagnificationConnectionIfNeeded(AccessibilityUserState userState) {
        if (!mMagnificationController.supportWindowMagnification()) {
            return;
        }
        final boolean connect = (userState.isShortcutMagnificationEnabledLocked()
                || userState.isDisplayMagnificationEnabledLocked())
                && (userState.getMagnificationCapabilitiesLocked()
                != Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN)
                || userHasMagnificationServicesLocked(userState);
        getWindowMagnificationMgr().requestConnection(connect);
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
     * 1) Update accessibility button availability to accessibility services.
     * 2) Check if the target that will be enabled by the accessibility button is installed.
     *    If it isn't, remove it from the list and associated setting so a side loaded service can't
     *    spoof the package name of the default service.
     */
    private void updateAccessibilityButtonTargetsLocked(AccessibilityUserState userState) {
        // Update accessibility button availability.
        for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (service.mRequestAccessibilityButton) {
                service.notifyAccessibilityButtonAvailabilityChangedLocked(
                        service.isAccessibilityButtonAvailableLocked(userState));
            }
        }

        final Set<String> currentTargets =
                userState.getShortcutTargetsLocked(ACCESSIBILITY_BUTTON);
        final int lastSize = currentTargets.size();
        if (lastSize == 0) {
            return;
        }
        currentTargets.removeIf(
                name -> !userState.isShortcutTargetInstalledLocked(name));
        if (lastSize == currentTargets.size()) {
            return;
        }

        // Update setting key with new value.
        persistColonDelimitedSetToSettingLocked(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                userState.mUserId, currentTargets, str -> str);
        scheduleNotifyClientsOfServicesStateChangeLocked(userState);
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
                userState.getShortcutTargetsLocked(ACCESSIBILITY_BUTTON);
        int lastSize = buttonTargets.size();
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
                    & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
            if (requestA11yButton && !userState.mEnabledServices.contains(componentName)) {
                // An a11y service targeting sdk version > Q and request A11y button and is assigned
                // to a11y btn should be in the enabled list.
                Slog.v(LOG_TAG, "Service requesting a11y button and be assigned to the button"
                        + componentName + " should be enabled state");
                return true;
            }
            return false;
        });
        boolean changed = (lastSize != buttonTargets.size());
        lastSize = buttonTargets.size();

        final Set<String> shortcutKeyTargets =
                userState.getShortcutTargetsLocked(ACCESSIBILITY_SHORTCUT_KEY);
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
            if (doesShortcutTargetsStringContain(buttonTargets, serviceName)
                    || doesShortcutTargetsStringContain(shortcutKeyTargets, serviceName)) {
                return;
            }
            // For enabled a11y services targeting sdk version > Q and requesting a11y button should
            // be assigned to a shortcut.
            Slog.v(LOG_TAG, "A enabled service requesting a11y button " + componentName
                    + " should be assign to the button or shortcut.");
            buttonTargets.add(serviceName);
        });
        changed |= (lastSize != buttonTargets.size());
        if (!changed) {
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
        final ComponentName serviceName = service.getComponentName();
        if (userState.removeShortcutTargetLocked(ACCESSIBILITY_SHORTCUT_KEY, serviceName)) {
            final Set<String> currentTargets = userState.getShortcutTargetsLocked(
                    ACCESSIBILITY_SHORTCUT_KEY);
            persistColonDelimitedSetToSettingLocked(
                    Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                    userState.mUserId, currentTargets, str -> str);
        }
        if (userState.removeShortcutTargetLocked(ACCESSIBILITY_BUTTON, serviceName)) {
            final Set<String> currentTargets = userState.getShortcutTargetsLocked(
                    ACCESSIBILITY_BUTTON);
            persistColonDelimitedSetToSettingLocked(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                    userState.mUserId, currentTargets, str -> str);
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
    @Override
    public void performAccessibilityShortcut(String targetName) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".performAccessibilityShortcut",
                    FLAGS_ACCESSIBILITY_MANAGER, "targetName=" + targetName);
        }

        if ((UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID)
                && (mContext.checkCallingPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
                != PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException(
                    "performAccessibilityShortcut requires the MANAGE_ACCESSIBILITY permission");
        }
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::performAccessibilityShortcutInternal, this,
                Display.DEFAULT_DISPLAY, ACCESSIBILITY_SHORTCUT_KEY, targetName));
    }

    /**
     * Perform the accessibility shortcut action.
     *
     * @param shortcutType The shortcut type.
     * @param displayId The display id of the accessibility button.
     * @param targetName The flattened {@link ComponentName} string or the class name of a system
     *        class implementing a supported accessibility feature, or {@code null} if there's no
     *        specified target.
     */
    private void performAccessibilityShortcutInternal(int displayId,
            @ShortcutType int shortcutType, @Nullable String targetName) {
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
                showAccessibilityTargetsSelection(displayId, shortcutType);
                return;
            }
            targetName = shortcutTargets.get(0);
        }
        // In case user assigned magnification to the given shortcut.
        if (targetName.equals(MAGNIFICATION_CONTROLLER_NAME)) {
            final boolean enabled =
                    !getMagnificationController().getFullScreenMagnificationController()
                            .isMagnifying(displayId);
            logAccessibilityShortcutActivated(mContext, MAGNIFICATION_COMPONENT_NAME, shortcutType,
                    enabled);
            sendAccessibilityButtonToInputFilter(displayId);
            return;
        }
        final ComponentName targetComponentName = ComponentName.unflattenFromString(targetName);
        if (targetComponentName == null) {
            Slog.d(LOG_TAG, "Perform shortcut failed, invalid target name:" + targetName);
            return;
        }
        // In case user assigned an accessibility framework feature to the given shortcut.
        if (performAccessibilityFrameworkFeature(targetComponentName, shortcutType)) {
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

    private boolean performAccessibilityFrameworkFeature(ComponentName assignedTarget,
            @ShortcutType int shortcutType) {
        final Map<ComponentName, ToggleableFrameworkFeatureInfo> frameworkFeatureMap =
                AccessibilityShortcutController.getFrameworkShortcutFeaturesMap();
        if (!frameworkFeatureMap.containsKey(assignedTarget)) {
            return false;
        }
        // Toggle the requested framework feature
        final ToggleableFrameworkFeatureInfo featureInfo = frameworkFeatureMap.get(assignedTarget);
        final SettingStringHelper setting = new SettingStringHelper(mContext.getContentResolver(),
                featureInfo.getSettingKey(), mCurrentUserId);
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
     * 1) For {@link AccessibilityManager#ACCESSIBILITY_BUTTON} type and services targeting sdk
     *    version <= Q: callbacks to accessibility service if service is bounded and requests
     *    accessibility button.
     * 2) For {@link AccessibilityManager#ACCESSIBILITY_SHORTCUT_KEY} type and service targeting sdk
     *    version <= Q: turns on / off the accessibility service.
     * 3) For {@link AccessibilityManager#ACCESSIBILITY_SHORTCUT_KEY} type and service targeting sdk
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
            @ShortcutType int shortcutType, ComponentName assignedTarget) {
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
                    & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
            // Turns on / off the accessibility service
            if ((targetSdk <= Build.VERSION_CODES.Q && shortcutType == ACCESSIBILITY_SHORTCUT_KEY)
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
            if (shortcutType == ACCESSIBILITY_SHORTCUT_KEY && targetSdk > Build.VERSION_CODES.Q
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

    @Override
    public List<String> getAccessibilityShortcutTargets(@ShortcutType int shortcutType) {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getAccessibilityShortcutTargets",
                    FLAGS_ACCESSIBILITY_MANAGER, "shortcutType=" + shortcutType);
        }

        if (mContext.checkCallingOrSelfPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "getAccessibilityShortcutService requires the MANAGE_ACCESSIBILITY permission");
        }
        return getAccessibilityShortcutTargetsInternal(shortcutType);
    }

    private List<String> getAccessibilityShortcutTargetsInternal(@ShortcutType int shortcutType) {
        synchronized (mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();
            final ArrayList<String> shortcutTargets = new ArrayList<>(
                    userState.getShortcutTargetsLocked(shortcutType));
            if (shortcutType != ACCESSIBILITY_BUTTON) {
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
    public boolean sendFingerprintGesture(int gestureKeyCode) {
        if (mTraceManager.isA11yTracingEnabledForTypes(
                FLAGS_ACCESSIBILITY_MANAGER | FLAGS_FINGERPRINT)) {
            mTraceManager.logTrace(LOG_TAG + ".sendFingerprintGesture",
                    FLAGS_ACCESSIBILITY_MANAGER | FLAGS_FINGERPRINT,
                    "gestureKeyCode=" + gestureKeyCode);
        }

        synchronized(mLock) {
            if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
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
    public long getRecommendedTimeoutMillis() {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(
                    LOG_TAG + ".getRecommendedTimeoutMillis", FLAGS_ACCESSIBILITY_MANAGER);
        }

        synchronized(mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();
            return getRecommendedTimeoutMillisLocked(userState);
        }
    }

    private long getRecommendedTimeoutMillisLocked(AccessibilityUserState userState) {
        return IntPair.of(userState.getInteractiveUiTimeoutLocked(),
                userState.getNonInteractiveUiTimeoutLocked());
    }

    @Override
    public void setWindowMagnificationConnection(
            IWindowMagnificationConnection connection) throws RemoteException {
        if (mTraceManager.isA11yTracingEnabledForTypes(
                FLAGS_ACCESSIBILITY_MANAGER | FLAGS_WINDOW_MAGNIFICATION_CONNECTION)) {
            mTraceManager.logTrace(LOG_TAG + ".setWindowMagnificationConnection",
                    FLAGS_ACCESSIBILITY_MANAGER | FLAGS_WINDOW_MAGNIFICATION_CONNECTION,
                    "connection=" + connection);
        }

        mSecurityPolicy.enforceCallingOrSelfPermission(
                android.Manifest.permission.STATUS_BAR_SERVICE);

        getWindowMagnificationMgr().setConnection(connection);
    }

    /**
     * Getter of {@link WindowMagnificationManager}.
     *
     * @return WindowMagnificationManager
     */
    public WindowMagnificationManager getWindowMagnificationMgr() {
        synchronized (mLock) {
            return mMagnificationController.getWindowMagnificationMgr();
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
    public int getFocusStrokeWidth() {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getFocusStrokeWidth", FLAGS_ACCESSIBILITY_MANAGER);
        }
        synchronized (mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();

            return userState.getFocusStrokeWidthLocked();
        }
    }

    /**
     * Gets the color of the focus rectangle.
     * @return The color.
     */
    @Override
    public int getFocusColor() {
        if (mTraceManager.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_MANAGER)) {
            mTraceManager.logTrace(LOG_TAG + ".getFocusColor", FLAGS_ACCESSIBILITY_MANAGER);
        }
        synchronized (mLock) {
            final AccessibilityUserState userState = getCurrentUserStateLocked();

            return userState.getFocusColorLocked();
        }
    }

    /**
     * Gets the status of the audio description preference.
     * @return {@code true} if the audio description is enabled, {@code false} otherwise.
     */
    @Override
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

    @Override
    @RequiresPermission(Manifest.permission.SET_SYSTEM_AUDIO_CAPTION)
    public void setSystemAudioCaptioningEnabled(boolean isEnabled, int userId) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.SET_SYSTEM_AUDIO_CAPTION,
                "setSystemAudioCaptioningEnabled");

        mCaptioningManagerImpl.setSystemAudioCaptioningEnabled(isEnabled, userId);
    }

    @Override
    public boolean isSystemAudioCaptioningUiEnabled(int userId) {
        return mCaptioningManagerImpl.isSystemAudioCaptioningUiEnabled(userId);
    }

    @Override
    @RequiresPermission(Manifest.permission.SET_SYSTEM_AUDIO_CAPTION)
    public void setSystemAudioCaptioningUiEnabled(boolean isEnabled, int userId) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.SET_SYSTEM_AUDIO_CAPTION,
                "setSystemAudioCaptioningUiEnabled");

        mCaptioningManagerImpl.setSystemAudioCaptioningUiEnabled(isEnabled, userId);
    }

    @Override
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;
        synchronized (mLock) {
            pw.println("ACCESSIBILITY MANAGER (dumpsys accessibility)");
            pw.println();
            pw.append("currentUserId=").append(String.valueOf(mCurrentUserId));
            pw.println();
            pw.append("hasWindowMagnificationConnection=").append(
                    String.valueOf(getWindowMagnificationMgr().isConnected()));
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
        AccessibilityUserState userState = getUserStateLocked(mCurrentUserId);
        onUserStateChangedLocked(userState);
        if (serviceInfoChanged) {
            scheduleNotifyClientsOfServicesStateChangeLocked(userState);
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        new AccessibilityShellCommand(this, mSystemActionPerformer).exec(this, in, out, err, args,
                callback, resultReceiver);
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
                final WindowInfo windowInfo = mA11yWindowManager.findWindowInfoByIdLocked(
                        focus.getWindowId());
                MagnificationSpec spec = null;
                if (windowInfo != null) {
                    spec = new MagnificationSpec();
                    spec.setTo(windowInfo.mMagnificationSpec);
                }

                if (spec != null && !spec.isNop()) {
                    boundsInScreenBeforeMagnification.offset((int) -spec.offsetX,
                            (int) -spec.offsetY);
                    boundsInScreenBeforeMagnification.scale(1 / spec.scale);
                }

                //Clip to the window bounds.
                Rect windowBounds = mTempRect1;
                getWindowBounds(focus.getWindowId(), windowBounds);
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
     * A Utility class to handle display state.
     */
    public class AccessibilityDisplayListener implements DisplayManager.DisplayListener {
        private final DisplayManager mDisplayManager;
        private final ArrayList<Display> mDisplaysList = new ArrayList<>();
        private int mSystemUiUid = 0;

        AccessibilityDisplayListener(Context context, MainHandler handler) {
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
            final Display display = mDisplayManager.getDisplay(displayId);
            if (!isValidDisplay(display)) {
                return;
            }

            synchronized (mLock) {
                mDisplaysList.add(display);
                if (mInputFilter != null) {
                    mInputFilter.onDisplayAdded(display);
                }
                AccessibilityUserState userState = getCurrentUserStateLocked();
                if (displayId != Display.DEFAULT_DISPLAY) {
                    final List<AccessibilityServiceConnection> services = userState.mBoundServices;
                    for (int i = 0; i < services.size(); i++) {
                        AccessibilityServiceConnection boundClient = services.get(i);
                        boundClient.onDisplayAdded(displayId);
                    }
                }
                updateMagnificationLocked(userState);
                updateWindowsForAccessibilityCallbackLocked(userState);
                notifyClearAccessibilityCacheLocked();
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            synchronized (mLock) {
                if (!removeDisplayFromList(displayId)) {
                    return;
                }
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

        private Client(IAccessibilityManagerClient callback, int clientUid,
                AccessibilityUserState userState) {
            mCallback = callback;
            mPackageNames = mPackageManager.getPackagesForUid(clientUid);
            synchronized (mLock) {
                mLastSentRelevantEventTypes = computeRelevantEventTypesLocked(userState, this);
            }
        }
    }

    private final class AccessibilityContentObserver extends ContentObserver {

        private final Uri mTouchExplorationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.TOUCH_EXPLORATION_ENABLED);

        private final Uri mDisplayMagnificationEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED);

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

        public AccessibilityContentObserver(Handler handler) {
            super(handler);
        }

        public void register(ContentResolver contentResolver) {
            contentResolver.registerContentObserver(mTouchExplorationEnabledUri,
                    false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(mDisplayMagnificationEnabledUri,
                    false, this, UserHandle.USER_ALL);
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
                    mUserNonInteractiveUiTimeoutUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mUserInteractiveUiTimeoutUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mMagnificationModeUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mMagnificationCapabilityUri, false, this, UserHandle.USER_ALL);
            contentResolver.registerContentObserver(
                    mMagnificationFollowTypingUri, false, this, UserHandle.USER_ALL);
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
                } else if (mDisplayMagnificationEnabledUri.equals(uri)) {
                    if (readMagnificationEnabledSettingsLocked(userState)) {
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
                        userState.updateCrashedServicesIfNeededLocked();
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
                    if (readAccessibilityShortcutKeySettingLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilityButtonComponentIdUri.equals(uri)) {
                    if (readAccessibilityButtonTargetComponentLocked(userState)) {
                        onUserStateChangedLocked(userState);
                    }
                } else if (mAccessibilityButtonTargetsUri.equals(uri)) {
                    if (readAccessibilityButtonTargetsLocked(userState)) {
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
        updateWindowMagnificationConnectionIfNeeded(userState);
        // Remove magnification button UI when the magnification capability is not all mode or
        // magnification is disabled.
        if (!(userState.isDisplayMagnificationEnabledLocked()
                || userState.isShortcutMagnificationEnabledLocked())
                || userState.getMagnificationCapabilitiesLocked()
                != Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL) {

            for (int i = 0; i < displays.size(); i++) {
                final int displayId = displays.get(i).getDisplayId();
                getWindowMagnificationMgr().removeMagnificationButton(displayId);
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
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::createSessionForConnection, this, connection));
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::bindAndStartInputForConnection, this, connection));
    }

    @Override
    public void unbindImeLocked(AbstractAccessibilityServiceConnection connection) {
        mMainHandler.sendMessage(obtainMessage(
                AccessibilityManagerService::unbindInputForConnection, this, connection));
    }

    private void createSessionForConnection(AbstractAccessibilityServiceConnection connection) {
        synchronized (mLock) {
            if (mInputSessionRequested) {
                connection.createImeSessionLocked();
            }
        }
    }

    private void bindAndStartInputForConnection(AbstractAccessibilityServiceConnection connection) {
        synchronized (mLock) {
            if (mInputBinding != null) {
                connection.bindInputLocked(mInputBinding);
                connection.startInputLocked(mStartInputToken, mInputContext, mEditorInfo,
                        mRestarting);
            }
        }
    }

    private void unbindInputForConnection(AbstractAccessibilityServiceConnection connection) {
        InputMethodManagerInternal.get().unbindAccessibilityFromCurrentClient(connection.mId);
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
                client.mCallback.setFocusAppearance(userState.getFocusStrokeWidthLocked(),
                        userState.getFocusColorLocked());
            }));
        });

    }

    public AccessibilityTraceManager getTraceManager() {
        return mTraceManager;
    }

    /**
     * Bind input for accessibility services which request ime capabilities.
     *
     * @param binding Information given to an accessibility service about a client connecting to it.
     */
    public void scheduleBindInput(InputBinding binding) {
        mMainHandler.sendMessage(obtainMessage(AccessibilityManagerService::bindInput, this,
                binding));
    }

    private void bindInput(InputBinding binding) {
        synchronized (mLock) {
            // Keep records of these in case new Accessibility Services are enabled.
            mInputBinding = binding;
            AccessibilityUserState userState = getCurrentUserStateLocked();
            for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
                if (service.requestImeApis()) {
                    service.bindInputLocked(binding);
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
    public void scheduleStartInput(IBinder startInputToken, IInputContext inputContext,
            EditorInfo editorInfo, boolean restarting) {
        mMainHandler.sendMessage(obtainMessage(AccessibilityManagerService::startInput, this,
                startInputToken, inputContext, editorInfo, restarting));
    }

    private void startInput(IBinder startInputToken, IInputContext inputContext,
            EditorInfo editorInfo, boolean restarting) {
        synchronized (mLock) {
            // Keep records of these in case new Accessibility Services are enabled.
            mStartInputToken = startInputToken;
            mInputContext = inputContext;
            mEditorInfo = editorInfo;
            mRestarting = restarting;
            AccessibilityUserState userState = getCurrentUserStateLocked();
            for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
                if (service.requestImeApis()) {
                    service.startInputLocked(startInputToken, inputContext, editorInfo, restarting);
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
    public void scheduleSetImeSessionEnabled(SparseArray<IInputMethodSession> sessions,
            boolean enabled) {
        mMainHandler.sendMessage(obtainMessage(AccessibilityManagerService::setImeSessionEnabled,
                this, sessions, enabled));
    }

    private void setImeSessionEnabled(SparseArray<IInputMethodSession> sessions, boolean enabled) {
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
}
