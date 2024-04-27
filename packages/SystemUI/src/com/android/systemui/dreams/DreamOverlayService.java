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

import static com.android.systemui.dreams.dagger.DreamModule.DREAM_OVERLAY_WINDOW_TITLE;
import static com.android.systemui.dreams.dagger.DreamModule.DREAM_TOUCH_INSET_MANAGER;
import static com.android.systemui.dreams.dagger.DreamModule.HOME_CONTROL_PANEL_DREAM_COMPONENT;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

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
import com.android.systemui.communal.shared.model.CommunalScenes;
import com.android.systemui.complication.Complication;
import com.android.systemui.complication.dagger.ComplicationComponent;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.touch.TouchInsetManager;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.Arrays;
import java.util.HashSet;
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
    private final WindowManager mWindowManager;
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

    private final ComplicationComponent mComplicationComponent;

    private final AmbientTouchComponent mAmbientTouchComponent;

    private final com.android.systemui.dreams.complication.dagger.ComplicationComponent
            mDreamComplicationComponent;

    private final DreamOverlayComponent mDreamOverlayComponent;

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
            });
        }
    };

    private final DreamOverlayStateController.Callback mExitAnimationFinishedCallback =
            new DreamOverlayStateController.Callback() {
                @Override
                public void onStateChanged() {
                    if (!mStateController.areExitAnimationsRunning()) {
                        mStateController.removeCallback(mExitAnimationFinishedCallback);
                        resetCurrentDreamOverlayLocked();
                    }
                }
            };

    private final DreamOverlayStateController mStateController;

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
            WindowManager windowManager,
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
            @Named(DREAM_OVERLAY_WINDOW_TITLE) String windowTitle) {
        super(executor);
        mContext = context;
        mExecutor = executor;
        mWindowManager = windowManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mScrimManager = scrimManager;
        mLowLightDreamComponent = lowLightDreamComponent;
        mHomeControlPanelDreamComponent = homeControlPanelDreamComponent;
        mKeyguardUpdateMonitor.registerCallback(mKeyguardCallback);
        mStateController = stateController;
        mUiEventLogger = uiEventLogger;
        mDreamOverlayCallbackController = dreamOverlayCallbackController;
        mWindowTitle = windowTitle;
        mCommunalInteractor = communalInteractor;
        mSystemDialogsCloser = systemDialogsCloser;

        final ViewModelStore viewModelStore = new ViewModelStore();
        final Complication.Host host =
                () -> mExecutor.execute(DreamOverlayService.this::requestExit);

        mComplicationComponent = complicationComponentFactory.create(lifecycleOwner, host,
                viewModelStore, touchInsetManager);
        mDreamComplicationComponent = dreamComplicationComponentFactory.create(
                mComplicationComponent.getVisibilityController(), touchInsetManager);
        mDreamOverlayComponent = dreamOverlayComponentFactory.create(lifecycleOwner,
                mComplicationComponent.getComplicationHostViewController(), touchInsetManager);
        mAmbientTouchComponent = ambientTouchComponentFactory.create(lifecycleOwner,
                new HashSet<>(Arrays.asList(
                        mDreamComplicationComponent.getHideComplicationTouchHandler(),
                        mDreamOverlayComponent.getCommunalTouchHandler())));
        mLifecycleRegistry = lifecycleOwner.getRegistry();

        mExecutor.execute(() -> setLifecycleStateLocked(Lifecycle.State.CREATED));

        collectFlow(getLifecycle(), mCommunalInteractor.isCommunalAvailable(),
                mIsCommunalAvailableCallback);
        collectFlow(getLifecycle(), communalInteractor.isCommunalVisible(),
                mCommunalVisibleConsumer);
        collectFlow(getLifecycle(), keyguardInteractor.primaryBouncerShowing,
                mBouncerShowingConsumer);
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

        mExecutor.execute(() -> {
            setLifecycleStateLocked(Lifecycle.State.DESTROYED);

            resetCurrentDreamOverlayLocked();

            mDestroyed = true;
        });

        mDispatcher.onServicePreSuperOnDestroy();
        super.onDestroy();
    }

    @Override
    public void onStartDream(@NonNull WindowManager.LayoutParams layoutParams) {
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
            resetCurrentDreamOverlayLocked();
        }

        mDreamOverlayContainerViewController =
                mDreamOverlayComponent.getDreamOverlayContainerViewController();
        mTouchMonitor = mAmbientTouchComponent.getTouchMonitor();
        mTouchMonitor.init();

        mStateController.setShouldShowComplications(shouldShowComplications());

        // If we are not able to add the overlay window, reset the overlay.
        if (!addOverlayWindowLocked(layoutParams)) {
            resetCurrentDreamOverlayLocked();
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
    }

    private void updateRedirectWakeup() {
        if (!mStarted || !dreamWakeRedirect()) {
            return;
        }

        redirectWake(mCommunalAvailable);
    }

    @Override
    public void onEndDream() {
        resetCurrentDreamOverlayLocked();
    }

    @Override
    public void onWakeRequested() {
        mCommunalInteractor.changeScene(CommunalScenes.Communal, null);
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
            mDreamOverlayContainerViewController.wakeUp();
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
        mCommunalInteractor.changeScene(CommunalScenes.Blank, null);
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

    private void resetCurrentDreamOverlayLocked() {
        if (mStateController.areExitAnimationsRunning()) {
            mStateController.addCallback(mExitAnimationFinishedCallback);
            return;
        }

        if (mStarted && mWindow != null) {
            try {
                mWindowManager.removeView(mWindow.getDecorView());
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error removing decor view when resetting overlay", e);
            }
        }

        mStateController.setOverlayActive(false);
        mStateController.setLowLightActive(false);
        mStateController.setEntryAnimationsFinished(false);

        mDreamOverlayContainerViewController = null;
        mTouchMonitor = null;

        mWindow = null;
        mStarted = false;
    }
}
