/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.car;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.car.Car;
import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.car.trust.CarTrustAgentEnrollmentManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.notification.CarHeadsUpNotificationManager;
import com.android.car.notification.CarNotificationListener;
import com.android.car.notification.CarNotificationView;
import com.android.car.notification.CarUxRestrictionManagerWrapper;
import com.android.car.notification.HeadsUpEntry;
import com.android.car.notification.NotificationClickHandlerFactory;
import com.android.car.notification.NotificationDataManager;
import com.android.car.notification.NotificationViewController;
import com.android.car.notification.PreprocessingManager;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.CarSystemUIFactory;
import com.android.systemui.Dependency;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.classifier.FalsingLog;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.qs.car.CarQSFragment;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.car.hvac.HvacController;
import com.android.systemui.statusbar.car.hvac.TemperatureView;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.UserSwitcherController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Map;

/**
 * A status bar (and navigation bar) tailored for the automotive use case.
 */
public class CarStatusBar extends StatusBar implements CarBatteryController.BatteryViewHandler {
    private static final String TAG = "CarStatusBar";
    // used to calculate how fast to open or close the window
    private static final float DEFAULT_FLING_VELOCITY = 0;
    // max time a fling animation takes
    private static final float FLING_ANIMATION_MAX_TIME = 0.5f;
    // acceleration rate for the fling animation
    private static final float FLING_SPEED_UP_FACTOR = 0.6f;

    private float mOpeningVelocity = DEFAULT_FLING_VELOCITY;
    private float mClosingVelocity = DEFAULT_FLING_VELOCITY;

    private TaskStackListenerImpl mTaskStackListener;

    private FullscreenUserSwitcher mFullscreenUserSwitcher;

    private CarBatteryController mCarBatteryController;
    private BatteryMeterView mBatteryMeterView;
    private Drawable mNotificationPanelBackground;

    private ViewGroup mNavigationBarWindow;
    private ViewGroup mLeftNavigationBarWindow;
    private ViewGroup mRightNavigationBarWindow;
    private CarNavigationBarView mNavigationBarView;
    private CarNavigationBarView mLeftNavigationBarView;
    private CarNavigationBarView mRightNavigationBarView;

    private final Object mQueueLock = new Object();
    private boolean mShowLeft;
    private boolean mShowRight;
    private boolean mShowBottom;
    private CarFacetButtonController mCarFacetButtonController;
    private ActivityManagerWrapper mActivityManagerWrapper;
    private DeviceProvisionedController mDeviceProvisionedController;
    private boolean mDeviceIsProvisioned = true;
    private HvacController mHvacController;
    private DrivingStateHelper mDrivingStateHelper;
    private PowerManagerHelper mPowerManagerHelper;
    private FlingAnimationUtils mFlingAnimationUtils;
    private SwitchToGuestTimer mSwitchToGuestTimer;
    private NotificationDataManager mNotificationDataManager;
    private NotificationClickHandlerFactory mNotificationClickHandlerFactory;
    private ScreenLifecycle mScreenLifecycle;

    // The container for the notifications.
    private CarNotificationView mNotificationView;
    private RecyclerView mNotificationList;
    // The handler bar view at the bottom of notification shade.
    private View mHandleBar;
    // The controller for the notification view.
    private NotificationViewController mNotificationViewController;
    // The state of if the notification list is currently showing the bottom.
    private boolean mNotificationListAtBottom;
    // Was the notification list at the bottom when the user first touched the screen
    private boolean mNotificationListAtBottomAtTimeOfTouch;
    // To be attached to the navigation bars such that they can close the notification panel if
    // it's open.
    private View.OnTouchListener mNavBarNotificationTouchListener;

    // Percentage from top of the screen after which the notification shade will open. This value
    // will be used while opening the notification shade.
    private int mSettleOpenPercentage;
    // Percentage from top of the screen below which the notification shade will close. This
    // value will be used while closing the notification shade.
    private int mSettleClosePercentage;
    // Percentage of notification shade open from top of the screen.
    private int mPercentageFromBottom;
    // If notification shade is animation to close or to open.
    private boolean mIsNotificationAnimating;

    // Tracks when the notification shade is being scrolled. This refers to the glass pane being
    // scrolled not the recycler view.
    private boolean mIsTracking;
    private float mFirstTouchDownOnGlassPane;

    // If the notification card inside the recycler view is being swiped.
    private boolean mIsNotificationCardSwiping;
    // If notification shade is being swiped vertically to close.
    private boolean mIsSwipingVerticallyToClose;
    // Whether heads-up notifications should be shown when shade is open.
    private boolean mEnableHeadsUpNotificationWhenNotificationShadeOpen;
    // If the nav bar should be hidden when the soft keyboard is visible.
    private boolean mHideNavBarForKeyboard;
    private boolean mBottomNavBarVisible;

    private final CarPowerStateListener mCarPowerStateListener =
            (int state) -> {
                // When the car powers on, clear all notifications and mute/unread states.
                Log.d(TAG, "New car power state: " + state);
                if (state == CarPowerStateListener.ON) {
                    if (mNotificationClickHandlerFactory != null) {
                        mNotificationClickHandlerFactory.clearAllNotifications();
                    }
                    if (mNotificationDataManager != null) {
                        mNotificationDataManager.clearAll();
                    }
                }
            };

    @Override
    public void start() {
        // get the provisioned state before calling the parent class since it's that flow that
        // builds the nav bar
        mDeviceProvisionedController = Dependency.get(DeviceProvisionedController.class);
        mDeviceIsProvisioned = mDeviceProvisionedController.isDeviceProvisioned();

        // Keyboard related setup, before nav bars are created.
        mHideNavBarForKeyboard = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_automotiveHideNavBarForKeyboard);
        mBottomNavBarVisible = false;

