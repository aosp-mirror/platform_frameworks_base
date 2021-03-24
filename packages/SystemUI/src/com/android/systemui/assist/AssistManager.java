package com.android.systemui.assist;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.DejankUtils.whitelistIpcs;
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
import android.metrics.LogMaker;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.assist.ui.DefaultUiController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Class to manage everything related to assist in SystemUI.
 */
@SysUISingleton
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
         * Hides any SysUI for the assistant, but _does not_ close the assistant itself.
         */
        void hide();
    }

    private static final String TAG = "AssistManager";

    // Note that VERBOSE logging may leak PII (e.g. transcription contents).
    private static final boolean VERBOSE = false;

    private static final String INVOCATION_TIME_MS_KEY = "invocation_time_ms";
    private static final String INVOCATION_PHONE_STATE_KEY = "invocation_phone_state";
    public static final String INVOCATION_TYPE_KEY = "invocation_type";
    protected static final String ACTION_KEY = "action";
    protected static final String SET_ASSIST_GESTURE_CONSTRAINED_ACTION =
            "set_assist_gesture_constrained";
    protected static final String CONSTRAINED_KEY = "should_constrain";

    public static final int INVOCATION_TYPE_GESTURE = 1;
    public static final int INVOCATION_TYPE_OTHER = 2;
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
    private final AssistDisclosure mAssistDisclosure;
    private final PhoneStateMonitor mPhoneStateMonitor;
    private final UiController mUiController;
    protected final Lazy<SysUiState> mSysUiState;
    protected final AssistLogger mAssistLogger;

    private final DeviceProvisionedController mDeviceProvisionedController;
    private final CommandQueue mCommandQueue;
    private final AssistOrbController mOrbController;
    protected final AssistUtils mAssistUtils;

    private IVoiceInteractionSessionShowCallback mShowCallback =
            new IVoiceInteractionSessionShowCallback.Stub() {

                @Override
                public void onFailed() throws RemoteException {
                    mOrbController.postHide();
                }

                @Override
                public void onShown() throws RemoteException {
                    mOrbController.postHide();
                }
            };

    @Inject
    public AssistManager(
            DeviceProvisionedController controller,
            Context context,
            AssistUtils assistUtils,
            CommandQueue commandQueue,
            PhoneStateMonitor phoneStateMonitor,
            OverviewProxyService overviewProxyService,
            ConfigurationController configurationController,
            Lazy<SysUiState> sysUiState,
            DefaultUiController defaultUiController,
            AssistLogger assistLogger) {
        mContext = context;
        mDeviceProvisionedController = controller;
        mCommandQueue = commandQueue;
        mAssistUtils = assistUtils;
        mAssistDisclosure = new AssistDisclosure(context, new Handler());
        mPhoneStateMonitor = phoneStateMonitor;
        mAssistLogger = assistLogger;

        mOrbController = new AssistOrbController(configurationController, context);

        registerVoiceInteractionSessionListener();

        mUiController = defaultUiController;

        mSysUiState = sysUiState;

        overviewProxyService.addCallback(new OverviewProxyService.OverviewProxyListener() {
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
                        mAssistLogger.reportAssistantSessionEvent(
                                AssistantSessionEvent.ASSISTANT_SESSION_UPDATE);
                    }

                    @Override
                    public void onVoiceSessionHidden() throws RemoteException {
                        if (VERBOSE) {
                            Log.v(TAG, "Voice closed");
                        }
                        mAssistLogger.reportAssistantSessionEvent(
                                AssistantSessionEvent.ASSISTANT_SESSION_CLOSE);
                    }

                    @Override
                    public void onSetUiHints(Bundle hints) {
                        if (VERBOSE) {
                            Log.v(TAG, "UI hints received");
                        }

                        String action = hints.getString(ACTION_KEY);
                        if (SET_ASSIST_GESTURE_CONSTRAINED_ACTION.equals(action)) {
                            mSysUiState.get()
                                    .setFlag(
                                            SYSUI_STATE_ASSIST_GESTURE_CONSTRAINED,
                                            hints.getBoolean(CONSTRAINED_KEY, false))
                                    .commitUpdate(DEFAULT_DISPLAY);
                        }
                    }
                });
    }

    protected boolean shouldShowOrb() {
        return !ActivityManager.isLowRamDeviceStatic();
    }

    public void startAssist(Bundle args) {
        final ComponentName assistComponent = getAssistInfo();
        if (assistComponent == null) {
            return;
        }

        final boolean isService = assistComponent.equals(getVoiceInteractorComponentName());
        if (!isService || (!isVoiceSessionRunning() && shouldShowOrb())) {
            mOrbController.showOrb(assistComponent, isService);
            mOrbController.postHideDelayed(isService ? TIMEOUT_SERVICE : TIMEOUT_ACTIVITY);
        }

        if (args == null) {
            args = new Bundle();
        }
        int legacyInvocationType = args.getInt(INVOCATION_TYPE_KEY, 0);
        int legacyDeviceState = mPhoneStateMonitor.getPhoneState();
        args.putInt(INVOCATION_PHONE_STATE_KEY, legacyDeviceState);
        args.putLong(INVOCATION_TIME_MS_KEY, SystemClock.elapsedRealtime());
        mAssistLogger.reportAssistantInvocationEventFromLegacy(
                legacyInvocationType,
                /* isInvocationComplete = */ true,
                assistComponent,
                legacyDeviceState);
        logStartAssistLegacy(legacyInvocationType, legacyDeviceState);
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

    public void hideAssist() {
        mAssistUtils.hideCurrentSession();
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
        mCommandQueue.animateCollapsePanels(
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

        if (structureEnabled && AssistUtils.isDisclosureEnabled(mContext)) {
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
        // TODO(b/140051519)
        return whitelistIpcs(() -> mAssistUtils.activeServiceSupportsLaunchFromKeyguard());
    }

    public ComponentName getVoiceInteractorComponentName() {
        return mAssistUtils.getActiveServiceComponentName();
    }

    private boolean isVoiceSessionRunning() {
        return mAssistUtils.isSessionRunning();
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
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                mAssistUtils.onLockscreenShown();
            }
        });
    }

    /** Returns the logging flags for the given Assistant invocation type. */
    public int toLoggingSubType(int invocationType) {
        return toLoggingSubType(invocationType, mPhoneStateMonitor.getPhoneState());
    }

    protected void logStartAssistLegacy(int invocationType, int phoneState) {
        MetricsLogger.action(
                new LogMaker(MetricsEvent.ASSISTANT)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(toLoggingSubType(invocationType, phoneState)));
    }

    protected final int toLoggingSubType(int invocationType, int phoneState) {
        // Note that this logic will break if the number of Assistant invocation types exceeds 7.
        // There are currently 5 invocation types, but we will be migrating to the new logging
        // framework in the next update.
        int subType = 0;
        subType |= invocationType << 1;
        subType |= phoneState << 4;
        return subType;
    }
}
