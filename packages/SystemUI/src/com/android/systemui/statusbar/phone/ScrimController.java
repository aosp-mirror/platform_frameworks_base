/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER;
import static com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB;
import static com.android.systemui.keyguard.shared.model.KeyguardState.GONE;
import static com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN;
import static com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import static java.lang.Float.isNaN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.app.AlarmManager;
import android.graphics.Color;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.util.MathUtils;
import android.util.Pair;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.FloatRange;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.util.ContrastColorUtil;
import com.android.internal.util.function.TriConsumer;
import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.Utils;
import com.android.systemui.CoreStartable;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.animation.ShadeInterpolation;
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.Edge;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.keyguard.shared.model.ScrimAlpha;
import com.android.systemui.keyguard.shared.model.TransitionState;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToGoneTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.scene.shared.model.Scenes;
import com.android.systemui.scrim.ScrimView;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.statusbar.notification.stack.ViewState;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.wakelock.DelayedWakeLock;
import com.android.systemui.util.wakelock.WakeLock;
import com.android.systemui.wallpapers.data.repository.WallpaperRepository;

import kotlinx.coroutines.CoroutineDispatcher;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Controls both the scrim behind the notifications and in front of the notifications (when a
 * security method gets shown).
 */
@SysUISingleton
public class ScrimController implements ViewTreeObserver.OnPreDrawListener, Dumpable,
        CoreStartable {

    static final String TAG = "ScrimController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // debug mode colors scrims with below debug colors, irrespectively of which state they're in
    public static final boolean DEBUG_MODE = false;

    public static final int DEBUG_NOTIFICATIONS_TINT = Color.RED;
    public static final int DEBUG_FRONT_TINT = Color.GREEN;
    public static final int DEBUG_BEHIND_TINT = Color.BLUE;

    /**
     * General scrim animation duration.
     */
    public static final long ANIMATION_DURATION = 220;
    /**
     * Longer duration, currently only used when going to AOD.
     */
    public static final long ANIMATION_DURATION_LONG = 1000;
    /**
     * When both scrims have 0 alpha.
     */
    public static final int TRANSPARENT = 0;
    /**
     * When scrims aren't transparent (alpha 0) but also not opaque (alpha 1.)
     */
    public static final int SEMI_TRANSPARENT = 1;
    /**
     * When at least 1 scrim is fully opaque (alpha set to 1.)
     */
    public static final int OPAQUE = 2;
    private boolean mClipsQsScrim;

    /**
     * Whether an activity is launching over the lockscreen. During the launch animation, we want to
     * delay certain scrim changes until after the animation ends.
     */
    private boolean mOccludeAnimationPlaying = false;

    /**
     * The amount of progress we are currently in if we're transitioning to the full shade.
     * 0.0f means we're not transitioning yet, while 1 means we're all the way in the full
     * shade.
     */
    private float mTransitionToFullShadeProgress;

    /**
     * Same as {@link #mTransitionToFullShadeProgress}, but specifically for the notifications scrim
     * on the lock screen.
     *
     * On split shade lock screen we want the different scrims to fade in at different times and
     * rates.
     */
    private float mTransitionToLockScreenFullShadeNotificationsProgress;

    /**
     * If we're currently transitioning to the full shade.
     */
    private boolean mTransitioningToFullShade;

    /**
     * The percentage of the bouncer which is hidden. If 1, the bouncer is completely hidden. If
     * 0, the bouncer is visible.
     */
    @FloatRange(from = 0, to = 1)
    private float mBouncerHiddenFraction = KeyguardBouncerConstants.EXPANSION_HIDDEN;

    @IntDef(prefix = {"VISIBILITY_"}, value = {
            TRANSPARENT,
            SEMI_TRANSPARENT,
            OPAQUE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScrimVisibility {
    }

    /**
     * Default alpha value for most scrims.
     */
    protected static final float KEYGUARD_SCRIM_ALPHA = 0.2f;
    /**
     * Scrim opacity when the phone is about to wake-up.
     */
    public static final float WAKE_SENSOR_SCRIM_ALPHA = 0.6f;

    /**
     * The default scrim under the shade and dialogs.
     * This should not be lower than 0.54, otherwise we won't pass GAR.
     */
    public static final float BUSY_SCRIM_ALPHA = 1f;

    /**
     * Scrim opacity that can have text on top.
     */
    public static final float GAR_SCRIM_ALPHA = 0.6f;

    static final int TAG_KEY_ANIM = R.id.scrim;
    private static final int TAG_START_ALPHA = R.id.scrim_alpha_start;
    private static final int TAG_END_ALPHA = R.id.scrim_alpha_end;
    private static final float NOT_INITIALIZED = -1;

    private ScrimState mState = ScrimState.UNINITIALIZED;

    private ScrimView mScrimInFront;
    private ScrimView mNotificationsScrim;
    private ScrimView mScrimBehind;

    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final DozeParameters mDozeParameters;
    private final DockManager mDockManager;
    private final AlarmTimeout mTimeTicker;
    private final KeyguardVisibilityCallback mKeyguardVisibilityCallback;
    private final Handler mHandler;
    private final Executor mMainExecutor;
    private final JavaAdapter mJavaAdapter;
    private final ScreenOffAnimationController mScreenOffAnimationController;
    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final KeyguardInteractor mKeyguardInteractor;

    private GradientColors mColors;
    private boolean mNeedsDrawableColorUpdate;

    private float mAdditionalScrimBehindAlphaKeyguard = 0f;
    // Combined scrim behind keyguard alpha of default scrim + additional scrim
    // (if wallpaper dimming is applied).
    private float mScrimBehindAlphaKeyguard = KEYGUARD_SCRIM_ALPHA;
    private final float mDefaultScrimAlpha;

    private float mRawPanelExpansionFraction;
    private float mPanelScrimMinFraction;
    // Calculated based on mRawPanelExpansionFraction and mPanelScrimMinFraction
    private float mPanelExpansionFraction = 1f; // Assume shade is expanded during initialization
    private float mQsExpansion;
    private boolean mQsBottomVisible;
    private boolean mAnimatingPanelExpansionOnUnlock; // don't animate scrim

    private boolean mDarkenWhileDragging;
    private boolean mExpansionAffectsAlpha = true;
    private boolean mAnimateChange;
    private boolean mUpdatePending;
    private long mAnimationDuration = -1;
    private long mAnimationDelay;
    private Animator.AnimatorListener mAnimatorListener;
    private final Interpolator mInterpolator = new DecelerateInterpolator();

    private float mInFrontAlpha = NOT_INITIALIZED;
    private float mBehindAlpha = NOT_INITIALIZED;
    private float mNotificationsAlpha = NOT_INITIALIZED;

    private int mInFrontTint;
    private int mBehindTint;
    private int mNotificationsTint;

    private boolean mWallpaperVisibilityTimedOut;
    private int mScrimsVisibility;
    private final TriConsumer<ScrimState, Float, GradientColors> mScrimStateListener;
    private final LargeScreenShadeInterpolator mLargeScreenShadeInterpolator;
    private Consumer<Integer> mScrimVisibleListener;
    private boolean mBlankScreen;
    private boolean mScreenBlankingCallbackCalled;
    private Callback mCallback;
    private boolean mWallpaperSupportsAmbientMode;
    private boolean mScreenOn;
    private boolean mTransparentScrimBackground;

    // Scrim blanking callbacks
    private Runnable mPendingFrameCallback;
    private Runnable mBlankingTransitionRunnable;

    private final WakeLock mWakeLock;
    private boolean mWakeLockHeld;
    private boolean mKeyguardOccluded;

    private KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    private final WallpaperRepository mWallpaperRepository;
    private CoroutineDispatcher mMainDispatcher;
    private boolean mIsBouncerToGoneTransitionRunning = false;
    private PrimaryBouncerToGoneTransitionViewModel mPrimaryBouncerToGoneTransitionViewModel;
    private AlternateBouncerToGoneTransitionViewModel mAlternateBouncerToGoneTransitionViewModel;
    private final Consumer<ScrimAlpha> mScrimAlphaConsumer =
            (ScrimAlpha alphas) -> {
                mInFrontAlpha = alphas.getFrontAlpha();
                mScrimInFront.setViewAlpha(mInFrontAlpha);

                mNotificationsAlpha = alphas.getNotificationsAlpha();
                mNotificationsScrim.setViewAlpha(mNotificationsAlpha);

                mBehindAlpha = alphas.getBehindAlpha();
                mScrimBehind.setViewAlpha(mBehindAlpha);
            };

    /**
     * Consumer that fades the behind scrim in and out during the transition between the lock screen
     * and the glanceable hub.
     *
     * While the lock screen is showing, the behind scrim is used to slightly darken the lock screen
     * wallpaper underneath. Since the glanceable hub is under all of the scrims, we want to fade
     * out the scrim so that the glanceable hub isn't darkened when it opens.
     *
     * {@link #applyState()} handles the scrim alphas once on the glanceable hub, this is only
     * responsible for setting the behind alpha during the transition.
     */
    private final Consumer<TransitionStep> mGlanceableHubConsumer = (TransitionStep step) -> {
        final float baseAlpha = ScrimState.KEYGUARD.getBehindAlpha();
        final float transitionProgress = step.getValue();
        if (step.getTo() == KeyguardState.LOCKSCREEN) {
            // Transitioning back to lock screen, fade in behind scrim again.
            mBehindAlpha = baseAlpha * transitionProgress;
        } else if (step.getTo() == GLANCEABLE_HUB) {
            // Transitioning to glanceable hub, fade out behind scrim.
            mBehindAlpha = baseAlpha * (1 - transitionProgress);
        }
        mScrimBehind.setViewAlpha(mBehindAlpha);
    };

    @VisibleForTesting
    Consumer<TransitionStep> mBouncerToGoneTransition;

    private boolean mViewsAttached;

    @Inject
    public ScrimController(
            LightBarController lightBarController,
            DozeParameters dozeParameters,
            AlarmManager alarmManager,
            KeyguardStateController keyguardStateController,
            DelayedWakeLock.Factory delayedWakeLockFactory,
            Handler handler,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DockManager dockManager,
            ConfigurationController configurationController,
            @Main Executor mainExecutor,
            JavaAdapter javaAdapter,
            ScreenOffAnimationController screenOffAnimationController,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            PrimaryBouncerToGoneTransitionViewModel primaryBouncerToGoneTransitionViewModel,
            AlternateBouncerToGoneTransitionViewModel alternateBouncerToGoneTransitionViewModel,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            KeyguardInteractor keyguardInteractor,
            WallpaperRepository wallpaperRepository,
            @Main CoroutineDispatcher mainDispatcher,
            LargeScreenShadeInterpolator largeScreenShadeInterpolator) {
        mScrimStateListener = lightBarController::setScrimState;
        mLargeScreenShadeInterpolator = largeScreenShadeInterpolator;
        mDefaultScrimAlpha = BUSY_SCRIM_ALPHA;

        mKeyguardStateController = keyguardStateController;
        mDarkenWhileDragging = !mKeyguardStateController.canDismissLockScreen();
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardVisibilityCallback = new KeyguardVisibilityCallback();
        mHandler = handler;
        mMainExecutor = mainExecutor;
        mJavaAdapter = javaAdapter;
        mScreenOffAnimationController = screenOffAnimationController;
        mTimeTicker = new AlarmTimeout(alarmManager, this::onHideWallpaperTimeout,
                "hide_aod_wallpaper", mHandler);
        mWakeLock = delayedWakeLockFactory.create("Scrims");
        // Scrim alpha is initially set to the value on the resource but might be changed
        // to make sure that text on top of it is legible.
        mDozeParameters = dozeParameters;
        mDockManager = dockManager;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        keyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onKeyguardFadingAwayChanged() {
                setKeyguardFadingAway(keyguardStateController.isKeyguardFadingAway(),
                        keyguardStateController.getKeyguardFadingAwayDuration());
            }
        });
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        configurationController.addCallback(new ConfigurationController.ConfigurationListener() {
            @Override
            public void onThemeChanged() {
                ScrimController.this.onThemeChanged();
            }

            @Override
            public void onUiModeChanged() {
                ScrimController.this.onThemeChanged();
            }
        });
        mColors = new GradientColors();
        mPrimaryBouncerToGoneTransitionViewModel = primaryBouncerToGoneTransitionViewModel;
        mAlternateBouncerToGoneTransitionViewModel = alternateBouncerToGoneTransitionViewModel;
        mKeyguardTransitionInteractor = keyguardTransitionInteractor;
        mKeyguardInteractor = keyguardInteractor;
        mWallpaperRepository = wallpaperRepository;
        mMainDispatcher = mainDispatcher;
    }

    @Override
    public void start() {
        mJavaAdapter.alwaysCollectFlow(
                mWallpaperRepository.getWallpaperSupportsAmbientMode(),
                this::setWallpaperSupportsAmbientMode);
    }

    /**
     * Attach the controller to the supplied views.
     */
    public void attachViews(ScrimView behindScrim, ScrimView notificationsScrim,
                            ScrimView scrimInFront) {
        mNotificationsScrim = notificationsScrim;
        mScrimBehind = behindScrim;
        mScrimInFront = scrimInFront;
        updateThemeColors();
        mNotificationsScrim.setScrimName(getScrimName(mNotificationsScrim));
        mScrimBehind.setScrimName(getScrimName(mScrimBehind));
        mScrimInFront.setScrimName(getScrimName(mScrimInFront));

        behindScrim.enableBottomEdgeConcave(mClipsQsScrim);
        mNotificationsScrim.enableRoundedCorners(true);

        final ScrimState[] states = ScrimState.values();
        for (int i = 0; i < states.length; i++) {
            states[i].init(mScrimInFront, mScrimBehind, mDozeParameters, mDockManager);
            states[i].setScrimBehindAlphaKeyguard(mScrimBehindAlphaKeyguard);
            states[i].setDefaultScrimAlpha(mDefaultScrimAlpha);
        }

        mTransparentScrimBackground = notificationsScrim.getResources()
                .getBoolean(R.bool.notification_scrim_transparent);
        updateScrims();
        mKeyguardUpdateMonitor.registerCallback(mKeyguardVisibilityCallback);

        // prepare() sets proper initial values for most states
        for (ScrimState state : ScrimState.values()) {
            state.prepare(state);
        }

        hydrateStateInternally(behindScrim);

        mViewsAttached = true;
    }

    private void hydrateStateInternally(ScrimView behindScrim) {
        if (SceneContainerFlag.isEnabled()) {
            return;
        }

        // Directly control transition to UNLOCKED scrim state from PRIMARY_BOUNCER, and make sure
        // to report back that keyguard has faded away. This fixes cases where the scrim state was
        // rapidly switching on unlock, due to shifts in state in CentralSurfacesImpl
        mBouncerToGoneTransition =
                (TransitionStep step) -> {
                    TransitionState state = step.getTransitionState();

                    mIsBouncerToGoneTransitionRunning = state == TransitionState.RUNNING;

                    if (state == TransitionState.STARTED) {
                        setExpansionAffectsAlpha(false);
                        legacyTransitionTo(ScrimState.UNLOCKED);
                    }

                    if (state == TransitionState.FINISHED || state == TransitionState.CANCELED) {
                        setExpansionAffectsAlpha(true);
                        if (mKeyguardStateController.isKeyguardFadingAway()) {
                            mStatusBarKeyguardViewManager.onKeyguardFadedAway();
                        }
                        dispatchScrimsVisible();
                    }
                };

        // PRIMARY_BOUNCER->GONE
        collectFlow(behindScrim, mKeyguardTransitionInteractor.transition(
                Edge.Companion.create(PRIMARY_BOUNCER, GONE)),
                mBouncerToGoneTransition, mMainDispatcher);
        collectFlow(behindScrim, mPrimaryBouncerToGoneTransitionViewModel.getScrimAlpha(),
                mScrimAlphaConsumer, mMainDispatcher);

        // ALTERNATE_BOUNCER->GONE
        collectFlow(behindScrim, mKeyguardTransitionInteractor.transition(
                Edge.Companion.create(ALTERNATE_BOUNCER, Scenes.Gone),
                Edge.Companion.create(ALTERNATE_BOUNCER, GONE)),
                mBouncerToGoneTransition, mMainDispatcher);
        collectFlow(behindScrim, mAlternateBouncerToGoneTransitionViewModel.getScrimAlpha(),
                mScrimAlphaConsumer, mMainDispatcher);

        // LOCKSCREEN<->GLANCEABLE_HUB
        collectFlow(
                behindScrim,
                mKeyguardTransitionInteractor.transition(
                        Edge.Companion.create(LOCKSCREEN, Scenes.Communal),
                        Edge.Companion.create(LOCKSCREEN, GLANCEABLE_HUB)),
                mGlanceableHubConsumer,
                mMainDispatcher);
        collectFlow(behindScrim,
                mKeyguardTransitionInteractor.transition(
                        Edge.Companion.create(Scenes.Communal, LOCKSCREEN),
                        Edge.Companion.create(GLANCEABLE_HUB, LOCKSCREEN)),
                mGlanceableHubConsumer, mMainDispatcher);
    }

    // TODO(b/270984686) recompute scrim height accurately, based on shade contents.
    /** Set corner radius of the bottom edge of the Notification scrim. */
    public void setNotificationBottomRadius(float radius) {
        if (mNotificationsScrim == null) {
            return;
        }
        mNotificationsScrim.setBottomEdgeRadius(radius);
    }

    /** Sets corner radius of scrims. */
    public void setScrimCornerRadius(int radius) {
        if (mScrimBehind == null || mNotificationsScrim == null) {
            return;
        }
        mScrimBehind.setCornerRadius(radius);
        mNotificationsScrim.setCornerRadius(radius);
    }

    void setScrimVisibleListener(Consumer<Integer> listener) {
        mScrimVisibleListener = listener;
    }

    public void transitionTo(ScrimState state) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode() || !mViewsAttached) {
            return;
        }

        internalTransitionTo(state, null);
    }

    /**
     * Transitions to the given {@link ScrimState}.
     *
     * @deprecated Legacy codepath only. Do not call directly.
     */
    @Deprecated
    public void legacyTransitionTo(ScrimState state) {
        SceneContainerFlag.assertInLegacyMode();
        internalTransitionTo(state, null);
    }

    /**
     * Transitions to the given {@link ScrimState}.
     *
     * @deprecated Legacy codepath only. Do not call directly.
     */
    @Deprecated
    public void legacyTransitionTo(ScrimState state, Callback callback) {
        SceneContainerFlag.assertInLegacyMode();
        internalTransitionTo(state, callback);
    }

    private void internalTransitionTo(ScrimState state, Callback callback) {
        if (mIsBouncerToGoneTransitionRunning) {
            Log.i(TAG, "Skipping transition to: " + state
                    + " while mIsBouncerToGoneTransitionRunning");
            return;
        }
        if (state == mState) {
            // Call the callback anyway, unless it's already enqueued
            if (callback != null && mCallback != callback) {
                callback.onFinished();
            }
            return;
        } else if (DEBUG) {
            Log.d(TAG, "State changed to: " + state);
        }

        if (state == ScrimState.UNINITIALIZED) {
            throw new IllegalArgumentException("Cannot change to UNINITIALIZED.");
        }

        final ScrimState oldState = mState;
        mState = state;
        Trace.traceCounter(Trace.TRACE_TAG_APP, "scrim_state", mState.ordinal());

        if (mCallback != null) {
            mCallback.onCancelled();
        }
        mCallback = callback;

        state.prepare(oldState);
        mScreenBlankingCallbackCalled = false;
        mAnimationDelay = 0;
        mBlankScreen = state.getBlanksScreen();
        mAnimateChange = state.getAnimateChange();
        mAnimationDuration = state.getAnimationDuration();

        if (mState == ScrimState.GLANCEABLE_HUB_OVER_DREAM) {
            // When the device is docked while on GLANCEABLE_HUB, the dream starts underneath the
            // hub and the ScrimState transitions to GLANCEABLE_HUB_OVER_DREAM. To prevent the
            // scrims from flickering in during this transition, we set the panel expansion
            // fraction, which is 1 when idle on GLANCEABLE_HUB, to 0. This only occurs when the hub
            // is open because the hub lives in the same window as the shade, which is not visible
            // when transitioning from KEYGUARD to DREAMING.
            mPanelExpansionFraction = 0f;
        }

        applyState();

        mScrimInFront.setBlendWithMainColor(state.shouldBlendWithMainColor());

        // Cancel blanking transitions that were pending before we requested a new state
        if (mPendingFrameCallback != null) {
            mScrimBehind.removeCallbacks(mPendingFrameCallback);
            mPendingFrameCallback = null;
        }
        if (mHandler.hasCallbacks(mBlankingTransitionRunnable)) {
            mHandler.removeCallbacks(mBlankingTransitionRunnable);
            mBlankingTransitionRunnable = null;
        }

        // Showing/hiding the keyguard means that scrim colors have to be switched, not necessary
        // to do the same when you're just showing the brightness mirror.
        mNeedsDrawableColorUpdate = state != ScrimState.BRIGHTNESS_MIRROR;

        // The device might sleep if it's entering AOD, we need to make sure that
        // the animation plays properly until the last frame.
        // It's important to avoid holding the wakelock unless necessary because
        // WakeLock#aqcuire will trigger an IPC and will cause jank.
        if (mState.isLowPowerState()) {
            holdWakeLock();
        }

        // AOD wallpapers should fade away after a while.
        // Docking pulses may take a long time, wallpapers should also fade away after a while.
        mWallpaperVisibilityTimedOut = false;
        if (shouldFadeAwayWallpaper()) {
            DejankUtils.postAfterTraversal(() -> {
                mTimeTicker.schedule(mDozeParameters.getWallpaperAodDuration(),
                        AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
            });
        } else {
            DejankUtils.postAfterTraversal(mTimeTicker::cancel);
        }

        if (mKeyguardUpdateMonitor.needsSlowUnlockTransition() && mState == ScrimState.UNLOCKED) {
            mAnimationDelay = CentralSurfaces.FADE_KEYGUARD_START_DELAY;
            scheduleUpdate();
        } else if (((oldState == ScrimState.AOD || oldState == ScrimState.PULSING)  // leaving doze
                && (!mDozeParameters.getAlwaysOn() || mState == ScrimState.UNLOCKED))
                || (mState == ScrimState.AOD && !mDozeParameters.getDisplayNeedsBlanking())) {
            // Scheduling a frame isn't enough when:
            //  • Leaving doze and we need to modify scrim color immediately
            //  • ColorFade will not kick-in and scrim cannot wait for pre-draw.
            onPreDraw();
        } else {
            // Schedule a frame
            scheduleUpdate();
        }

        dispatchBackScrimState(mScrimBehind.getViewAlpha());
    }

    private boolean shouldFadeAwayWallpaper() {
        if (!mWallpaperSupportsAmbientMode) {
            return false;
        }

        if (mState == ScrimState.AOD
                && (mDozeParameters.getAlwaysOn() || mDockManager.isDocked())) {
            return true;
        }

        return false;
    }

    public ScrimState getState() {
        return mState;
    }

    /**
     * Sets the additional scrim behind alpha keyguard that would be blended with the default scrim
     * by applying alpha composition on both values.
     *
     * @param additionalScrimAlpha alpha value of additional scrim behind alpha keyguard.
     */
    protected void setAdditionalScrimBehindAlphaKeyguard(float additionalScrimAlpha) {
        mAdditionalScrimBehindAlphaKeyguard = additionalScrimAlpha;
    }

    /**
     * Applies alpha composition to the default scrim behind alpha keyguard and the additional
     * scrim alpha, and sets this value to the scrim behind alpha keyguard.
     * This is used to apply additional keyguard dimming on top of the default scrim alpha value.
     */
    protected void applyCompositeAlphaOnScrimBehindKeyguard() {
        int compositeAlpha = ColorUtils.compositeAlpha(
                (int) (255 * mAdditionalScrimBehindAlphaKeyguard),
                (int) (255 * KEYGUARD_SCRIM_ALPHA));
        float keyguardScrimAlpha = (float) compositeAlpha / 255;
        setScrimBehindValues(keyguardScrimAlpha);
    }

    /**
     * Sets the scrim behind alpha keyguard values. This is how much the keyguard will be dimmed.
     *
     * @param scrimBehindAlphaKeyguard alpha value of the scrim behind
     */
    private void setScrimBehindValues(float scrimBehindAlphaKeyguard) {
        mScrimBehindAlphaKeyguard = scrimBehindAlphaKeyguard;
        ScrimState[] states = ScrimState.values();
        for (int i = 0; i < states.length; i++) {
            states[i].setScrimBehindAlphaKeyguard(scrimBehindAlphaKeyguard);
        }
        scheduleUpdate();
    }

    /** This is used by the predictive back gesture animation to scale the Shade. */
    public void applyBackScaling(float scale) {
        mNotificationsScrim.setScaleX(scale);
        mNotificationsScrim.setScaleY(scale);
    }

    public float getBackScaling() {
        return mNotificationsScrim.getScaleY();
    }

    public void onTrackingStarted() {
        mDarkenWhileDragging = !mKeyguardStateController.canDismissLockScreen();
        if (!mKeyguardUnlockAnimationController.isPlayingCannedUnlockAnimation()) {
            mAnimatingPanelExpansionOnUnlock = false;
        }
    }

    @VisibleForTesting
    protected void onHideWallpaperTimeout() {
        if (mState != ScrimState.AOD && mState != ScrimState.PULSING) {
            return;
        }

        holdWakeLock();
        mWallpaperVisibilityTimedOut = true;
        mAnimateChange = true;
        mAnimationDuration = mDozeParameters.getWallpaperFadeOutDuration();
        scheduleUpdate();
    }

    private void holdWakeLock() {
        if (!mWakeLockHeld) {
            if (mWakeLock != null) {
                mWakeLockHeld = true;
                mWakeLock.acquire(TAG);
            } else {
                Log.w(TAG, "Cannot hold wake lock, it has not been set yet");
            }
        }
    }

    /**
     * Current state of the shade expansion when pulling it from the top.
     * This value is 1 when on top of the keyguard and goes to 0 as the user drags up.
     *
     * The expansion fraction is tied to the scrim opacity.
     *
     * See {@link ScrimShadeTransitionController#onPanelExpansionChanged}.
     *
     * @param rawPanelExpansionFraction From 0 to 1 where 0 means collapsed and 1 expanded.
     */
    public void setRawPanelExpansionFraction(
             @FloatRange(from = 0.0, to = 1.0) float rawPanelExpansionFraction) {
        if (isNaN(rawPanelExpansionFraction)) {
            throw new IllegalArgumentException("rawPanelExpansionFraction should not be NaN");
        }
        mRawPanelExpansionFraction = rawPanelExpansionFraction;
        calculateAndUpdatePanelExpansion();
    }

    /** See {@link ShadeViewController#setPanelScrimMinFraction(float)}. */
    public void setPanelScrimMinFraction(float minFraction) {
        if (isNaN(minFraction)) {
            throw new IllegalArgumentException("minFraction should not be NaN");
        }
        mPanelScrimMinFraction = minFraction;
        calculateAndUpdatePanelExpansion();
    }

    private void calculateAndUpdatePanelExpansion() {
        float panelExpansionFraction = mRawPanelExpansionFraction;
        if (mPanelScrimMinFraction < 1.0f) {
            panelExpansionFraction = Math.max(
                    (mRawPanelExpansionFraction - mPanelScrimMinFraction)
                            / (1.0f - mPanelScrimMinFraction),
                    0);
        }

        if (mPanelExpansionFraction != panelExpansionFraction) {
            if (panelExpansionFraction != 0f
                    && mKeyguardUnlockAnimationController.isPlayingCannedUnlockAnimation()
                    && mState != ScrimState.UNLOCKED) {
                mAnimatingPanelExpansionOnUnlock = true;
            } else if (panelExpansionFraction == 0f) {
                mAnimatingPanelExpansionOnUnlock = false;
            }

            mPanelExpansionFraction = panelExpansionFraction;

            boolean relevantState = (mState == ScrimState.UNLOCKED
                    || mState == ScrimState.KEYGUARD
                    || mState == ScrimState.DREAMING
                    || mState == ScrimState.GLANCEABLE_HUB_OVER_DREAM
                    || mState == ScrimState.SHADE_LOCKED
                    || mState == ScrimState.PULSING);
            if (!(relevantState && mExpansionAffectsAlpha) || mAnimatingPanelExpansionOnUnlock) {
                return;
            }
            applyAndDispatchState();
        }
    }

    public void onUnlockAnimationFinished() {
        mAnimatingPanelExpansionOnUnlock = false;
        applyAndDispatchState();
    }

    /**
     * Set the amount of progress we are currently in if we're transitioning to the full shade.
     * 0.0f means we're not transitioning yet, while 1 means we're all the way in the full
     * shade.
     *
     * @param progress the progress for all scrims.
     * @param lockScreenNotificationsProgress the progress specifically for the notifications scrim.
     */
    public void setTransitionToFullShadeProgress(float progress,
            float lockScreenNotificationsProgress) {
        if (progress != mTransitionToFullShadeProgress || lockScreenNotificationsProgress
                != mTransitionToLockScreenFullShadeNotificationsProgress) {
            mTransitionToFullShadeProgress = progress;
            mTransitionToLockScreenFullShadeNotificationsProgress = lockScreenNotificationsProgress;
            setTransitionToFullShade(progress > 0.0f || lockScreenNotificationsProgress > 0.0f);
            applyAndDispatchState();
        }
    }

    /**
     * Set if we're currently transitioning to the full shade
     */
    private void setTransitionToFullShade(boolean transitioning) {
        if (transitioning != mTransitioningToFullShade) {
            mTransitioningToFullShade = transitioning;
        }
    }


    /**
     * Set bounds for notifications background, all coordinates are absolute
     */
    public void setNotificationsBounds(float left, float top, float right, float bottom) {
        if (mClipsQsScrim) {
            // notification scrim's rounded corners are anti-aliased, but clipping of the QS/behind
            // scrim can't be and it's causing jagged corners. That's why notification scrim needs
            // to overlap QS scrim by one pixel horizontally (left - 1 and right + 1)
            // see: b/186644628
            mNotificationsScrim.setDrawableBounds(left - 1, top, right + 1, bottom);
            mScrimBehind.setBottomEdgePosition((int) top);
        } else {
            mNotificationsScrim.setDrawableBounds(left, top, right, bottom);
        }

        // Only clip if the notif scrim is visible
        if (mNotificationsAlpha > 0f) {
            mKeyguardInteractor.setTopClippingBounds((int) top);
        } else {
            mKeyguardInteractor.setTopClippingBounds(null);
        }
    }

    /**
     * Sets the amount of vertical over scroll that should be performed on the notifications scrim.
     */
    public void setNotificationsOverScrollAmount(int overScrollAmount) {
        if (mNotificationsScrim != null) mNotificationsScrim.setTranslationY(overScrollAmount);
    }

    /**
     * Current state of the QuickSettings when pulling it from the top.
     *
     * @param expansionFraction From 0 to 1 where 0 means collapsed and 1 expanded.
     * @param qsPanelBottomY Absolute Y position of qs panel bottom
     */
    public void setQsPosition(float expansionFraction, int qsPanelBottomY) {
        if (isNaN(expansionFraction)) {
            return;
        }
        expansionFraction = ShadeInterpolation.getNotificationScrimAlpha(expansionFraction);
        boolean qsBottomVisible = qsPanelBottomY > 0;
        if (mQsExpansion != expansionFraction || mQsBottomVisible != qsBottomVisible) {
            mQsExpansion = expansionFraction;
            mQsBottomVisible = qsBottomVisible;
            boolean relevantState = (mState == ScrimState.SHADE_LOCKED
                    || mState == ScrimState.KEYGUARD
                    || mState == ScrimState.PULSING);
            if (!(relevantState && mExpansionAffectsAlpha)) {
                return;
            }
            applyAndDispatchState();
        }
    }

    /**
     * Updates the percentage of the bouncer which is hidden.
     */
    public void setBouncerHiddenFraction(@FloatRange(from = 0, to = 1) float bouncerHiddenAmount) {
        if (mBouncerHiddenFraction == bouncerHiddenAmount) {
            return;
        }
        mBouncerHiddenFraction = bouncerHiddenAmount;
        if (mState == ScrimState.DREAMING || mState == ScrimState.GLANCEABLE_HUB
                || mState == ScrimState.GLANCEABLE_HUB_OVER_DREAM) {
            // The dreaming and glanceable hub states requires this for the scrim calculation, so we
            // should only trigger an update in those states.
            applyAndDispatchState();
        }
    }

    /**
     * If QS and notification scrims should not overlap, and should be clipped to each other's
     * bounds instead.
     */
    public void setClipsQsScrim(boolean clipScrim) {
        if (clipScrim == mClipsQsScrim) {
            return;
        }
        mClipsQsScrim = clipScrim;
        for (ScrimState state : ScrimState.values()) {
            state.setClipQsScrim(mClipsQsScrim);
        }
        if (mScrimBehind != null) {
            mScrimBehind.enableBottomEdgeConcave(mClipsQsScrim);
        }
        if (mState != ScrimState.UNINITIALIZED) {
            // the clipScrimState has changed, let's reprepare ourselves
            mState.prepare(mState);
            applyAndDispatchState();
        }
    }

    @VisibleForTesting
    public boolean getClipQsScrim() {
        return mClipsQsScrim;
    }

    public void setOccludeAnimationPlaying(boolean occludeAnimationPlaying) {
        mOccludeAnimationPlaying = occludeAnimationPlaying;

        for (ScrimState state : ScrimState.values()) {
            state.setOccludeAnimationPlaying(occludeAnimationPlaying);
        }

        applyAndDispatchState();
    }

    private void setOrAdaptCurrentAnimation(@Nullable View scrim) {
        if (scrim == null) {
            return;
        }

        float alpha = getCurrentScrimAlpha(scrim);
        boolean qsScrimPullingDown = scrim == mScrimBehind && mQsBottomVisible;
        if (isAnimating(scrim) && !qsScrimPullingDown) {
            // Adapt current animation.
            ValueAnimator previousAnimator = (ValueAnimator) scrim.getTag(TAG_KEY_ANIM);
            float previousEndValue = (Float) scrim.getTag(TAG_END_ALPHA);
            float previousStartValue = (Float) scrim.getTag(TAG_START_ALPHA);
            float relativeDiff = alpha - previousEndValue;
            float newStartValue = previousStartValue + relativeDiff;
            scrim.setTag(TAG_START_ALPHA, newStartValue);
            scrim.setTag(TAG_END_ALPHA, alpha);
            previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
        } else {
            // Set animation.
            updateScrimColor(scrim, alpha, getCurrentScrimTint(scrim));
        }
    }

    private void applyState() {
        mInFrontTint = mState.getFrontTint();
        mBehindTint = mState.getBehindTint();
        mNotificationsTint = mState.getNotifTint();

        mInFrontAlpha = mState.getFrontAlpha();
        mBehindAlpha = mState.getBehindAlpha();
        mNotificationsAlpha = mState.getNotifAlpha();

        assertAlphasValid();

        if (!mExpansionAffectsAlpha) {
            return;
        }

        if (mState == ScrimState.UNLOCKED || mState == ScrimState.DREAMING
                || mState == ScrimState.GLANCEABLE_HUB_OVER_DREAM) {
            final boolean occluding =
                    mOccludeAnimationPlaying || mState.mLaunchingAffordanceWithPreview;
            // Darken scrim as it's pulled down while unlocked. If we're unlocked but playing the
            // screen off/occlusion animations, ignore expansion changes while those animations
            // play.
            if (!mScreenOffAnimationController.shouldExpandNotifications()
                    && !mAnimatingPanelExpansionOnUnlock
                    && !occluding) {
                if (mTransparentScrimBackground) {
                    mBehindAlpha = 0;
                    mNotificationsAlpha = 0;
                } else if (mClipsQsScrim) {
                    float behindFraction = getInterpolatedFraction();
                    behindFraction = (float) Math.pow(behindFraction, 0.8f);
                    mBehindAlpha = 1;
                    mNotificationsAlpha = behindFraction * mDefaultScrimAlpha;
                } else {
                    mBehindAlpha = mLargeScreenShadeInterpolator.getBehindScrimAlpha(
                            mPanelExpansionFraction * mDefaultScrimAlpha);
                    mNotificationsAlpha =
                            mLargeScreenShadeInterpolator.getNotificationScrimAlpha(
                                    mPanelExpansionFraction);
                }
                mBehindTint = mState.getBehindTint();
                mInFrontAlpha = 0;
            }

            if ((mState == ScrimState.DREAMING || mState == ScrimState.GLANCEABLE_HUB_OVER_DREAM)
                    && mBouncerHiddenFraction != KeyguardBouncerConstants.EXPANSION_HIDDEN) {
                // Bouncer is opening over dream or glanceable hub over dream.
                final float interpolatedFraction =
                        BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(
                                mBouncerHiddenFraction);
                mBehindAlpha = MathUtils.lerp(mDefaultScrimAlpha, mBehindAlpha,
                        interpolatedFraction);
                mBehindTint = ColorUtils.blendARGB(ScrimState.BOUNCER.getBehindTint(),
                        mBehindTint,
                        interpolatedFraction);
            }
        } else if (mState == ScrimState.AUTH_SCRIMMED_SHADE) {
            mNotificationsAlpha = (float) Math.pow(getInterpolatedFraction(), 0.8f);
        } else if (mState == ScrimState.KEYGUARD || mState == ScrimState.SHADE_LOCKED
                || mState == ScrimState.PULSING || mState == ScrimState.GLANCEABLE_HUB) {
            Pair<Integer, Float> result = calculateBackStateForState(mState);
            int behindTint = result.first;
            float behindAlpha = result.second;
            if (mTransitionToFullShadeProgress > 0.0f) {
                Pair<Integer, Float> shadeResult = calculateBackStateForState(
                        ScrimState.SHADE_LOCKED);
                behindAlpha = MathUtils.lerp(behindAlpha, shadeResult.second,
                        mTransitionToFullShadeProgress);
                behindTint = ColorUtils.blendARGB(behindTint, shadeResult.first,
                        mTransitionToFullShadeProgress);
            } else if (mState == ScrimState.GLANCEABLE_HUB && mTransitionToFullShadeProgress == 0.0f
                    && mBouncerHiddenFraction == KeyguardBouncerConstants.EXPANSION_HIDDEN) {
                // Behind scrim should not be visible when idle on the glanceable hub and neither
                // bouncer nor shade are showing.
                behindAlpha = 0f;
            }
            mInFrontAlpha = mState.getFrontAlpha();
            if (mClipsQsScrim) {
                mNotificationsAlpha = behindAlpha;
                mNotificationsTint = behindTint;
                mBehindAlpha = 1;
                mBehindTint = Color.BLACK;
            } else {
                mBehindAlpha = behindAlpha;
                if (mState == ScrimState.KEYGUARD && mTransitionToFullShadeProgress > 0.0f) {
                    mNotificationsAlpha = MathUtils
                            .saturate(mTransitionToLockScreenFullShadeNotificationsProgress);
                } else if (mState == ScrimState.SHADE_LOCKED) {
                    // going from KEYGUARD to SHADE_LOCKED state
                    mNotificationsAlpha = getInterpolatedFraction();
                } else if (mState == ScrimState.GLANCEABLE_HUB
                        && mTransitionToFullShadeProgress == 0.0f) {
                    // Notification scrim should not be visible on the glanceable hub unless the
                    // shade is showing or transitioning in. Otherwise the notification scrim will
                    // be visible as the bouncer transitions in or after the notification shade
                    // closes.
                    mNotificationsAlpha = 0;
                } else {
                    mNotificationsAlpha = Math.max(1.0f - getInterpolatedFraction(), mQsExpansion);
                }
                mNotificationsTint = mState.getNotifTint();
                mBehindTint = behindTint;
            }

            // At the end of a launch animation over the lockscreen, the state is either KEYGUARD or
            // SHADE_LOCKED and this code is called. We have to set the notification alpha to 0
            // otherwise there is a flicker to its previous value.
            boolean hideNotificationScrim = (mState == ScrimState.KEYGUARD
                    && mTransitionToFullShadeProgress == 0
                    && mQsExpansion == 0
                    && !mClipsQsScrim);
            if (mKeyguardOccluded || hideNotificationScrim) {
                mNotificationsAlpha = 0;
            }
        }
        if (mState != ScrimState.UNLOCKED) {
            mAnimatingPanelExpansionOnUnlock = false;
        }

        assertAlphasValid();
    }

    private void assertAlphasValid() {
        if (isNaN(mBehindAlpha) || isNaN(mInFrontAlpha) || isNaN(mNotificationsAlpha)) {
            throw new IllegalStateException("Scrim opacity is NaN for state: " + mState
                    + ", front: " + mInFrontAlpha + ", back: " + mBehindAlpha + ", notif: "
                    + mNotificationsAlpha);
        }
    }

    private Pair<Integer, Float> calculateBackStateForState(ScrimState state) {
        // Either darken of make the scrim transparent when you
        // pull down the shade
        float interpolatedFract = getInterpolatedFraction();

        float stateBehind = mClipsQsScrim ? state.getNotifAlpha() : state.getBehindAlpha();
        float behindAlpha;
        int behindTint = state.getBehindTint();
        if (mDarkenWhileDragging) {
            behindAlpha = MathUtils.lerp(mDefaultScrimAlpha, stateBehind,
                    interpolatedFract);
        } else {
            behindAlpha = MathUtils.lerp(0 /* start */, stateBehind,
                    interpolatedFract);
        }
        if (mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()) {
            if (mClipsQsScrim) {
                behindTint = ColorUtils.blendARGB(ScrimState.BOUNCER.getNotifTint(),
                    state.getNotifTint(), interpolatedFract);
            } else {
                behindTint = ColorUtils.blendARGB(ScrimState.BOUNCER.getBehindTint(),
                    state.getBehindTint(), interpolatedFract);
            }
        }
        if (mQsExpansion > 0) {
            behindAlpha = MathUtils.lerp(behindAlpha, mDefaultScrimAlpha, mQsExpansion);
            float tintProgress = mQsExpansion;
            if (mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()) {
                // this is case of - on lockscreen - going from expanded QS to bouncer.
                // Because mQsExpansion is already interpolated and transition between tints
                // is too slow, we want to speed it up and make it more aligned to bouncer
                // showing up progress. This issue is visible on large screens, both portrait and
                // split shade because then transition is between very different tints
                tintProgress = BouncerPanelExpansionCalculator
                        .showBouncerProgress(mPanelExpansionFraction);
            }
            int stateTint = mClipsQsScrim ? ScrimState.SHADE_LOCKED.getNotifTint()
                    : ScrimState.SHADE_LOCKED.getBehindTint();
            behindTint = ColorUtils.blendARGB(behindTint, stateTint, tintProgress);
        }

        // If the keyguard is going away, we should not be opaque.
        if (mKeyguardStateController.isKeyguardGoingAway()) {
            behindAlpha = 0f;
        }

        return new Pair<>(behindTint, behindAlpha);
    }


    private void applyAndDispatchState() {
        applyState();
        if (mUpdatePending) {
            return;
        }
        setOrAdaptCurrentAnimation(mScrimBehind);
        setOrAdaptCurrentAnimation(mNotificationsScrim);
        setOrAdaptCurrentAnimation(mScrimInFront);
        dispatchBackScrimState(mScrimBehind.getViewAlpha());

        // Reset wallpaper timeout if it's already timeout like expanding panel while PULSING
        // and docking.
        if (mWallpaperVisibilityTimedOut) {
            mWallpaperVisibilityTimedOut = false;
            DejankUtils.postAfterTraversal(() -> {
                mTimeTicker.schedule(mDozeParameters.getWallpaperAodDuration(),
                        AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
            });
        }
    }

    /**
     * Sets the front scrim opacity in AOD so it's not as bright.
     * <p>
     * Displays usually don't support multiple dimming settings when in low power mode.
     * The workaround is to modify the front scrim opacity when in AOD, so it's not as
     * bright when you're at the movies or lying down on bed.
     * <p>
     * This value will be lost during transitions and only updated again after the the
     * device is dozing when the light sensor is on.
     */
    public void setAodFrontScrimAlpha(float alpha) {
        if (mInFrontAlpha != alpha && shouldUpdateFrontScrimAlpha()) {
            mInFrontAlpha = alpha;
            updateScrims();
        }

        mState.AOD.setAodFrontScrimAlpha(alpha);
        mState.PULSING.setAodFrontScrimAlpha(alpha);
    }

    private boolean shouldUpdateFrontScrimAlpha() {
        if (mState == ScrimState.AOD
                && (mDozeParameters.getAlwaysOn() || mDockManager.isDocked())) {
            return true;
        }

        if (mState == ScrimState.PULSING) {
            return true;
        }

        return false;
    }

    /**
     * If the lock screen sensor is active.
     */
    public void setWakeLockScreenSensorActive(boolean active) {
        for (ScrimState state : ScrimState.values()) {
            state.setWakeLockScreenSensorActive(active);
        }

        if (mState == ScrimState.PULSING) {
            float newBehindAlpha = mState.getBehindAlpha();
            if (mBehindAlpha != newBehindAlpha) {
                mBehindAlpha = newBehindAlpha;
                if (isNaN(mBehindAlpha)) {
                    throw new IllegalStateException("Scrim opacity is NaN for state: " + mState
                            + ", back: " + mBehindAlpha);
                }
                updateScrims();
            }
        }
    }

    protected void scheduleUpdate() {
        if (mUpdatePending || mScrimBehind == null) return;

        // Make sure that a frame gets scheduled.
        mScrimBehind.invalidate();
        mScrimBehind.getViewTreeObserver().addOnPreDrawListener(this);
        mUpdatePending = true;
    }

    protected void updateScrims() {
        // Make sure we have the right gradients and their opacities will satisfy GAR.
        if (mNeedsDrawableColorUpdate) {
            mNeedsDrawableColorUpdate = false;
            // Only animate scrim color if the scrim view is actually visible
            boolean animateScrimInFront = mScrimInFront.getViewAlpha() != 0 && !mBlankScreen;
            boolean animateBehindScrim = mScrimBehind.getViewAlpha() != 0 && !mBlankScreen;
            boolean animateScrimNotifications = mNotificationsScrim.getViewAlpha() != 0
                    && !mBlankScreen;

            mScrimInFront.setColors(mColors, animateScrimInFront);
            mScrimBehind.setColors(mColors, animateBehindScrim);
            mNotificationsScrim.setColors(mColors, animateScrimNotifications);

            dispatchBackScrimState(mScrimBehind.getViewAlpha());
        }

        // We want to override the back scrim opacity for the AOD state
        // when it's time to fade the wallpaper away.
        boolean aodWallpaperTimeout = (mState == ScrimState.AOD || mState == ScrimState.PULSING)
                && mWallpaperVisibilityTimedOut;
        // We also want to hide FLAG_SHOW_WHEN_LOCKED activities under the scrim.
        boolean hideFlagShowWhenLockedActivities =
                (mState == ScrimState.PULSING || mState == ScrimState.AOD)
                && mKeyguardOccluded;
        if (aodWallpaperTimeout || hideFlagShowWhenLockedActivities) {
            mBehindAlpha = 1;
        }
        // Prevent notification scrim flicker when transitioning away from keyguard.
        if (mKeyguardStateController.isKeyguardGoingAway()) {
            mNotificationsAlpha = 0;
        }

        // Prevent flickering for activities above keyguard and quick settings in keyguard.
        if (mKeyguardOccluded
                && (mState == ScrimState.KEYGUARD || mState == ScrimState.SHADE_LOCKED)) {
            mBehindAlpha = 0;
            mNotificationsAlpha = 0;
        }

        setScrimAlpha(mScrimInFront, mInFrontAlpha);
        setScrimAlpha(mScrimBehind, mBehindAlpha);
        setScrimAlpha(mNotificationsScrim, mNotificationsAlpha);

        // The animation could have all already finished, let's call onFinished just in case
        onFinished(mState);
        dispatchScrimsVisible();
    }

    private void dispatchBackScrimState(float alpha) {
        // When clipping QS, the notification scrim is the one that feels behind.
        // mScrimBehind will be drawing black and its opacity will always be 1.
        if (mClipsQsScrim && mQsBottomVisible) {
            alpha = mNotificationsAlpha;
        }
        mScrimStateListener.accept(mState, alpha, mColors);
    }

    private void dispatchScrimsVisible() {
        final ScrimView backScrim = mClipsQsScrim ? mNotificationsScrim : mScrimBehind;
        final int currentScrimVisibility;
        if (mScrimInFront.getViewAlpha() == 1 || backScrim.getViewAlpha() == 1) {
            currentScrimVisibility = OPAQUE;
        } else if (mScrimInFront.getViewAlpha() == 0 && backScrim.getViewAlpha() == 0) {
            currentScrimVisibility = TRANSPARENT;
        } else {
            currentScrimVisibility = SEMI_TRANSPARENT;
        }

        if (mScrimsVisibility != currentScrimVisibility) {
            mScrimsVisibility = currentScrimVisibility;
            mScrimVisibleListener.accept(currentScrimVisibility);
        }
    }

    private float getInterpolatedFraction() {
        if (mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()) {
            return BouncerPanelExpansionCalculator
                    .aboutToShowBouncerProgress(mPanelExpansionFraction);
        }
        return ShadeInterpolation.getNotificationScrimAlpha(mPanelExpansionFraction);
    }

    private void setScrimAlpha(ScrimView scrim, float alpha) {
        if (alpha == 0f) {
            scrim.setClickable(false);
        } else {
            // Eat touch events (unless dozing).
            scrim.setClickable(mState != ScrimState.AOD);
        }
        updateScrim(scrim, alpha);
    }

    private String getScrimName(ScrimView scrim) {
        if (scrim == mScrimInFront) {
            return "front_scrim";
        } else if (scrim == mScrimBehind) {
            return "behind_scrim";
        } else if (scrim == mNotificationsScrim) {
            return "notifications_scrim";
        }
        return "unknown_scrim";
    }

    private void updateScrimColor(View scrim, float alpha, int tint) {
        alpha = Math.max(0, Math.min(1.0f, alpha));
        if (scrim instanceof ScrimView) {
            ScrimView scrimView = (ScrimView) scrim;
            if (DEBUG_MODE) {
                tint = getDebugScrimTint(scrimView);
            }

            Trace.traceCounter(Trace.TRACE_TAG_APP, getScrimName(scrimView) + "_alpha",
                    (int) (alpha * 255));

            Trace.traceCounter(Trace.TRACE_TAG_APP, getScrimName(scrimView) + "_tint",
                    Color.alpha(tint));
            scrimView.setTint(tint);
            if (!mIsBouncerToGoneTransitionRunning) {
                scrimView.setViewAlpha(alpha);
            }
        } else {
            scrim.setAlpha(alpha);
        }
        dispatchScrimsVisible();
    }

    private int getDebugScrimTint(ScrimView scrim) {
        if (scrim == mScrimBehind) return DEBUG_BEHIND_TINT;
        if (scrim == mScrimInFront) return DEBUG_FRONT_TINT;
        if (scrim == mNotificationsScrim) return DEBUG_NOTIFICATIONS_TINT;
        throw new RuntimeException("scrim can't be matched with known scrims");
    }

    private void startScrimAnimation(final View scrim, float current) {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        if (mAnimatorListener != null) {
            anim.addListener(mAnimatorListener);
        }
        final int initialScrimTint = scrim instanceof ScrimView ? ((ScrimView) scrim).getTint() :
                Color.TRANSPARENT;
        anim.addUpdateListener(animation -> {
            final float startAlpha = (Float) scrim.getTag(TAG_START_ALPHA);
            final float animAmount = (float) animation.getAnimatedValue();
            final int finalScrimTint = getCurrentScrimTint(scrim);
            final float finalScrimAlpha = getCurrentScrimAlpha(scrim);
            float alpha = MathUtils.lerp(startAlpha, finalScrimAlpha, animAmount);
            alpha = MathUtils.constrain(alpha, 0f, 1f);
            int tint = ColorUtils.blendARGB(initialScrimTint, finalScrimTint, animAmount);
            updateScrimColor(scrim, alpha, tint);
            dispatchScrimsVisible();
        });
        anim.setInterpolator(mInterpolator);
        anim.setStartDelay(mAnimationDelay);
        anim.setDuration(mAnimationDuration);
        anim.addListener(new AnimatorListenerAdapter() {
            private final ScrimState mLastState = mState;
            private final Callback mLastCallback = mCallback;

            @Override
            public void onAnimationEnd(Animator animation) {
                scrim.setTag(TAG_KEY_ANIM, null);
                onFinished(mLastCallback, mLastState);

                dispatchScrimsVisible();
            }
        });

        // Cache alpha values because we might want to update this animator in the future if
        // the user expands the panel while the animation is still running.
        scrim.setTag(TAG_START_ALPHA, current);
        scrim.setTag(TAG_END_ALPHA, getCurrentScrimAlpha(scrim));

        scrim.setTag(TAG_KEY_ANIM, anim);
        anim.start();
    }

    private float getCurrentScrimAlpha(View scrim) {
        if (scrim == mScrimInFront) {
            return mInFrontAlpha;
        } else if (scrim == mScrimBehind) {
            return mBehindAlpha;
        } else if (scrim == mNotificationsScrim) {
            return mNotificationsAlpha;
        } else {
            throw new IllegalArgumentException("Unknown scrim view");
        }
    }

    private int getCurrentScrimTint(View scrim) {
        if (scrim == mScrimInFront) {
            return mInFrontTint;
        } else if (scrim == mScrimBehind) {
            return mBehindTint;
        } else if (scrim == mNotificationsScrim) {
            return mNotificationsTint;
        } else {
            throw new IllegalArgumentException("Unknown scrim view");
        }
    }

    @Override
    public boolean onPreDraw() {
        mScrimBehind.getViewTreeObserver().removeOnPreDrawListener(this);
        mUpdatePending = false;
        if (mCallback != null) {
            mCallback.onStart();
        }
        updateScrims();
        return true;
    }

    /**
     * @param state that finished
     */
    private void onFinished(ScrimState state) {
        onFinished(mCallback, state);
    }

    private void onFinished(Callback callback, ScrimState state) {
        if (mPendingFrameCallback != null) {
            // No animations can finish while we're waiting on the blanking to finish
            return;

        }
        if (isAnimating(mScrimBehind)
                || isAnimating(mNotificationsScrim)
                || isAnimating(mScrimInFront)) {
            if (callback != null && callback != mCallback) {
                // Since we only notify the callback that we're finished once everything has
                // finished, we need to make sure that any changing callbacks are also invoked
                callback.onFinished();
            }
            return;
        }
        if (mWakeLockHeld) {
            mWakeLock.release(TAG);
            mWakeLockHeld = false;
        }

        if (callback != null) {
            callback.onFinished();

            if (callback == mCallback) {
                mCallback = null;
            }
        }

        // When unlocking with fingerprint, we'll fade the scrims from black to transparent.
        // At the end of the animation we need to remove the tint.
        if (state == ScrimState.UNLOCKED) {
            mInFrontTint = Color.TRANSPARENT;
            mBehindTint = mState.getBehindTint();
            mNotificationsTint = mState.getNotifTint();
            updateScrimColor(mScrimInFront, mInFrontAlpha, mInFrontTint);
            updateScrimColor(mScrimBehind, mBehindAlpha, mBehindTint);
            updateScrimColor(mNotificationsScrim, mNotificationsAlpha, mNotificationsTint);
        }
    }

    private boolean isAnimating(@Nullable View scrim) {
        return scrim != null && scrim.getTag(TAG_KEY_ANIM) != null;
    }

    @VisibleForTesting
    void setAnimatorListener(Animator.AnimatorListener animatorListener) {
        mAnimatorListener = animatorListener;
    }

    private void updateScrim(ScrimView scrim, float alpha) {
        final float currentAlpha = scrim.getViewAlpha();

        ValueAnimator previousAnimator = ViewState.getChildTag(scrim, TAG_KEY_ANIM);
        if (previousAnimator != null) {
            // Previous animators should always be cancelled. Not doing so would cause
            // overlap, especially on states that don't animate, leading to flickering,
            // and in the worst case, an internal state that doesn't represent what
            // transitionTo requested.
            cancelAnimator(previousAnimator);
        }

        if (mPendingFrameCallback != null) {
            // Display is off and we're waiting.
            return;
        } else if (mBlankScreen) {
            // Need to blank the display before continuing.
            blankDisplay();
            return;
        } else if (!mScreenBlankingCallbackCalled) {
            // Not blanking the screen. Letting the callback know that we're ready
            // to replace what was on the screen before.
            if (mCallback != null) {
                mCallback.onDisplayBlanked();
                mScreenBlankingCallbackCalled = true;
            }
        }

        if (scrim == mScrimBehind) {
            dispatchBackScrimState(alpha);
        }

        final boolean wantsAlphaUpdate = alpha != currentAlpha;
        final boolean wantsTintUpdate = scrim.getTint() != getCurrentScrimTint(scrim);

        if (wantsAlphaUpdate || wantsTintUpdate) {
            if (mAnimateChange) {
                startScrimAnimation(scrim, currentAlpha);
            } else {
                // update the alpha directly
                updateScrimColor(scrim, alpha, getCurrentScrimTint(scrim));
            }
        }
    }

    private void cancelAnimator(ValueAnimator previousAnimator) {
        if (previousAnimator != null) {
            previousAnimator.cancel();
        }
    }

    private void blankDisplay() {
        updateScrimColor(mScrimInFront, 1, Color.BLACK);

        // Notify callback that the screen is completely black and we're
        // ready to change the display power mode
        mPendingFrameCallback = () -> {
            if (mCallback != null) {
                mCallback.onDisplayBlanked();
                mScreenBlankingCallbackCalled = true;
            }

            mBlankingTransitionRunnable = () -> {
                mBlankingTransitionRunnable = null;
                mPendingFrameCallback = null;
                mBlankScreen = false;
                // Try again.
                updateScrims();
            };

            // Setting power states can happen after we push out the frame. Make sure we
            // stay fully opaque until the power state request reaches the lower levels.
            final int delay = mScreenOn ? 32 : 500;
            if (DEBUG) {
                Log.d(TAG, "Fading out scrims with delay: " + delay);
            }
            mHandler.postDelayed(mBlankingTransitionRunnable, delay);
        };
        doOnTheNextFrame(mPendingFrameCallback);
    }

    /**
     * Executes a callback after the frame has hit the display.
     *
     * @param callback What to run.
     */
    @VisibleForTesting
    protected void doOnTheNextFrame(Runnable callback) {
        // Just calling View#postOnAnimation isn't enough because the frame might not have reached
        // the display yet. A timeout is the safest solution.
        mScrimBehind.postOnAnimationDelayed(callback, 32 /* delayMillis */);
    }

    private void updateThemeColors() {
        if (mScrimBehind == null) return;
        int background = Utils.getColorAttr(mScrimBehind.getContext(),
                com.android.internal.R.attr.materialColorSurfaceDim).getDefaultColor();
        int accent = Utils.getColorAttr(mScrimBehind.getContext(),
                com.android.internal.R.attr.materialColorPrimary).getDefaultColor();
        mColors.setMainColor(background);
        mColors.setSecondaryColor(accent);
        final boolean isBackgroundLight = !ContrastColorUtil.isColorDark(background);
        mColors.setSupportsDarkText(isBackgroundLight);

        int surface = Utils.getColorAttr(mScrimBehind.getContext(),
                com.android.internal.R.attr.materialColorSurface).getDefaultColor();
        for (ScrimState state : ScrimState.values()) {
            state.setSurfaceColor(surface);
        }

        mNeedsDrawableColorUpdate = true;
    }

    private void onThemeChanged() {
        updateThemeColors();
        scheduleUpdate();
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(" ScrimController: ");
        pw.print("  state: ");
        pw.println(mState);
        pw.println("    mClipQsScrim = " + mState.mClipQsScrim);

        pw.print("  frontScrim:");
        pw.print(" viewAlpha=");
        pw.print(mScrimInFront.getViewAlpha());
        pw.print(" alpha=");
        pw.print(mInFrontAlpha);
        pw.print(" tint=0x");
        pw.println(Integer.toHexString(mScrimInFront.getTint()));

        pw.print("  behindScrim:");
        pw.print(" viewAlpha=");
        pw.print(mScrimBehind.getViewAlpha());
        pw.print(" alpha=");
        pw.print(mBehindAlpha);
        pw.print(" tint=0x");
        pw.println(Integer.toHexString(mScrimBehind.getTint()));

        pw.print("  notificationsScrim:");
        pw.print(" viewAlpha=");
        pw.print(mNotificationsScrim.getViewAlpha());
        pw.print(" alpha=");
        pw.print(mNotificationsAlpha);
        pw.print(" tint=0x");
        pw.println(Integer.toHexString(mNotificationsScrim.getTint()));
        pw.print(" expansionProgress=");
        pw.println(mTransitionToLockScreenFullShadeNotificationsProgress);

        pw.print("  mDefaultScrimAlpha=");
        pw.println(mDefaultScrimAlpha);
        pw.print("  mPanelExpansionFraction=");
        pw.println(mPanelExpansionFraction);
        pw.print("  mExpansionAffectsAlpha=");
        pw.println(mExpansionAffectsAlpha);

        pw.print("  mState.getMaxLightRevealScrimAlpha=");
        pw.println(mState.getMaxLightRevealScrimAlpha());
    }

    private void setWallpaperSupportsAmbientMode(boolean wallpaperSupportsAmbientMode) {
        mWallpaperSupportsAmbientMode = wallpaperSupportsAmbientMode;
        ScrimState[] states = ScrimState.values();
        for (int i = 0; i < states.length; i++) {
            states[i].setWallpaperSupportsAmbientMode(wallpaperSupportsAmbientMode);
        }
    }

    /**
     * Interrupts blanking transitions once the display notifies that it's already on.
     */
    public void onScreenTurnedOn() {
        mScreenOn = true;
        if (mHandler.hasCallbacks(mBlankingTransitionRunnable)) {
            if (DEBUG) {
                Log.d(TAG, "Shorter blanking because screen turned on. All good.");
            }
            mHandler.removeCallbacks(mBlankingTransitionRunnable);
            mBlankingTransitionRunnable.run();
        }
    }

    public void onScreenTurnedOff() {
        mScreenOn = false;
    }

    public boolean isScreenOn() {
        return mScreenOn;
    }

    public void setExpansionAffectsAlpha(boolean expansionAffectsAlpha) {
        mExpansionAffectsAlpha = expansionAffectsAlpha;
    }

    public void setKeyguardOccluded(boolean keyguardOccluded) {
        if (mKeyguardOccluded == keyguardOccluded) {
            return;
        }
        mKeyguardOccluded = keyguardOccluded;
        updateScrims();
    }

    public void setHasBackdrop(boolean hasBackdrop) {
        for (ScrimState state : ScrimState.values()) {
            state.setHasBackdrop(hasBackdrop);
        }

        // Backdrop event may arrive after state was already applied,
        // in this case, back-scrim needs to be re-evaluated
        if (mState == ScrimState.AOD || mState == ScrimState.PULSING) {
            float newBehindAlpha = mState.getBehindAlpha();
            if (isNaN(newBehindAlpha)) {
                throw new IllegalStateException("Scrim opacity is NaN for state: " + mState
                        + ", back: " + mBehindAlpha);
            }
            if (mBehindAlpha != newBehindAlpha) {
                mBehindAlpha = newBehindAlpha;
                updateScrims();
            }
        }
    }

    private void setKeyguardFadingAway(boolean fadingAway, long duration) {
        for (ScrimState state : ScrimState.values()) {
            state.setKeyguardFadingAway(fadingAway, duration);
        }
    }

    public void setLaunchingAffordanceWithPreview(boolean launchingAffordanceWithPreview) {
        for (ScrimState state : ScrimState.values()) {
            state.setLaunchingAffordanceWithPreview(launchingAffordanceWithPreview);
        }
    }

    public interface Callback {
        default void onStart() {
        }

        default void onDisplayBlanked() {
        }

        default void onFinished() {
        }

        default void onCancelled() {
        }
    }

    /**
     * Simple keyguard callback that updates scrims when keyguard visibility changes.
     */
    private class KeyguardVisibilityCallback extends KeyguardUpdateMonitorCallback {

        @Override
        public void onKeyguardVisibilityChanged(boolean visible) {
            mNeedsDrawableColorUpdate = true;
            scheduleUpdate();
        }
    }
}
