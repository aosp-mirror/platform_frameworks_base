/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.keyguard.KeyguardClockSwitch.LARGE;
import static com.android.keyguard.KeyguardClockSwitch.SMALL;

import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.log.dagger.KeyguardClockLog;
import com.android.systemui.plugins.ClockAnimations;
import com.android.systemui.plugins.ClockController;
import com.android.systemui.plugins.log.LogBuffer;
import com.android.systemui.plugins.log.LogLevel;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.clocks.ClockRegistry;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Injectable controller for {@link KeyguardClockSwitch}.
 */
public class KeyguardClockSwitchController extends ViewController<KeyguardClockSwitch>
        implements Dumpable {
    private static final String TAG = "KeyguardClockSwitchController";

    private final StatusBarStateController mStatusBarStateController;
    private final ClockRegistry mClockRegistry;
    private final KeyguardSliceViewController mKeyguardSliceViewController;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final LockscreenSmartspaceController mSmartspaceController;
    private final SecureSettings mSecureSettings;
    private final DumpManager mDumpManager;
    private final ClockEventController mClockEventController;
    private final LogBuffer mLogBuffer;

    private FrameLayout mSmallClockFrame; // top aligned clock
    private FrameLayout mLargeClockFrame; // centered clock

    @KeyguardClockSwitch.ClockSize
    private int mCurrentClockSize = SMALL;

    private int mKeyguardSmallClockTopMargin = 0;
    private int mKeyguardLargeClockTopMargin = 0;
    private final ClockRegistry.ClockChangeListener mClockChangedListener;

    private ViewGroup mStatusArea;
    // If set will replace keyguard_slice_view
    private View mSmartspaceView;

    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;

    private boolean mOnlyClock = false;
    private final Executor mUiExecutor;
    private boolean mCanShowDoubleLineClock = true;
    private final ContentObserver mDoubleLineClockObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean change) {
            updateDoubleLineClock();
        }
    };

    private final KeyguardUnlockAnimationController.KeyguardUnlockAnimationListener
            mKeyguardUnlockAnimationListener =
            new KeyguardUnlockAnimationController.KeyguardUnlockAnimationListener() {
                @Override
                public void onUnlockAnimationFinished() {
                    // For performance reasons, reset this once the unlock animation ends.
                    setClipChildrenForUnlock(true);
                }
            };

    @Inject
    public KeyguardClockSwitchController(
            KeyguardClockSwitch keyguardClockSwitch,
            StatusBarStateController statusBarStateController,
            ClockRegistry clockRegistry,
            KeyguardSliceViewController keyguardSliceViewController,
            NotificationIconAreaController notificationIconAreaController,
            LockscreenSmartspaceController smartspaceController,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            SecureSettings secureSettings,
            @Main Executor uiExecutor,
            DumpManager dumpManager,
            ClockEventController clockEventController,
            @KeyguardClockLog LogBuffer logBuffer) {
        super(keyguardClockSwitch);
        mStatusBarStateController = statusBarStateController;
        mClockRegistry = clockRegistry;
        mKeyguardSliceViewController = keyguardSliceViewController;
        mNotificationIconAreaController = notificationIconAreaController;
        mSmartspaceController = smartspaceController;
        mSecureSettings = secureSettings;
        mUiExecutor = uiExecutor;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mDumpManager = dumpManager;
        mClockEventController = clockEventController;
        mLogBuffer = logBuffer;
        mView.setLogBuffer(mLogBuffer);

        mClockChangedListener = () -> {
            setClock(mClockRegistry.createCurrentClock());
        };
    }

    /**
     * Mostly used for alternate displays, limit the information shown
     */
    public void setOnlyClock(boolean onlyClock) {
        mOnlyClock = onlyClock;
    }

    /**
     * Attach the controller to the view it relates to.
     */
    @Override
    protected void onInit() {
        mKeyguardSliceViewController.init();

        mSmallClockFrame = mView.findViewById(R.id.lockscreen_clock_view);
        mLargeClockFrame = mView.findViewById(R.id.lockscreen_clock_view_large);

        mDumpManager.unregisterDumpable(getClass().toString()); // unregister previous clocks
        mDumpManager.registerDumpable(getClass().toString(), this);
    }

    @Override
    protected void onViewAttached() {
        mClockRegistry.registerClockChangeListener(mClockChangedListener);
        setClock(mClockRegistry.createCurrentClock());
        mClockEventController.registerListeners(mView);
        mKeyguardSmallClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_clock_top_margin);
        mKeyguardLargeClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_large_clock_top_margin);

        if (mOnlyClock) {
            View ksv = mView.findViewById(R.id.keyguard_slice_view);
            ksv.setVisibility(View.GONE);

            View nic = mView.findViewById(
                    R.id.left_aligned_notification_icon_container);
            nic.setVisibility(View.GONE);
            return;
        }
        updateAodIcons();

        mStatusArea = mView.findViewById(R.id.keyguard_status_area);

        if (mSmartspaceController.isEnabled()) {
            View ksv = mView.findViewById(R.id.keyguard_slice_view);
            int ksvIndex = mStatusArea.indexOfChild(ksv);
            ksv.setVisibility(View.GONE);

            addSmartspaceView(ksvIndex);
        }

        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.getUriFor(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK),
                false, /* notifyForDescendants */
                mDoubleLineClockObserver,
                UserHandle.USER_ALL
        );

        updateDoubleLineClock();

        mKeyguardUnlockAnimationController.addKeyguardUnlockAnimationListener(
                mKeyguardUnlockAnimationListener);
    }

    int getNotificationIconAreaHeight() {
        return mNotificationIconAreaController.getHeight();
    }

    @Override
    protected void onViewDetached() {
        mClockRegistry.unregisterClockChangeListener(mClockChangedListener);
        mClockEventController.unregisterListeners();
        setClock(null);

        mSecureSettings.unregisterContentObserver(mDoubleLineClockObserver);

        mKeyguardUnlockAnimationController.removeKeyguardUnlockAnimationListener(
                mKeyguardUnlockAnimationListener);
    }

    void onLocaleListChanged() {
        if (mSmartspaceController.isEnabled()) {
            int index = mStatusArea.indexOfChild(mSmartspaceView);
            if (index >= 0) {
                mStatusArea.removeView(mSmartspaceView);
                addSmartspaceView(index);
            }
        }
    }

    private void addSmartspaceView(int index) {
        mSmartspaceView = mSmartspaceController.buildAndConnectView(mView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        mStatusArea.addView(mSmartspaceView, index, lp);
        int startPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_start);
        int endPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_end);
        mSmartspaceView.setPaddingRelative(startPadding, 0, endPadding, 0);

        mKeyguardUnlockAnimationController.setLockscreenSmartspace(mSmartspaceView);
    }

    /**
     * Apply dp changes on font/scale change
     */
    public void onDensityOrFontScaleChanged() {
        mView.onDensityOrFontScaleChanged();
        mKeyguardSmallClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_clock_top_margin);
        mKeyguardLargeClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_large_clock_top_margin);
        mView.updateClockTargetRegions();
    }


    /**
     * Set which clock should be displayed on the keyguard. The other one will be automatically
     * hidden.
     */
    public void displayClock(@KeyguardClockSwitch.ClockSize int clockSize, boolean animate) {
        if (!mCanShowDoubleLineClock && clockSize == KeyguardClockSwitch.LARGE) {
            return;
        }

        mCurrentClockSize = clockSize;

        ClockController clock = getClock();
        boolean appeared = mView.switchToClock(clockSize, animate);
        if (clock != null && animate && appeared && clockSize == LARGE) {
            clock.getAnimations().enter();
        }
    }

    /**
     * Animates the clock view between folded and unfolded states
     */
    public void animateFoldToAod(float foldFraction) {
        ClockController clock = getClock();
        if (clock != null) {
            clock.getAnimations().fold(foldFraction);
        }
    }

    /**
     * Refresh clock. Called in response to TIME_TICK broadcasts.
     */
    void refresh() {
        if (mSmartspaceController != null) {
            mSmartspaceController.requestSmartspaceUpdate();
        }
        ClockController clock = getClock();
        if (clock != null) {
            clock.getEvents().onTimeTick();
        }
    }

    /**
     * Update position of the view, with optional animation. Move the slice view and the clock
     * slightly towards the center in order to prevent burn-in. Y positioning occurs at the
     * view parent level. The large clock view will scale instead of using x position offsets, to
     * keep the clock centered.
     */
    void updatePosition(int x, float scale, AnimationProperties props, boolean animate) {
        x = getCurrentLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -x : x;

        PropertyAnimator.setProperty(mSmallClockFrame, AnimatableProperty.TRANSLATION_X,
                x, props, animate);
        PropertyAnimator.setProperty(mLargeClockFrame, AnimatableProperty.SCALE_X,
                scale, props, animate);
        PropertyAnimator.setProperty(mLargeClockFrame, AnimatableProperty.SCALE_Y,
                scale, props, animate);

        if (mStatusArea != null) {
            PropertyAnimator.setProperty(mStatusArea, AnimatableProperty.TRANSLATION_X,
                    x, props, animate);
        }
    }

    /**
     * Get y-bottom position of the currently visible clock on the keyguard.
     * We can't directly getBottom() because clock changes positions in AOD for burn-in
     */
    int getClockBottom(int statusBarHeaderHeight) {
        ClockController clock = getClock();
        if (clock == null) {
            return 0;
        }

        if (mLargeClockFrame.getVisibility() == View.VISIBLE) {
            // This gets the expected clock bottom if mLargeClockFrame had a top margin, but it's
            // top margin only contributed to height and didn't move the top of the view (as this
            // was the computation previously). As we no longer have a margin, we add this back
            // into the computation manually.
            int frameHeight = mLargeClockFrame.getHeight();
            int clockHeight = clock.getLargeClock().getView().getHeight();
            return frameHeight / 2 + clockHeight / 2 + mKeyguardLargeClockTopMargin / -2;
        } else {
            int clockHeight = clock.getSmallClock().getView().getHeight();
            return clockHeight + statusBarHeaderHeight + mKeyguardSmallClockTopMargin;
        }
    }

    /**
     * Get the height of the currently visible clock on the keyguard.
     */
    int getClockHeight() {
        ClockController clock = getClock();
        if (clock == null) {
            return 0;
        }

        if (mLargeClockFrame.getVisibility() == View.VISIBLE) {
            return clock.getLargeClock().getView().getHeight();
        } else {
            return clock.getSmallClock().getView().getHeight();
        }
    }

    boolean isClockTopAligned() {
        return mLargeClockFrame.getVisibility() != View.VISIBLE;
    }

    private void updateAodIcons() {
        NotificationIconContainer nic = (NotificationIconContainer)
                mView.findViewById(
                        com.android.systemui.R.id.left_aligned_notification_icon_container);
        mNotificationIconAreaController.setupAodIcons(nic);
    }

    private void setClock(ClockController clock) {
        if (clock != null && mLogBuffer != null) {
            mLogBuffer.log(TAG, LogLevel.INFO, "New Clock");
        }

        mClockEventController.setClock(clock);
        mView.setClock(clock, mStatusBarStateController.getState());
    }

    private ClockController getClock() {
        return mClockEventController.getClock();
    }

    private int getCurrentLayoutDirection() {
        return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault());
    }

    private void updateDoubleLineClock() {
        mCanShowDoubleLineClock = mSecureSettings.getIntForUser(
            Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 1,
                UserHandle.USER_CURRENT) != 0;

        if (!mCanShowDoubleLineClock) {
            mUiExecutor.execute(() -> displayClock(KeyguardClockSwitch.SMALL, /* animate */ true));
        }
    }

    /**
     * Sets the clipChildren property on relevant views, to allow the smartspace to draw out of
     * bounds during the unlock transition.
     */
    private void setClipChildrenForUnlock(boolean clip) {
        if (mStatusArea != null) {
            mStatusArea.setClipChildren(clip);
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("currentClockSizeLarge=" + (mCurrentClockSize == LARGE));
        pw.println("mCanShowDoubleLineClock=" + mCanShowDoubleLineClock);
        mView.dump(pw, args);
        ClockController clock = getClock();
        if (clock != null) {
            clock.dump(pw);
        }
    }

    /** Gets the animations for the current clock. */
    public ClockAnimations getClockAnimations() {
        return getClock().getAnimations();
    }
}

