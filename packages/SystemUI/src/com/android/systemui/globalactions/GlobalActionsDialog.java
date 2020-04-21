/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.globalactions;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.UserInfo;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.sysprop.TelephonyProperties;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.colorextraction.drawable.ScrimDrawable;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.EmergencyAffordanceManager;
import com.android.internal.util.ScreenRecordHelper;
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.view.RotationPolicy;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.MultiListLayout;
import com.android.systemui.MultiListLayout.MultiListAdapter;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.controls.ControlsServiceInfo;
import com.android.systemui.controls.controller.ControlsController;
import com.android.systemui.controls.management.ControlsListingController;
import com.android.systemui.controls.ui.ControlsUiController;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.GlobalActions.GlobalActionsManager;
import com.android.systemui.plugins.GlobalActionsPanelPlugin;
import com.android.systemui.statusbar.BlurUtils;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.EmergencyDialerConstants;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.leak.RotationUtils;
import com.android.systemui.volume.SystemUIInterpolators.LogAccelerateInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that may show depending
 * on whether the keyguard is showing, and whether the device is provisioned.
 */
public class GlobalActionsDialog implements DialogInterface.OnDismissListener,
        DialogInterface.OnShowListener,
        ConfigurationController.ConfigurationListener,
        GlobalActionsPanelPlugin.Callbacks,
        LifecycleOwner {

    public static final String SYSTEM_DIALOG_REASON_KEY = "reason";
    public static final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    public static final String SYSTEM_DIALOG_REASON_DREAM = "dream";

    private static final String TAG = "GlobalActionsDialog";

    private static final boolean SHOW_SILENT_TOGGLE = true;

    /* Valid settings for global actions keys.
     * see config.xml config_globalActionList */
    @VisibleForTesting
    protected static final String GLOBAL_ACTION_KEY_POWER = "power";
    protected static final String GLOBAL_ACTION_KEY_AIRPLANE = "airplane";
    protected static final String GLOBAL_ACTION_KEY_BUGREPORT = "bugreport";
    protected static final String GLOBAL_ACTION_KEY_SILENT = "silent";
    protected static final String GLOBAL_ACTION_KEY_USERS = "users";
    protected static final String GLOBAL_ACTION_KEY_SETTINGS = "settings";
    protected static final String GLOBAL_ACTION_KEY_LOCKDOWN = "lockdown";
    protected static final String GLOBAL_ACTION_KEY_VOICEASSIST = "voiceassist";
    protected static final String GLOBAL_ACTION_KEY_ASSIST = "assist";
    protected static final String GLOBAL_ACTION_KEY_RESTART = "restart";
    protected static final String GLOBAL_ACTION_KEY_LOGOUT = "logout";
    protected static final String GLOBAL_ACTION_KEY_EMERGENCY = "emergency";
    protected static final String GLOBAL_ACTION_KEY_SCREENSHOT = "screenshot";

    private static final String PREFS_CONTROLS_SEEDING_COMPLETED = "ControlsSeedingCompleted";
    private static final String PREFS_CONTROLS_FILE = "controls_prefs";

    private final Context mContext;
    private final GlobalActionsManager mWindowManagerFuncs;
    private final AudioManager mAudioManager;
    private final IDreamManager mDreamManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardStateController mKeyguardStateController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ContentResolver mContentResolver;
    private final Resources mResources;
    private final ConfigurationController mConfigurationController;
    private final UserManager mUserManager;
    private final TrustManager mTrustManager;
    private final IActivityManager mIActivityManager;
    private final TelecomManager mTelecomManager;
    private final MetricsLogger mMetricsLogger;
    private final UiEventLogger mUiEventLogger;
    private final NotificationShadeDepthController mDepthController;
    private final BlurUtils mBlurUtils;

    // Used for RingerModeTracker
    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

    @VisibleForTesting
    protected final ArrayList<Action> mItems = new ArrayList<>();
    @VisibleForTesting
    protected final ArrayList<Action> mOverflowItems = new ArrayList<>();

    private ActionsDialog mDialog;

    private Action mSilentModeAction;
    private ToggleAction mAirplaneModeOn;

    private MyAdapter mAdapter;
    private MyOverflowAdapter mOverflowAdapter;

    private boolean mKeyguardShowing = false;
    private boolean mDeviceProvisioned = false;
    private ToggleState mAirplaneState = ToggleState.Off;
    private boolean mIsWaitingForEcmExit = false;
    private boolean mHasTelephony;
    private boolean mHasVibrator;
    private final boolean mShowSilentToggle;
    private final EmergencyAffordanceManager mEmergencyAffordanceManager;
    private final ScreenshotHelper mScreenshotHelper;
    private final ScreenRecordHelper mScreenRecordHelper;
    private final ActivityStarter mActivityStarter;
    private final SysuiColorExtractor mSysuiColorExtractor;
    private final IStatusBarService mStatusBarService;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private GlobalActionsPanelPlugin mPanelPlugin;
    private ControlsUiController mControlsUiController;
    private final IWindowManager mIWindowManager;
    private final Executor mBackgroundExecutor;
    private final ControlsListingController mControlsListingController;
    private List<ControlsServiceInfo> mControlsServiceInfos = new ArrayList<>();
    private ControlsController mControlsController;
    private SharedPreferences mControlsPreferences;
    private final RingerModeTracker mRingerModeTracker;
    private int mDialogPressDelay = DIALOG_PRESS_DELAY; // ms

    @VisibleForTesting
    public enum GlobalActionsEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The global actions / power menu surface became visible on the screen.")
        GA_POWER_MENU_OPEN(337),

        @UiEvent(doc = "The global actions bugreport button was pressed.")
        GA_BUGREPORT_PRESS(344),

        @UiEvent(doc = "The global actions bugreport button was long pressed.")
        GA_BUGREPORT_LONG_PRESS(345),

        @UiEvent(doc = "The global actions emergency button was pressed.")
        GA_EMERGENCY_DIALER_PRESS(346),

        @UiEvent(doc = "The global actions screenshot button was pressed.")
        GA_SCREENSHOT_PRESS(347),

        @UiEvent(doc = "The global actions screenshot button was long pressed.")
        GA_SCREENSHOT_LONG_PRESS(348);

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
    public GlobalActionsDialog(Context context, GlobalActionsManager windowManagerFuncs,
            AudioManager audioManager, IDreamManager iDreamManager,
            DevicePolicyManager devicePolicyManager, LockPatternUtils lockPatternUtils,
            BroadcastDispatcher broadcastDispatcher,
            ConnectivityManager connectivityManager, TelephonyManager telephonyManager,
            ContentResolver contentResolver, @Nullable Vibrator vibrator, @Main Resources resources,
            ConfigurationController configurationController, ActivityStarter activityStarter,
            KeyguardStateController keyguardStateController, UserManager userManager,
            TrustManager trustManager, IActivityManager iActivityManager,
            @Nullable TelecomManager telecomManager, MetricsLogger metricsLogger,
            NotificationShadeDepthController depthController, SysuiColorExtractor colorExtractor,
            IStatusBarService statusBarService, BlurUtils blurUtils,
            NotificationShadeWindowController notificationShadeWindowController,
            ControlsUiController controlsUiController, IWindowManager iWindowManager,
            @Background Executor backgroundExecutor,
            ControlsListingController controlsListingController,
            ControlsController controlsController, UiEventLogger uiEventLogger,
            RingerModeTracker ringerModeTracker) {
        mContext = new ContextThemeWrapper(context, com.android.systemui.R.style.qs_theme);
        mWindowManagerFuncs = windowManagerFuncs;
        mAudioManager = audioManager;
        mDreamManager = iDreamManager;
        mDevicePolicyManager = devicePolicyManager;
        mLockPatternUtils = lockPatternUtils;
        mKeyguardStateController = keyguardStateController;
        mBroadcastDispatcher = broadcastDispatcher;
        mContentResolver = contentResolver;
        mResources = resources;
        mConfigurationController = configurationController;
        mUserManager = userManager;
        mTrustManager = trustManager;
        mIActivityManager = iActivityManager;
        mTelecomManager = telecomManager;
        mMetricsLogger = metricsLogger;
        mUiEventLogger = uiEventLogger;
        mDepthController = depthController;
        mSysuiColorExtractor = colorExtractor;
        mStatusBarService = statusBarService;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mControlsUiController = controlsUiController;
        mIWindowManager = iWindowManager;
        mBackgroundExecutor = backgroundExecutor;
        mControlsListingController = controlsListingController;
        mBlurUtils = blurUtils;
        mRingerModeTracker = ringerModeTracker;
        mControlsController = controlsController;

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter);

        mHasTelephony = connectivityManager.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        // get notified of phone state changes
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), true,
                mAirplaneModeObserver);
        mHasVibrator = vibrator != null && vibrator.hasVibrator();

        mShowSilentToggle = SHOW_SILENT_TOGGLE && !resources.getBoolean(
                R.bool.config_useFixedVolume);
        if (mShowSilentToggle) {
            mRingerModeTracker.getRingerMode().observe(this, ringer ->
                    mHandler.sendEmptyMessage(MESSAGE_REFRESH)
            );
        }

        mEmergencyAffordanceManager = new EmergencyAffordanceManager(context);
        mScreenshotHelper = new ScreenshotHelper(context);
        mScreenRecordHelper = new ScreenRecordHelper(context);

        mConfigurationController.addCallback(this);

        mActivityStarter = activityStarter;
        keyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onUnlockedChanged() {
                if (mDialog != null && mDialog.mPanelController != null) {
                    boolean unlocked = keyguardStateController.isUnlocked()
                            || keyguardStateController.canDismissLockScreen();
                    mDialog.mPanelController.onDeviceLockStateChanged(unlocked);
                }
            }
        });

        mControlsListingController.addCallback(list -> {
            mControlsServiceInfos = list;
        });

        // Need to be user-specific with the context to make sure we read the correct prefs
        Context userContext = context.createContextAsUser(
                new UserHandle(mUserManager.getUserHandle()), 0);
        mControlsPreferences = userContext.getSharedPreferences(PREFS_CONTROLS_FILE,
            Context.MODE_PRIVATE);

    }

    private void seedFavorites() {
        if (mControlsServiceInfos.isEmpty()
                || mControlsController.getFavorites().size() > 0
                || mControlsPreferences.getBoolean(PREFS_CONTROLS_SEEDING_COMPLETED, false)) {
            return;
        }

        /*
         * See if any service providers match the preferred component. If they do,
         * and there are no current favorites, and we haven't successfully loaded favorites to
         * date, query the preferred component for a limited number of suggested controls.
         */
        String preferredControlsPackage = mContext.getResources()
                .getString(com.android.systemui.R.string.config_controlsPreferredPackage);

        ComponentName preferredComponent = null;
        for (ControlsServiceInfo info : mControlsServiceInfos) {
            if (info.componentName.getPackageName().equals(preferredControlsPackage)) {
                preferredComponent = info.componentName;
                break;
            }
        }

        if (preferredComponent == null) {
            Log.i(TAG, "Controls seeding: No preferred component has been set, will not seed");
            mControlsPreferences.edit().putBoolean(PREFS_CONTROLS_SEEDING_COMPLETED, true).apply();
            return;
        }

        mControlsController.seedFavoritesForComponent(
                preferredComponent,
                (accepted) -> {
                    Log.i(TAG, "Controls seeded: " + accepted);
                    mControlsPreferences.edit().putBoolean(PREFS_CONTROLS_SEEDING_COMPLETED,
                        accepted).apply();
                });
    }

    /**
     * Show the global actions dialog (creating if necessary)
     *
     * @param keyguardShowing True if keyguard is showing
     */
    public void showDialog(boolean keyguardShowing, boolean isDeviceProvisioned,
            GlobalActionsPanelPlugin panelPlugin) {
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = isDeviceProvisioned;
        mPanelPlugin = panelPlugin;
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
            // Show delayed, so that the dismiss of the previous dialog completes
            mHandler.sendEmptyMessage(MESSAGE_SHOW);
        } else {
            handleShow();
        }
    }

    /**
     * Dismiss the global actions dialog, if it's currently shown
     */
    public void dismissDialog() {
        mHandler.removeMessages(MESSAGE_DISMISS);
        mHandler.sendEmptyMessage(MESSAGE_DISMISS);
    }

    private void awakenIfNecessary() {
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

    private void handleShow() {
        awakenIfNecessary();
        mDialog = createDialog();
        prepareDialog();
        seedFavorites();

        WindowManager.LayoutParams attrs = mDialog.getWindow().getAttributes();
        attrs.setTitle("ActionsDialog");
        attrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mDialog.getWindow().setAttributes(attrs);
        mDialog.show();
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
        return true;
    }

    /**
     * Returns the maximum number of power menu items to show based on which GlobalActions
     * layout is being used.
     */
    @VisibleForTesting
    protected int getMaxShownPowerItems() {
        if (shouldUseControlsLayout()) {
            return mResources.getInteger(com.android.systemui.R.integer.power_menu_max_columns);
        } else {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Add a power menu action item for to either the main or overflow items lists, depending on
     * whether controls are enabled and whether the max number of shown items has been reached.
     */
    private void addActionItem(Action action) {
        if (shouldShowAction(action)) {
            if (mItems.size() < getMaxShownPowerItems()) {
                mItems.add(action);
            } else {
                mOverflowItems.add(action);
            }
        }
    }

    @VisibleForTesting
    protected String[] getDefaultActions() {
        return mResources.getStringArray(R.array.config_globalActionsList);
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
        String[] defaultActions = getDefaultActions();

        // make sure emergency affordance action is first, if needed
        if (mEmergencyAffordanceManager.needsEmergencyAffordance()) {
            addActionItem(new EmergencyAffordanceAction());
        }

        ArraySet<String> addedKeys = new ArraySet<String>();
        for (int i = 0; i < defaultActions.length; i++) {
            String actionKey = defaultActions[i];
            if (addedKeys.contains(actionKey)) {
                // If we already have added this, don't add it again.
                continue;
            }
            if (GLOBAL_ACTION_KEY_POWER.equals(actionKey)) {
                addActionItem(new PowerAction());
            } else if (GLOBAL_ACTION_KEY_AIRPLANE.equals(actionKey)) {
                addActionItem(mAirplaneModeOn);
            } else if (GLOBAL_ACTION_KEY_BUGREPORT.equals(actionKey)) {
                if (Settings.Global.getInt(mContentResolver,
                        Settings.Global.BUGREPORT_IN_POWER_MENU, 0) != 0 && isCurrentUserOwner()) {
                    addActionItem(new BugReportAction());
                }
            } else if (GLOBAL_ACTION_KEY_SILENT.equals(actionKey)) {
                if (mShowSilentToggle) {
                    addActionItem(mSilentModeAction);
                }
            } else if (GLOBAL_ACTION_KEY_USERS.equals(actionKey)) {
                if (SystemProperties.getBoolean("fw.power_user_switcher", false)) {
                    addUsersToMenu();
                }
            } else if (GLOBAL_ACTION_KEY_SETTINGS.equals(actionKey)) {
                addActionItem(getSettingsAction());
            } else if (GLOBAL_ACTION_KEY_LOCKDOWN.equals(actionKey)) {
                if (Settings.Secure.getIntForUser(mContentResolver,
                        Settings.Secure.LOCKDOWN_IN_POWER_MENU, 0, getCurrentUser().id) != 0
                        && shouldDisplayLockdown()) {
                    addActionItem(getLockdownAction());
                }
            } else if (GLOBAL_ACTION_KEY_VOICEASSIST.equals(actionKey)) {
                addActionItem(getVoiceAssistAction());
            } else if (GLOBAL_ACTION_KEY_ASSIST.equals(actionKey)) {
                addActionItem(getAssistAction());
            } else if (GLOBAL_ACTION_KEY_RESTART.equals(actionKey)) {
                addActionItem(new RestartAction());
            } else if (GLOBAL_ACTION_KEY_SCREENSHOT.equals(actionKey)) {
                addActionItem(new ScreenshotAction());
            } else if (GLOBAL_ACTION_KEY_LOGOUT.equals(actionKey)) {
                if (mDevicePolicyManager.isLogoutEnabled()
                        && getCurrentUser().id != UserHandle.USER_SYSTEM) {
                    addActionItem(new LogoutAction());
                }
            } else if (GLOBAL_ACTION_KEY_EMERGENCY.equals(actionKey)) {
                if (!mEmergencyAffordanceManager.needsEmergencyAffordance()) {
                    addActionItem(new EmergencyDialerAction());
                }
            } else {
                Log.e(TAG, "Invalid global action key " + actionKey);
            }
            // Add here so we don't add more than one.
            addedKeys.add(actionKey);
        }
    }

    private void onRotate() {
        // re-allocate actions between main and overflow lists
        this.createActionItems();
    }

    /**
     * Create the global actions dialog.
     *
     * @return A new dialog.
     */
    private ActionsDialog createDialog() {
        createActionItems();

        mAdapter = new MyAdapter();
        mOverflowAdapter = new MyOverflowAdapter();

        mDepthController.setShowingHomeControls(shouldUseControlsLayout());
        ActionsDialog dialog = new ActionsDialog(mContext, mAdapter, mOverflowAdapter,
                getWalletPanelViewController(), mDepthController, mSysuiColorExtractor,
                mStatusBarService, mNotificationShadeWindowController,
                shouldShowControls() ? mControlsUiController : null, mBlurUtils,
                shouldUseControlsLayout(), this::onRotate);
        dialog.setCanceledOnTouchOutside(false); // Handled by the custom class.
        dialog.setKeyguardShowing(mKeyguardShowing);

        dialog.setOnDismissListener(this);
        dialog.setOnShowListener(this);

        return dialog;
    }

    private boolean shouldDisplayLockdown() {
        // Lockdown is meaningless without a place to go.
        if (!mKeyguardStateController.isMethodSecure()) {
            return false;
        }

        int userId = getCurrentUser().id;
        // Only show the lockdown button if the device isn't locked down (for whatever reason).
        int state = mLockPatternUtils.getStrongAuthForUser(userId);
        return (state == STRONG_AUTH_NOT_REQUIRED
                || state == SOME_AUTH_REQUIRED_AFTER_USER_REQUEST);
    }

    @Override
    public void onUiModeChanged() {
        mContext.getTheme().applyStyle(mContext.getThemeResId(), true);
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.refreshDialog();
        }
    }

    public void destroy() {
        mConfigurationController.removeCallback(this);
    }

    @Nullable
    private GlobalActionsPanelPlugin.PanelViewController getWalletPanelViewController() {
        if (mPanelPlugin == null) {
            return null;
        }
        return mPanelPlugin.onPanelShown(this, !mKeyguardStateController.isUnlocked());
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.Callbacks#dismissGlobalActionsMenu()}, which is
     * called when the quick access wallet requests dismissal.
     */
    @Override
    public void dismissGlobalActionsMenu() {
        dismissDialog();
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.Callbacks#dismissGlobalActionsMenu()}, which is
     * called when the quick access wallet requests that an intent be started (with lock screen
     * shown first if needed).
     */
    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent pendingIntent) {
        mActivityStarter.startPendingIntentDismissingKeyguard(pendingIntent);
    }

    private final class PowerAction extends SinglePressAction implements LongPressAction {
        private PowerAction() {
            super(R.drawable.ic_lock_power_off,
                    R.string.global_action_power_off);
        }

        @Override
        public boolean onLongPress() {
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
            // shutdown by making sure radio and power are handled accordingly.
            mWindowManagerFuncs.shutdown();
        }
    }

    private abstract class EmergencyAction extends SinglePressAction {
        EmergencyAction(int iconResId, int messageResId) {
            super(iconResId, messageResId);
        }

        @Override
        public boolean shouldBeSeparated() {
            return !shouldUseControlsLayout();
        }

        @Override
        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = super.create(context, convertView, parent, inflater);
            int textColor;
            if (shouldUseControlsLayout()) {
                v.setBackgroundTintList(ColorStateList.valueOf(v.getResources().getColor(
                        com.android.systemui.R.color.global_actions_emergency_background)));
                textColor = v.getResources().getColor(
                        com.android.systemui.R.color.global_actions_emergency_text);
            } else if (shouldBeSeparated()) {
                textColor = v.getResources().getColor(
                        com.android.systemui.R.color.global_actions_alert_text);
            } else {
                textColor = v.getResources().getColor(
                        com.android.systemui.R.color.global_actions_text);
            }
            TextView messageView = v.findViewById(R.id.message);
            messageView.setTextColor(textColor);
            messageView.setSelected(true); // necessary for marquee to work
            ImageView icon = v.findViewById(R.id.icon);
            icon.getDrawable().setTint(textColor);
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

    private final class RestartAction extends SinglePressAction implements LongPressAction {
        private RestartAction() {
            super(R.drawable.ic_restart, R.string.global_action_restart);
        }

        @Override
        public boolean onLongPress() {
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
            mWindowManagerFuncs.reboot(false);
        }
    }

    @VisibleForTesting
    class ScreenshotAction extends SinglePressAction implements LongPressAction {
        public ScreenshotAction() {
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
                    mScreenshotHelper.takeScreenshot(1, true, true, mHandler, null);
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
        public boolean onLongPress() {
            if (FeatureFlagUtils.isEnabled(mContext, FeatureFlagUtils.SCREENRECORD_LONG_PRESS)) {
                mUiEventLogger.log(GlobalActionsEvent.GA_SCREENSHOT_LONG_PRESS);
                mScreenRecordHelper.launchRecordPrompt();
            } else {
                onPress();
            }
            return true;
        }
    }

    @VisibleForTesting
    ScreenshotAction makeScreenshotActionForTesting() {
        return new ScreenshotAction();
    }

    @VisibleForTesting
    class BugReportAction extends SinglePressAction implements LongPressAction {

        public BugReportAction() {
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
            } catch (RemoteException e) {
            }
            return false;
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
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
                try {
                    int currentUserId = getCurrentUser().id;
                    mIActivityManager.switchUser(UserHandle.USER_SYSTEM);
                    mIActivityManager.stopUser(currentUserId, true /*force*/, null);
                } catch (RemoteException re) {
                    Log.e(TAG, "Couldn't logout user " + re);
                }
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

    private Action getLockdownAction() {
        return new SinglePressAction(R.drawable.ic_lock_lockdown,
                R.string.global_action_lockdown) {

            @Override
            public void onPress() {
                mLockPatternUtils.requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN,
                        UserHandle.USER_ALL);
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
        };
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

    private UserInfo getCurrentUser() {
        try {
            return mIActivityManager.getCurrentUser();
        } catch (RemoteException re) {
            return null;
        }
    }

    private boolean isCurrentUserOwner() {
        UserInfo currentUser = getCurrentUser();
        return currentUser == null || currentUser.isPrimary();
    }

    private void addUsersToMenu() {
        if (mUserManager.isUserSwitcherEnabled()) {
            List<UserInfo> users = mUserManager.getUsers();
            UserInfo currentUser = getCurrentUser();
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
                    addActionItem(switchToUser);
                }
            }
        }
    }

    private void prepareDialog() {
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
    public void onDismiss(DialogInterface dialog) {
        if (mDialog == dialog) {
            mDialog = null;
        }
        mWindowManagerFuncs.onGlobalActionsHidden();
        mLifecycle.setCurrentState(Lifecycle.State.DESTROYED);
    }

    /**
     * {@inheritDoc}
     */
    public void onShow(DialogInterface dialog) {
        mMetricsLogger.visible(MetricsEvent.POWER_MENU);
        mUiEventLogger.log(GlobalActionsEvent.GA_POWER_MENU_OPEN);
    }

    private int getActionLayoutId() {
        if (shouldUseControlsLayout()) {
            return com.android.systemui.R.layout.global_actions_grid_item_v2;
        }
        return com.android.systemui.R.layout.global_actions_grid_item;
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
                    mDialog.dismiss();
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
            textView.setOnClickListener(v -> onClickItem(position));
            if (action.getMessageResId() != 0) {
                textView.setText(action.getMessageResId());
            } else {
                textView.setText(action.getMessage());
            }

            if (action instanceof LongPressAction) {
                textView.setOnLongClickListener(v -> onLongClickItem(position));
            } else {
                textView.setOnLongClickListener(null);
            }
            return textView;
        }

        private boolean onLongClickItem(int position) {
            final Action action = getItem(position);
            if (action instanceof LongPressAction) {
                if (mDialog != null) {
                    mDialog.hidePowerOverflowMenu();
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
                    mDialog.hidePowerOverflowMenu();
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

        View create(Context context, View convertView, ViewGroup parent, LayoutInflater inflater);

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

        boolean isEnabled();

        default boolean shouldBeSeparated() {
            return false;
        }

        /**
         * Return the id of the message associated with this action, or 0 if it doesn't have one.
         * @return
         */
        int getMessageResId();

        /**
         * Return the message associated with this action, or null if it doesn't have one.
         * @return
         */
        CharSequence getMessage();
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

        abstract public void onPress();

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

        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = inflater.inflate(getActionLayoutId(), parent, false /* attach */);

            ImageView icon = v.findViewById(R.id.icon);
            TextView messageView = v.findViewById(R.id.message);
            messageView.setSelected(true); // necessary for marquee to work

            if (mIcon != null) {
                icon.setImageDrawable(mIcon);
                icon.setScaleType(ScaleType.CENTER_CROP);
            } else if (mIconResId != 0) {
                icon.setImageDrawable(context.getDrawable(mIconResId));
            }
            if (mMessage != null) {
                messageView.setText(mMessage);
            } else {
                messageView.setText(mMessageResId);
            }

            return v;
        }
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
        public ToggleAction(int enabledIconResId,
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

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            willCreate();

            View v = inflater.inflate(getActionLayoutId(), parent, false /* attach */);

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
        public SilentModeToggleAction() {
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

        private final int[] ITEM_IDS = {R.id.option1, R.id.option2, R.id.option3};

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

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (!mHasTelephony) return;
            final boolean inAirplaneMode = serviceState.getState() == ServiceState.STATE_POWER_OFF;
            mAirplaneState = inAirplaneMode ? ToggleState.On : ToggleState.Off;
            mAirplaneModeOn.updateState(mAirplaneState);
            mAdapter.notifyDataSetChanged();
        }
    };

    private ContentObserver mAirplaneModeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            onAirplaneModeChanged();
        }
    };

    private static final int MESSAGE_DISMISS = 0;
    private static final int MESSAGE_REFRESH = 1;
    private static final int MESSAGE_SHOW = 2;
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
                            mDialog.dismissImmediately();
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
                case MESSAGE_SHOW:
                    handleShow();
                    break;
            }
        }
    };

    private void onAirplaneModeChanged() {
        // Let the service state callbacks handle the state.
        if (mHasTelephony) return;

        boolean airplaneModeOn = Settings.Global.getInt(
                mContentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0) == 1;
        mAirplaneState = airplaneModeOn ? ToggleState.On : ToggleState.Off;
        mAirplaneModeOn.updateState(mAirplaneState);
    }

    /**
     * Change the airplane mode system setting
     */
    private void changeAirplaneModeSystemSetting(boolean on) {
        Settings.Global.putInt(
                mContentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                on ? 1 : 0);
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

    private static final class ActionsDialog extends Dialog implements DialogInterface,
            ColorExtractor.OnColorsChangedListener {

        private final Context mContext;
        private final MyAdapter mAdapter;
        private final MyOverflowAdapter mOverflowAdapter;
        private final IStatusBarService mStatusBarService;
        private final IBinder mToken = new Binder();
        private MultiListLayout mGlobalActionsLayout;
        private Drawable mBackgroundDrawable;
        private final SysuiColorExtractor mColorExtractor;
        private final GlobalActionsPanelPlugin.PanelViewController mPanelController;
        private boolean mKeyguardShowing;
        private boolean mShowing;
        private float mScrimAlpha;
        private ResetOrientationData mResetOrientationData;
        private boolean mHadTopUi;
        private final NotificationShadeWindowController mNotificationShadeWindowController;
        private final NotificationShadeDepthController mDepthController;
        private final BlurUtils mBlurUtils;
        private final boolean mUseControlsLayout;
        private ListPopupWindow mOverflowPopup;
        private final Runnable mOnRotateCallback;

        private ControlsUiController mControlsUiController;
        private ViewGroup mControlsView;

        ActionsDialog(Context context, MyAdapter adapter, MyOverflowAdapter overflowAdapter,
                GlobalActionsPanelPlugin.PanelViewController plugin,
                NotificationShadeDepthController depthController,
                SysuiColorExtractor sysuiColorExtractor, IStatusBarService statusBarService,
                NotificationShadeWindowController notificationShadeWindowController,
                ControlsUiController controlsUiController, BlurUtils blurUtils,
                boolean useControlsLayout, Runnable onRotateCallback) {
            super(context, com.android.systemui.R.style.Theme_SystemUI_Dialog_GlobalActions);
            mContext = context;
            mAdapter = adapter;
            mOverflowAdapter = overflowAdapter;
            mDepthController = depthController;
            mColorExtractor = sysuiColorExtractor;
            mStatusBarService = statusBarService;
            mNotificationShadeWindowController = notificationShadeWindowController;
            mControlsUiController = controlsUiController;
            mBlurUtils = blurUtils;
            mUseControlsLayout = useControlsLayout;
            mOnRotateCallback = onRotateCallback;

            // Window initialization
            Window window = getWindow();
            window.requestFeature(Window.FEATURE_NO_TITLE);
            // Inflate the decor view, so the attributes below are not overwritten by the theme.
            window.getDecorView();
            window.getAttributes().systemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            window.setLayout(MATCH_PARENT, MATCH_PARENT);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
            window.getAttributes().setFitInsetsTypes(0 /* types */);
            setTitle(R.string.global_actions);

            mPanelController = plugin;
            initializeLayout();
        }

        private boolean shouldUsePanel() {
            return mPanelController != null && mPanelController.getPanelContent() != null;
        }

        private void initializePanel() {
            int rotation = RotationUtils.getRotation(mContext);
            boolean rotationLocked = RotationPolicy.isRotationLocked(mContext);
            if (rotation != RotationUtils.ROTATION_NONE) {
                if (rotationLocked) {
                    if (mResetOrientationData == null) {
                        mResetOrientationData = new ResetOrientationData();
                        mResetOrientationData.locked = true;
                        mResetOrientationData.rotation = rotation;
                    }

                    // Unlock rotation, so user can choose to rotate to portrait to see the panel.
                    // This call is posted so that the rotation does not change until post-layout,
                    // otherwise onConfigurationChanged() may not get invoked.
                    mGlobalActionsLayout.post(() ->
                            RotationPolicy.setRotationLockAtAngle(
                                    mContext, false, RotationUtils.ROTATION_NONE));
                }
            } else {
                if (!rotationLocked) {
                    if (mResetOrientationData == null) {
                        mResetOrientationData = new ResetOrientationData();
                        mResetOrientationData.locked = false;
                    }

                    // Lock to portrait, so the user doesn't accidentally hide the panel.
                    // This call is posted so that the rotation does not change until post-layout,
                    // otherwise onConfigurationChanged() may not get invoked.
                    mGlobalActionsLayout.post(() ->
                            RotationPolicy.setRotationLockAtAngle(
                                    mContext, true, RotationUtils.ROTATION_NONE));
                }

                // Disable rotation suggestions, if enabled
                setRotationSuggestionsEnabled(false);

                FrameLayout panelContainer =
                        findViewById(com.android.systemui.R.id.global_actions_panel_container);
                FrameLayout.LayoutParams panelParams =
                        new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT);
                panelContainer.addView(mPanelController.getPanelContent(), panelParams);
            }
        }

        private ListPopupWindow createPowerOverflowPopup() {
            ListPopupWindow popup = new ListPopupWindow(new ContextThemeWrapper(
                    mContext, com.android.systemui.R.style.Control_ListPopupWindow));
            popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
            View overflowButton =
                    findViewById(com.android.systemui.R.id.global_actions_overflow_button);
            popup.setAnchorView(overflowButton);
            int parentWidth = mGlobalActionsLayout.getWidth();
            // arbitrarily set the menu width to half of parent
            // TODO: Logic for menu sizing based on contents.
            int halfParentWidth = Math.round(parentWidth * 0.5f);
            popup.setContentWidth(halfParentWidth);
            popup.setAdapter(mOverflowAdapter);
            popup.setModal(true);
            return popup;
        }

        private void showPowerOverflowMenu() {
            mOverflowPopup.show();

            // Width is fixed to slightly more than half of the GlobalActionsLayout container.
            // TODO: Resize the width of this dialog based on the sizes of the items in it.
            int width = Math.round(mGlobalActionsLayout.getWidth() * 0.6f);

            ListView listView = mOverflowPopup.getListView();
            listView.setDividerHeight(mContext.getResources()
                    .getDimensionPixelSize(com.android.systemui.R.dimen.control_list_divider));
            listView.setDivider(mContext.getResources().getDrawable(
                    com.android.systemui.R.drawable.controls_list_divider));
            mOverflowPopup.setWidth(width);
            mOverflowPopup.setHorizontalOffset(-width + mOverflowPopup.getAnchorView().getWidth());
            mOverflowPopup.setVerticalOffset(mOverflowPopup.getAnchorView().getHeight());
            mOverflowPopup.show();
        }

        private void hidePowerOverflowMenu() {
            mOverflowPopup.dismiss();
        }

        private void initializeLayout() {
            setContentView(getGlobalActionsLayoutId(mContext));
            fixNavBarClipping();
            mControlsView = findViewById(com.android.systemui.R.id.global_actions_controls);
            mGlobalActionsLayout = findViewById(com.android.systemui.R.id.global_actions_view);
            mGlobalActionsLayout.setOutsideTouchListener(view -> dismiss());
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

            mOverflowPopup = createPowerOverflowPopup();

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

            View globalActionsParent = (View) mGlobalActionsLayout.getParent();
            globalActionsParent.setOnClickListener(v -> dismiss());

            // add fall-through dismiss handling to root view
            View rootView = findViewById(com.android.systemui.R.id.global_actions_grid_root);
            if (rootView != null) {
                rootView.setOnClickListener(v -> dismiss());
            }

            if (shouldUsePanel()) {
                initializePanel();
            }
            if (mBackgroundDrawable == null) {
                mBackgroundDrawable = new ScrimDrawable();
                if (mUseControlsLayout) {
                    mScrimAlpha = 1.0f;
                } else {
                    mScrimAlpha = mBlurUtils.supportsBlursOnWindows()
                            ? ScrimController.BLUR_SCRIM_ALPHA : ScrimController.BUSY_SCRIM_ALPHA;
                }
            }
            getWindow().setBackgroundDrawable(mBackgroundDrawable);
        }

        private void fixNavBarClipping() {
            ViewGroup content = findViewById(android.R.id.content);
            content.setClipChildren(false);
            content.setClipToPadding(false);
            ViewGroup contentParent = (ViewGroup) content.getParent();
            contentParent.setClipChildren(false);
            contentParent.setClipToPadding(false);
        }

        private int getGlobalActionsLayoutId(Context context) {
            if (mUseControlsLayout) {
                return com.android.systemui.R.layout.global_actions_grid_v2;
            }

            int rotation = RotationUtils.getRotation(context);
            boolean useGridLayout = isForceGridEnabled(context)
                    || (shouldUsePanel() && rotation == RotationUtils.ROTATION_NONE);
            if (rotation == RotationUtils.ROTATION_SEASCAPE) {
                if (useGridLayout) {
                    return com.android.systemui.R.layout.global_actions_grid_seascape;
                } else {
                    return com.android.systemui.R.layout.global_actions_column_seascape;
                }
            } else {
                if (useGridLayout) {
                    return com.android.systemui.R.layout.global_actions_grid;
                } else {
                    return com.android.systemui.R.layout.global_actions_column;
                }
            }
        }

        @Override
        protected void onStart() {
            super.setCanceledOnTouchOutside(true);
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
            ((ScrimDrawable) mBackgroundDrawable).setColor(
                    !mUseControlsLayout && colors.supportsDarkText()
                            ? Color.WHITE : Color.BLACK, animate);
            View decorView = getWindow().getDecorView();
            if (colors.supportsDarkText()) {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR |
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
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
        public void show() {
            super.show();
            mShowing = true;
            mHadTopUi = mNotificationShadeWindowController.getForceHasTopUi();
            mNotificationShadeWindowController.setForceHasTopUi(true);
            mBackgroundDrawable.setAlpha(0);
            mGlobalActionsLayout.setTranslationX(mGlobalActionsLayout.getAnimationOffsetX());
            mGlobalActionsLayout.setTranslationY(mGlobalActionsLayout.getAnimationOffsetY());
            mGlobalActionsLayout.setAlpha(0);
            mGlobalActionsLayout.animate()
                    .alpha(1)
                    .translationX(0)
                    .translationY(0)
                    .setDuration(450)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .setUpdateListener(animation -> {
                        float animatedValue = animation.getAnimatedFraction();
                        int alpha = (int) (animatedValue * mScrimAlpha * 255);
                        mBackgroundDrawable.setAlpha(alpha);
                        mDepthController.updateGlobalDialogVisibility(animatedValue,
                                mGlobalActionsLayout);
                    })
                    .start();
            ViewGroup root = (ViewGroup) mGlobalActionsLayout.getRootView();
            root.setOnApplyWindowInsetsListener((v, windowInsets) -> {
                if (mUseControlsLayout) {
                    root.setPadding(windowInsets.getStableInsetLeft(),
                            windowInsets.getStableInsetTop(),
                            windowInsets.getStableInsetRight(),
                            windowInsets.getStableInsetBottom());
                }
                return WindowInsets.CONSUMED;
            });
            if (mControlsUiController != null) {
                mControlsUiController.show(mControlsView);
            }
        }

        @Override
        public void dismiss() {
            if (!mShowing) {
                return;
            }
            mShowing = false;
            if (mControlsUiController != null) mControlsUiController.hide();
            mGlobalActionsLayout.setTranslationX(0);
            mGlobalActionsLayout.setTranslationY(0);
            mGlobalActionsLayout.setAlpha(1);
            mGlobalActionsLayout.animate()
                    .alpha(0)
                    .translationX(mGlobalActionsLayout.getAnimationOffsetX())
                    .translationY(mGlobalActionsLayout.getAnimationOffsetY())
                    .setDuration(550)
                    .withEndAction(this::completeDismiss)
                    .setInterpolator(new LogAccelerateInterpolator())
                    .setUpdateListener(animation -> {
                        float animatedValue = 1f - animation.getAnimatedFraction();
                        int alpha = (int) (animatedValue * mScrimAlpha * 255);
                        mBackgroundDrawable.setAlpha(alpha);
                        mDepthController.updateGlobalDialogVisibility(animatedValue,
                                mGlobalActionsLayout);
                    })
                    .start();
            dismissPanel();
            resetOrientation();
        }

        void dismissImmediately() {
            mShowing = false;
            if (mControlsUiController != null) mControlsUiController.hide();
            dismissPanel();
            resetOrientation();
            completeDismiss();
        }

        private void completeDismiss() {
            mNotificationShadeWindowController.setForceHasTopUi(mHadTopUi);
            mDepthController.updateGlobalDialogVisibility(0, null /* view */);
            super.dismiss();
        }

        private void dismissPanel() {
            if (mPanelController != null) {
                mPanelController.onDismissed();
            }
        }

        private void setRotationSuggestionsEnabled(boolean enabled) {
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

        private void resetOrientation() {
            if (mResetOrientationData != null) {
                RotationPolicy.setRotationLockAtAngle(mContext, mResetOrientationData.locked,
                        mResetOrientationData.rotation);
            }
            setRotationSuggestionsEnabled(true);
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
            initializeLayout();
            mGlobalActionsLayout.updateList();
            if (mControlsUiController != null) {
                mControlsUiController.show(mControlsView);
            }
        }

        public void onRotate(int from, int to) {
            if (mShowing) {
                mOnRotateCallback.run();
                refreshDialog();
            }
        }

        private static class ResetOrientationData {
            public boolean locked;
            public int rotation;
        }
    }

    /**
     * Determines whether or not debug mode has been activated for the Global Actions Panel.
     */
    private static boolean isPanelDebugModeEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.GLOBAL_ACTIONS_PANEL_DEBUG_ENABLED, 0) == 1;
    }

    /**
     * Determines whether or not the Global Actions menu should be forced to use the newer
     * grid-style layout.
     */
    private static boolean isForceGridEnabled(Context context) {
        return isPanelDebugModeEnabled(context);
    }

    @VisibleForTesting
    protected boolean shouldShowControls() {
        return mKeyguardStateController.isUnlocked()
                && mControlsUiController.getAvailable()
                && !mControlsServiceInfos.isEmpty();
    }

    // TODO: Remove legacy layout XML and classes.
    protected boolean shouldUseControlsLayout() {
        // always use new controls layout
        return true;
    }
}