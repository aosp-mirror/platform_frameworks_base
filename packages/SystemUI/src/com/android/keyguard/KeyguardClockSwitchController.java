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
import static com.android.systemui.Flags.migrateClocksToBlueprint;
import static com.android.systemui.Flags.smartspaceRelocateToBottom;
import static com.android.systemui.flags.Flags.LOCKSCREEN_WALLPAPER_DREAM_ENABLED;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.annotation.Nullable;
import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlagsClassic;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl;
import com.android.systemui.keyguard.ui.view.InWindowLauncherUnlockAnimationManager;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.core.LogLevel;
import com.android.systemui.log.dagger.KeyguardClockLog;
import com.android.systemui.plugins.clocks.ClockController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.shared.clocks.ClockRegistry;
import com.android.systemui.shared.regionsampling.RegionSampler;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerAlwaysOnDisplayViewBinder;
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;

import kotlinx.coroutines.DisposableHandle;

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
    private final NotificationIconContainerAlwaysOnDisplayViewBinder mNicViewBinder;
    private FrameLayout mSmallClockFrame; // top aligned clock
    private FrameLayout mLargeClockFrame; // centered clock

    @KeyguardClockSwitch.ClockSize
    private int mCurrentClockSize = SMALL;

    private int mKeyguardSmallClockTopMargin = 0;
    private int mKeyguardLargeClockTopMargin = 0;
    private int mKeyguardDateWeatherViewInvisibility = View.INVISIBLE;
    private final ClockRegistry.ClockChangeListener mClockChangedListener;

    private ViewGroup mStatusArea;

    // If the SMARTSPACE flag is set, keyguard_slice_view is replaced by the following views.
    private ViewGroup mDateWeatherView;
    private View mWeatherView;
    private View mSmartspaceView;

    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private final InWindowLauncherUnlockAnimationManager mInWindowLauncherUnlockAnimationManager;

    private boolean mShownOnSecondaryDisplay = false;
    private boolean mOnlyClock = false;
    private boolean mIsActiveDreamLockscreenHosted = false;
    private final FeatureFlagsClassic mFeatureFlags;
    private KeyguardInteractor mKeyguardInteractor;
    private KeyguardClockInteractor mKeyguardClockInteractor;
    private final DelayableExecutor mUiExecutor;
    private final Executor mBgExecutor;
    private boolean mCanShowDoubleLineClock = true;
    private DisposableHandle mAodIconsBindHandle;
    @Nullable private NotificationIconContainer mAodIconContainer;

    @VisibleForTesting
    final Consumer<Boolean> mIsActiveDreamLockscreenHostedCallback =
            (Boolean isLockscreenHosted) -> {
                if (mIsActiveDreamLockscreenHosted == isLockscreenHosted) {
                    return;
                }
                mIsActiveDreamLockscreenHosted = isLockscreenHosted;
                updateKeyguardStatusAreaVisibility();
            };
    private final ContentObserver mDoubleLineClockObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean change) {
            updateDoubleLineClock();
        }
    };
    private final ContentObserver mShowWeatherObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean change) {
            setWeatherVisibility();
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
            NotificationIconContainerAlwaysOnDisplayViewBinder nicViewBinder,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            SecureSettings secureSettings,
            @Main DelayableExecutor uiExecutor,
            @Background Executor bgExecutor,
            DumpManager dumpManager,
            ClockEventController clockEventController,
            @KeyguardClockLog LogBuffer logBuffer,
            KeyguardInteractor keyguardInteractor,
            KeyguardClockInteractor keyguardClockInteractor,
            FeatureFlagsClassic featureFlags,
            InWindowLauncherUnlockAnimationManager inWindowLauncherUnlockAnimationManager) {
        super(keyguardClockSwitch);
        mStatusBarStateController = statusBarStateController;
        mClockRegistry = clockRegistry;
        mKeyguardSliceViewController = keyguardSliceViewController;
        mNotificationIconAreaController = notificationIconAreaController;
        mSmartspaceController = smartspaceController;
        mNicViewBinder = nicViewBinder;
        mSecureSettings = secureSettings;
        mUiExecutor = uiExecutor;
        mBgExecutor = bgExecutor;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mDumpManager = dumpManager;
        mClockEventController = clockEventController;
        mLogBuffer = logBuffer;
        mView.setLogBuffer(mLogBuffer);
        mFeatureFlags = featureFlags;
        mKeyguardInteractor = keyguardInteractor;
        mKeyguardClockInteractor = keyguardClockInteractor;
        mInWindowLauncherUnlockAnimationManager = inWindowLauncherUnlockAnimationManager;

        mClockChangedListener = new ClockRegistry.ClockChangeListener() {
            @Override
            public void onCurrentClockChanged() {
                if (!migrateClocksToBlueprint()) {
                    setClock(mClockRegistry.createCurrentClock());
                }
            }
            @Override
            public void onAvailableClocksChanged() { }
        };
    }

    /**
     * When set, limits the information shown in an external display.
     */
    public void setShownOnSecondaryDisplay(boolean shownOnSecondaryDisplay) {
        mShownOnSecondaryDisplay = shownOnSecondaryDisplay;
    }

    /**
     * Mostly used for alternate displays, limit the information shown
     *
     * @deprecated use {@link KeyguardClockSwitchController#setShownOnSecondaryDisplay}
     */
    @Deprecated
    public void setOnlyClock(boolean onlyClock) {
        mOnlyClock = onlyClock;
    }

    /**
     * Used for status view to pass the screen offset from parent view
     */
    public void setLockscreenClockY(int clockY) {
        if (mView.screenOffsetYPadding != clockY) {
            mView.screenOffsetYPadding = clockY;
            mView.post(() -> mView.updateClockTargetRegions());
        }
    }

    /**
     * Attach the controller to the view it relates to.
     */
    @Override
    protected void onInit() {
        mKeyguardSliceViewController.init();

        mSmallClockFrame = mView.findViewById(R.id.lockscreen_clock_view);
        mLargeClockFrame = mView.findViewById(R.id.lockscreen_clock_view_large);

        if (!mOnlyClock) {
            mDumpManager.unregisterDumpable(getClass().getSimpleName()); // unregister previous
            mDumpManager.registerDumpable(getClass().getSimpleName(), this);
        }

        if (mFeatureFlags.isEnabled(LOCKSCREEN_WALLPAPER_DREAM_ENABLED)) {
            mStatusArea = mView.findViewById(R.id.keyguard_status_area);
            collectFlow(mStatusArea, mKeyguardInteractor.isActiveDreamLockscreenHosted(),
                    mIsActiveDreamLockscreenHostedCallback);
        }
    }

    public KeyguardClockSwitch getView() {
        return mView;
    }

    private void hideSliceViewAndNotificationIconContainer() {
        View ksv = mView.findViewById(R.id.keyguard_slice_view);
        ksv.setVisibility(View.GONE);

        View nic = mView.findViewById(
                R.id.left_aligned_notification_icon_container);
        if (nic != null) {
            nic.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onViewAttached() {
        mClockRegistry.registerClockChangeListener(mClockChangedListener);
        setClock(mClockRegistry.createCurrentClock());
        mClockEventController.registerListeners(mView);
        mKeyguardSmallClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_clock_top_margin);
        mKeyguardLargeClockTopMargin =
                mView.getResources().getDimensionPixelSize(
                        com.android.systemui.customization.R.dimen.keyguard_large_clock_top_margin);
        mKeyguardDateWeatherViewInvisibility =
                mView.getResources().getInteger(R.integer.keyguard_date_weather_view_invisibility);

        if (mShownOnSecondaryDisplay) {
            mView.setLargeClockOnSecondaryDisplay(true);
            mClockEventController.setLargeClockOnSecondaryDisplay(true);
            displayClock(LARGE, /* animate= */ false);
            hideSliceViewAndNotificationIconContainer();
            return;
        }

        if (mOnlyClock) {
            hideSliceViewAndNotificationIconContainer();
            return;
        }
        updateAodIcons();
        mStatusArea = mView.findViewById(R.id.keyguard_status_area);

        mBgExecutor.execute(() -> {
            mSecureSettings.registerContentObserverForUser(
                    Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK,
                    false, /* notifyForDescendants */
                    mDoubleLineClockObserver,
                    UserHandle.USER_ALL
            );

            mSecureSettings.registerContentObserverForUser(
                    Settings.Secure.LOCK_SCREEN_WEATHER_ENABLED,
                    false, /* notifyForDescendants */
                    mShowWeatherObserver,
                    UserHandle.USER_ALL
            );
        });

        updateDoubleLineClock();

        mKeyguardUnlockAnimationController.addKeyguardUnlockAnimationListener(
                mKeyguardUnlockAnimationListener);

        if (mSmartspaceController.isEnabled()) {
            View ksv = mView.findViewById(R.id.keyguard_slice_view);
            int viewIndex = mStatusArea.indexOfChild(ksv);
            ksv.setVisibility(View.GONE);

            removeViewsFromStatusArea();
            addSmartspaceView();
            // TODO(b/261757708): add content observer for the Settings toggle and add/remove
            //  weather according to the Settings.
            if (mSmartspaceController.isDateWeatherDecoupled()) {
                addDateWeatherView();
            }
        }
        if (!migrateClocksToBlueprint()) {
            setDateWeatherVisibility();
            setWeatherVisibility();
        }

    }

    int getNotificationIconAreaHeight() {
        if (KeyguardShadeMigrationNssl.isEnabled()) {
            return 0;
        } else if (NotificationIconContainerRefactor.isEnabled()) {
            return mAodIconContainer != null ? mAodIconContainer.getHeight() : 0;
        } else {
            return mNotificationIconAreaController.getHeight();
        }
    }

    @Nullable
    View getAodNotifIconContainer() {
        return mAodIconContainer;
    }

    @Override
    protected void onViewDetached() {
        mClockRegistry.unregisterClockChangeListener(mClockChangedListener);
        mClockEventController.unregisterListeners();
        setClock(null);

        mBgExecutor.execute(() -> {
            mSecureSettings.unregisterContentObserver(mDoubleLineClockObserver);
            mSecureSettings.unregisterContentObserver(mShowWeatherObserver);
        });

        mKeyguardUnlockAnimationController.removeKeyguardUnlockAnimationListener(
                mKeyguardUnlockAnimationListener);
    }

    void onLocaleListChanged() {
        if (mSmartspaceController.isEnabled()) {
            removeViewsFromStatusArea();
            addSmartspaceView();
            if (mSmartspaceController.isDateWeatherDecoupled()) {
                mDateWeatherView.removeView(mWeatherView);
                addDateWeatherView();
                setDateWeatherVisibility();
                setWeatherVisibility();
            }
        }
    }

    private void addDateWeatherView() {
        if (migrateClocksToBlueprint()) {
            return;
        }
        mDateWeatherView = (ViewGroup) mSmartspaceController.buildAndConnectDateView(mView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        mStatusArea.addView(mDateWeatherView, 0, lp);
        int startPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_start);
        int endPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_end);
        mDateWeatherView.setPaddingRelative(startPadding, 0, endPadding, 0);
        addWeatherView();
    }

    private void addWeatherView() {
        if (migrateClocksToBlueprint()) {
            return;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                WRAP_CONTENT, WRAP_CONTENT);
        mWeatherView = mSmartspaceController.buildAndConnectWeatherView(mView);
        // Place weather right after the date, before the extras
        final int index = mDateWeatherView.getChildCount() == 0 ? 0 : 1;
        mDateWeatherView.addView(mWeatherView, index, lp);
        mWeatherView.setPaddingRelative(0, 0, 4, 0);
    }

    private void addSmartspaceView() {
        if (migrateClocksToBlueprint()) {
            return;
        }

        if (smartspaceRelocateToBottom()) {
            return;
        }

        mSmartspaceView = mSmartspaceController.buildAndConnectView(mView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        mStatusArea.addView(mSmartspaceView, 0, lp);
        int startPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_start);
        int endPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_end);
        mSmartspaceView.setPaddingRelative(startPadding, 0, endPadding, 0);

        mKeyguardUnlockAnimationController.setLockscreenSmartspace(mSmartspaceView);
        mInWindowLauncherUnlockAnimationManager.setLockscreenSmartspace(mSmartspaceView);

        mView.setSmartspace(mSmartspaceView);
    }

    /**
     * Apply dp changes on configuration change
     */
    public void onConfigChanged() {
        mView.onConfigChanged();
        mKeyguardSmallClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_clock_top_margin);
        mKeyguardLargeClockTopMargin =
                mView.getResources().getDimensionPixelSize(
                        com.android.systemui.customization.R.dimen.keyguard_large_clock_top_margin);
        mKeyguardDateWeatherViewInvisibility =
                mView.getResources().getInteger(R.integer.keyguard_date_weather_view_invisibility);
        mView.updateClockTargetRegions();
        setDateWeatherVisibility();
    }

    /**
     * Enable or disable split shade center specific positioning
     */
    public void setSplitShadeCentered(boolean splitShadeCentered) {
        mView.setSplitShadeCentered(splitShadeCentered);
    }

    /**
     * Set if the split shade is enabled
     */
    public void setSplitShadeEnabled(boolean splitShadeEnabled) {
        mSmartspaceController.setSplitShadeEnabled(splitShadeEnabled);
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
        setDateWeatherVisibility();

        ClockController clock = getClock();
        boolean appeared = mView.switchToClock(clockSize, animate);
        if (clock != null && animate && appeared && clockSize == LARGE) {
            mUiExecutor.executeDelayed(() -> clock.getLargeClock().getAnimations().enter(),
                    KeyguardClockSwitch.CLOCK_IN_START_DELAY_MILLIS);
        }
    }

    /**
     * Animates the clock view between folded and unfolded states
     */
    public void animateFoldToAod(float foldFraction) {
        ClockController clock = getClock();
        if (clock != null) {
            clock.getSmallClock().getAnimations().fold(foldFraction);
            clock.getLargeClock().getAnimations().fold(foldFraction);
        }
    }

    /**
     * Refresh clock. Called in response to TIME_TICK broadcasts.
     */
    void refresh() {
        mLogBuffer.log(TAG, LogLevel.INFO, "refresh");
        if (mSmartspaceController != null) {
            mSmartspaceController.requestSmartspaceUpdate();
        }
        ClockController clock = getClock();
        if (clock != null) {
            clock.getSmallClock().getEvents().onTimeTick();
            clock.getLargeClock().getEvents().onTimeTick();
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
            PropertyAnimator.setProperty(mStatusArea, KeyguardStatusAreaView.TRANSLATE_X_AOD,
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
        if (!KeyguardShadeMigrationNssl.isEnabled()) {
            NotificationIconContainer nic = (NotificationIconContainer)
                    mView.findViewById(
                            com.android.systemui.res.R.id.left_aligned_notification_icon_container);
            if (NotificationIconContainerRefactor.isEnabled()) {
                if (mAodIconsBindHandle != null) {
                    mAodIconsBindHandle.dispose();
                }
                if (nic != null) {
                    mAodIconsBindHandle = mNicViewBinder.bindWhileAttached(nic);
                    mAodIconContainer = nic;
                }
            } else {
                mNotificationIconAreaController.setupAodIcons(nic);
                mAodIconContainer = nic;
            }
        }
    }

    private void setClock(ClockController clock) {
        if (migrateClocksToBlueprint()) {
            return;
        }
        if (clock != null && mLogBuffer != null) {
            mLogBuffer.log(TAG, LogLevel.INFO, "New Clock");
        }

        mClockEventController.setClock(clock);
        mView.setClock(clock, mStatusBarStateController.getState());
        setDateWeatherVisibility();
    }

    @Nullable
    public ClockController getClock() {
        if (migrateClocksToBlueprint()) {
            return mKeyguardClockInteractor.getClock();
        } else {
            return mClockEventController.getClock();
        }
    }

    private int getCurrentLayoutDirection() {
        return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault());
    }

    private void updateDoubleLineClock() {
        if (migrateClocksToBlueprint()) {
            return;
        }
        mCanShowDoubleLineClock = mSecureSettings.getIntForUser(
            Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, mView.getResources()
                    .getInteger(com.android.internal.R.integer.config_doublelineClockDefault),
            UserHandle.USER_CURRENT) != 0;

        if (!mCanShowDoubleLineClock) {
            mUiExecutor.execute(() -> displayClock(KeyguardClockSwitch.SMALL,
                    /* animate */ true));
        }
    }

    private void setDateWeatherVisibility() {
        if (mDateWeatherView != null) {
            mUiExecutor.execute(() -> {
                mDateWeatherView.setVisibility(clockHasCustomWeatherDataDisplay()
                        ? mKeyguardDateWeatherViewInvisibility
                        : View.VISIBLE);
            });
        }
    }

    private void setWeatherVisibility() {
        if (mWeatherView != null) {
            mUiExecutor.execute(() -> {
                mWeatherView.setVisibility(
                        mSmartspaceController.isWeatherEnabled() ? View.VISIBLE : View.GONE);
            });
        }
    }

    private void updateKeyguardStatusAreaVisibility() {
        if (mStatusArea != null) {
            mUiExecutor.execute(() -> {
                mStatusArea.setVisibility(
                        mIsActiveDreamLockscreenHosted ? View.INVISIBLE : View.VISIBLE);
            });
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
        pw.println("currentClockSizeLarge: " + (mCurrentClockSize == LARGE));
        pw.println("mCanShowDoubleLineClock: " + mCanShowDoubleLineClock);
        mView.dump(pw, args);
        mClockRegistry.dump(pw, args);
        ClockController clock = getClock();
        if (clock != null) {
            clock.dump(pw);
        }
        final RegionSampler smallRegionSampler = mClockEventController.getSmallRegionSampler();
        if (smallRegionSampler != null) {
            smallRegionSampler.dump(pw);
        }
        final RegionSampler largeRegionSampler = mClockEventController.getLargeRegionSampler();
        if (largeRegionSampler != null) {
            largeRegionSampler.dump(pw);
        }
    }

    /** Returns true if the clock handles the display of weather information */
    private boolean clockHasCustomWeatherDataDisplay() {
        ClockController clock = getClock();
        if (clock == null) {
            return false;
        }

        return ((mCurrentClockSize == LARGE) ? clock.getLargeClock() : clock.getSmallClock())
                .getConfig().getHasCustomWeatherDataDisplay();
    }

    private void removeViewsFromStatusArea() {
        for  (int i = mStatusArea.getChildCount() - 1; i >= 0; i--) {
            final View childView = mStatusArea.getChildAt(i);
            if (childView.getTag(R.id.tag_smartspace_view) != null) {
                mStatusArea.removeViewAt(i);
            }
        }
    }
}
