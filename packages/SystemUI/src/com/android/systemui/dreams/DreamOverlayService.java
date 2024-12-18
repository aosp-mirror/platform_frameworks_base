/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams;

import static android.service.dreams.Flags.dreamWakeRedirect;

import static com.android.systemui.Flags.glanceableHubAllowKeyguardWhenDreaming;
import static com.android.systemui.dreams.dagger.DreamModule.DREAM_OVERLAY_WINDOW_TITLE;
import static com.android.systemui.dreams.dagger.DreamModule.DREAM_TOUCH_INSET_MANAGER;
import static com.android.systemui.dreams.dagger.DreamModule.HOME_CONTROL_PANEL_DREAM_COMPONENT;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.LifecycleService;
import androidx.lifecycle.ServiceLifecycleDispatcher;
import androidx.lifecycle.ViewModelStore;

import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.dream.lowlight.dagger.LowLightDreamModule;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.policy.PhoneWindow;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.ambient.touch.TouchMonitor;
import com.android.systemui.ambient.touch.dagger.AmbientTouchComponent;
import com.android.systemui.ambient.touch.scrim.ScrimManager;
import com.android.systemui.communal.domain.interactor.CommunalInteractor;
import com.android.systemui.communal.shared.log.CommunalUiEvent;
import com.android.systemui.communal.shared.model.CommunalScenes;
import com.android.systemui.complication.dagger.ComplicationComponent;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.navigationbar.gestural.domain.GestureInteractor;
import com.android.systemui.navigationbar.gestural.domain.TaskMatcher;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.touch.TouchInsetManager;
import com.android.systemui.util.concurrency.DelayableExecutor;

import kotlinx.coroutines.Job;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * The {@link DreamOverlayService} is responsible for placing an overlay on top of a dream. The
 * dream reaches directly out to the service with a Window reference (via LayoutParams), which the
 * service uses to insert its own child Window into the dream's parent Window.
 */