        super.start();
        mTaskStackListener = new TaskStackListenerImpl();
        mActivityManagerWrapper = ActivityManagerWrapper.getInstance();
        mActivityManagerWrapper.registerTaskStackListener(mTaskStackListener);

        mNotificationPanel.setScrollingEnabled(true);
        mSettleOpenPercentage = mContext.getResources().getInteger(
                R.integer.notification_settle_open_percentage);
        mSettleClosePercentage = mContext.getResources().getInteger(
                R.integer.notification_settle_close_percentage);
        mFlingAnimationUtils = new FlingAnimationUtils(mContext,
                FLING_ANIMATION_MAX_TIME, FLING_SPEED_UP_FACTOR);

        createBatteryController();
        mCarBatteryController.startListening();

        mHvacController.connectToCarService();

        CarSystemUIFactory factory = SystemUIFactory.getInstance();
        if (!mDeviceIsProvisioned) {
            mDeviceProvisionedController.addCallback(
                    new DeviceProvisionedController.DeviceProvisionedListener() {
                        @Override
                        public void onDeviceProvisionedChanged() {
                            mHandler.post(() -> {
                                // on initial boot we are getting a call even though the value
                                // is the same so we are confirming the reset is needed
                                boolean deviceProvisioned =
                                        mDeviceProvisionedController.isDeviceProvisioned();
                                if (mDeviceIsProvisioned != deviceProvisioned) {
                                    mDeviceIsProvisioned = deviceProvisioned;
                                    restartNavBars();
                                }
                            });
                        }
                    });
        }

        // Register a listener for driving state changes.
        mDrivingStateHelper = new DrivingStateHelper(mContext, this::onDrivingStateChanged);
        mDrivingStateHelper.connectToCarService();

        mPowerManagerHelper = new PowerManagerHelper(mContext, mCarPowerStateListener);
        mPowerManagerHelper.connectToCarService();

        mSwitchToGuestTimer = new SwitchToGuestTimer(mContext);

