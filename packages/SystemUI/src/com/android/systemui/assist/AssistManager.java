package com.android.systemui.assist;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ASSIST_GESTURE_CONSTRAINED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.metrics.LogMaker;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.assist.ui.DefaultUiController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Class to manage everything related to assist in SystemUI.
 */
@Singleton
public class AssistManager {

    /**
     * Controls the UI for showing Assistant invocation progress.
     */
    public interface UiController {
        /**
         * Updates the invocation progress.
         *
         * @param type     one of INVOCATION_TYPE_GESTURE, INVOCATION_TYPE_ACTIVE_EDGE,
         *                 INVOCATION_TYPE_VOICE, INVOCATION_TYPE_QUICK_SEARCH_BAR,
         *                 INVOCATION_HOME_BUTTON_LONG_PRESS
         * @param progress a float between 0 and 1 inclusive. 0 represents the beginning of the
         *                 gesture; 1 represents the end.
         */
        void onInvocationProgress(int type, float progress);

        /**
         * Called when an invocation gesture completes.
         *
         * @param velocity the speed of the invocation gesture, in pixels per millisecond. For
         *                 drags, this is 0.
         */
        void onGestureCompletion(float velocity);

        /**
         * Called with the Bundle from VoiceInteractionSessionListener.onSetUiHints.
         */
        void processBundle(Bundle hints);

        /**
         * Hides any SysUI for the assistant, but _does not_ close the assistant itself.
         */
        void hide();
    }

    private static final String TAG = "AssistManager";

    // Note that VERBOSE logging may leak PII (e.g. transcription contents).
    private static final boolean VERBOSE = false;

    private static final String ASSIST_ICON_METADATA_NAME =
            "com.android.systemui.action_assist_icon";
    private static final String INVOCATION_TIME_MS_KEY = "invocation_time_ms";
    private static final String INVOCATION_PHONE_STATE_KEY = "invocation_phone_state";
    public static final String INVOCATION_TYPE_KEY = "invocation_type";
    protected static final String ACTION_KEY = "action";
    protected static final String SHOW_ASSIST_HANDLES_ACTION = "show_assist_handles";
    protected static final String SET_ASSIST_GESTURE_CONSTRAINED_ACTION =
            "set_assist_gesture_constrained";
    protected static final String CONSTRAINED_KEY = "should_constrain";

    public static final int INVOCATION_TYPE_GESTURE = 1;
    public static final int INVOCATION_TYPE_ACTIVE_EDGE = 2;
    public static final int INVOCATION_TYPE_VOICE = 3;
    public static final int INVOCATION_TYPE_QUICK_SEARCH_BAR = 4;
    public static final int INVOCATION_HOME_BUTTON_LONG_PRESS = 5;

    public static final int DISMISS_REASON_INVOCATION_CANCELLED = 1;
    public static final int DISMISS_REASON_TAP = 2;
    public static final int DISMISS_REASON_BACK = 3;
    public static final int DISMISS_REASON_TIMEOUT = 4;

    private static final long TIMEOUT_SERVICE = 2500;
    private static final long TIMEOUT_ACTIVITY = 1000;

    protected final Context mContext;
    private final WindowManager mWindowManager;
    private final AssistDisclosure mAssistDisclosure;
    private final InterestingConfigChanges mInterestingConfigChanges;
    private final PhoneStateMonitor mPhoneStateMonitor;
    private final AssistHandleBehaviorController mHandleController;
    private final UiController mUiController;
    protected final OverviewProxyService mOverviewProxyService;

    private AssistOrbContainer mView;
    private final DeviceProvisionedController mDeviceProvisionedController;
    protected final AssistUtils mAssistUtils;
    private final boolean mShouldEnableOrb;

    private IVoiceInteractionSessionShowCallback mShowCallback =
            new IVoiceInteractionSessionShowCallback.Stub() {

                @Override
                public void onFailed() throws RemoteException {
                    mView.post(mHideRunnable);
                }

                @Override
                public void onShown() throws RemoteException {
                    mView.post(mHideRunnable);
                }
            };

