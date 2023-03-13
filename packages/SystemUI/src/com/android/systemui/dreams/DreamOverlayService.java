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

import static com.android.systemui.dreams.dagger.DreamModule.DREAM_OVERLAY_WINDOW_TITLE;

import android.content.ComponentName;
import android.content.Context;
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
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ViewModelStore;

import com.android.dream.lowlight.dagger.LowLightDreamModule;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.policy.PhoneWindow;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.dreams.touch.DreamOverlayTouchMonitor;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * The {@link DreamOverlayService} is responsible for placing an overlay on top of a dream. The
 * dream reaches directly out to the service with a Window reference (via LayoutParams), which the
 * service uses to insert its own child Window into the dream's parent Window.
 */
public class DreamOverlayService extends android.service.dreams.DreamOverlayService {
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
    @Nullable
    private final ComponentName mLowLightDreamComponent;
    private final UiEventLogger mUiEventLogger;
    private final WindowManager mWindowManager;
    private final String mWindowTitle;

    // A reference to the {@link Window} used to hold the dream overlay.
    private Window mWindow;

    // True if a dream has bound to the service and dream overlay service has started.
    private boolean mStarted = false;

    // True if the service has been destroyed.
    private boolean mDestroyed = false;

    private final DreamOverlayComponent mDreamOverlayComponent;

    private final LifecycleRegistry mLifecycleRegistry;

    private DreamOverlayTouchMonitor mDreamOverlayTouchMonitor;

    private final KeyguardUpdateMonitorCallback mKeyguardCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onShadeExpandedChanged(boolean expanded) {
                    mExecutor.execute(() -> {
                        if (getCurrentStateLocked() != Lifecycle.State.RESUMED
                                && getCurrentStateLocked() != Lifecycle.State.STARTED) {
                            return;
                        }

                        setCurrentStateLocked(
                                expanded ? Lifecycle.State.STARTED : Lifecycle.State.RESUMED);
                    });
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
            @Main DelayableExecutor executor,
            WindowManager windowManager,
            DreamOverlayComponent.Factory dreamOverlayComponentFactory,
            DreamOverlayStateController stateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            UiEventLogger uiEventLogger,
            @Nullable @Named(LowLightDreamModule.LOW_LIGHT_DREAM_COMPONENT)
                    ComponentName lowLightDreamComponent,
            DreamOverlayCallbackController dreamOverlayCallbackController,
            @Named(DREAM_OVERLAY_WINDOW_TITLE) String windowTitle) {
        super(executor);
        mContext = context;
        mExecutor = executor;
        mWindowManager = windowManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mLowLightDreamComponent = lowLightDreamComponent;
        mKeyguardUpdateMonitor.registerCallback(mKeyguardCallback);
        mStateController = stateController;
        mUiEventLogger = uiEventLogger;
        mDreamOverlayCallbackController = dreamOverlayCallbackController;
        mWindowTitle = windowTitle;

        final ViewModelStore viewModelStore = new ViewModelStore();
        final Complication.Host host =
                () -> mExecutor.execute(DreamOverlayService.this::requestExit);
        mDreamOverlayComponent = dreamOverlayComponentFactory.create(viewModelStore, host);
        mLifecycleRegistry = mDreamOverlayComponent.getLifecycleRegistry();

        mExecutor.execute(() -> setCurrentStateLocked(Lifecycle.State.CREATED));
    }

    @Override
    public void onDestroy() {
        mKeyguardUpdateMonitor.removeCallback(mKeyguardCallback);

        mExecutor.execute(() -> {
            setCurrentStateLocked(Lifecycle.State.DESTROYED);

            resetCurrentDreamOverlayLocked();

            mDestroyed = true;
        });

        super.onDestroy();
    }

    @Override
    public void onStartDream(@NonNull WindowManager.LayoutParams layoutParams) {
        setCurrentStateLocked(Lifecycle.State.STARTED);

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
        mDreamOverlayTouchMonitor = mDreamOverlayComponent.getDreamOverlayTouchMonitor();
        mDreamOverlayTouchMonitor.init();

        mStateController.setShouldShowComplications(shouldShowComplications());

        // If we are not able to add the overlay window, reset the overlay.
        if (!addOverlayWindowLocked(layoutParams)) {
            resetCurrentDreamOverlayLocked();
            return;
        }

        setCurrentStateLocked(Lifecycle.State.RESUMED);
        mStateController.setOverlayActive(true);
        final ComponentName dreamComponent = getDreamComponent();
        mStateController.setLowLightActive(
                dreamComponent != null && dreamComponent.equals(mLowLightDreamComponent));
        mUiEventLogger.log(DreamOverlayEvent.DREAM_OVERLAY_COMPLETE_START);

        mDreamOverlayCallbackController.onStartDream();
        mStarted = true;
    }

    @Override
    public void onEndDream() {
        resetCurrentDreamOverlayLocked();
    }

    private Lifecycle.State getCurrentStateLocked() {
        return mLifecycleRegistry.getCurrentState();
    }

    private void setCurrentStateLocked(Lifecycle.State state) {
        mLifecycleRegistry.setCurrentState(state);
    }

    @Override
    public void onWakeUp(@NonNull Runnable onCompletedCallback) {
        if (mDreamOverlayContainerViewController != null) {
            mDreamOverlayCallbackController.onWakeUp();
            mDreamOverlayContainerViewController.wakeUp(onCompletedCallback, mExecutor);
        }
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
        mDreamOverlayTouchMonitor = null;

        mWindow = null;
        mStarted = false;
    }
}