public class DreamOverlayService extends android.service.dreams.DreamOverlayService implements
        LifecycleOwner {
    private static final String TAG = "DreamOverlayService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final TaskMatcher DREAM_TYPE_MATCHER =
            new TaskMatcher.TopActivityType(WindowConfiguration.ACTIVITY_TYPE_DREAM);

    // The Context is used to construct the hosting constraint layout and child overlay views.
    private final Context mContext;
    // The Executor ensures actions and ui updates happen on the same thread.
    private final DelayableExecutor mExecutor;
    // A controller for the dream overlay container view (which contains both the status bar and the
    // content area).
    private DreamOverlayContainerViewController mDreamOverlayContainerViewController;
    private final DreamOverlayCallbackController mDreamOverlayCallbackController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ScrimManager mScrimManager;
    @Nullable
    private final ComponentName mLowLightDreamComponent;
    @Nullable
    private final ComponentName mHomeControlPanelDreamComponent;
    private final UiEventLogger mUiEventLogger;
    private final ViewCaptureAwareWindowManager mWindowManager;
    private final String mWindowTitle;

    // A reference to the {@link Window} used to hold the dream overlay.
    private Window mWindow;

    // True if a dream has bound to the service and dream overlay service has started.
    private boolean mStarted = false;

    // True if the service has been destroyed.
    private boolean mDestroyed = false;

    /**
     * True if the notification shade is open.
     */
    private boolean mShadeExpanded = false;

    /**
     * True if any part of the glanceable hub is visible.
     */
    private boolean mCommunalVisible = false;

    /**
     * True if the primary bouncer is visible.
     */
    private boolean mBouncerShowing = false;

    private final com.android.systemui.dreams.complication.dagger.ComplicationComponent.Factory
            mDreamComplicationComponentFactory;
    private final ComplicationComponent.Factory mComplicationComponentFactory;
    private final DreamOverlayComponent.Factory mDreamOverlayComponentFactory;
    private final AmbientTouchComponent.Factory mAmbientTouchComponentFactory;

    private final TouchInsetManager mTouchInsetManager;
    private final LifecycleOwner mLifecycleOwner;

    private final ArrayList<Job> mFlows = new ArrayList<>();

    /**
     * This {@link LifecycleRegistry} controls when dream overlay functionality, like touch
     * handling, should be active. It will automatically be paused when the dream overlay is hidden
     * while dreaming, such as when the notification shade, bouncer, or glanceable hub are visible.
     */
    private final LifecycleRegistry mLifecycleRegistry;

    /**
     * Drives the lifecycle exposed by this service's {@link #getLifecycle()}.
     * <p>
     * Used to mimic a {@link LifecycleService}, though we do not update the lifecycle in
     * {@link #onBind(Intent)} since it's final in the base class.
     */
    private final ServiceLifecycleDispatcher mDispatcher = new ServiceLifecycleDispatcher(this);

    private TouchMonitor mTouchMonitor;

    private final CommunalInteractor mCommunalInteractor;

    private boolean mCommunalAvailable;

    final Consumer<Boolean> mIsCommunalAvailableCallback =
            isAvailable -> {
                mCommunalAvailable = isAvailable;
                updateRedirectWakeup();
            };

    private final SystemDialogsCloser mSystemDialogsCloser;

    private final KeyguardUpdateMonitorCallback mKeyguardCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onShadeExpandedChanged(boolean expanded) {
                    mExecutor.execute(() -> {
                        if (mShadeExpanded == expanded) {
                            return;
                        }
                        mShadeExpanded = expanded;

                        updateLifecycleStateLocked();
                        updateGestureBlockingLocked();
                    });
                }
            };

    private final Consumer<Boolean> mCommunalVisibleConsumer = new Consumer<>() {
        @Override
        public void accept(Boolean communalVisible) {
            mExecutor.execute(() -> {
                if (mCommunalVisible == communalVisible) {
                    return;
                }

                mCommunalVisible = communalVisible;

                updateLifecycleStateLocked();
            });
        }
    };

    private final Consumer<Boolean> mBouncerShowingConsumer = new Consumer<>() {
        @Override
        public void accept(Boolean bouncerShowing) {
            mExecutor.execute(() -> {
                if (mBouncerShowing == bouncerShowing) {
                    return;
                }

                mBouncerShowing = bouncerShowing;

                updateLifecycleStateLocked();
                updateGestureBlockingLocked();
            });
        }
    };

    /**
     * {@link ResetHandler} protects resetting {@link DreamOverlayService} by making sure reset
     * requests are processed before subsequent actions proceed. Requests themselves are also
     * ordered between each other as well to ensure actions are correctly sequenced.
     */
    private final class ResetHandler {
        @FunctionalInterface
        interface Callback {
            void onComplete();
        }

        private record Info(Callback callback, String source) {}

        private final ArrayList<Info> mPendingCallbacks = new ArrayList<>();

        DreamOverlayStateController.Callback mStateCallback =
                new DreamOverlayStateController.Callback() {
                    @Override
                    public void onStateChanged() {
                        process(true);
                    }
                };

        /**
         * Called from places where there is no need to wait for the reset to complete. This still
         * will defer the reset until it is okay to reset and also sequences the request with
         * others.
         */
        public void reset(String source) {
            reset(() -> {}, source);
        }

        /**
         * Invoked to request a reset with a callback that will fire after reset if it is deferred.
         *
         * @return {@code true} if the reset happened immediately, {@code false} if it was deferred
         * and will fire later, invoking the callback.
         */
        public boolean reset(Callback callback, String source) {
            // Always add listener pre-emptively
            if (mPendingCallbacks.isEmpty()) {
                mStateController.addCallback(mStateCallback);
            }

            final Info info = new Info(callback, source);
            mPendingCallbacks.add(info);
            process(false);

            boolean processed = !mPendingCallbacks.contains(info);

            if (!processed) {
                Log.d(TAG, "delayed resetting from: " + source);
            }

            return processed;
        }

        private void resetInternal() {
            // This ensures the container view of the current dream is removed before
            // the controller is potentially reset.
            removeContainerViewFromParentLocked();

            if (mStarted && mWindow != null) {
                try {
                    mWindow.clearContentView();
                    mWindowManager.removeView(mWindow.getDecorView());
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Error removing decor view when resetting overlay", e);
                }
            }

            mStateController.setOverlayActive(false);
            mStateController.setLowLightActive(false);
            mStateController.setEntryAnimationsFinished(false);

            mDreamOverlayCallbackController.onWakeUp();

            if (mDreamOverlayContainerViewController != null) {
                mDreamOverlayContainerViewController.destroy();
                mDreamOverlayContainerViewController = null;
            }

            if (mTouchMonitor != null) {
                mTouchMonitor.destroy();
                mTouchMonitor = null;
            }

            mWindow = null;

            // Always unregister the any set DreamActivity from being blocked from gestures.
            mGestureInteractor.removeGestureBlockedMatcher(DREAM_TYPE_MATCHER,
                    GestureInteractor.Scope.Global);

            mStarted = false;
        }

        private boolean canReset() {
            return !mStateController.areExitAnimationsRunning();
        }

        private void process(boolean fromDelayedCallback) {
            while (canReset() && !mPendingCallbacks.isEmpty()) {
                final Info callbackInfo = mPendingCallbacks.removeFirst();
                resetInternal();
                callbackInfo.callback.onComplete();

                if (fromDelayedCallback) {
                    Log.d(TAG, "reset overlay (delayed) for " + callbackInfo.source);
                }
            }

            if (mPendingCallbacks.isEmpty()) {
                mStateController.removeCallback(mStateCallback);
            }
        }
    }

    private final ResetHandler mResetHandler = new ResetHandler();

    private final DreamOverlayStateController mStateController;

    private final GestureInteractor mGestureInteractor;

    @VisibleForTesting
    public enum DreamOverlayEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The dream overlay has entered start.")
        DREAM_OVERLAY_ENTER_START(989),
        @UiEvent(doc = "The dream overlay has completed start.")
        DREAM_OVERLAY_COMPLETE_START(990);

        private final int mId;

        DreamOverlayEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    @Inject
    public DreamOverlayService(
            Context context,
            DreamOverlayLifecycleOwner lifecycleOwner,
            @Main DelayableExecutor executor,
            ViewCaptureAwareWindowManager viewCaptureAwareWindowManager,
            ComplicationComponent.Factory complicationComponentFactory,
            com.android.systemui.dreams.complication.dagger.ComplicationComponent.Factory
                    dreamComplicationComponentFactory,
            DreamOverlayComponent.Factory dreamOverlayComponentFactory,
            AmbientTouchComponent.Factory ambientTouchComponentFactory,
            DreamOverlayStateController stateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ScrimManager scrimManager,
            CommunalInteractor communalInteractor,
            SystemDialogsCloser systemDialogsCloser,
            UiEventLogger uiEventLogger,
            @Named(DREAM_TOUCH_INSET_MANAGER) TouchInsetManager touchInsetManager,
            @Nullable @Named(LowLightDreamModule.LOW_LIGHT_DREAM_COMPONENT)
            ComponentName lowLightDreamComponent,
            @Nullable @Named(HOME_CONTROL_PANEL_DREAM_COMPONENT)
            ComponentName homeControlPanelDreamComponent,
            DreamOverlayCallbackController dreamOverlayCallbackController,
            KeyguardInteractor keyguardInteractor,
            GestureInteractor gestureInteractor,
            @Named(DREAM_OVERLAY_WINDOW_TITLE) String windowTitle) {
        super(executor);
        mContext = context;
        mExecutor = executor;
        mWindowManager = viewCaptureAwareWindowManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mScrimManager = scrimManager;
        mLowLightDreamComponent = lowLightDreamComponent;
        mHomeControlPanelDreamComponent = homeControlPanelDreamComponent;
        mKeyguardUpdateMonitor.registerCallback(mKeyguardCallback);
        mStateController = stateController;
        mUiEventLogger = uiEventLogger;
        mComplicationComponentFactory = complicationComponentFactory;
        mDreamComplicationComponentFactory = dreamComplicationComponentFactory;
        mDreamOverlayCallbackController = dreamOverlayCallbackController;
        mWindowTitle = windowTitle;
        mCommunalInteractor = communalInteractor;
        mSystemDialogsCloser = systemDialogsCloser;
        mGestureInteractor = gestureInteractor;
        mDreamOverlayComponentFactory = dreamOverlayComponentFactory;
        mAmbientTouchComponentFactory = ambientTouchComponentFactory;
        mTouchInsetManager = touchInsetManager;
        mLifecycleOwner = lifecycleOwner;
        mLifecycleRegistry = lifecycleOwner.getRegistry();

        mExecutor.execute(() -> setLifecycleStateLocked(Lifecycle.State.CREATED));

        mFlows.add(collectFlow(getLifecycle(), mCommunalInteractor.isCommunalAvailable(),
                mIsCommunalAvailableCallback));
        mFlows.add(collectFlow(getLifecycle(), communalInteractor.isCommunalVisible(),
                mCommunalVisibleConsumer));
        mFlows.add(collectFlow(getLifecycle(), keyguardInteractor.primaryBouncerShowing,
                mBouncerShowingConsumer));
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mDispatcher.getLifecycle();
    }

    @Override
    public void onCreate() {
        mDispatcher.onServicePreSuperOnCreate();
        super.onCreate();
    }

    @Override
    public void onStart(Intent intent, int startId) {
        mDispatcher.onServicePreSuperOnStart();
        super.onStart(intent, startId);
    }

    @Override
    public void onDestroy() {
        mKeyguardUpdateMonitor.removeCallback(mKeyguardCallback);

        for (Job job : mFlows) {
            job.cancel(new CancellationException());
        }
        mFlows.clear();

        mExecutor.execute(() -> {
            setLifecycleStateLocked(Lifecycle.State.DESTROYED);
            mDestroyed = true;
            mResetHandler.reset("destroying");
        });

        mDispatcher.onServicePreSuperOnDestroy();
        super.onDestroy();
    }

    @Override
    public void onStartDream(@NonNull WindowManager.LayoutParams layoutParams) {
        final ComplicationComponent complicationComponent = mComplicationComponentFactory.create(
                mLifecycleOwner,
                () -> mExecutor.execute(DreamOverlayService.this::requestExit),
                new ViewModelStore(), mTouchInsetManager);
        final com.android.systemui.dreams.complication.dagger.ComplicationComponent
                dreamComplicationComponent = mDreamComplicationComponentFactory.create(
                complicationComponent.getVisibilityController(), mTouchInsetManager);

        final DreamOverlayComponent dreamOverlayComponent = mDreamOverlayComponentFactory.create(
                mLifecycleOwner, complicationComponent.getComplicationHostViewController(),
                mTouchInsetManager);
        final AmbientTouchComponent ambientTouchComponent = mAmbientTouchComponentFactory.create(
                mLifecycleOwner,
                new HashSet<>(Arrays.asList(
                        dreamComplicationComponent.getHideComplicationTouchHandler(),
                        dreamOverlayComponent.getCommunalTouchHandler())), TAG);

        setLifecycleStateLocked(Lifecycle.State.STARTED);

        mUiEventLogger.log(DreamOverlayEvent.DREAM_OVERLAY_ENTER_START);

        if (mDestroyed) {
            // The task could still be executed after the service has been destroyed. Bail if
            // that is the case.
            return;
        }

        if (mStarted) {
            // Reset the current dream overlay before starting a new one. This can happen
            // when two dreams overlap (briefly, for a smoother dream transition) and both
            // dreams are bound to the dream overlay service.
            if (!mResetHandler.reset(() -> onStartDream(layoutParams),
                    "starting with dream already started")) {
                return;
            }
        }

        mDreamOverlayContainerViewController =
                dreamOverlayComponent.getDreamOverlayContainerViewController();
        mTouchMonitor = ambientTouchComponent.getTouchMonitor();
        mTouchMonitor.init();

        mStateController.setShouldShowComplications(shouldShowComplications());

        // If we are not able to add the overlay window, reset the overlay.
        if (!addOverlayWindowLocked(layoutParams)) {
            mResetHandler.reset("couldn't add window while starting");
            return;
        }

        // Set lifecycle to resumed only if there's nothing covering the dream, ex. shade, bouncer,
        // or hub. These updates can come in before onStartDream runs.
        updateLifecycleStateLocked();
        mStateController.setOverlayActive(true);
        final ComponentName dreamComponent = getDreamComponent();
        mStateController.setLowLightActive(
                dreamComponent != null && dreamComponent.equals(mLowLightDreamComponent));

        mStateController.setHomeControlPanelActive(
                dreamComponent != null && dreamComponent.equals(mHomeControlPanelDreamComponent));

        mUiEventLogger.log(DreamOverlayEvent.DREAM_OVERLAY_COMPLETE_START);

        mDreamOverlayCallbackController.onStartDream();
        mStarted = true;

        updateRedirectWakeup();
        updateGestureBlockingLocked();
    }

    private void updateRedirectWakeup() {
        if (!mStarted || !dreamWakeRedirect()) {
            return;
        }

        redirectWake(mCommunalAvailable && !glanceableHubAllowKeyguardWhenDreaming());
    }

    @Override
    public void onEndDream() {
        mResetHandler.reset("ending dream");
    }

    @Override
    public void onWakeRequested() {
        mUiEventLogger.log(CommunalUiEvent.DREAM_TO_COMMUNAL_HUB_DREAM_AWAKE_START);
        mCommunalInteractor.changeScene(CommunalScenes.Communal,
                "dream wake requested",
                null);
    }

    private void updateGestureBlockingLocked() {
        final boolean shouldBlock = mStarted && !mShadeExpanded && !mBouncerShowing
                && !isDreamInPreviewMode();

        if (shouldBlock) {
            mGestureInteractor.addGestureBlockedMatcher(DREAM_TYPE_MATCHER,
                    GestureInteractor.Scope.Global);
        } else {
            mGestureInteractor.removeGestureBlockedMatcher(DREAM_TYPE_MATCHER,
                    GestureInteractor.Scope.Global);
        }
    }

    private Lifecycle.State getLifecycleStateLocked() {
        return mLifecycleRegistry.getCurrentState();
    }

    private void setLifecycleStateLocked(Lifecycle.State state) {
        mLifecycleRegistry.setCurrentState(state);
    }

    private void updateLifecycleStateLocked() {
        if (getLifecycleStateLocked() != Lifecycle.State.RESUMED
                && getLifecycleStateLocked() != Lifecycle.State.STARTED) {
            return;
        }

        // If anything is on top of the dream, we should stop touch handling.
        boolean shouldPause = mShadeExpanded || mCommunalVisible || mBouncerShowing;

        setLifecycleStateLocked(
                shouldPause ? Lifecycle.State.STARTED : Lifecycle.State.RESUMED);
    }

    @Override
    public void onWakeUp() {
        if (mDreamOverlayContainerViewController != null) {
            mDreamOverlayCallbackController.onWakeUp();
            mDreamOverlayContainerViewController.onWakeUp();
        }
    }

    @Override
    public void onComeToFront() {
        // Make sure the bouncer is closed. Expanding the shade effectively contracts the bouncer
        // an equal amount.
        if (mDreamOverlayContainerViewController != null
                && mDreamOverlayContainerViewController.isBouncerShowing()) {
            mScrimManager.getCurrentController().expand(
                    new ShadeExpansionChangeEvent(
                            /* fraction= */ 1.f,
                            /* expanded= */ false,
                            /* tracking= */ true));
        }

        // closeSystemDialogs takes care of closing anything that responds to the
        // {@link Intent.ACTION_CLOSE_SYSTEM_DIALOGS} broadcast (which includes the notification
        // shade).
        mSystemDialogsCloser.closeSystemDialogs();

        // Hide glanceable hub (this is a nop if glanceable hub is not open).
        mCommunalInteractor.changeScene(CommunalScenes.Blank, "dream come to front", null);
    }

    /**
     * Inserts {@link Window} to host the dream overlay into the dream's parent window. Must be
     * called from the main executing thread. The window attributes closely mirror those that are
     * set by the {@link android.service.dreams.DreamService} on the dream Window.
     *
     * @param layoutParams The {@link android.view.WindowManager.LayoutParams} which allow inserting
     *                     into the dream window.
     */
    private boolean addOverlayWindowLocked(WindowManager.LayoutParams layoutParams) {

        mWindow = new PhoneWindow(mContext);
        // Default to SystemUI name for TalkBack.
        mWindow.setTitle(mWindowTitle);
        mWindow.setAttributes(layoutParams);
        mWindow.setWindowManager(null, layoutParams.token, "DreamOverlay", true);

        mWindow.setBackgroundDrawable(new ColorDrawable(0));

        mWindow.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        mWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        mWindow.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS);
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        // Hide all insets when the dream is showing
        mWindow.getDecorView().getWindowInsetsController().hide(WindowInsets.Type.systemBars());
        mWindow.setDecorFitsSystemWindows(false);

        if (DEBUG) {
            Log.d(TAG, "adding overlay window to dream");
        }

        mDreamOverlayContainerViewController.init();
        // Make extra sure the container view has been removed from its old parent (otherwise we
        // risk an IllegalStateException in some cases when setting the container view as the
        // window's content view and the container view hasn't been properly removed previously).
        removeContainerViewFromParentLocked();

        mWindow.setContentView(mDreamOverlayContainerViewController.getContainerView());

        // It is possible that a dream's window (and the dream as a whole) is no longer valid by
        // the time the overlay service processes the dream. This can happen for example if
        // another dream is started immediately after the existing dream begins. In this case, the
        // overlay service should identify the situation through the thrown exception and tear down
        // the overlay.
        try {
            mWindowManager.addView(mWindow.getDecorView(), mWindow.getAttributes());
            return true;
        } catch (WindowManager.BadTokenException exception) {
            Log.e(TAG, "Dream activity window invalid: " + layoutParams.packageName,
                    exception);
            return false;
        }
    }

    private void removeContainerViewFromParentLocked() {
        if (mDreamOverlayContainerViewController == null) {
            return;
        }

        View containerView = mDreamOverlayContainerViewController.getContainerView();
        if (containerView == null) {
            return;
        }
        ViewGroup parentView = (ViewGroup) containerView.getParent();
        if (parentView == null) {
            return;
        }
        Log.w(TAG, "Removing dream overlay container view parent!");
        parentView.removeView(containerView);
    }
}