        mScreenLifecycle = Dependency.get(ScreenLifecycle.class);
        mScreenLifecycle.addObserver(mScreenObserver);
    }

    /**
     * Remove all content from navbars and rebuild them. Used to allow for different nav bars
     * before and after the device is provisioned. . Also for change of density and font size.
     */
    private void restartNavBars() {
        // remove and reattach all hvac components such that we don't keep a reference to unused
        // ui elements
        mHvacController.removeAllComponents();
        addTemperatureViewToController(mStatusBarWindow);
        mCarFacetButtonController.removeAll();
        if (mNavigationBarWindow != null) {
            mNavigationBarWindow.removeAllViews();
            mNavigationBarView = null;
        }

        if (mLeftNavigationBarWindow != null) {
            mLeftNavigationBarWindow.removeAllViews();
            mLeftNavigationBarView = null;
        }

        if (mRightNavigationBarWindow != null) {
            mRightNavigationBarWindow.removeAllViews();
            mRightNavigationBarView = null;
        }

        buildNavBarContent();
        // If the UI was rebuilt (day/night change) while the keyguard was up we need to
        // correctly respect that state.
        if (mIsKeyguard) {
            updateNavBarForKeyguardContent();
        }
        // CarFacetButtonController was reset therefore we need to re-add the status bar elements
        // to the controller.
        mCarFacetButtonController.addAllFacetButtons(mStatusBarWindow);
    }

    private void addTemperatureViewToController(View v) {
        if (v instanceof TemperatureView) {
            mHvacController.addHvacTextView((TemperatureView) v);
        } else if (v instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) v;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                addTemperatureViewToController(viewGroup.getChildAt(i));
            }
        }
    }

    /**
     * Allows for showing or hiding just the navigation bars. This is indented to be used when
     * the full screen user selector is shown.
     */
    void setNavBarVisibility(@View.Visibility int visibility) {
        if (mNavigationBarWindow != null) {
            mNavigationBarWindow.setVisibility(visibility);
        }
        if (mLeftNavigationBarWindow != null) {
            mLeftNavigationBarWindow.setVisibility(visibility);
        }
        if (mRightNavigationBarWindow != null) {
            mRightNavigationBarWindow.setVisibility(visibility);
        }
    }


    @Override
    public boolean hideKeyguard() {
        boolean result = super.hideKeyguard();
        if (mNavigationBarView != null) {
            mNavigationBarView.hideKeyguardButtons();
        }
        if (mLeftNavigationBarView != null) {
            mLeftNavigationBarView.hideKeyguardButtons();
        }
        if (mRightNavigationBarView != null) {
            mRightNavigationBarView.hideKeyguardButtons();
        }
        return result;
    }

    @Override
    public void showKeyguard() {
        super.showKeyguard();
        updateNavBarForKeyguardContent();
    }

    /**
     * Switch to the keyguard applicable content contained in the nav bars
     */
    private void updateNavBarForKeyguardContent() {
        if (mNavigationBarView != null) {
            mNavigationBarView.showKeyguardButtons();
        }
        if (mLeftNavigationBarView != null) {
            mLeftNavigationBarView.showKeyguardButtons();
        }
        if (mRightNavigationBarView != null) {
            mRightNavigationBarView.showKeyguardButtons();
        }
    }

    @Override
    protected void makeStatusBarView(@Nullable RegisterStatusBarResult result) {
        super.makeStatusBarView(result);
        mHvacController = new HvacController(mContext);

        CarSystemUIFactory factory = SystemUIFactory.getInstance();
        mCarFacetButtonController = factory.getCarDependencyComponent()
                .getCarFacetButtonController();
        mNotificationPanelBackground = getDefaultWallpaper();
        mScrimController.setScrimBehindDrawable(mNotificationPanelBackground);

        FragmentHostManager manager = FragmentHostManager.get(mStatusBarWindow);
        manager.addTagListener(CollapsedStatusBarFragment.TAG, (tag, fragment) -> {
            mBatteryMeterView = fragment.getView().findViewById(R.id.battery);

            // By default, the BatteryMeterView should not be visible. It will be toggled
            // when a device has connected by bluetooth.
            mBatteryMeterView.setVisibility(View.GONE);
        });

        connectNotificationsUI();
    }

    /**
     * Attach the notification listeners and controllers to the UI as well as build all the
     * touch listeners needed for opening and closing the notification panel
     */
    private void connectNotificationsUI() {
        // Attached to the status bar to detect pull down of the notification shade.
        GestureDetector openGestureDetector = new GestureDetector(mContext,
                new OpenNotificationGestureListener() {
                    @Override
                    protected void openNotification() {
                        animateExpandNotificationsPanel();
                    }
                });
        // Attached to the notification ui to detect close request of the notification shade.
        GestureDetector closeGestureDetector = new GestureDetector(mContext,
                new CloseNotificationGestureListener() {
                    @Override
                    protected void close() {
                        if (mPanelExpanded) {
                            animateCollapsePanels();
                        }
                    }
                });
        // Attached to the NavBars to close the notification shade
        GestureDetector navBarCloseNotificationGestureDetector = new GestureDetector(mContext,
                new NavBarCloseNotificationGestureListener() {
                    @Override
                    protected void close() {
                        if (mPanelExpanded) {
                            animateCollapsePanels();
                        }
                    }
                });

        // Attached to the Handle bar to close the notification shade
        GestureDetector handleBarCloseNotificationGestureDetector = new GestureDetector(mContext,
                new HandleBarCloseNotificationGestureListener());

        mNavBarNotificationTouchListener =
                (v, event) -> {
                    boolean consumed = navBarCloseNotificationGestureDetector.onTouchEvent(event);
                    if (consumed) {
                        return true;
                    }
                    maybeCompleteAnimation(event);
                    return true;
                };

        // The following are the ui elements that the user would call the status bar.
        // This will set the status bar so it they can make call backs.
        CarNavigationBarView topBar = mStatusBarWindow.findViewById(R.id.car_top_bar);
        topBar.setStatusBar(this);
        topBar.setStatusBarWindowTouchListener((v1, event1) -> {

                    boolean consumed = openGestureDetector.onTouchEvent(event1);
                    if (consumed) {
                        return true;
                    }
                    maybeCompleteAnimation(event1);
                    return true;
                }
        );

        mNotificationClickHandlerFactory = new NotificationClickHandlerFactory(
                mBarService,
                launchResult -> {
                    if (launchResult == ActivityManager.START_TASK_TO_FRONT
                            || launchResult == ActivityManager.START_SUCCESS) {
                        animateCollapsePanels();
                    }
                });
        Car car = Car.createCar(mContext);
        CarUxRestrictionsManager carUxRestrictionsManager = (CarUxRestrictionsManager)
                car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
        CarNotificationListener carNotificationListener = new CarNotificationListener();
        CarUxRestrictionManagerWrapper carUxRestrictionManagerWrapper =
                new CarUxRestrictionManagerWrapper();
        carUxRestrictionManagerWrapper.setCarUxRestrictionsManager(carUxRestrictionsManager);

        mNotificationDataManager = new NotificationDataManager();
        mNotificationDataManager.setOnUnseenCountUpdateListener(
                () -> {
                    if (mNavigationBarView != null && mNotificationDataManager != null) {
                        Boolean hasUnseen =
                                mNotificationDataManager.getUnseenNotificationCount() > 0;
                        if (mNavigationBarView != null) {
                            mNavigationBarView.toggleNotificationUnseenIndicator(hasUnseen);
                        }

                        if (mLeftNavigationBarView != null) {
                            mLeftNavigationBarView.toggleNotificationUnseenIndicator(hasUnseen);
                        }

                        if (mRightNavigationBarView != null) {
                            mRightNavigationBarView.toggleNotificationUnseenIndicator(hasUnseen);
                        }
                    }
                });

        mEnableHeadsUpNotificationWhenNotificationShadeOpen = mContext.getResources().getBoolean(
                R.bool.config_enableHeadsUpNotificationWhenNotificationShadeOpen);
        CarHeadsUpNotificationManager carHeadsUpNotificationManager =
                new CarSystemUIHeadsUpNotificationManager(mContext,
                        mNotificationClickHandlerFactory, mNotificationDataManager);
        mNotificationClickHandlerFactory.setNotificationDataManager(mNotificationDataManager);

        carNotificationListener.registerAsSystemService(mContext, carUxRestrictionManagerWrapper,
                carHeadsUpNotificationManager, mNotificationDataManager);

        mNotificationView = mStatusBarWindow.findViewById(R.id.notification_view);
        View glassPane = mStatusBarWindow.findViewById(R.id.glass_pane);
        mHandleBar = mStatusBarWindow.findViewById(R.id.handle_bar);
        mNotificationView.setClickHandlerFactory(mNotificationClickHandlerFactory);
        mNotificationView.setNotificationDataManager(mNotificationDataManager);

        // The glass pane is used to view touch events before passed to the notification list.
        // This allows us to initialize gesture listeners and detect when to close the notifications
        glassPane.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mFirstTouchDownOnGlassPane = event.getRawX();
                mNotificationListAtBottomAtTimeOfTouch = mNotificationListAtBottom;
                // Reset the tracker when there is a touch down on the glass pane.
                mIsTracking = false;
                // Pass the down event to gesture detector so that it knows where the touch event
                // started.
                closeGestureDetector.onTouchEvent(event);
            }
            return false;
        });

        mHandleBar.setOnTouchListener((v, event) -> {
            handleBarCloseNotificationGestureDetector.onTouchEvent(event);
            maybeCompleteAnimation(event);
            return true;
        });

        mNotificationList = mNotificationView.findViewById(R.id.notifications);
        mNotificationList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (!mNotificationList.canScrollVertically(1)) {
                    mNotificationListAtBottom = true;
                    return;
                }
                mNotificationListAtBottom = false;
                mIsSwipingVerticallyToClose = false;
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
        });
        mNotificationList.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                mIsNotificationCardSwiping = Math.abs(mFirstTouchDownOnGlassPane - event.getRawX())
                        > SWIPE_MAX_OFF_PATH;
                if (mNotificationListAtBottomAtTimeOfTouch && mNotificationListAtBottom) {
                    // We need to save the state here as if notification card is swiping we will
                    // change the mNotificationListAtBottomAtTimeOfTouch. This is to protect
                    // closing the notification shade while the notification card is being swiped.
                    mIsSwipingVerticallyToClose = true;
                }

                // If the card is swiping we should not allow the notification shade to close.
                // Hence setting mNotificationListAtBottomAtTimeOfTouch to false will stop that
                // for us. We are also checking for mIsTracking because while swiping the
                // notification shade to close if the user goes a bit horizontal while swiping
                // upwards then also this should close.
                if (mIsNotificationCardSwiping && !mIsTracking) {
                    mNotificationListAtBottomAtTimeOfTouch = false;
                }

                boolean handled = closeGestureDetector.onTouchEvent(event);
                boolean isTracking = mIsTracking;
                Rect rect = mNotificationView.getClipBounds();
                float clippedHeight = 0;
                if (rect != null) {
                    clippedHeight = rect.bottom;
                }
                if (!handled && event.getActionMasked() == MotionEvent.ACTION_UP
                        && mIsSwipingVerticallyToClose) {
                    if (mSettleClosePercentage < mPercentageFromBottom && isTracking) {
                        animateNotificationPanel(DEFAULT_FLING_VELOCITY, false);
                    } else if (clippedHeight != mNotificationView.getHeight() && isTracking) {
                        // this can be caused when user is at the end of the list and trying to
                        // fling to top of the list by scrolling down.
                        animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);
                    }
                }

                // Updating the mNotificationListAtBottomAtTimeOfTouch state has to be done after
                // the event has been passed to the closeGestureDetector above, such that the
                // closeGestureDetector sees the up event before the state has changed.
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    mNotificationListAtBottomAtTimeOfTouch = false;
                }
                return handled || isTracking;
            }
        });

        mNotificationViewController = new NotificationViewController(
                mNotificationView,
                PreprocessingManager.getInstance(mContext),
                carNotificationListener,
                carUxRestrictionManagerWrapper,
                mNotificationDataManager);
        mNotificationViewController.enable();
    }

    /**
     * @return true if the notification panel is currently visible
     */
    boolean isNotificationPanelOpen() {
        return mPanelExpanded;
    }

    @Override
    public void animateExpandNotificationsPanel() {
        if (!mCommandQueue.panelsEnabled() || !mUserSetup) {
            return;
        }
        // scroll to top
        mNotificationList.scrollToPosition(0);
        mStatusBarWindowController.setPanelVisible(true);
        mNotificationView.setVisibility(View.VISIBLE);
        animateNotificationPanel(mOpeningVelocity, false);

        setPanelExpanded(true);
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force, boolean delayed,
            float speedUpFactor) {
        super.animateCollapsePanels(flags, force, delayed, speedUpFactor);
        if (!mPanelExpanded || mNotificationView.getVisibility() == View.INVISIBLE) {
            return;
        }
        mStatusBarWindowController.setStatusBarFocusable(false);
        mStatusBarWindow.cancelExpandHelper();
        mStatusBarView.collapsePanel(true /* animate */, delayed, speedUpFactor);

        animateNotificationPanel(mClosingVelocity, true);

        if (!mIsTracking) {
            mStatusBarWindowController.setPanelVisible(false);
            mNotificationView.setVisibility(View.INVISIBLE);
        }

        setPanelExpanded(false);
    }

    private void maybeCompleteAnimation(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                && mNotificationView.getVisibility() == View.VISIBLE) {
            if (mSettleClosePercentage < mPercentageFromBottom) {
                animateNotificationPanel(DEFAULT_FLING_VELOCITY, false);
            } else {
                animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);
            }
        }
    }

    /**
     * Animates the notification shade from one position to other. This is used to either open or
     * close the notification shade completely with a velocity. If the animation is to close the
     * notification shade this method also makes the view invisible after animation ends.
     */
    private void animateNotificationPanel(float velocity, boolean isClosing) {
        float to = 0;
        if (!isClosing) {
            to = mNotificationView.getHeight();
        }

        Rect rect = mNotificationView.getClipBounds();
        if (rect != null) {
            float from = rect.bottom;
            animate(from, to, velocity, isClosing);
            return;
        }

        // We will only be here if the shade is being opened programmatically.
        ViewTreeObserver notificationTreeObserver = mNotificationView.getViewTreeObserver();
        notificationTreeObserver.addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        ViewTreeObserver obs = mNotificationView.getViewTreeObserver();
                        obs.removeOnGlobalLayoutListener(this);
                        float to = mNotificationView.getHeight();
                        animate(/* from= */ 0, to, velocity, isClosing);
                    }
                });
    }

    private void animate(float from, float to, float velocity, boolean isClosing) {
        if (mIsNotificationAnimating) {
            return;
        }
        mIsNotificationAnimating = true;
        mIsTracking = true;
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.addUpdateListener(
                animation -> {
                    float animatedValue = (Float) animation.getAnimatedValue();
                    setNotificationViewClipBounds((int) animatedValue);
                });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mIsNotificationAnimating = false;
                mIsTracking = false;
                mOpeningVelocity = DEFAULT_FLING_VELOCITY;
                mClosingVelocity = DEFAULT_FLING_VELOCITY;
                if (isClosing) {
                    mStatusBarWindowController.setPanelVisible(false);
                    mNotificationView.setVisibility(View.INVISIBLE);
                    mNotificationView.setClipBounds(null);
                    mNotificationViewController.setIsInForeground(false);
                    // let the status bar know that the panel is closed
                    setPanelExpanded(false);
                } else {
                    mNotificationViewController.setIsInForeground(true);
                    // let the status bar know that the panel is open
                    mNotificationView.setVisibleNotificationsAsSeen();
                    setPanelExpanded(true);
                }
            }
        });
        mFlingAnimationUtils.apply(animator, from, to, Math.abs(velocity));
        animator.start();
    }

    @Override
    protected QS createDefaultQSFragment() {
        return new CarQSFragment();
    }

    private BatteryController createBatteryController() {
        mCarBatteryController = new CarBatteryController(mContext);
        mCarBatteryController.addBatteryViewHandler(this);
        return mCarBatteryController;
    }

    @Override
    protected void createNavigationBar(@Nullable RegisterStatusBarResult result) {
        mShowBottom = mContext.getResources().getBoolean(R.bool.config_enableBottomNavigationBar);
        mShowLeft = mContext.getResources().getBoolean(R.bool.config_enableLeftNavigationBar);
        mShowRight = mContext.getResources().getBoolean(R.bool.config_enableRightNavigationBar);

        buildNavBarWindows();
        buildNavBarContent();
        attachNavBarWindows();

        // Try setting up the initial state of the nav bar if applicable.
        if (result != null) {
            setImeWindowStatus(Display.DEFAULT_DISPLAY, result.mImeToken,
                    result.mImeWindowVis, result.mImeBackDisposition,
                    result.mShowImeSwitcher);
        }

        // There has been a car customized nav bar on the default display, so just create nav bars
        // on external displays.
        mNavigationBarController.createNavigationBars(false /* includeDefaultDisplay */, result);
    }

    private void buildNavBarContent() {
        if (mShowBottom) {
            buildBottomBar((mDeviceIsProvisioned) ? R.layout.car_navigation_bar :
                    R.layout.car_navigation_bar_unprovisioned);
        }

        if (mShowLeft) {
            buildLeft((mDeviceIsProvisioned) ? R.layout.car_left_navigation_bar :
                    R.layout.car_left_navigation_bar_unprovisioned);
        }

        if (mShowRight) {
            buildRight((mDeviceIsProvisioned) ? R.layout.car_right_navigation_bar :
                    R.layout.car_right_navigation_bar_unprovisioned);
        }
    }

    private void buildNavBarWindows() {
        if (mShowBottom) {
            mNavigationBarWindow = (ViewGroup) View.inflate(mContext,
                    R.layout.navigation_bar_window, null);
        }
        if (mShowLeft) {
            mLeftNavigationBarWindow = (ViewGroup) View.inflate(mContext,
                    R.layout.navigation_bar_window, null);
        }
        if (mShowRight) {
            mRightNavigationBarWindow = (ViewGroup) View.inflate(mContext,
                    R.layout.navigation_bar_window, null);
        }

    }

    /**
     * We register for soft keyboard visibility events such that we can hide the navigation bar
     * giving more screen space to the IME. Note: this is optional and controlled by
     * {@code com.android.internal.R.bool.config_automotiveHideNavBarForKeyboard}.
     */
    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        if (!mHideNavBarForKeyboard) {
            return;
        }

        if (mContext.getDisplay().getDisplayId() != displayId) {
            return;
        }

        boolean isKeyboardVisible = (vis & InputMethodService.IME_VISIBLE) != 0;
        if (!isKeyboardVisible) {
            attachBottomNavBarWindow();
        } else {
            detachBottomNavBarWindow();
        }
    }

    private void attachNavBarWindows() {
        attachBottomNavBarWindow();

        if (mShowLeft) {
            int width = mContext.getResources().getDimensionPixelSize(
                    R.dimen.car_left_navigation_bar_width);
            WindowManager.LayoutParams leftlp = new WindowManager.LayoutParams(
                    width, LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            leftlp.setTitle("LeftCarNavigationBar");
            leftlp.windowAnimations = 0;
            leftlp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_IS_SCREEN_DECOR;
            leftlp.gravity = Gravity.LEFT;
            mWindowManager.addView(mLeftNavigationBarWindow, leftlp);
        }
        if (mShowRight) {
            int width = mContext.getResources().getDimensionPixelSize(
                    R.dimen.car_right_navigation_bar_width);
            WindowManager.LayoutParams rightlp = new WindowManager.LayoutParams(
                    width, LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            rightlp.setTitle("RightCarNavigationBar");
            rightlp.windowAnimations = 0;
            rightlp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_IS_SCREEN_DECOR;
            rightlp.gravity = Gravity.RIGHT;
            mWindowManager.addView(mRightNavigationBarWindow, rightlp);
        }
    }

    /**
     * Attaches the bottom nav bar window. Can be extended to modify the specific behavior of
     * attaching the bottom nav bar.
     */
    protected void attachBottomNavBarWindow() {
        if (!mShowBottom) {
            return;
        }

        if (mBottomNavBarVisible) {
            return;
        }
        mBottomNavBarVisible = true;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("CarNavigationBar");
        lp.windowAnimations = 0;
        mWindowManager.addView(mNavigationBarWindow, lp);
    }

    /**
     * Detaches the bottom nav bar window. Can be extended to modify the specific behavior of
     * detaching the bottom nav bar.
     */
    protected void detachBottomNavBarWindow() {
        if (!mShowBottom) {
            return;
        }

        if (!mBottomNavBarVisible) {
            return;
        }
        mBottomNavBarVisible = false;
        mWindowManager.removeView(mNavigationBarWindow);
    }

    private void buildBottomBar(int layout) {
        // SystemUI requires that the navigation bar view have a parent. Since the regular
        // StatusBar inflates navigation_bar_window as this parent view, use the same view for the
        // CarNavigationBarView.
        View.inflate(mContext, layout, mNavigationBarWindow);
        mNavigationBarView = (CarNavigationBarView) mNavigationBarWindow.getChildAt(0);
        if (mNavigationBarView == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.car_navigation_bar");
            throw new RuntimeException("Unable to build botom nav bar due to missing layout");
        }
        mNavigationBarView.setStatusBar(this);
        addTemperatureViewToController(mNavigationBarView);
        mNavigationBarView.setStatusBarWindowTouchListener(mNavBarNotificationTouchListener);
    }

    private void buildLeft(int layout) {
        View.inflate(mContext, layout, mLeftNavigationBarWindow);
        mLeftNavigationBarView = (CarNavigationBarView) mLeftNavigationBarWindow.getChildAt(0);
        if (mLeftNavigationBarView == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.car_navigation_bar");
            throw new RuntimeException("Unable to build left nav bar due to missing layout");
        }
        mLeftNavigationBarView.setStatusBar(this);
        addTemperatureViewToController(mLeftNavigationBarView);
        mLeftNavigationBarView.setStatusBarWindowTouchListener(mNavBarNotificationTouchListener);
    }


    private void buildRight(int layout) {
        View.inflate(mContext, layout, mRightNavigationBarWindow);
        mRightNavigationBarView = (CarNavigationBarView) mRightNavigationBarWindow.getChildAt(0);
        if (mRightNavigationBarView == null) {
            Log.e(TAG, "CarStatusBar failed inflate for R.layout.car_navigation_bar");
            throw new RuntimeException("Unable to build right nav bar due to missing layout");
        }
        mRightNavigationBarView.setStatusBar(this);
        addTemperatureViewToController(mRightNavigationBarView);
        mRightNavigationBarView.setStatusBarWindowTouchListener(mNavBarNotificationTouchListener);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        //When executing dump() function simultaneously, we need to serialize them
        //to get mStackScroller's position correctly.
        synchronized (mQueueLock) {
            pw.println("  mStackScroller: " + viewInfo(mStackScroller));
            pw.println("  mStackScroller: " + viewInfo(mStackScroller)
                    + " scroll " + mStackScroller.getScrollX()
                    + "," + mStackScroller.getScrollY());
        }

        pw.print("  mTaskStackListener=");
        pw.println(mTaskStackListener);
        pw.print("  mCarFacetButtonController=");
        pw.println(mCarFacetButtonController);
        pw.print("  mFullscreenUserSwitcher=");
        pw.println(mFullscreenUserSwitcher);
        pw.print("  mCarBatteryController=");
        pw.println(mCarBatteryController);
        pw.print("  mBatteryMeterView=");
        pw.println(mBatteryMeterView);
        pw.print("  mNavigationBarView=");
        pw.println(mNavigationBarView);

        if (KeyguardUpdateMonitor.getInstance(mContext) != null) {
            KeyguardUpdateMonitor.getInstance(mContext).dump(fd, pw, args);
        }

        Dependency.get(FalsingManager.class).dump(pw);
        FalsingLog.dump(pw);

        pw.println("SharedPreferences:");
        for (Map.Entry<String, ?> entry : Prefs.getAll(mContext).entrySet()) {
            pw.print("  ");
            pw.print(entry.getKey());
            pw.print("=");
            pw.println(entry.getValue());
        }
    }

    @Override
    public void showBatteryView() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "showBatteryView(). mBatteryMeterView: " + mBatteryMeterView);
        }

        if (mBatteryMeterView != null) {
            mBatteryMeterView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void hideBatteryView() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "hideBatteryView(). mBatteryMeterView: " + mBatteryMeterView);
        }

        if (mBatteryMeterView != null) {
            mBatteryMeterView.setVisibility(View.GONE);
        }
    }

    /**
     * An implementation of TaskStackChangeListener, that listens for changes in the system
     * task stack and notifies the navigation bar.
     */
    private class TaskStackListenerImpl extends TaskStackChangeListener {
        @Override
        public void onTaskStackChanged() {
            try {
                mCarFacetButtonController.taskChanged(
                        ActivityTaskManager.getService().getAllStackInfos());
            } catch (Exception e) {
                Log.e(TAG, "Getting StackInfo from activity manager failed", e);
            }
        }

        @Override
        public void onTaskDisplayChanged(int taskId, int newDisplayId) {
            try {
                mCarFacetButtonController.taskChanged(
                        ActivityTaskManager.getService().getAllStackInfos());
            } catch (Exception e) {
                Log.e(TAG, "Getting StackInfo from activity manager failed", e);
            }
        }
    }

    private void onDrivingStateChanged(CarDrivingStateEvent notUsed) {
        // Check if we need to start the timer every time driving state changes.
        startSwitchToGuestTimerIfDrivingOnKeyguard();
    }

    private void startSwitchToGuestTimerIfDrivingOnKeyguard() {
        if (mDrivingStateHelper.isCurrentlyDriving() && mState != StatusBarState.SHADE) {
            // We're driving while keyguard is up.
            mSwitchToGuestTimer.start();
        } else {
            mSwitchToGuestTimer.cancel();
        }
    }

    @Override
    protected void createUserSwitcher() {
        UserSwitcherController userSwitcherController =
                Dependency.get(UserSwitcherController.class);
        if (userSwitcherController.useFullscreenUserSwitcher()) {
            Car car = Car.createCar(mContext);
            CarTrustAgentEnrollmentManager enrollmentManager = (CarTrustAgentEnrollmentManager) car
                    .getCarManager(Car.CAR_TRUST_AGENT_ENROLLMENT_SERVICE);
            mFullscreenUserSwitcher = new FullscreenUserSwitcher(this,
                    mStatusBarWindow.findViewById(R.id.fullscreen_user_switcher_stub),
                    enrollmentManager, mContext);
        } else {
            super.createUserSwitcher();
        }
    }

    @Override
    public void setLockscreenUser(int newUserId) {
        super.setLockscreenUser(newUserId);
        // Try to dismiss the keyguard after every user switch.
        dismissKeyguardWhenUserSwitcherNotDisplayed();
    }

    @Override
    public void onStateChanged(int newState) {
        super.onStateChanged(newState);

        startSwitchToGuestTimerIfDrivingOnKeyguard();

        if (newState != StatusBarState.FULLSCREEN_USER_SWITCHER) {
            hideUserSwitcher();
        } else {
            dismissKeyguardWhenUserSwitcherNotDisplayed();
        }
    }

    /** Makes the full screen user switcher visible, if applicable. */
    public void showUserSwitcher() {
        if (mFullscreenUserSwitcher != null && mState == StatusBarState.FULLSCREEN_USER_SWITCHER) {
            mFullscreenUserSwitcher.show(); // Makes the switcher visible.
        }
    }

    private void hideUserSwitcher() {
        if (mFullscreenUserSwitcher != null) {
            mFullscreenUserSwitcher.hide();
        }
    }

    final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurnedOn() {
            dismissKeyguardWhenUserSwitcherNotDisplayed();
        }
    };

    // We automatically dismiss keyguard unless user switcher is being shown on the keyguard.
    private void dismissKeyguardWhenUserSwitcherNotDisplayed() {
        if (mFullscreenUserSwitcher == null) {
            return; // Not using the full screen user switcher.
        }

        if (mState == StatusBarState.FULLSCREEN_USER_SWITCHER
                && !mFullscreenUserSwitcher.isVisible()) {
            // Current execution path continues to set state after this, thus we deffer the
            // dismissal to the next execution cycle.
            postDismissKeyguard(); // Dismiss the keyguard if switcher is not visible.
        }
    }

    public void postDismissKeyguard() {
        mHandler.post(this::dismissKeyguard);
    }

    /**
     * Dismisses the keyguard and shows bouncer if authentication is necessary.
     */
    public void dismissKeyguard() {
        // Don't dismiss keyguard when the screen is off.
        if (mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_OFF) {
            return;
        }
        executeRunnableDismissingKeyguard(null/* runnable */, null /* cancelAction */,
                true /* dismissShade */, true /* afterKeyguardGone */, true /* deferred */);
    }

    /**
     * Ensures that relevant child views are appropriately recreated when the device's density
     * changes.
     */
    @Override
    public void onDensityOrFontScaleChanged() {
        super.onDensityOrFontScaleChanged();
        restartNavBars();
        // Need to update the background on density changed in case the change was due to night
        // mode.
        mNotificationPanelBackground = getDefaultWallpaper();
        mScrimController.setScrimBehindDrawable(mNotificationPanelBackground);
    }

    /**
     * Returns the {@link Drawable} that represents the wallpaper that the user has currently set.
     */
    private Drawable getDefaultWallpaper() {
        return mContext.getDrawable(com.android.internal.R.drawable.default_wallpaper);
    }

    private void setNotificationViewClipBounds(int height) {
        if (height > mNotificationView.getHeight()) {
            height = mNotificationView.getHeight();
        }
        Rect clipBounds = new Rect();
        clipBounds.set(0, 0, mNotificationView.getWidth(), height);
        // Sets the clip region on the notification list view.
        mNotificationView.setClipBounds(clipBounds);
        if (mHandleBar != null) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) mHandleBar.getLayoutParams();
            mHandleBar.setTranslationY(height - mHandleBar.getHeight() - lp.bottomMargin);
        }
        if (mNotificationView.getHeight() > 0) {
            // Calculates the alpha value for the background based on how much of the notification
            // shade is visible to the user. When the notification shade is completely open then
            // alpha value will be 1.
            float alpha = (float) height / mNotificationView.getHeight();
            Drawable background = mNotificationView.getBackground();

            background.setAlpha((int) (alpha * 255));
        }
    }

    private void calculatePercentageFromBottom(float height) {
        if (mNotificationView.getHeight() > 0) {
            mPercentageFromBottom = (int) Math.abs(
                    height / mNotificationView.getHeight() * 100);
        }
    }

    private static final int SWIPE_DOWN_MIN_DISTANCE = 25;
    private static final int SWIPE_MAX_OFF_PATH = 75;
    private static final int SWIPE_THRESHOLD_VELOCITY = 200;

    /**
     * Only responsible for open hooks. Since once the panel opens it covers all elements
     * there is no need to merge with close.
     */
    private abstract class OpenNotificationGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {

            if (mNotificationView.getVisibility() == View.INVISIBLE) {
                // when the on-scroll is called for the first time to open.
                mNotificationList.scrollToPosition(0);
            }
            mStatusBarWindowController.setPanelVisible(true);
            mNotificationView.setVisibility(View.VISIBLE);

            // clips the view for the notification shade when the user scrolls to open.
            setNotificationViewClipBounds((int) event2.getRawY());

            // Initially the scroll starts with height being zero. This checks protects from divide
            // by zero error.
            calculatePercentageFromBottom(event2.getRawY());

            mIsTracking = true;
            return true;
        }


        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            if (velocityY > SWIPE_THRESHOLD_VELOCITY) {
                mOpeningVelocity = velocityY;
                openNotification();
                return true;
            }
            animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);

            return false;
        }

        protected abstract void openNotification();
    }

    /**
     * To be installed on the open panel notification panel
     */
    private abstract class CloseNotificationGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapUp(MotionEvent motionEvent) {
            if (mPanelExpanded) {
                animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            // should not clip while scroll to the bottom of the list.
            if (!mNotificationListAtBottomAtTimeOfTouch) {
                return false;
            }
            float actualNotificationHeight =
                    mNotificationView.getHeight() - (event1.getRawY() - event2.getRawY());
            if (actualNotificationHeight > mNotificationView.getHeight()) {
                actualNotificationHeight = mNotificationView.getHeight();
            }
            if (mNotificationView.getHeight() > 0) {
                mPercentageFromBottom = (int) Math.abs(
                        actualNotificationHeight / mNotificationView.getHeight() * 100);
                boolean isUp = distanceY > 0;

                // This check is to figure out if onScroll was called while swiping the card at
                // bottom of the list. At that time we should not allow notification shade to
                // close. We are also checking for the upwards swipe gesture here because it is
                // possible if a user is closing the notification shade and while swiping starts
                // to open again but does not fling. At that time we should allow the
                // notification shade to close fully or else it would stuck in between.
                if (Math.abs(mNotificationView.getHeight() - actualNotificationHeight)
                        > SWIPE_DOWN_MIN_DISTANCE && isUp) {
                    setNotificationViewClipBounds((int) actualNotificationHeight);
                    mIsTracking = true;
                } else if (!isUp) {
                    setNotificationViewClipBounds((int) actualNotificationHeight);
                }
            }
            // if we return true the the items in RV won't be scrollable.
            return false;
        }


        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            // should not fling if the touch does not start when view is at the bottom of the list.
            if (!mNotificationListAtBottomAtTimeOfTouch) {
                return false;
            }
            if (Math.abs(event1.getX() - event2.getX()) > SWIPE_MAX_OFF_PATH
                    || Math.abs(velocityY) < SWIPE_THRESHOLD_VELOCITY) {
                // swipe was not vertical or was not fast enough
                return false;
            }
            boolean isUp = velocityY < 0;
            if (isUp) {
                close();
                return true;
            } else {
                // we should close the shade
                animateNotificationPanel(velocityY, false);
            }
            return false;
        }

        protected abstract void close();
    }

    /**
     * To be installed on the nav bars.
     */
    private abstract class NavBarCloseNotificationGestureListener extends
            CloseNotificationGestureListener {
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            mClosingVelocity = DEFAULT_FLING_VELOCITY;
            if (mPanelExpanded) {
                close();
            }
            return super.onSingleTapUp(e);
        }

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            calculatePercentageFromBottom(event2.getRawY());
            setNotificationViewClipBounds((int) event2.getRawY());
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            mClosingVelocity = DEFAULT_FLING_VELOCITY;
            close();
            super.onLongPress(e);
        }
    }

    /**
     * To be installed on the handle bar.
     */
    private class HandleBarCloseNotificationGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            calculatePercentageFromBottom(event2.getRawY());
            // To prevent the jump in the clip bounds while closing the notification shade using
            // the handle bar we should calculate the height using the diff of event1 and event2.
            // This will help the notification shade to clip smoothly as the event2 value changes
            // as event1 value will be fixed.
            int clipHeight =
                    mNotificationView.getHeight() - (int) (event1.getRawY() - event2.getRawY());
            setNotificationViewClipBounds(clipHeight);
            return true;
        }
    }

    /**
     * SystemUi version of the notification manager that overrides methods such that the
     * notifications end up in the status bar layouts instead of a standalone window.
     */
    private class CarSystemUIHeadsUpNotificationManager extends CarHeadsUpNotificationManager {

        CarSystemUIHeadsUpNotificationManager(Context context,
                NotificationClickHandlerFactory clickHandlerFactory,
                NotificationDataManager notificationDataManager) {
            super(context, clickHandlerFactory, notificationDataManager);
        }

        @Override
        protected View createHeadsUpPanel() {
            // In SystemUi the view is already in the window so just return a reference.
            return mStatusBarWindow.findViewById(R.id.notification_headsup);
        }

        @Override
        protected void addHeadsUpPanelToDisplay() {
            // Set the panel initial state to invisible
            mHeadsUpPanel.setVisibility(View.INVISIBLE);
        }

        @Override
        protected void setInternalInsetsInfo(ViewTreeObserver.InternalInsetsInfo info,
                HeadsUpEntry currentNotification, boolean panelExpanded) {
            super.setInternalInsetsInfo(info, currentNotification, mPanelExpanded);
        }

        @Override
        protected void setHeadsUpVisible() {
            // if the Notifications panel is showing don't show the Heads up
            if (!mEnableHeadsUpNotificationWhenNotificationShadeOpen && mPanelExpanded) {
                return;
            }

            super.setHeadsUpVisible();
            if (mHeadsUpPanel.getVisibility() == View.VISIBLE) {
                mStatusBarWindowController.setHeadsUpShowing(true);
                mStatusBarWindowController.setForceStatusBarVisible(true);
            }
        }

        @Override
        protected void removeNotificationFromPanel(HeadsUpEntry currentHeadsUpNotification) {
            super.removeNotificationFromPanel(currentHeadsUpNotification);
            // If the panel ended up empty and hidden we can remove it from SystemUi
            if (mHeadsUpPanel.getVisibility() != View.VISIBLE) {
                mStatusBarWindowController.setHeadsUpShowing(false);
                mStatusBarWindowController.setForceStatusBarVisible(false);
            }
        }
    }
}