    private Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            mView.removeCallbacks(this);
            mView.show(false /* show */, true /* animate */);
        }
    };

    private ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onConfigChanged(Configuration newConfig) {
                    if (!mInterestingConfigChanges.applyNewConfig(mContext.getResources())) {
                        return;
                    }
                    boolean visible = false;
                    if (mView != null) {
                        visible = mView.isShowing();
                        mWindowManager.removeView(mView);
                    }

                    mView = (AssistOrbContainer) LayoutInflater.from(mContext).inflate(
                            R.layout.assist_orb, null);
                    mView.setVisibility(View.GONE);
                    mView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                    WindowManager.LayoutParams lp = getLayoutParams();
                    mWindowManager.addView(mView, lp);
                    if (visible) {
                        mView.show(true /* show */, false /* animate */);
                    }
                }
            };

    @Inject
    public AssistManager(
            DeviceProvisionedController controller,
            Context context,
            AssistUtils assistUtils,
            AssistHandleBehaviorController handleController,
            ConfigurationController configurationController,
            OverviewProxyService overviewProxyService) {
        mContext = context;
        mDeviceProvisionedController = controller;
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mAssistUtils = assistUtils;
        mAssistDisclosure = new AssistDisclosure(context, new Handler());
        mPhoneStateMonitor = new PhoneStateMonitor(context);
        mHandleController = handleController;

        configurationController.addCallback(mConfigurationListener);

        registerVoiceInteractionSessionListener();
        mInterestingConfigChanges = new InterestingConfigChanges(ActivityInfo.CONFIG_ORIENTATION
                | ActivityInfo.CONFIG_LOCALE | ActivityInfo.CONFIG_UI_MODE
                | ActivityInfo.CONFIG_SCREEN_LAYOUT | ActivityInfo.CONFIG_ASSETS_PATHS);
        mConfigurationListener.onConfigChanged(context.getResources().getConfiguration());
        mShouldEnableOrb = !ActivityManager.isLowRamDeviceStatic();

        mUiController = new DefaultUiController(mContext);

        mOverviewProxyService = overviewProxyService;
        mOverviewProxyService.addCallback(new OverviewProxyService.OverviewProxyListener() {
            @Override
            public void onAssistantProgress(float progress) {
                // Progress goes from 0 to 1 to indicate how close the assist gesture is to
                // completion.
                onInvocationProgress(INVOCATION_TYPE_GESTURE, progress);
            }

            @Override
            public void onAssistantGestureCompletion(float velocity) {
                onGestureCompletion(velocity);
            }
        });
    }

    protected void registerVoiceInteractionSessionListener() {
        mAssistUtils.registerVoiceInteractionSessionListener(
                new IVoiceInteractionSessionListener.Stub() {
                    @Override
                    public void onVoiceSessionShown() throws RemoteException {
                        if (VERBOSE) {
                            Log.v(TAG, "Voice open");
                        }
                    }

                    @Override
                    public void onVoiceSessionHidden() throws RemoteException {
                        if (VERBOSE) {
                            Log.v(TAG, "Voice closed");
                        }
                    }

                    @Override
                    public void onSetUiHints(Bundle hints) {
                        if (VERBOSE) {
                            Log.v(TAG, "UI hints received");
                        }

                        String action = hints.getString(ACTION_KEY);
                        if (SHOW_ASSIST_HANDLES_ACTION.equals(action)) {
                            requestAssistHandles();
                        } else if (SET_ASSIST_GESTURE_CONSTRAINED_ACTION.equals(action)) {
                            mOverviewProxyService.setSystemUiStateFlag(
                                    SYSUI_STATE_ASSIST_GESTURE_CONSTRAINED,
                                    hints.getBoolean(CONSTRAINED_KEY, false),
                                    DEFAULT_DISPLAY);
                        }
                    }
                });
    }

    protected boolean shouldShowOrb() {
        return false;
    }

    public void startAssist(Bundle args) {
        final ComponentName assistComponent = getAssistInfo();
        if (assistComponent == null) {
            return;
        }

        final boolean isService = assistComponent.equals(getVoiceInteractorComponentName());
        if (!isService || (!isVoiceSessionRunning() && shouldShowOrb())) {
            showOrb(assistComponent, isService);
            mView.postDelayed(mHideRunnable, isService
                    ? TIMEOUT_SERVICE
                    : TIMEOUT_ACTIVITY);
        }

        if (args == null) {
            args = new Bundle();
        }
        int invocationType = args.getInt(INVOCATION_TYPE_KEY, 0);
        if (invocationType == INVOCATION_TYPE_GESTURE) {
            mHandleController.onAssistantGesturePerformed();
        }
        int phoneState = mPhoneStateMonitor.getPhoneState();
        args.putInt(INVOCATION_PHONE_STATE_KEY, phoneState);
        args.putLong(INVOCATION_TIME_MS_KEY, SystemClock.elapsedRealtime());
        logStartAssist(invocationType, phoneState);
        startAssistInternal(args, assistComponent, isService);
    }

    /** Called when the user is performing an assistant invocation action (e.g. Active Edge) */
    public void onInvocationProgress(int type, float progress) {
        mUiController.onInvocationProgress(type, progress);
    }

    /**
     * Called when the user has invoked the assistant with the incoming velocity, in pixels per
     * millisecond. For invocations without a velocity (e.g. slow drag), the velocity is set to
     * zero.
     */
    public void onGestureCompletion(float velocity) {
        mUiController.onGestureCompletion(velocity);
    }

    protected void requestAssistHandles() {
        mHandleController.onAssistHandlesRequested();
    }

    public void hideAssist() {
        mAssistUtils.hideCurrentSession();
    }

    private WindowManager.LayoutParams getLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                mContext.getResources().getDimensionPixelSize(R.dimen.assist_orb_scrim_height),
                WindowManager.LayoutParams.TYPE_VOICE_INTERACTION_STARTING,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.token = new Binder();
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setTitle("AssistPreviewPanel");
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        return lp;
    }

    private void showOrb(@NonNull ComponentName assistComponent, boolean isService) {
        maybeSwapSearchIcon(assistComponent, isService);
        if (mShouldEnableOrb) {
            mView.show(true /* show */, true /* animate */);
        }
    }

    private void startAssistInternal(Bundle args, @NonNull ComponentName assistComponent,
            boolean isService) {
        if (isService) {
            startVoiceInteractor(args);
        } else {
            startAssistActivity(args, assistComponent);
        }
    }

    private void startAssistActivity(Bundle args, @NonNull ComponentName assistComponent) {
        if (!mDeviceProvisionedController.isDeviceProvisioned()) {
            return;
        }

        // Close Recent Apps if needed
        SysUiServiceProvider.getComponent(mContext, CommandQueue.class).animateCollapsePanels(
                CommandQueue.FLAG_EXCLUDE_SEARCH_PANEL | CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                false /* force */);

        boolean structureEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ASSIST_STRUCTURE_ENABLED, 1, UserHandle.USER_CURRENT) != 0;

        final SearchManager searchManager =
                (SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE);
        if (searchManager == null) {
            return;
        }
        final Intent intent = searchManager.getAssistIntent(structureEnabled);
        if (intent == null) {
            return;
        }
        intent.setComponent(assistComponent);
        intent.putExtras(args);

        if (structureEnabled) {
            showDisclosure();
        }

        try {
            final ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                    R.anim.search_launch_enter, R.anim.search_launch_exit);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    mContext.startActivityAsUser(intent, opts.toBundle(),
                            new UserHandle(UserHandle.USER_CURRENT));
                }
            });
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Activity not found for " + intent.getAction());
        }
    }

    private void startVoiceInteractor(Bundle args) {
        mAssistUtils.showSessionForActiveService(args,
                VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE, mShowCallback, null);
    }

    public void launchVoiceAssistFromKeyguard() {
        mAssistUtils.launchVoiceAssistFromKeyguard();
    }

    public boolean canVoiceAssistBeLaunchedFromKeyguard() {
        return mAssistUtils.activeServiceSupportsLaunchFromKeyguard();
    }

    public ComponentName getVoiceInteractorComponentName() {
        return mAssistUtils.getActiveServiceComponentName();
    }

    private boolean isVoiceSessionRunning() {
        return mAssistUtils.isSessionRunning();
    }

    private void maybeSwapSearchIcon(@NonNull ComponentName assistComponent, boolean isService) {
        replaceDrawable(mView.getOrb().getLogo(), assistComponent, ASSIST_ICON_METADATA_NAME,
                isService);
    }

    public void replaceDrawable(ImageView v, ComponentName component, String name,
            boolean isService) {
        if (component != null) {
            try {
                PackageManager packageManager = mContext.getPackageManager();
                // Look for the search icon specified in the activity meta-data
                Bundle metaData = isService
                        ? packageManager.getServiceInfo(
                        component, PackageManager.GET_META_DATA).metaData
                        : packageManager.getActivityInfo(
                                component, PackageManager.GET_META_DATA).metaData;
                if (metaData != null) {
                    int iconResId = metaData.getInt(name);
                    if (iconResId != 0) {
                        Resources res = packageManager.getResourcesForApplication(
                                component.getPackageName());
                        v.setImageDrawable(res.getDrawable(iconResId));
                        return;
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                if (VERBOSE) {
                    Log.v(TAG, "Assistant component "
                            + component.flattenToShortString() + " not found");
                }
            } catch (Resources.NotFoundException nfe) {
                Log.w(TAG, "Failed to swap drawable from "
                        + component.flattenToShortString(), nfe);
            }
        }
        v.setImageDrawable(null);
    }

    protected AssistHandleBehaviorController getHandleBehaviorController() {
        return mHandleController;
    }

    @Nullable
    public ComponentName getAssistInfoForUser(int userId) {
        return mAssistUtils.getAssistComponentForUser(userId);
    }

    @Nullable
    private ComponentName getAssistInfo() {
        return getAssistInfoForUser(KeyguardUpdateMonitor.getCurrentUser());
    }

    public void showDisclosure() {
        mAssistDisclosure.postShow();
    }

    public void onLockscreenShown() {
        mAssistUtils.onLockscreenShown();
    }

    public long getAssistHandleShowAndGoRemainingDurationMs() {
        return mHandleController.getShowAndGoRemainingTimeMs();
    }

    /** Returns the logging flags for the given Assistant invocation type. */
    public int toLoggingSubType(int invocationType) {
        return toLoggingSubType(invocationType, mPhoneStateMonitor.getPhoneState());
    }

    protected void logStartAssist(int invocationType, int phoneState) {
        MetricsLogger.action(
                new LogMaker(MetricsEvent.ASSISTANT)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(toLoggingSubType(invocationType, phoneState)));
    }

    protected final int toLoggingSubType(int invocationType, int phoneState) {
        // Note that this logic will break if the number of Assistant invocation types exceeds 7.
        // There are currently 5 invocation types, but we will be migrating to the new logging
        // framework in the next update.
        int subType = mHandleController.areHandlesShowing() ? 0 : 1;
        subType |= invocationType << 1;
        subType |= phoneState << 4;
        return subType;
    }
}
