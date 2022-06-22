/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.systemui.globalactions;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_GLOBAL_ACTIONS;
import static android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.IActivityManager;
import android.app.StatusBarManager;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.sysprop.TelephonyProperties;
import android.telecom.TelecomManager;
import android.telephony.ServiceState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.GestureDetector;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.EmergencyAffordanceManager;
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.MultiListLayout;
import com.android.systemui.MultiListLayout.MultiListAdapter;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.GlobalActions.GlobalActionsManager;
import com.android.systemui.plugins.GlobalActionsPanelPlugin;
import com.android.systemui.scrim.ScrimDrawable;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.util.EmergencyDialerConstants;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that may show depending
 * on whether the keyguard is showing, and whether the device is provisioned.
 */
public class GlobalActionsDialogLite implements DialogInterface.OnDismissListener,
        DialogInterface.OnShowListener,
        ConfigurationController.ConfigurationListener,
        GlobalActionsPanelPlugin.Callbacks,
        LifecycleOwner {

    public static final String SYSTEM_DIALOG_REASON_KEY = "reason";
    public static final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    public static final String SYSTEM_DIALOG_REASON_DREAM = "dream";

    private static final String TAG = "GlobalActionsDialogLite";

    private static final String INTERACTION_JANK_TAG = "global_actions";

    private static final boolean SHOW_SILENT_TOGGLE = true;

    /* Valid settings for global actions keys.
     * see config.xml config_globalActionList */
    @VisibleForTesting
    static final String GLOBAL_ACTION_KEY_POWER = "power";
    private static final String GLOBAL_ACTION_KEY_AIRPLANE = "airplane";
    static final String GLOBAL_ACTION_KEY_BUGREPORT = "bugreport";
    private static final String GLOBAL_ACTION_KEY_SILENT = "silent";
    private static final String GLOBAL_ACTION_KEY_USERS = "users";
    private static final String GLOBAL_ACTION_KEY_SETTINGS = "settings";
    static final String GLOBAL_ACTION_KEY_LOCKDOWN = "lockdown";
    private static final String GLOBAL_ACTION_KEY_VOICEASSIST = "voiceassist";
    private static final String GLOBAL_ACTION_KEY_ASSIST = "assist";
    static final String GLOBAL_ACTION_KEY_RESTART = "restart";
    private static final String GLOBAL_ACTION_KEY_LOGOUT = "logout";
    static final String GLOBAL_ACTION_KEY_EMERGENCY = "emergency";
    static final String GLOBAL_ACTION_KEY_SCREENSHOT = "screenshot";

    // See NotificationManagerService#scheduleDurationReachedLocked
    private static final long TOAST_FADE_TIME = 333;
    // See NotificationManagerService.LONG_DELAY
    private static final int TOAST_VISIBLE_TIME = 3500;

    private final Context mContext;
    private final GlobalActionsManager mWindowManagerFuncs;
    private final AudioManager mAudioManager;
    private final IDreamManager mDreamManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final LockPatternUtils mLockPatternUtils;
    private final TelephonyListenerManager mTelephonyListenerManager;
    private final KeyguardStateController mKeyguardStateController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    protected final GlobalSettings mGlobalSettings;
    protected final SecureSettings mSecureSettings;
    protected final Resources mResources;
    private final ConfigurationController mConfigurationController;
    private final UserManager mUserManager;
    private final TrustManager mTrustManager;
    private final IActivityManager mIActivityManager;
    private final TelecomManager mTelecomManager;
    private final MetricsLogger mMetricsLogger;
    private final UiEventLogger mUiEventLogger;

    // Used for RingerModeTracker
    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

    @VisibleForTesting
    protected final ArrayList<Action> mItems = new ArrayList<>();
    @VisibleForTesting
    protected final ArrayList<Action> mOverflowItems = new ArrayList<>();
    @VisibleForTesting
    protected final ArrayList<Action> mPowerItems = new ArrayList<>();

    @VisibleForTesting
    protected ActionsDialogLite mDialog;

    private Action mSilentModeAction;
    private ToggleAction mAirplaneModeOn;

    protected MyAdapter mAdapter;
    protected MyOverflowAdapter mOverflowAdapter;
    protected MyPowerOptionsAdapter mPowerAdapter;

    private boolean mKeyguardShowing = false;
    private boolean mDeviceProvisioned = false;
    private ToggleState mAirplaneState = ToggleState.Off;
    private boolean mIsWaitingForEcmExit = false;
    private boolean mHasTelephony;
    private boolean mHasVibrator;
    private final boolean mShowSilentToggle;
    private final EmergencyAffordanceManager mEmergencyAffordanceManager;
    private final ScreenshotHelper mScreenshotHelper;
    private final SysuiColorExtractor mSysuiColorExtractor;
    private final IStatusBarService mStatusBarService;
    protected final NotificationShadeWindowController mNotificationShadeWindowController;
    private final IWindowManager mIWindowManager;
    private final Executor mBackgroundExecutor;
    private final RingerModeTracker mRingerModeTracker;
    private int mDialogPressDelay = DIALOG_PRESS_DELAY; // ms
    protected Handler mMainHandler;
    private int mSmallestScreenWidthDp;
    private final Optional<CentralSurfaces> mCentralSurfacesOptional;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final DialogLaunchAnimator mDialogLaunchAnimator;

    @VisibleForTesting
    public enum GlobalActionsEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The global actions / power menu surface became visible on the screen.")
        GA_POWER_MENU_OPEN(337),

        @UiEvent(doc = "The global actions / power menu surface was dismissed.")
        GA_POWER_MENU_CLOSE(471),

        @UiEvent(doc = "The global actions bugreport button was pressed.")
        GA_BUGREPORT_PRESS(344),

        @UiEvent(doc = "The global actions bugreport button was long pressed.")
        GA_BUGREPORT_LONG_PRESS(345),

        @UiEvent(doc = "The global actions emergency button was pressed.")
        GA_EMERGENCY_DIALER_PRESS(346),

        @UiEvent(doc = "The global actions screenshot button was pressed.")
        GA_SCREENSHOT_PRESS(347),

        @UiEvent(doc = "The global actions screenshot button was long pressed.")
        GA_SCREENSHOT_LONG_PRESS(348),

        @UiEvent(doc = "The global actions power off button was pressed.")
        GA_SHUTDOWN_PRESS(802),

        @UiEvent(doc = "The global actions power off button was long pressed.")
        GA_SHUTDOWN_LONG_PRESS(803),

        @UiEvent(doc = "The global actions reboot button was pressed.")
        GA_REBOOT_PRESS(349),

        @UiEvent(doc = "The global actions reboot button was long pressed.")
        GA_REBOOT_LONG_PRESS(804),

        @UiEvent(doc = "The global actions lockdown button was pressed.")
        GA_LOCKDOWN_PRESS(354), // already created by cwren apparently

        @UiEvent(doc = "Power menu was opened via quick settings button.")
        GA_OPEN_QS(805),

        @UiEvent(doc = "Power menu was opened via power + volume up.")
        GA_OPEN_POWER_VOLUP(806),

        @UiEvent(doc = "Power menu was opened via long press on power.")
        GA_OPEN_LONG_PRESS_POWER(807),

        @UiEvent(doc = "Power menu was closed via long press on power.")
        GA_CLOSE_LONG_PRESS_POWER(808),

        @UiEvent(doc = "Power menu was dismissed by back gesture.")
        GA_CLOSE_BACK(809),

        @UiEvent(doc = "Power menu was dismissed by tapping outside dialog.")
        GA_CLOSE_TAP_OUTSIDE(810),

        @UiEvent(doc = "Power menu was closed via power + volume up.")
        GA_CLOSE_POWER_VOLUP(811);

        private final int mId;

        GlobalActionsEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    /**
     * @param context everything needs a context :(
     */
    @Inject
    public GlobalActionsDialogLite(
            Context context,
            GlobalActionsManager windowManagerFuncs,
            AudioManager audioManager,
            IDreamManager iDreamManager,
            DevicePolicyManager devicePolicyManager,
            LockPatternUtils lockPatternUtils,
            BroadcastDispatcher broadcastDispatcher,
            TelephonyListenerManager telephonyListenerManager,
            GlobalSettings globalSettings,
            SecureSettings secureSettings,
            @NonNull VibratorHelper vibrator,
            @Main Resources resources,
            ConfigurationController configurationController,
            KeyguardStateController keyguardStateController,
            UserManager userManager,
            TrustManager trustManager,
            IActivityManager iActivityManager,
            @Nullable TelecomManager telecomManager,
            MetricsLogger metricsLogger,
            SysuiColorExtractor colorExtractor,
            IStatusBarService statusBarService,
            NotificationShadeWindowController notificationShadeWindowController,
            IWindowManager iWindowManager,
            @Background Executor backgroundExecutor,
            UiEventLogger uiEventLogger,
            RingerModeTracker ringerModeTracker,
            @Main Handler handler,
            PackageManager packageManager,
            Optional<CentralSurfaces> centralSurfacesOptional,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DialogLaunchAnimator dialogLaunchAnimator) {
        mContext = context;
        mWindowManagerFuncs = windowManagerFuncs;
        mAudioManager = audioManager;
        mDreamManager = iDreamManager;
        mDevicePolicyManager = devicePolicyManager;
        mLockPatternUtils = lockPatternUtils;
        mTelephonyListenerManager = telephonyListenerManager;
        mKeyguardStateController = keyguardStateController;
        mBroadcastDispatcher = broadcastDispatcher;
        mGlobalSettings = globalSettings;
        mSecureSettings = secureSettings;
        mResources = resources;
        mConfigurationController = configurationController;
        mUserManager = userManager;
        mTrustManager = trustManager;
        mIActivityManager = iActivityManager;
        mTelecomManager = telecomManager;
        mMetricsLogger = metricsLogger;
        mUiEventLogger = uiEventLogger;
        mSysuiColorExtractor = colorExtractor;
        mStatusBarService = statusBarService;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mIWindowManager = iWindowManager;
        mBackgroundExecutor = backgroundExecutor;
        mRingerModeTracker = ringerModeTracker;
        mMainHandler = handler;
        mSmallestScreenWidthDp = resources.getConfiguration().smallestScreenWidthDp;
        mCentralSurfacesOptional = centralSurfacesOptional;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mDialogLaunchAnimator = dialogLaunchAnimator;

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter);

        mHasTelephony = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);

        // get notified of phone state changes
        mTelephonyListenerManager.addServiceStateListener(mPhoneStateListener);
        mGlobalSettings.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), true,
                mAirplaneModeObserver);
        mHasVibrator = vibrator.hasVibrator();

        mShowSilentToggle = SHOW_SILENT_TOGGLE && !resources.getBoolean(
                R.bool.config_useFixedVolume);
        if (mShowSilentToggle) {
            mRingerModeTracker.getRingerMode().observe(this, ringer ->
                    mHandler.sendEmptyMessage(MESSAGE_REFRESH)
            );
        }

        mEmergencyAffordanceManager = new EmergencyAffordanceManager(context);
        mScreenshotHelper = new ScreenshotHelper(context);

        mConfigurationController.addCallback(this);
    }

    /**
     * Clean up callbacks
     */
    public void destroy() {
        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        mTelephonyListenerManager.removeServiceStateListener(mPhoneStateListener);
        mGlobalSettings.unregisterContentObserver(mAirplaneModeObserver);
        mConfigurationController.removeCallback(this);
    }

    protected Context getContext() {
        return mContext;
    }

    protected UiEventLogger getEventLogger() {
        return mUiEventLogger;
    }

    protected Optional<CentralSurfaces> getCentralSurfaces() {
        return mCentralSurfacesOptional;
    }

    protected KeyguardUpdateMonitor getKeyguardUpdateMonitor() {
        return mKeyguardUpdateMonitor;
    }

    /**
     * Show the global actions dialog (creating if necessary) or hide it if it's already showing.
     *
     * @param keyguardShowing     True if keyguard is showing
     * @param isDeviceProvisioned True if device is provisioned
     * @param view                The view from which we should animate the dialog when showing it
     */
    public void showOrHideDialog(boolean keyguardShowing, boolean isDeviceProvisioned,
            @Nullable View view) {
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = isDeviceProvisioned;
        if (mDialog != null && mDialog.isShowing()) {
            // In order to force global actions to hide on the same affordance press, we must
            // register a call to onGlobalActionsShown() first to prevent the default actions
            // menu from showing. This will be followed by a subsequent call to
            // onGlobalActionsHidden() on dismiss()
            mWindowManagerFuncs.onGlobalActionsShown();
            mDialog.dismiss();
            mDialog = null;
        } else {
            handleShow(view);
        }
    }

    protected boolean isKeyguardShowing() {
        return mKeyguardShowing;
    }

    protected boolean isDeviceProvisioned() {
        return mDeviceProvisioned;
    }

    /**
     * Dismiss the global actions dialog, if it's currently shown
     */
    public void dismissDialog() {
        mHandler.removeMessages(MESSAGE_DISMISS);
        mHandler.sendEmptyMessage(MESSAGE_DISMISS);
    }

    protected void awakenIfNecessary() {
        if (mDreamManager != null) {
            try {
                if (mDreamManager.isDreaming()) {
                    mDreamManager.awaken();
                }
            } catch (RemoteException e) {
                // we tried
            }
        }
    }

    protected void handleShow(@Nullable View view) {
        awakenIfNecessary();
        mDialog = createDialog();
        prepareDialog();

        WindowManager.LayoutParams attrs = mDialog.getWindow().getAttributes();
        attrs.setTitle("ActionsDialog");
        attrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mDialog.getWindow().setAttributes(attrs);
        // Don't acquire soft keyboard focus, to avoid destroying state when capturing bugreports
        mDialog.getWindow().addFlags(FLAG_ALT_FOCUSABLE_IM);

        if (view != null) {
            mDialogLaunchAnimator.showFromView(mDialog, view,
                    new DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                            INTERACTION_JANK_TAG));
        } else {
            mDialog.show();
        }
        mWindowManagerFuncs.onGlobalActionsShown();
    }

    @VisibleForTesting
    protected boolean shouldShowAction(Action action) {
        if (mKeyguardShowing && !action.showDuringKeyguard()) {
            return false;
        }
        if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
            return false;
        }
        return action.shouldShow();
    }

    /**
     * Returns the maximum number of power menu items to show based on which GlobalActions
     * layout is being used.
     */
    @VisibleForTesting
    protected int getMaxShownPowerItems() {
        return mResources.getInteger(com.android.systemui.R.integer.power_menu_lite_max_columns)
                * mResources.getInteger(com.android.systemui.R.integer.power_menu_lite_max_rows);
    }

    /**
     * Add a power menu action item for to either the main or overflow items lists, depending on
     * whether controls are enabled and whether the max number of shown items has been reached.
     */
    private void addActionItem(Action action) {
        if (mItems.size() < getMaxShownPowerItems()) {
            mItems.add(action);
        } else {
            mOverflowItems.add(action);
        }
    }

    @VisibleForTesting
    protected String[] getDefaultActions() {
        return mResources.getStringArray(R.array.config_globalActionsList);
    }

    private void addIfShouldShowAction(List<Action> actions, Action action) {
        if (shouldShowAction(action)) {
            actions.add(action);
        }
    }

    @VisibleForTesting
    protected void createActionItems() {
        // Simple toggle style if there's no vibrator, otherwise use a tri-state
        if (!mHasVibrator) {
            mSilentModeAction = new SilentModeToggleAction();
        } else {
            mSilentModeAction = new SilentModeTriStateAction(mAudioManager, mHandler);
        }
        mAirplaneModeOn = new AirplaneModeAction();
        onAirplaneModeChanged();

        mItems.clear();
        mOverflowItems.clear();
        mPowerItems.clear();
        String[] defaultActions = getDefaultActions();

        ShutDownAction shutdownAction = new ShutDownAction();
        RestartAction restartAction = new RestartAction();
        ArraySet<String> addedKeys = new ArraySet<>();
        List<Action> tempActions = new ArrayList<>();
        CurrentUserProvider currentUser = new CurrentUserProvider();

        // make sure emergency affordance action is first, if needed
        if (mEmergencyAffordanceManager.needsEmergencyAffordance()) {
            addIfShouldShowAction(tempActions, new EmergencyAffordanceAction());
            addedKeys.add(GLOBAL_ACTION_KEY_EMERGENCY);
        }

        for (int i = 0; i < defaultActions.length; i++) {
            String actionKey = defaultActions[i];
            if (addedKeys.contains(actionKey)) {
                // If we already have added this, don't add it again.
                continue;
            }
            if (GLOBAL_ACTION_KEY_POWER.equals(actionKey)) {
                addIfShouldShowAction(tempActions, shutdownAction);
            } else if (GLOBAL_ACTION_KEY_AIRPLANE.equals(actionKey)) {
                addIfShouldShowAction(tempActions, mAirplaneModeOn);
            } else if (GLOBAL_ACTION_KEY_BUGREPORT.equals(actionKey)) {
                if (shouldDisplayBugReport(currentUser.get())) {
                    addIfShouldShowAction(tempActions, new BugReportAction());
                }
            } else if (GLOBAL_ACTION_KEY_SILENT.equals(actionKey)) {
                if (mShowSilentToggle) {
                    addIfShouldShowAction(tempActions, mSilentModeAction);
                }
            } else if (GLOBAL_ACTION_KEY_USERS.equals(actionKey)) {
                if (SystemProperties.getBoolean("fw.power_user_switcher", false)) {
                    addUserActions(tempActions, currentUser.get());
                }
            } else if (GLOBAL_ACTION_KEY_SETTINGS.equals(actionKey)) {
                addIfShouldShowAction(tempActions, getSettingsAction());
            } else if (GLOBAL_ACTION_KEY_LOCKDOWN.equals(actionKey)) {
                if (shouldDisplayLockdown(currentUser.get())) {
                    addIfShouldShowAction(tempActions, new LockDownAction());
                }
            } else if (GLOBAL_ACTION_KEY_VOICEASSIST.equals(actionKey)) {
                addIfShouldShowAction(tempActions, getVoiceAssistAction());
            } else if (GLOBAL_ACTION_KEY_ASSIST.equals(actionKey)) {
                addIfShouldShowAction(tempActions, getAssistAction());
            } else if (GLOBAL_ACTION_KEY_RESTART.equals(actionKey)) {
                addIfShouldShowAction(tempActions, restartAction);
            } else if (GLOBAL_ACTION_KEY_SCREENSHOT.equals(actionKey)) {
                addIfShouldShowAction(tempActions, new ScreenshotAction());
            } else if (GLOBAL_ACTION_KEY_LOGOUT.equals(actionKey)) {
                // TODO(b/206032495): should call mDevicePolicyManager.getLogoutUserId() instead of
                // hardcode it to USER_SYSTEM so it properly supports headless system user mode
                // (and then call mDevicePolicyManager.clearLogoutUser() after switched)
                if (mDevicePolicyManager.isLogoutEnabled()
                        && currentUser.get() != null
                        && currentUser.get().id != UserHandle.USER_SYSTEM) {
                    addIfShouldShowAction(tempActions, new LogoutAction());
                }
            } else if (GLOBAL_ACTION_KEY_EMERGENCY.equals(actionKey)) {
                if (shouldDisplayEmergency()) {
                    addIfShouldShowAction(tempActions, new EmergencyDialerAction());
                }
            } else {
                Log.e(TAG, "Invalid global action key " + actionKey);
            }
            // Add here so we don't add more than one.
            addedKeys.add(actionKey);
        }

        // replace power and restart with a single power options action, if needed
        if (tempActions.contains(shutdownAction) && tempActions.contains(restartAction)
                && tempActions.size() > getMaxShownPowerItems()) {
            // transfer shutdown and restart to their own list of power actions
            int powerOptionsIndex = Math.min(tempActions.indexOf(restartAction),
                    tempActions.indexOf(shutdownAction));
            tempActions.remove(shutdownAction);
            tempActions.remove(restartAction);
            mPowerItems.add(shutdownAction);
            mPowerItems.add(restartAction);

            // add the PowerOptionsAction after Emergency, if present
            tempActions.add(powerOptionsIndex, new PowerOptionsAction());
        }
        for (Action action : tempActions) {
            addActionItem(action);
        }
    }

    protected void onRefresh() {
        // re-allocate actions between main and overflow lists
        this.createActionItems();
    }

    protected void initDialogItems() {
        createActionItems();
        mAdapter = new MyAdapter();
        mOverflowAdapter = new MyOverflowAdapter();
        mPowerAdapter = new MyPowerOptionsAdapter();
    }

    /**
     * Create the global actions dialog.
     *
     * @return A new dialog.
     */
    protected ActionsDialogLite createDialog() {
        initDialogItems();

        ActionsDialogLite dialog = new ActionsDialogLite(mContext,
                com.android.systemui.R.style.Theme_SystemUI_Dialog_GlobalActionsLite,
                mAdapter, mOverflowAdapter, mSysuiColorExtractor, mStatusBarService,
                mNotificationShadeWindowController, this::onRefresh, mKeyguardShowing,
                mPowerAdapter, mUiEventLogger, mCentralSurfacesOptional, mKeyguardUpdateMonitor,
                mLockPatternUtils);

        dialog.setOnDismissListener(this);
        dialog.setOnShowListener(this);

        return dialog;
    }

    @VisibleForTesting
    boolean shouldDisplayLockdown(UserInfo user) {
        if (user == null) {
            return false;
        }

        int userId = user.id;

        // Lockdown is meaningless without a place to go.
        if (!mKeyguardStateController.isMethodSecure()) {
            return false;
        }

        // Only show the lockdown button if the device isn't locked down (for whatever reason).
        int state = mLockPatternUtils.getStrongAuthForUser(userId);
        return (state == STRONG_AUTH_NOT_REQUIRED
                || state == SOME_AUTH_REQUIRED_AFTER_USER_REQUEST);
    }

    @VisibleForTesting
    boolean shouldDisplayEmergency() {
        // Emergency calling requires a telephony radio.
        return mHasTelephony;
    }

    @VisibleForTesting
    boolean shouldDisplayBugReport(UserInfo currentUser) {
        return mGlobalSettings.getInt(Settings.Global.BUGREPORT_IN_POWER_MENU, 0) != 0
                && (currentUser == null || currentUser.isPrimary());
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        if (mDialog != null && mDialog.isShowing()
                && (newConfig.smallestScreenWidthDp != mSmallestScreenWidthDp)) {
            mSmallestScreenWidthDp = newConfig.smallestScreenWidthDp;
            mDialog.refreshDialog();
        }
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.Callbacks#dismissGlobalActionsMenu()}, which is
     * called when the quick access wallet requests dismissal.
     */
    @Override
    public void dismissGlobalActionsMenu() {
        dismissDialog();
    }

    @VisibleForTesting
    protected final class PowerOptionsAction extends SinglePressAction {
        private PowerOptionsAction() {
            super(com.android.systemui.R.drawable.ic_settings_power,
                    R.string.global_action_power_options);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            if (mDialog != null) {
                mDialog.showPowerOptionsMenu();
            }
        }
    }

    @VisibleForTesting
    final class ShutDownAction extends SinglePressAction implements LongPressAction {
        ShutDownAction() {
            super(R.drawable.ic_lock_power_off,
                    R.string.global_action_power_off);
        }

        @Override
        public boolean onLongPress() {
            mUiEventLogger.log(GlobalActionsEvent.GA_SHUTDOWN_LONG_PRESS);
            if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_SAFE_BOOT)) {
                mWindowManagerFuncs.reboot(true);
                return true;
            }
            return false;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            mUiEventLogger.log(GlobalActionsEvent.GA_SHUTDOWN_PRESS);
            // shutdown by making sure radio and power are handled accordingly.
            mWindowManagerFuncs.shutdown();
        }
    }

    @VisibleForTesting
    protected abstract class EmergencyAction extends SinglePressAction {
        EmergencyAction(int iconResId, int messageResId) {
            super(iconResId, messageResId);
        }

        @Override
        public boolean shouldBeSeparated() {
            return false;
        }

        @Override
        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = super.create(context, convertView, parent, inflater);
            int textColor = getEmergencyTextColor(context);
            int iconColor = getEmergencyIconColor(context);
            int backgroundColor = getEmergencyBackgroundColor(context);
            TextView messageView = v.findViewById(R.id.message);
            messageView.setTextColor(textColor);
            messageView.setSelected(true); // necessary for marquee to work
            ImageView icon = v.findViewById(R.id.icon);
            icon.getDrawable().setTint(iconColor);
            icon.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
            v.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
            return v;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }
    }

    protected int getEmergencyTextColor(Context context) {
        return context.getResources().getColor(
                com.android.systemui.R.color.global_actions_lite_text);
    }

    protected int getEmergencyIconColor(Context context) {
        return context.getResources().getColor(
                com.android.systemui.R.color.global_actions_lite_emergency_icon);
    }

    protected int getEmergencyBackgroundColor(Context context) {
        return context.getResources().getColor(
                com.android.systemui.R.color.global_actions_lite_emergency_background);
    }

    private class EmergencyAffordanceAction extends EmergencyAction {
        EmergencyAffordanceAction() {
            super(R.drawable.emergency_icon,
                    R.string.global_action_emergency);
        }

        @Override
        public void onPress() {
            mEmergencyAffordanceManager.performEmergencyCall();
        }
    }

    @VisibleForTesting
    class EmergencyDialerAction extends EmergencyAction {
        private EmergencyDialerAction() {
            super(com.android.systemui.R.drawable.ic_emergency_star,
                    R.string.global_action_emergency);
        }

        @Override
        public void onPress() {
            mMetricsLogger.action(MetricsEvent.ACTION_EMERGENCY_DIALER_FROM_POWER_MENU);
            mUiEventLogger.log(GlobalActionsEvent.GA_EMERGENCY_DIALER_PRESS);
            if (mTelecomManager != null) {
                // Close shade so user sees the activity
                mCentralSurfacesOptional.ifPresent(CentralSurfaces::collapseShade);
                Intent intent = mTelecomManager.createLaunchEmergencyDialerIntent(
                        null /* number */);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra(EmergencyDialerConstants.EXTRA_ENTRY_TYPE,
                        EmergencyDialerConstants.ENTRY_TYPE_POWER_MENU);
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            }
        }
    }

    @VisibleForTesting
    EmergencyDialerAction makeEmergencyDialerActionForTesting() {
        return new EmergencyDialerAction();
    }

    @VisibleForTesting
    final class RestartAction extends SinglePressAction implements LongPressAction {
        RestartAction() {
            super(R.drawable.ic_restart, R.string.global_action_restart);
        }

        @Override
        public boolean onLongPress() {
            mUiEventLogger.log(GlobalActionsEvent.GA_REBOOT_LONG_PRESS);
            if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_SAFE_BOOT)) {
                mWindowManagerFuncs.reboot(true);
                return true;
            }
            return false;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            mUiEventLogger.log(GlobalActionsEvent.GA_REBOOT_PRESS);
            mWindowManagerFuncs.reboot(false);
        }
    }

    @VisibleForTesting
    class ScreenshotAction extends SinglePressAction {
        ScreenshotAction() {
            super(R.drawable.ic_screenshot, R.string.global_action_screenshot);
        }

        @Override
        public void onPress() {
            // Add a little delay before executing, to give the
            // dialog a chance to go away before it takes a
            // screenshot.
            // TODO: instead, omit global action dialog layer
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mScreenshotHelper.takeScreenshot(TAKE_SCREENSHOT_FULLSCREEN, true, true,
                            SCREENSHOT_GLOBAL_ACTIONS, mHandler, null);
                    mMetricsLogger.action(MetricsEvent.ACTION_SCREENSHOT_POWER_MENU);
                    mUiEventLogger.log(GlobalActionsEvent.GA_SCREENSHOT_PRESS);
                }
            }, mDialogPressDelay);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        @Override
        public boolean shouldShow() {
            // Include screenshot in power menu for legacy nav because it is not accessible
            // through Recents in that mode
            return is2ButtonNavigationEnabled();
        }

        boolean is2ButtonNavigationEnabled() {
            return NAV_BAR_MODE_2BUTTON == mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_navBarInteractionMode);
        }
    }

    @VisibleForTesting
    ScreenshotAction makeScreenshotActionForTesting() {
        return new ScreenshotAction();
    }

    @VisibleForTesting
    class BugReportAction extends SinglePressAction implements LongPressAction {

        BugReportAction() {
            super(R.drawable.ic_lock_bugreport, R.string.bugreport_title);
        }

        @Override
        public void onPress() {
            // don't actually trigger the bugreport if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return;
            }
            // Add a little delay before executing, to give the
            // dialog a chance to go away before it takes a
            // screenshot.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Take an "interactive" bugreport.
                        mMetricsLogger.action(
                                MetricsEvent.ACTION_BUGREPORT_FROM_POWER_MENU_INTERACTIVE);
                        mUiEventLogger.log(GlobalActionsEvent.GA_BUGREPORT_PRESS);
                        if (!mIActivityManager.launchBugReportHandlerApp()) {
                            Log.w(TAG, "Bugreport handler could not be launched");
                            mIActivityManager.requestInteractiveBugReport();
                        }
                        // Close shade so user sees the activity
                        mCentralSurfacesOptional.ifPresent(CentralSurfaces::collapseShade);
                    } catch (RemoteException e) {
                    }
                }
            }, mDialogPressDelay);
        }

        @Override
        public boolean onLongPress() {
            // don't actually trigger the bugreport if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return false;
            }
            try {
                // Take a "full" bugreport.
                mMetricsLogger.action(MetricsEvent.ACTION_BUGREPORT_FROM_POWER_MENU_FULL);
                mUiEventLogger.log(GlobalActionsEvent.GA_BUGREPORT_LONG_PRESS);
                mIActivityManager.requestFullBugReport();
                // Close shade so user sees the activity
                mCentralSurfacesOptional.ifPresent(CentralSurfaces::collapseShade);
            } catch (RemoteException e) {
            }
            return false;
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return Build.isDebuggable() && mGlobalSettings.getInt(
                    Settings.Global.BUGREPORT_IN_POWER_MENU, 0) != 0;
        }
    }

    @VisibleForTesting
    BugReportAction makeBugReportActionForTesting() {
        return new BugReportAction();
    }

    private final class LogoutAction extends SinglePressAction {
        private LogoutAction() {
            super(R.drawable.ic_logout, R.string.global_action_logout);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        @Override
        public void onPress() {
            // Add a little delay before executing, to give the dialog a chance to go away before
            // switching user
            mHandler.postDelayed(() -> {
                mDevicePolicyManager.logoutUser();
            }, mDialogPressDelay);
        }
    }

    private Action getSettingsAction() {
        return new SinglePressAction(R.drawable.ic_settings,
                R.string.global_action_settings) {

            @Override
            public void onPress() {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getAssistAction() {
        return new SinglePressAction(R.drawable.ic_action_assist_focused,
                R.string.global_action_assist) {
            @Override
            public void onPress() {
                Intent intent = new Intent(Intent.ACTION_ASSIST);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getVoiceAssistAction() {
        return new SinglePressAction(R.drawable.ic_voice_search,
                R.string.global_action_voice_assist) {
            @Override
            public void onPress() {
                Intent intent = new Intent(Intent.ACTION_VOICE_ASSIST);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    @VisibleForTesting
    class LockDownAction extends SinglePressAction {
        LockDownAction() {
            super(R.drawable.ic_lock_lockdown, R.string.global_action_lockdown);
        }

        @Override
        public void onPress() {
            mLockPatternUtils.requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN,
                    UserHandle.USER_ALL);
            mUiEventLogger.log(GlobalActionsEvent.GA_LOCKDOWN_PRESS);
            try {
                mIWindowManager.lockNow(null);
                // Lock profiles (if any) on the background thread.
                mBackgroundExecutor.execute(() -> lockProfiles());
            } catch (RemoteException e) {
                Log.e(TAG, "Error while trying to lock device.", e);
            }
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private void lockProfiles() {
        final int currentUserId = getCurrentUser().id;
        final int[] profileIds = mUserManager.getEnabledProfileIds(currentUserId);
        for (final int id : profileIds) {
            if (id != currentUserId) {
                mTrustManager.setDeviceLockedForUser(id, true);
            }
        }
    }

    protected UserInfo getCurrentUser() {
        try {
            return mIActivityManager.getCurrentUser();
        } catch (RemoteException re) {
            return null;
        }
    }

    /**
     * Non-thread-safe current user provider that caches the result - helpful when a method needs
     * to fetch it an indeterminate number of times.
     */
    private class CurrentUserProvider {
        private UserInfo mUserInfo = null;
        private boolean mFetched = false;

        @Nullable
        UserInfo get() {
            if (!mFetched) {
                mFetched = true;
                mUserInfo = getCurrentUser();
            }
            return mUserInfo;
        }
    }

    private void addUserActions(List<Action> actions, UserInfo currentUser) {
        if (mUserManager.isUserSwitcherEnabled()) {
            List<UserInfo> users = mUserManager.getUsers();
            for (final UserInfo user : users) {
                if (user.supportsSwitchToByUser()) {
                    boolean isCurrentUser = currentUser == null
                            ? user.id == 0 : (currentUser.id == user.id);
                    Drawable icon = user.iconPath != null ? Drawable.createFromPath(user.iconPath)
                            : null;
                    SinglePressAction switchToUser = new SinglePressAction(
                            R.drawable.ic_menu_cc, icon,
                            (user.name != null ? user.name : "Primary")
                                    + (isCurrentUser ? " \u2714" : "")) {
                        public void onPress() {
                            try {
                                mIActivityManager.switchUser(user.id);
                            } catch (RemoteException re) {
                                Log.e(TAG, "Couldn't switch user " + re);
                            }
                        }

                        public boolean showDuringKeyguard() {
                            return true;
                        }

                        public boolean showBeforeProvisioning() {
                            return false;
                        }
                    };
                    addIfShouldShowAction(actions, switchToUser);
                }
            }
        }
    }

    protected void prepareDialog() {
        refreshSilentMode();
        mAirplaneModeOn.updateState(mAirplaneState);
        mAdapter.notifyDataSetChanged();
        mLifecycle.setCurrentState(Lifecycle.State.RESUMED);
    }

    private void refreshSilentMode() {
        if (!mHasVibrator) {
            Integer value = mRingerModeTracker.getRingerMode().getValue();
            final boolean silentModeOn = value != null && value != AudioManager.RINGER_MODE_NORMAL;
            ((ToggleAction) mSilentModeAction).updateState(
                    silentModeOn ? ToggleState.On : ToggleState.Off);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDismiss(DialogInterface dialog) {
        if (mDialog == dialog) {
            mDialog = null;
        }
        mUiEventLogger.log(GlobalActionsEvent.GA_POWER_MENU_CLOSE);
        mWindowManagerFuncs.onGlobalActionsHidden();
        mLifecycle.setCurrentState(Lifecycle.State.CREATED);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onShow(DialogInterface dialog) {
        mMetricsLogger.visible(MetricsEvent.POWER_MENU);
        mUiEventLogger.log(GlobalActionsEvent.GA_POWER_MENU_OPEN);
    }

    /**
     * The adapter used for power menu items shown in the global actions dialog.
     */
    public class MyAdapter extends MultiListAdapter {
        private int countItems(boolean separated) {
            int count = 0;
            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);

                if (action.shouldBeSeparated() == separated) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int countSeparatedItems() {
            return countItems(true);
        }

        @Override
        public int countListItems() {
            return countItems(false);
        }

        @Override
        public int getCount() {
            return countSeparatedItems() + countListItems();
        }

        @Override
        public boolean isEnabled(int position) {
            return getItem(position).isEnabled();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public Action getItem(int position) {
            int filteredPos = 0;
            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);
                if (!shouldShowAction(action)) {
                    continue;
                }
                if (filteredPos == position) {
                    return action;
                }
                filteredPos++;
            }

            throw new IllegalArgumentException("position " + position
                    + " out of range of showable actions"
                    + ", filtered count=" + getCount()
                    + ", keyguardshowing=" + mKeyguardShowing
                    + ", provisioned=" + mDeviceProvisioned);
        }

        /**
         * Get the row ID for an item
         * @param position The position of the item within the adapter's data set
         * @return
         */
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            View view = action.create(mContext, convertView, parent, LayoutInflater.from(mContext));
            view.setOnClickListener(v -> onClickItem(position));
            if (action instanceof LongPressAction) {
                view.setOnLongClickListener(v -> onLongClickItem(position));
            }
            return view;
        }

        @Override
        public boolean onLongClickItem(int position) {
            final Action action = mAdapter.getItem(position);
            if (action instanceof LongPressAction) {
                if (mDialog != null) {
                    // Usually clicking an item shuts down the phone, locks, or starts an activity.
                    // We don't want to animate back into the power button when that happens, so we
                    // disable the dialog animation before dismissing.
                    mDialogLaunchAnimator.disableAllCurrentDialogsExitAnimations();
                    mDialog.dismiss();
                } else {
                    Log.w(TAG, "Action long-clicked while mDialog is null.");
                }
                return ((LongPressAction) action).onLongPress();
            }
            return false;
        }

        @Override
        public void onClickItem(int position) {
            Action item = mAdapter.getItem(position);
            if (!(item instanceof SilentModeTriStateAction)) {
                if (mDialog != null) {
                    // don't dismiss the dialog if we're opening the power options menu
                    if (!(item instanceof PowerOptionsAction)) {
                        // Usually clicking an item shuts down the phone, locks, or starts an
                        // activity. We don't want to animate back into the power button when that
                        // happens, so we disable the dialog animation before dismissing.
                        mDialogLaunchAnimator.disableAllCurrentDialogsExitAnimations();
                        mDialog.dismiss();
                    }
                } else {
                    Log.w(TAG, "Action clicked while mDialog is null.");
                }
                item.onPress();
            }
        }

        @Override
        public boolean shouldBeSeparated(int position) {
            return getItem(position).shouldBeSeparated();
        }
    }

    /**
     * The adapter used for items in the overflow menu.
     */
    public class MyPowerOptionsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mPowerItems.size();
        }

        @Override
        public Action getItem(int position) {
            return mPowerItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            if (action == null) {
                Log.w(TAG, "No power options action found at position: " + position);
                return null;
            }
            int viewLayoutResource = com.android.systemui.R.layout.global_actions_power_item;
            View view = convertView != null ? convertView
                    : LayoutInflater.from(mContext).inflate(viewLayoutResource, parent, false);
            view.setOnClickListener(v -> onClickItem(position));
            if (action instanceof LongPressAction) {
                view.setOnLongClickListener(v -> onLongClickItem(position));
            }
            ImageView icon = view.findViewById(R.id.icon);
            TextView messageView = view.findViewById(R.id.message);
            messageView.setSelected(true); // necessary for marquee to work

            icon.setImageDrawable(action.getIcon(mContext));
            icon.setScaleType(ScaleType.CENTER_CROP);

            if (action.getMessage() != null) {
                messageView.setText(action.getMessage());
            } else {
                messageView.setText(action.getMessageResId());
            }
            return view;
        }

        private boolean onLongClickItem(int position) {
            final Action action = getItem(position);
            if (action instanceof LongPressAction) {
                if (mDialog != null) {
                    // Usually clicking an item shuts down the phone, locks, or starts an activity.
                    // We don't want to animate back into the power button when that happens, so we
                    // disable the dialog animation before dismissing.
                    mDialogLaunchAnimator.disableAllCurrentDialogsExitAnimations();
                    mDialog.dismiss();
                } else {
                    Log.w(TAG, "Action long-clicked while mDialog is null.");
                }
                return ((LongPressAction) action).onLongPress();
            }
            return false;
        }

        private void onClickItem(int position) {
            Action item = getItem(position);
            if (!(item instanceof SilentModeTriStateAction)) {
                if (mDialog != null) {
                    // Usually clicking an item shuts down the phone, locks, or starts an activity.
                    // We don't want to animate back into the power button when that happens, so we
                    // disable the dialog animation before dismissing.
                    mDialogLaunchAnimator.disableAllCurrentDialogsExitAnimations();
                    mDialog.dismiss();
                } else {
                    Log.w(TAG, "Action clicked while mDialog is null.");
                }
                item.onPress();
            }
        }
    }

    /**
     * The adapter used for items in the power options menu, triggered by the PowerOptionsAction.
     */
    public class MyOverflowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mOverflowItems.size();
        }

        @Override
        public Action getItem(int position) {
            return mOverflowItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            if (action == null) {
                Log.w(TAG, "No overflow action found at position: " + position);
                return null;
            }
            int viewLayoutResource = com.android.systemui.R.layout.controls_more_item;
            View view = convertView != null ? convertView
                    : LayoutInflater.from(mContext).inflate(viewLayoutResource, parent, false);
            TextView textView = (TextView) view;
            if (action.getMessageResId() != 0) {
                textView.setText(action.getMessageResId());
            } else {
                textView.setText(action.getMessage());
            }
            return textView;
        }

        protected boolean onLongClickItem(int position) {
            final Action action = getItem(position);
            if (action instanceof LongPressAction) {
                if (mDialog != null) {
                    // Usually clicking an item shuts down the phone, locks, or starts an activity.
                    // We don't want to animate back into the power button when that happens, so we
                    // disable the dialog animation before dismissing.
                    mDialogLaunchAnimator.disableAllCurrentDialogsExitAnimations();
                    mDialog.dismiss();
                } else {
                    Log.w(TAG, "Action long-clicked while mDialog is null.");
                }
                return ((LongPressAction) action).onLongPress();
            }
            return false;
        }

        protected void onClickItem(int position) {
            Action item = getItem(position);
            if (!(item instanceof SilentModeTriStateAction)) {
                if (mDialog != null) {
                    // Usually clicking an item shuts down the phone, locks, or starts an activity.
                    // We don't want to animate back into the power button when that happens, so we
                    // disable the dialog animation before dismissing.
                    mDialogLaunchAnimator.disableAllCurrentDialogsExitAnimations();
                    mDialog.dismiss();
                } else {
                    Log.w(TAG, "Action clicked while mDialog is null.");
                }
                item.onPress();
            }
        }
    }

    // note: the scheme below made more sense when we were planning on having
    // 8 different things in the global actions dialog.  seems overkill with
    // only 3 items now, but may as well keep this flexible approach so it will
    // be easy should someone decide at the last minute to include something
    // else, such as 'enable wifi', or 'enable bluetooth'

    /**
     * What each item in the global actions dialog must be able to support.
     */
    public interface Action {
        /**
         * @return Text that will be announced when dialog is created.  null for none.
         */
        CharSequence getLabelForAccessibility(Context context);

        /**
         * Create the item's view
         * @param context
         * @param convertView
         * @param parent
         * @param inflater
         * @return
         */
        View create(Context context, View convertView, ViewGroup parent, LayoutInflater inflater);

        /**
         * Handle a regular press
         */
        void onPress();

        /**
         * @return whether this action should appear in the dialog when the keygaurd is showing.
         */
        boolean showDuringKeyguard();

        /**
         * @return whether this action should appear in the dialog before the
         * device is provisioned.f
         */
        boolean showBeforeProvisioning();

        /**
         * @return whether this action is enabled
         */
        boolean isEnabled();

        /**
         * @return whether this action should be in a separate section
         */
        default boolean shouldBeSeparated() {
            return false;
        }

        /**
         * Return the id of the message associated with this action, or 0 if it doesn't have one.
         * @return
         */
        int getMessageResId();

        /**
         * Return the icon drawable for this action.
         */
        Drawable getIcon(Context context);

        /**
         * Return the message associated with this action, or null if it doesn't have one.
         * @return
         */
        CharSequence getMessage();

        /**
         * @return whether the action should be visible
         */
        default boolean shouldShow() {
            return true;
        }
    }

    /**
     * An action that also supports long press.
     */
    private interface LongPressAction extends Action {
        boolean onLongPress();
    }

    /**
     * A single press action maintains no state, just responds to a press and takes an action.
     */

    private abstract class SinglePressAction implements Action {
        private final int mIconResId;
        private final Drawable mIcon;
        private final int mMessageResId;
        private final CharSequence mMessage;

        protected SinglePressAction(int iconResId, int messageResId) {
            mIconResId = iconResId;
            mMessageResId = messageResId;
            mMessage = null;
            mIcon = null;
        }

        protected SinglePressAction(int iconResId, Drawable icon, CharSequence message) {
            mIconResId = iconResId;
            mMessageResId = 0;
            mMessage = message;
            mIcon = icon;
        }

        public boolean isEnabled() {
            return true;
        }

        public String getStatus() {
            return null;
        }

        public abstract void onPress();

        public CharSequence getLabelForAccessibility(Context context) {
            if (mMessage != null) {
                return mMessage;
            } else {
                return context.getString(mMessageResId);
            }
        }

        public int getMessageResId() {
            return mMessageResId;
        }

        public CharSequence getMessage() {
            return mMessage;
        }

        @Override
        public Drawable getIcon(Context context) {
            if (mIcon != null) {
                return mIcon;
            } else {
                return context.getDrawable(mIconResId);
            }
        }

        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = inflater.inflate(getGridItemLayoutResource(), parent, false /* attach */);
            // ConstraintLayout flow needs an ID to reference
            v.setId(View.generateViewId());

            ImageView icon = v.findViewById(R.id.icon);
            TextView messageView = v.findViewById(R.id.message);
            messageView.setSelected(true); // necessary for marquee to work

            icon.setImageDrawable(getIcon(context));
            icon.setScaleType(ScaleType.CENTER_CROP);

            if (mMessage != null) {
                messageView.setText(mMessage);
            } else {
                messageView.setText(mMessageResId);
            }

            return v;
        }
    }

    protected int getGridItemLayoutResource() {
        return com.android.systemui.R.layout.global_actions_grid_item_lite;
    }

    private enum ToggleState {
        Off(false),
        TurningOn(true),
        TurningOff(true),
        On(false);

        private final boolean mInTransition;

        ToggleState(boolean intermediate) {
            mInTransition = intermediate;
        }

        public boolean inTransition() {
            return mInTransition;
        }
    }

    /**
     * A toggle action knows whether it is on or off, and displays an icon and status message
     * accordingly.
     */
    private abstract class ToggleAction implements Action {

        protected ToggleState mState = ToggleState.Off;

        // prefs
        protected int mEnabledIconResId;
        protected int mDisabledIconResid;
        protected int mMessageResId;
        protected int mEnabledStatusMessageResId;
        protected int mDisabledStatusMessageResId;

        /**
         * @param enabledIconResId           The icon for when this action is on.
         * @param disabledIconResid          The icon for when this action is off.
         * @param message                    The general information message, e.g 'Silent Mode'
         * @param enabledStatusMessageResId  The on status message, e.g 'sound disabled'
         * @param disabledStatusMessageResId The off status message, e.g. 'sound enabled'
         */
        ToggleAction(int enabledIconResId,
                int disabledIconResid,
                int message,
                int enabledStatusMessageResId,
                int disabledStatusMessageResId) {
            mEnabledIconResId = enabledIconResId;
            mDisabledIconResid = disabledIconResid;
            mMessageResId = message;
            mEnabledStatusMessageResId = enabledStatusMessageResId;
            mDisabledStatusMessageResId = disabledStatusMessageResId;
        }

        /**
         * Override to make changes to resource IDs just before creating the View.
         */
        void willCreate() {

        }

        @Override
        public CharSequence getLabelForAccessibility(Context context) {
            return context.getString(mMessageResId);
        }

        private boolean isOn() {
            return mState == ToggleState.On || mState == ToggleState.TurningOn;
        }

        @Override
        public CharSequence getMessage() {
            return null;
        }
        @Override
        public int getMessageResId() {
            return isOn() ? mEnabledStatusMessageResId : mDisabledStatusMessageResId;
        }

        private int getIconResId() {
            return isOn() ? mEnabledIconResId : mDisabledIconResid;
        }

        @Override
        public Drawable getIcon(Context context) {
            return context.getDrawable(getIconResId());
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            willCreate();

            View v = inflater.inflate(com.android.systemui.R.layout.global_actions_grid_item_v2,
                    parent, false /* attach */);
            ViewGroup.LayoutParams p = v.getLayoutParams();
            p.width = WRAP_CONTENT;
            v.setLayoutParams(p);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);
            final boolean enabled = isEnabled();

            if (messageView != null) {
                messageView.setText(getMessageResId());
                messageView.setEnabled(enabled);
                messageView.setSelected(true); // necessary for marquee to work
            }

            if (icon != null) {
                icon.setImageDrawable(context.getDrawable(getIconResId()));
                icon.setEnabled(enabled);
            }

            v.setEnabled(enabled);

            return v;
        }

        public final void onPress() {
            if (mState.inTransition()) {
                Log.w(TAG, "shouldn't be able to toggle when in transition");
                return;
            }

            final boolean nowOn = !(mState == ToggleState.On);
            onToggle(nowOn);
            changeStateFromPress(nowOn);
        }

        public boolean isEnabled() {
            return !mState.inTransition();
        }

        /**
         * Implementations may override this if their state can be in on of the intermediate states
         * until some notification is received (e.g airplane mode is 'turning off' until we know the
         * wireless connections are back online
         *
         * @param buttonOn Whether the button was turned on or off
         */
        protected void changeStateFromPress(boolean buttonOn) {
            mState = buttonOn ? ToggleState.On : ToggleState.Off;
        }

        abstract void onToggle(boolean on);

        public void updateState(ToggleState state) {
            mState = state;
        }
    }

    private class AirplaneModeAction extends ToggleAction {
        AirplaneModeAction() {
            super(
                    R.drawable.ic_lock_airplane_mode,
                    R.drawable.ic_lock_airplane_mode_off,
                    R.string.global_actions_toggle_airplane_mode,
                    R.string.global_actions_airplane_mode_on_status,
                    R.string.global_actions_airplane_mode_off_status);
        }

        void onToggle(boolean on) {
            if (mHasTelephony && TelephonyProperties.in_ecm_mode().orElse(false)) {
                mIsWaitingForEcmExit = true;
                // Launch ECM exit dialog
                Intent ecmDialogIntent =
                        new Intent(TelephonyManager.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null);
                ecmDialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(ecmDialogIntent);
            } else {
                changeAirplaneModeSystemSetting(on);
            }
        }

        @Override
        protected void changeStateFromPress(boolean buttonOn) {
            if (!mHasTelephony) return;

            // In ECM mode airplane state cannot be changed
            if (!TelephonyProperties.in_ecm_mode().orElse(false)) {
                mState = buttonOn ? ToggleState.TurningOn : ToggleState.TurningOff;
                mAirplaneState = mState;
            }
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private class SilentModeToggleAction extends ToggleAction {
        SilentModeToggleAction() {
            super(R.drawable.ic_audio_vol_mute,
                    R.drawable.ic_audio_vol,
                    R.string.global_action_toggle_silent_mode,
                    R.string.global_action_silent_mode_on_status,
                    R.string.global_action_silent_mode_off_status);
        }

        void onToggle(boolean on) {
            if (on) {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            } else {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            }
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private static class SilentModeTriStateAction implements Action, View.OnClickListener {

        private static final int[] ITEM_IDS = {R.id.option1, R.id.option2, R.id.option3};

        private final AudioManager mAudioManager;
        private final Handler mHandler;

        SilentModeTriStateAction(AudioManager audioManager, Handler handler) {
            mAudioManager = audioManager;
            mHandler = handler;
        }

        private int ringerModeToIndex(int ringerMode) {
            // They just happen to coincide
            return ringerMode;
        }

        private int indexToRingerMode(int index) {
            // They just happen to coincide
            return index;
        }

        @Override
        public CharSequence getLabelForAccessibility(Context context) {
            return null;
        }

        @Override
        public int getMessageResId() {
            return 0;
        }

        @Override
        public CharSequence getMessage() {
            return null;
        }

        @Override
        public Drawable getIcon(Context context) {
            return null;
        }


        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.global_actions_silent_mode, parent, false);

            int selectedIndex = ringerModeToIndex(mAudioManager.getRingerMode());
            for (int i = 0; i < 3; i++) {
                View itemView = v.findViewById(ITEM_IDS[i]);
                itemView.setSelected(selectedIndex == i);
                // Set up click handler
                itemView.setTag(i);
                itemView.setOnClickListener(this);
            }
            return v;
        }

        public void onPress() {
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }

        public boolean isEnabled() {
            return true;
        }

        void willCreate() {
        }

        public void onClick(View v) {
            if (!(v.getTag() instanceof Integer)) return;

            int index = (Integer) v.getTag();
            mAudioManager.setRingerMode(indexToRingerMode(index));
            mHandler.sendEmptyMessageDelayed(MESSAGE_DISMISS, DIALOG_DISMISS_DELAY);
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                String reason = intent.getStringExtra(SYSTEM_DIALOG_REASON_KEY);
                if (!SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS.equals(reason)) {
                    mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_DISMISS, reason));
                }
            } else if (TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED.equals(action)) {
                // Airplane mode can be changed after ECM exits if airplane toggle button
                // is pressed during ECM mode
                if (!(intent.getBooleanExtra(TelephonyManager.EXTRA_PHONE_IN_ECM_STATE, false))
                        && mIsWaitingForEcmExit) {
                    mIsWaitingForEcmExit = false;
                    changeAirplaneModeSystemSetting(true);
                }
            }
        }
    };

    private final TelephonyCallback.ServiceStateListener mPhoneStateListener =
            new TelephonyCallback.ServiceStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (!mHasTelephony) return;
            if (mAirplaneModeOn == null) {
                Log.d(TAG, "Service changed before actions created");
                return;
            }
            final boolean inAirplaneMode = serviceState.getState() == ServiceState.STATE_POWER_OFF;
            mAirplaneState = inAirplaneMode ? ToggleState.On : ToggleState.Off;
            mAirplaneModeOn.updateState(mAirplaneState);
            mAdapter.notifyDataSetChanged();
            mOverflowAdapter.notifyDataSetChanged();
            mPowerAdapter.notifyDataSetChanged();
        }
    };

    private final ContentObserver mAirplaneModeObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            onAirplaneModeChanged();
        }
    };

    private static final int MESSAGE_DISMISS = 0;
    private static final int MESSAGE_REFRESH = 1;
    private static final int DIALOG_DISMISS_DELAY = 300; // ms
    private static final int DIALOG_PRESS_DELAY = 850; // ms

    @VisibleForTesting void setZeroDialogPressDelayForTesting() {
        mDialogPressDelay = 0; // ms
    }

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_DISMISS:
                    if (mDialog != null) {
                        if (SYSTEM_DIALOG_REASON_DREAM.equals(msg.obj)) {
                            // Hide instantly.
                            mDialog.hide();
                            mDialog.dismiss();
                        } else {
                            mDialog.dismiss();
                        }
                        mDialog = null;
                    }
                    break;
                case MESSAGE_REFRESH:
                    refreshSilentMode();
                    mAdapter.notifyDataSetChanged();
                    break;
            }
        }
    };

    private void onAirplaneModeChanged() {
        // Let the service state callbacks handle the state.
        if (mHasTelephony || mAirplaneModeOn == null) return;

        boolean airplaneModeOn = mGlobalSettings.getInt(
                Settings.Global.AIRPLANE_MODE_ON,
                0) == 1;
        mAirplaneState = airplaneModeOn ? ToggleState.On : ToggleState.Off;
        mAirplaneModeOn.updateState(mAirplaneState);
    }

    /**
     * Change the airplane mode system setting
     */
    private void changeAirplaneModeSystemSetting(boolean on) {
        mGlobalSettings.putInt(Settings.Global.AIRPLANE_MODE_ON, on ? 1 : 0);
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("state", on);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        if (!mHasTelephony) {
            mAirplaneState = on ? ToggleState.On : ToggleState.Off;
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @VisibleForTesting
    static class ActionsDialogLite extends SystemUIDialog implements DialogInterface,
            ColorExtractor.OnColorsChangedListener {

        protected final Context mContext;
        protected MultiListLayout mGlobalActionsLayout;
        protected final MyAdapter mAdapter;
        protected final MyOverflowAdapter mOverflowAdapter;
        protected final MyPowerOptionsAdapter mPowerOptionsAdapter;
        protected final IStatusBarService mStatusBarService;
        protected final IBinder mToken = new Binder();
        protected Drawable mBackgroundDrawable;
        protected final SysuiColorExtractor mColorExtractor;
        private boolean mKeyguardShowing;
        protected float mScrimAlpha;
        protected final NotificationShadeWindowController mNotificationShadeWindowController;
        private ListPopupWindow mOverflowPopup;
        private Dialog mPowerOptionsDialog;
        protected final Runnable mOnRefreshCallback;
        private UiEventLogger mUiEventLogger;
        private GestureDetector mGestureDetector;
        private Optional<CentralSurfaces> mCentralSurfacesOptional;
        private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private LockPatternUtils mLockPatternUtils;
        private float mWindowDimAmount;

        protected ViewGroup mContainer;

        @VisibleForTesting
        protected GestureDetector.SimpleOnGestureListener mGestureListener =
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        // All gestures begin with this message, so continue listening
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        // Close without opening shade
                        mUiEventLogger.log(GlobalActionsEvent.GA_CLOSE_TAP_OUTSIDE);
                        cancel();
                        return false;
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
                            float distanceY) {
                        if (distanceY < 0 && distanceY > distanceX
                                && e1.getY() <= mCentralSurfacesOptional.map(
                                        CentralSurfaces::getStatusBarHeight).orElse(0)) {
                            // Downwards scroll from top
                            openShadeAndDismiss();
                            return true;
                        }
                        return false;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                            float velocityY) {
                        if (velocityY > 0 && Math.abs(velocityY) > Math.abs(velocityX)
                                && e1.getY() <= mCentralSurfacesOptional.map(
                                        CentralSurfaces::getStatusBarHeight).orElse(0)) {
                            // Downwards fling from top
                            openShadeAndDismiss();
                            return true;
                        }
                        return false;
                    }
                };

        ActionsDialogLite(Context context, int themeRes, MyAdapter adapter,
                MyOverflowAdapter overflowAdapter,
                SysuiColorExtractor sysuiColorExtractor, IStatusBarService statusBarService,
                NotificationShadeWindowController notificationShadeWindowController,
                Runnable onRefreshCallback, boolean keyguardShowing,
                MyPowerOptionsAdapter powerAdapter, UiEventLogger uiEventLogger,
                Optional<CentralSurfaces> centralSurfacesOptional,
                KeyguardUpdateMonitor keyguardUpdateMonitor,
                LockPatternUtils lockPatternUtils) {
            // We set dismissOnDeviceLock to false because we have a custom broadcast receiver to
            // dismiss this dialog when the device is locked.
            super(context, themeRes, false /* dismissOnDeviceLock */);
            mContext = context;
            mAdapter = adapter;
            mOverflowAdapter = overflowAdapter;
            mPowerOptionsAdapter = powerAdapter;
            mColorExtractor = sysuiColorExtractor;
            mStatusBarService = statusBarService;
            mNotificationShadeWindowController = notificationShadeWindowController;
            mOnRefreshCallback = onRefreshCallback;
            mKeyguardShowing = keyguardShowing;
            mUiEventLogger = uiEventLogger;
            mCentralSurfacesOptional = centralSurfacesOptional;
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mLockPatternUtils = lockPatternUtils;
            mGestureDetector = new GestureDetector(mContext, mGestureListener);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            initializeLayout();
            mWindowDimAmount = getWindow().getAttributes().dimAmount;
        }

        @Override
        protected int getWidth() {
            return MATCH_PARENT;
        }

        @Override
        protected int getHeight() {
            return MATCH_PARENT;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return mGestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
        }

        private void openShadeAndDismiss() {
            mUiEventLogger.log(GlobalActionsEvent.GA_CLOSE_TAP_OUTSIDE);
            if (mCentralSurfacesOptional.map(CentralSurfaces::isKeyguardShowing).orElse(false)) {
                // match existing lockscreen behavior to open QS when swiping from status bar
                mCentralSurfacesOptional.ifPresent(
                        centralSurfaces -> centralSurfaces.animateExpandSettingsPanel(null));
            } else {
                // otherwise, swiping down should expand notification shade
                mCentralSurfacesOptional.ifPresent(
                        centralSurfaces -> centralSurfaces.animateExpandNotificationsPanel());
            }
            dismiss();
        }

        private ListPopupWindow createPowerOverflowPopup() {
            GlobalActionsPopupMenu popup = new GlobalActionsPopupMenu(
                    new ContextThemeWrapper(
                            mContext,
                            com.android.systemui.R.style.Control_ListPopupWindow
                    ), false /* isDropDownMode */);
            popup.setOnItemClickListener(
                    (parent, view, position, id) -> mOverflowAdapter.onClickItem(position));
            popup.setOnItemLongClickListener(
                    (parent, view, position, id) -> mOverflowAdapter.onLongClickItem(position));
            View overflowButton =
                    findViewById(com.android.systemui.R.id.global_actions_overflow_button);
            popup.setAnchorView(overflowButton);
            popup.setAdapter(mOverflowAdapter);
            return popup;
        }

        public void showPowerOptionsMenu() {
            mPowerOptionsDialog = GlobalActionsPowerDialog.create(mContext, mPowerOptionsAdapter);
            mPowerOptionsDialog.show();
        }

        protected void showPowerOverflowMenu() {
            mOverflowPopup = createPowerOverflowPopup();
            mOverflowPopup.show();
        }

        protected int getLayoutResource() {
            return com.android.systemui.R.layout.global_actions_grid_lite;
        }

        protected void initializeLayout() {
            setContentView(getLayoutResource());
            fixNavBarClipping();

            mGlobalActionsLayout = findViewById(com.android.systemui.R.id.global_actions_view);
            mGlobalActionsLayout.setListViewAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public boolean dispatchPopulateAccessibilityEvent(
                        View host, AccessibilityEvent event) {
                    // Populate the title here, just as Activity does
                    event.getText().add(mContext.getString(R.string.global_actions));
                    return true;
                }
            });
            mGlobalActionsLayout.setRotationListener(this::onRotate);
            mGlobalActionsLayout.setAdapter(mAdapter);
            mContainer = findViewById(com.android.systemui.R.id.global_actions_container);
            mContainer.setOnTouchListener((v, event) -> {
                mGestureDetector.onTouchEvent(event);
                return v.onTouchEvent(event);
            });

            View overflowButton = findViewById(
                    com.android.systemui.R.id.global_actions_overflow_button);
            if (overflowButton != null) {
                if (mOverflowAdapter.getCount() > 0) {
                    overflowButton.setOnClickListener((view) -> showPowerOverflowMenu());
                    LinearLayout.LayoutParams params =
                            (LinearLayout.LayoutParams) mGlobalActionsLayout.getLayoutParams();
                    params.setMarginEnd(0);
                    mGlobalActionsLayout.setLayoutParams(params);
                } else {
                    overflowButton.setVisibility(View.GONE);
                    LinearLayout.LayoutParams params =
                            (LinearLayout.LayoutParams) mGlobalActionsLayout.getLayoutParams();
                    params.setMarginEnd(mContext.getResources().getDimensionPixelSize(
                            com.android.systemui.R.dimen.global_actions_side_margin));
                    mGlobalActionsLayout.setLayoutParams(params);
                }
            }

            if (mBackgroundDrawable == null) {
                mBackgroundDrawable = new ScrimDrawable();
                mScrimAlpha = 1.0f;
            }

            // If user entered from the lock screen and smart lock was enabled, disable it
            int user = KeyguardUpdateMonitor.getCurrentUser();
            boolean userHasTrust = mKeyguardUpdateMonitor.getUserHasTrust(user);
            if (mKeyguardShowing && userHasTrust) {
                mLockPatternUtils.requireCredentialEntry(KeyguardUpdateMonitor.getCurrentUser());
                showSmartLockDisabledMessage();
            }
        }

        protected void fixNavBarClipping() {
            ViewGroup content = findViewById(android.R.id.content);
            content.setClipChildren(false);
            content.setClipToPadding(false);
            ViewGroup contentParent = (ViewGroup) content.getParent();
            contentParent.setClipChildren(false);
            contentParent.setClipToPadding(false);
        }

        private void showSmartLockDisabledMessage() {
            // Since power menu is the top window, make a Toast-like view that will show up
            View message = LayoutInflater.from(mContext)
                    .inflate(com.android.systemui.R.layout.global_actions_toast, mContainer, false);

            // Set up animation
            AccessibilityManager mAccessibilityManager =
                    (AccessibilityManager) getContext().getSystemService(
                            Context.ACCESSIBILITY_SERVICE);
            final int visibleTime = mAccessibilityManager.getRecommendedTimeoutMillis(
                    TOAST_VISIBLE_TIME, AccessibilityManager.FLAG_CONTENT_TEXT);
            message.setVisibility(View.VISIBLE);
            message.setAlpha(0f);
            mContainer.addView(message);

            // Fade in
            message.animate()
                    .alpha(1f)
                    .setDuration(TOAST_FADE_TIME)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // Then fade out
                            message.animate()
                                    .alpha(0f)
                                    .setDuration(TOAST_FADE_TIME)
                                    .setStartDelay(visibleTime)
                                    .setListener(null);
                        }
                    });
        }

        @Override
        protected void onStart() {
            super.onStart();
            mGlobalActionsLayout.updateList();

            if (mBackgroundDrawable instanceof ScrimDrawable) {
                mColorExtractor.addOnColorsChangedListener(this);
                GradientColors colors = mColorExtractor.getNeutralColors();
                updateColors(colors, false /* animate */);
            }
        }

        /**
         * Updates background and system bars according to current GradientColors.
         *
         * @param colors  Colors and hints to use.
         * @param animate Interpolates gradient if true, just sets otherwise.
         */
        private void updateColors(GradientColors colors, boolean animate) {
            if (!(mBackgroundDrawable instanceof ScrimDrawable)) {
                return;
            }
            ((ScrimDrawable) mBackgroundDrawable).setColor(Color.BLACK, animate);
            View decorView = getWindow().getDecorView();
            if (colors.supportsDarkText()) {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            } else {
                decorView.setSystemUiVisibility(0);
            }
        }

        @Override
        protected void onStop() {
            super.onStop();
            mColorExtractor.removeOnColorsChangedListener(this);
        }

        @Override
        public void onBackPressed() {
            super.onBackPressed();
            mUiEventLogger.log(GlobalActionsEvent.GA_CLOSE_BACK);
        }

        @Override
        public void show() {
            super.show();
            mNotificationShadeWindowController.setRequestTopUi(true, TAG);

            // By default this dialog windowAnimationStyle is null, and therefore windowAnimations
            // should be equal to 0 which means we need to animate the dialog in-window. If it's not
            // equal to 0, it means it has been overridden to animate (e.g. by the
            // DialogLaunchAnimator) so we don't run the animation.
            boolean shouldAnimateInWindow = getWindow().getAttributes().windowAnimations == 0;
            if (shouldAnimateInWindow) {
                startAnimation(true /* isEnter */, null /* then */);

                // Override the dialog dismiss so that we can animate in-window before dismissing
                // the dialog.
                setDismissOverride(() -> {
                    startAnimation(false /* isEnter */, /* then */ () -> {
                        setDismissOverride(null);

                        // Hide then dismiss to instantly dismiss.
                        hide();
                        dismiss();
                    });
                });
            }
        }

        /** Run either the enter or exit animation, then run {@code then}. */
        private void startAnimation(boolean isEnter, @Nullable Runnable then) {
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);

            // Note: these specs should be the same as in popup_enter_material and
            // popup_exit_material.
            float translationPx;
            Resources resources = getContext().getResources();
            if (isEnter) {
                translationPx = resources.getDimension(R.dimen.popup_enter_animation_from_y_delta);
                animator.setInterpolator(Interpolators.STANDARD);
                animator.setDuration(resources.getInteger(R.integer.config_activityDefaultDur));
            } else {
                translationPx = resources.getDimension(R.dimen.popup_exit_animation_to_y_delta);
                animator.setInterpolator(Interpolators.STANDARD_ACCELERATE);
                animator.setDuration(resources.getInteger(R.integer.config_activityShortDur));
            }

            Window window = getWindow();
            int rotation = window.getWindowManager().getDefaultDisplay().getRotation();

            animator.addUpdateListener(valueAnimator -> {
                float progress = (float) valueAnimator.getAnimatedValue();

                float alpha = isEnter ? progress : 1 - progress;
                mGlobalActionsLayout.setAlpha(alpha);
                window.setDimAmount(mWindowDimAmount * alpha);

                // TODO(b/213872558): Support devices that don't have their power button on the
                // right.
                float translation =
                        isEnter ? translationPx * (1 - progress) : translationPx * progress;
                switch (rotation) {
                    case Surface.ROTATION_0:
                        mGlobalActionsLayout.setTranslationX(translation);
                        break;
                    case Surface.ROTATION_90:
                        mGlobalActionsLayout.setTranslationY(-translation);
                        break;
                    case Surface.ROTATION_180:
                        mGlobalActionsLayout.setTranslationX(-translation);
                        break;
                    case Surface.ROTATION_270:
                        mGlobalActionsLayout.setTranslationY(translation);
                        break;
                }
            });

            animator.addListener(new AnimatorListenerAdapter() {
                private int mPreviousLayerType;

                @Override
                public void onAnimationStart(Animator animation, boolean isReverse) {
                    mPreviousLayerType = mGlobalActionsLayout.getLayerType();
                    mGlobalActionsLayout.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mGlobalActionsLayout.setLayerType(mPreviousLayerType, null);
                    if (then != null) {
                        then.run();
                    }
                }
            });

            animator.start();
        }

        @Override
        public void dismiss() {
            dismissOverflow();
            dismissPowerOptions();

            mNotificationShadeWindowController.setRequestTopUi(false, TAG);
            super.dismiss();
        }

        protected final void dismissOverflow() {
            if (mOverflowPopup != null) {
                mOverflowPopup.dismiss();
            }
        }

        protected final void dismissPowerOptions() {
            if (mPowerOptionsDialog != null) {
                mPowerOptionsDialog.dismiss();
            }
        }

        protected final void setRotationSuggestionsEnabled(boolean enabled) {
            try {
                final int userId = Binder.getCallingUserHandle().getIdentifier();
                final int what = enabled
                        ? StatusBarManager.DISABLE2_NONE
                        : StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS;
                mStatusBarService.disable2ForUser(what, mToken, mContext.getPackageName(), userId);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        @Override
        public void onColorsChanged(ColorExtractor extractor, int which) {
            if (mKeyguardShowing) {
                if ((WallpaperManager.FLAG_LOCK & which) != 0) {
                    updateColors(extractor.getColors(WallpaperManager.FLAG_LOCK),
                            true /* animate */);
                }
            } else {
                if ((WallpaperManager.FLAG_SYSTEM & which) != 0) {
                    updateColors(extractor.getColors(WallpaperManager.FLAG_SYSTEM),
                            true /* animate */);
                }
            }
        }

        public void setKeyguardShowing(boolean keyguardShowing) {
            mKeyguardShowing = keyguardShowing;
        }

        public void refreshDialog() {
            mOnRefreshCallback.run();

            // Dismiss the dropdown menus.
            dismissOverflow();
            dismissPowerOptions();

            // Update the list as the max number of items per row has probably changed.
            mGlobalActionsLayout.updateList();
        }

        public void onRotate(int from, int to) {
            refreshDialog();
        }
    }
}
