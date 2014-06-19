/*
 * Copyright (C) 2012 The Android Open Source Project
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

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.KeyguardUpdateMonitor.DisplayClientState;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.admin.DevicePolicyManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Rect;
import android.media.RemoteControlClient;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.RemoteViews.OnClickHandler;

import java.lang.ref.WeakReference;

public class KeyguardHostView extends KeyguardViewBase {
    private static final String TAG = "KeyguardHostView";
    public static boolean DEBUG = KeyguardConstants.DEBUG;
    public static boolean DEBUGXPORT = true; // debug music transport control

    // Transport control states.
    static final int TRANSPORT_GONE = 0;
    static final int TRANSPORT_INVISIBLE = 1;
    static final int TRANSPORT_VISIBLE = 2;

    private int mTransportState = TRANSPORT_GONE;

    // Found in KeyguardAppWidgetPickActivity.java
    static final int APPWIDGET_HOST_ID = 0x4B455947;
    private final int MAX_WIDGETS = 5;

    private AppWidgetHost mAppWidgetHost;
    private AppWidgetManager mAppWidgetManager;
    private KeyguardWidgetPager mAppWidgetContainer;
    // TODO remove transport control references, these don't exist anymore
    private KeyguardTransportControlView mTransportControl;
    private int mAppWidgetToShow;

    protected int mFailedAttempts;

    private KeyguardViewStateManager mViewStateManager;

    private Rect mTempRect = new Rect();
    private int mDisabledFeatures;
    private boolean mCameraDisabled;
    private boolean mSafeModeEnabled;
    private boolean mUserSetupCompleted;

    // User for whom this host view was created.  Final because we should never change the
    // id without reconstructing an instance of KeyguardHostView. See note below...
    private final int mUserId;

    private KeyguardMultiUserSelectorView mKeyguardMultiUserSelectorView;

    protected int mClientGeneration;

    protected boolean mShowSecurityWhenReturn;

    private final Rect mInsets = new Rect();

    private MyOnClickHandler mOnClickHandler = new MyOnClickHandler(this);

    private Runnable mPostBootCompletedRunnable;

    /*package*/ interface UserSwitcherCallback {
        void hideSecurityView(int duration);
        void showSecurityView();
        void showUnlockHint();
        void userActivity();
    }

    interface TransportControlCallback {
        void userActivity();
    }

    public interface OnDismissAction {
        /**
         * @return true if the dismiss should be deferred
         */
        boolean onDismiss();
    }

    public KeyguardHostView(Context context) {
        this(context, null);
    }

    public KeyguardHostView(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (DEBUG) Log.e(TAG, "KeyguardHostView()");

        mLockPatternUtils = new LockPatternUtils(context);

        // Note: This depends on KeyguardHostView getting reconstructed every time the
        // user switches, since mUserId will be used for the entire session.
        // Once created, keyguard should *never* re-use this instance with another user.
        // In other words, mUserId should never change - hence it's marked final.
        mUserId = mLockPatternUtils.getCurrentUser();

        DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            mDisabledFeatures = getDisabledFeatures(dpm);
            mCameraDisabled = dpm.getCameraDisabled(null);
        }

        mSafeModeEnabled = LockPatternUtils.isSafeModeEnabled();

        // These need to be created with the user context...
        Context userContext = null;
        try {
            final String packageName = "system";
            userContext = mContext.createPackageContextAsUser(packageName, 0,
                    new UserHandle(mUserId));

        } catch (NameNotFoundException e) {
            e.printStackTrace();
            // This should never happen, but it's better to have no widgets than to crash.
            userContext = context;
        }

        mAppWidgetHost = new AppWidgetHost(userContext, APPWIDGET_HOST_ID, mOnClickHandler,
                Looper.myLooper());

        mAppWidgetManager = AppWidgetManager.getInstance(userContext);

        mViewStateManager = new KeyguardViewStateManager(this);

        mUserSetupCompleted = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_CURRENT) != 0;

        // Ensure we have the current state *before* we call showAppropriateWidgetPage()
        getInitialTransportState();

        if (mSafeModeEnabled) {
            Log.v(TAG, "Keyguard widgets disabled by safe mode");
        }
        if ((mDisabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL) != 0) {
            Log.v(TAG, "Keyguard widgets disabled by DPM");
        }
        if ((mDisabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0) {
            Log.v(TAG, "Keyguard secure camera disabled by DPM");
        }
    }

    private void getInitialTransportState() {
        DisplayClientState dcs = KeyguardUpdateMonitor.getInstance(mContext)
                .getCachedDisplayClientState();
        mTransportState = (dcs.clearing ? TRANSPORT_GONE :
            (isMusicPlaying(dcs.playbackState) ? TRANSPORT_VISIBLE : TRANSPORT_INVISIBLE));

        if (DEBUGXPORT) Log.v(TAG, "Initial transport state: "
                + mTransportState + ", pbstate=" + dcs.playbackState);
    }

    private void cleanupAppWidgetIds() {
        if (mSafeModeEnabled || widgetsDisabled()) return;

        // Clean up appWidgetIds that are bound to lockscreen, but not actually used
        // This is only to clean up after another bug: we used to not call
        // deleteAppWidgetId when a user manually deleted a widget in keyguard. This code
        // shouldn't have to run more than once per user. AppWidgetProviders rely on callbacks
        // that are triggered by deleteAppWidgetId, which is why we're doing this
        int[] appWidgetIdsInKeyguardSettings = mLockPatternUtils.getAppWidgets();
        int[] appWidgetIdsBoundToHost = mAppWidgetHost.getAppWidgetIds();
        for (int i = 0; i < appWidgetIdsBoundToHost.length; i++) {
            int appWidgetId = appWidgetIdsBoundToHost[i];
            if (!contains(appWidgetIdsInKeyguardSettings, appWidgetId)) {
                Log.d(TAG, "Found a appWidgetId that's not being used by keyguard, deleting id "
                        + appWidgetId);
                mAppWidgetHost.deleteAppWidgetId(appWidgetId);
            }
        }
    }

    private static boolean contains(int[] array, int target) {
        for (int value : array) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private KeyguardUpdateMonitorCallback mUpdateMonitorCallbacks =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onBootCompleted() {
            if (mPostBootCompletedRunnable != null) {
                mPostBootCompletedRunnable.run();
                mPostBootCompletedRunnable = null;
            }
        }
        @Override
        public void onUserSwitchComplete(int userId) {
            if (mKeyguardMultiUserSelectorView != null) {
                mKeyguardMultiUserSelectorView.finalizeActiveUserView(true);
            }
        }
    };

    private static final boolean isMusicPlaying(int playbackState) {
        // This should agree with the list in AudioService.isPlaystateActive()
        switch (playbackState) {
            case RemoteControlClient.PLAYSTATE_PLAYING:
            case RemoteControlClient.PLAYSTATE_BUFFERING:
            case RemoteControlClient.PLAYSTATE_FAST_FORWARDING:
            case RemoteControlClient.PLAYSTATE_REWINDING:
            case RemoteControlClient.PLAYSTATE_SKIPPING_BACKWARDS:
            case RemoteControlClient.PLAYSTATE_SKIPPING_FORWARDS:
                return true;
            default:
                return false;
        }
    }

    private SlidingChallengeLayout mSlidingChallengeLayout;
    private MultiPaneChallengeLayout mMultiPaneChallengeLayout;

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean result = super.onTouchEvent(ev);
        mTempRect.set(0, 0, 0, 0);
        offsetRectIntoDescendantCoords(getSecurityContainer(), mTempRect);
        ev.offsetLocation(mTempRect.left, mTempRect.top);
        result = getSecurityContainer().dispatchTouchEvent(ev) || result;
        ev.offsetLocation(-mTempRect.left, -mTempRect.top);
        return result;
    }

    private int getWidgetPosition(int id) {
        final KeyguardWidgetPager appWidgetContainer = mAppWidgetContainer;
        final int children = appWidgetContainer.getChildCount();
        for (int i = 0; i < children; i++) {
            final View content = appWidgetContainer.getWidgetPageAt(i).getContent();
            if (content != null && content.getId() == id) {
                return i;
            } else if (content == null) {
                // Attempt to track down bug #8886916
                Log.w(TAG, "*** Null content at " + "i=" + i + ",id=" + id + ",N=" + children);
            }
        }
        return -1;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        // Grab instances of and make any necessary changes to the main layouts. Create
        // view state manager and wire up necessary listeners / callbacks.
        View deleteDropTarget = findViewById(R.id.keyguard_widget_pager_delete_target);
        mAppWidgetContainer = (KeyguardWidgetPager) findViewById(R.id.app_widget_container);
        mAppWidgetContainer.setVisibility(VISIBLE);
        mAppWidgetContainer.setCallbacks(mWidgetCallbacks);
        mAppWidgetContainer.setDeleteDropTarget(deleteDropTarget);
        mAppWidgetContainer.setMinScale(0.5f);

        mSlidingChallengeLayout = (SlidingChallengeLayout) findViewById(R.id.sliding_layout);
        if (mSlidingChallengeLayout != null) {
            mSlidingChallengeLayout.setOnChallengeScrolledListener(mViewStateManager);
        }
        mAppWidgetContainer.setViewStateManager(mViewStateManager);
        mAppWidgetContainer.setLockPatternUtils(mLockPatternUtils);

        mMultiPaneChallengeLayout =
                (MultiPaneChallengeLayout) findViewById(R.id.multi_pane_challenge);
        ChallengeLayout challenge = mSlidingChallengeLayout != null ? mSlidingChallengeLayout :
                mMultiPaneChallengeLayout;
        challenge.setOnBouncerStateChangedListener(mViewStateManager);
        mAppWidgetContainer.setBouncerAnimationDuration(challenge.getBouncerAnimationDuration());
        mViewStateManager.setPagedView(mAppWidgetContainer);
        mViewStateManager.setChallengeLayout(challenge);

        mViewStateManager.setSecurityViewContainer(getSecurityContainer());

        if (KeyguardUpdateMonitor.getInstance(mContext).hasBootCompleted()) {
            updateAndAddWidgets();
        } else {
            // We can't add widgets until after boot completes because AppWidgetHost may try
            // to contact the providers.  Do it later.
            mPostBootCompletedRunnable = new Runnable() {
                @Override
                public void run() {
                    updateAndAddWidgets();
                }
            };
        }

        getSecurityContainer().updateSecurityViews(mViewStateManager.isBouncing());
        enableUserSelectorIfNecessary();
    }

    private void updateAndAddWidgets() {
        cleanupAppWidgetIds();
        addDefaultWidgets();
        addWidgetsFromSettings();
        maybeEnableAddButton();
        checkAppWidgetConsistency();

        // Don't let the user drag the challenge down if widgets are disabled.
        if (mSlidingChallengeLayout != null) {
            mSlidingChallengeLayout.setEnableChallengeDragging(!widgetsDisabled());
        }

        // Select the appropriate page
        mSwitchPageRunnable.run();

        // This needs to be called after the pages are all added.
        mViewStateManager.showUsabilityHints();
    }

    private void maybeEnableAddButton() {
        if (!shouldEnableAddWidget()) {
            mAppWidgetContainer.setAddWidgetEnabled(false);
        }
    }

    private boolean shouldEnableAddWidget() {
        return numWidgets() < MAX_WIDGETS && mUserSetupCompleted;
    }

    @Override
    public boolean dismiss(boolean authenticated) {
        boolean finished = super.dismiss(authenticated);
        if (!finished) {
            mViewStateManager.showBouncer(true);

            // Enter full screen mode if we're in SIM or Account screen
            SecurityMode securityMode = getSecurityContainer().getSecurityMode();
            boolean isFullScreen = getResources().getBoolean(R.bool.kg_sim_puk_account_full_screen);
            boolean isSimOrAccount = securityMode == SecurityMode.SimPin
                    || securityMode == SecurityMode.SimPuk
                    || securityMode == SecurityMode.Account;
            mAppWidgetContainer.setVisibility(
                    isSimOrAccount && isFullScreen ? View.GONE : View.VISIBLE);

            // Don't show camera or search in navbar when SIM or Account screen is showing
            setSystemUiVisibility(isSimOrAccount ?
                    (getSystemUiVisibility() | View.STATUS_BAR_DISABLE_SEARCH)
                    : (getSystemUiVisibility() & ~View.STATUS_BAR_DISABLE_SEARCH));

            if (mSlidingChallengeLayout != null) {
                mSlidingChallengeLayout.setChallengeInteractive(!isFullScreen);
            }
        }
        return finished;
    }

    private int getDisabledFeatures(DevicePolicyManager dpm) {
        int disabledFeatures = DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE;
        if (dpm != null) {
            final int currentUser = mLockPatternUtils.getCurrentUser();
            disabledFeatures = dpm.getKeyguardDisabledFeatures(null, currentUser);
        }
        return disabledFeatures;
    }

    private boolean widgetsDisabled() {
        boolean disabledByLowRamDevice = ActivityManager.isLowRamDeviceStatic();
        boolean disabledByDpm =
                (mDisabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL) != 0;
        boolean disabledByUser = !mLockPatternUtils.getWidgetsEnabled();
        return disabledByLowRamDevice || disabledByDpm || disabledByUser;
    }

    private boolean cameraDisabledByDpm() {
        return mCameraDisabled
                || (mDisabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0;
    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {
        super.setLockPatternUtils(utils);
        getSecurityContainer().updateSecurityViews(mViewStateManager.isBouncing());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAppWidgetHost.startListening();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateMonitorCallbacks);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAppWidgetHost.stopListening();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mUpdateMonitorCallbacks);
    }

    void addWidget(AppWidgetHostView view, int pageIndex) {
        mAppWidgetContainer.addWidget(view, pageIndex);
    }

    private KeyguardWidgetPager.Callbacks mWidgetCallbacks
            = new KeyguardWidgetPager.Callbacks() {
        @Override
        public void userActivity() {
            KeyguardHostView.this.userActivity();
        }

        @Override
        public void onUserActivityTimeoutChanged() {
            KeyguardHostView.this.onUserActivityTimeoutChanged();
        }

        @Override
        public void onAddView(View v) {
            if (!shouldEnableAddWidget()) {
                mAppWidgetContainer.setAddWidgetEnabled(false);
            }
        }

        @Override
        public void onRemoveView(View v, boolean deletePermanently) {
            if (deletePermanently) {
                final int appWidgetId = ((KeyguardWidgetFrame) v).getContentAppWidgetId();
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
                        appWidgetId != LockPatternUtils.ID_DEFAULT_STATUS_WIDGET) {
                    mAppWidgetHost.deleteAppWidgetId(appWidgetId);
                }
            }
        }

        @Override
        public void onRemoveViewAnimationCompleted() {
            if (shouldEnableAddWidget()) {
                mAppWidgetContainer.setAddWidgetEnabled(true);
            }
        }
    };

    @Override
    public void onUserSwitching(boolean switching) {
        if (!switching && mKeyguardMultiUserSelectorView != null) {
            mKeyguardMultiUserSelectorView.finalizeActiveUserView(false);
        }
    }

    public void userActivity() {
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.userActivity();
        }
    }

    public void onUserActivityTimeoutChanged() {
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.onUserActivityTimeoutChanged();
        }
    }

    @Override
    public long getUserActivityTimeout() {
        // Currently only considering user activity timeouts needed by widgets.
        // Could also take into account longer timeouts for certain security views.
        if (mAppWidgetContainer != null) {
            return mAppWidgetContainer.getUserActivityTimeout();
        }
        return -1;
    }

    private static class MyOnClickHandler extends OnClickHandler {

        // weak reference to the hostView to avoid keeping a live reference
        // due to Binder GC linkages to AppWidgetHost. By the same token,
        // this click handler should not keep references to any large
        // objects.
        WeakReference<KeyguardHostView> mKeyguardHostView;

        MyOnClickHandler(KeyguardHostView hostView) {
            mKeyguardHostView = new WeakReference<KeyguardHostView>(hostView);
        }

        @Override
        public boolean onClickHandler(final View view,
                final android.app.PendingIntent pendingIntent,
                final Intent fillInIntent) {
            KeyguardHostView hostView = mKeyguardHostView.get();
            if (hostView == null) {
                return false;
            }
            if (pendingIntent.isActivity()) {
                hostView.setOnDismissAction(new OnDismissAction() {
                    public boolean onDismiss() {
                        try {
                            // TODO: Unregister this handler if PendingIntent.FLAG_ONE_SHOT?
                            Context context = view.getContext();
                            ActivityOptions opts = ActivityOptions.makeScaleUpAnimation(view,
                                    0, 0,
                                    view.getMeasuredWidth(), view.getMeasuredHeight());
                            context.startIntentSender(
                                    pendingIntent.getIntentSender(), fillInIntent,
                                    Intent.FLAG_ACTIVITY_NEW_TASK,
                                    Intent.FLAG_ACTIVITY_NEW_TASK, 0, opts.toBundle());
                        } catch (IntentSender.SendIntentException e) {
                            android.util.Log.e(TAG, "Cannot send pending intent: ", e);
                        } catch (Exception e) {
                            android.util.Log.e(TAG, "Cannot send pending intent due to " +
                                    "unknown exception: ", e);
                        }
                        return false;
                    }
                });

                if (hostView.mViewStateManager.isChallengeShowing()) {
                    hostView.mViewStateManager.showBouncer(true);
                } else {
                    hostView.dismiss();
                }
                return true;
            } else {
                return super.onClickHandler(view, pendingIntent, fillInIntent);
            }
        };
    };

    @Override
    public void onResume() {
        super.onResume();
        if (mViewStateManager != null) {
            mViewStateManager.showUsabilityHints();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // We use mAppWidgetToShow to show a particular widget after you add it-- once the screen
        // turns off we reset that behavior
        clearAppWidgetToShow();
        if (KeyguardUpdateMonitor.getInstance(mContext).hasBootCompleted()) {
            checkAppWidgetConsistency();
        }
        CameraWidgetFrame cameraPage = findCameraPage();
        if (cameraPage != null) {
            cameraPage.onScreenTurnedOff();
        }
    }

    public void clearAppWidgetToShow() {
        mAppWidgetToShow = AppWidgetManager.INVALID_APPWIDGET_ID;
    }

    private boolean addWidget(int appId, int pageIndex, boolean updateDbIfFailed) {
        AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appId);
        if (appWidgetInfo != null) {
            AppWidgetHostView view = mAppWidgetHost.createView(mContext, appId, appWidgetInfo);
            addWidget(view, pageIndex);
            return true;
        } else {
            if (updateDbIfFailed) {
                Log.w(TAG, "*** AppWidgetInfo for app widget id " + appId + "  was null for user"
                        + mUserId + ", deleting");
                mAppWidgetHost.deleteAppWidgetId(appId);
                mLockPatternUtils.removeAppWidget(appId);
            }
            return false;
        }
    }

    private final CameraWidgetFrame.Callbacks mCameraWidgetCallbacks =
        new CameraWidgetFrame.Callbacks() {
            @Override
            public void onLaunchingCamera() {
                setSliderHandleAlpha(0);
            }

            @Override
            public void onCameraLaunchedSuccessfully() {
                if (mAppWidgetContainer.isCameraPage(mAppWidgetContainer.getCurrentPage())) {
                    mAppWidgetContainer.scrollLeft();
                }
                setSliderHandleAlpha(1);
                mShowSecurityWhenReturn = true;
            }

            @Override
            public void onCameraLaunchedUnsuccessfully() {
                setSliderHandleAlpha(1);
            }

            private void setSliderHandleAlpha(float alpha) {
                SlidingChallengeLayout slider =
                        (SlidingChallengeLayout) findViewById(R.id.sliding_layout);
                if (slider != null) {
                    slider.setHandleAlpha(alpha);
                }
            }
        };

    private int numWidgets() {
        final int childCount = mAppWidgetContainer.getChildCount();
        int widgetCount = 0;
        for (int i = 0; i < childCount; i++) {
            if (mAppWidgetContainer.isWidgetPage(i)) {
                widgetCount++;
            }
        }
        return widgetCount;
    }

    private void addDefaultWidgets() {
        if (!mSafeModeEnabled && !widgetsDisabled()) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            View addWidget = inflater.inflate(R.layout.keyguard_add_widget, this, false);
            mAppWidgetContainer.addWidget(addWidget, 0);
            View addWidgetButton = addWidget.findViewById(R.id.keyguard_add_widget_view);
            addWidgetButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Pass in an invalid widget id... the picker will allocate an ID for us
                    getActivityLauncher().launchWidgetPicker(AppWidgetManager.INVALID_APPWIDGET_ID);
                }
            });
        }

        // We currently disable cameras in safe mode because we support loading 3rd party
        // cameras we can't trust.  TODO: plumb safe mode into camera creation code and only
        // inflate system-provided camera?
        if (!mSafeModeEnabled && !cameraDisabledByDpm() && mUserSetupCompleted
                && mContext.getResources().getBoolean(R.bool.kg_enable_camera_default_widget)) {
            View cameraWidget = CameraWidgetFrame.create(mContext, mCameraWidgetCallbacks,
                    getActivityLauncher());
            if (cameraWidget != null) {
                mAppWidgetContainer.addWidget(cameraWidget);
            }
        }
    }

    /**
     * Create KeyguardTransportControlView on demand.
     * @return
     */
    private KeyguardTransportControlView getOrCreateTransportControl() {
        if (mTransportControl == null) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            mTransportControl = (KeyguardTransportControlView)
                    inflater.inflate(R.layout.keyguard_transport_control_view, this, false);
            mTransportControl.setTransportControlCallback(new TransportControlCallback() {
                public void userActivity() {
                    mViewMediatorCallback.userActivity();
                }
            });
        }
        return mTransportControl;
    }

    private int getInsertPageIndex() {
        View addWidget = mAppWidgetContainer.findViewById(R.id.keyguard_add_widget);
        int insertionIndex = mAppWidgetContainer.indexOfChild(addWidget);
        if (insertionIndex < 0) {
            insertionIndex = 0; // no add widget page found
        } else {
            insertionIndex++; // place after add widget
        }
        return insertionIndex;
    }

    private void addDefaultStatusWidget(int index) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        View statusWidget = inflater.inflate(R.layout.keyguard_status_view, null, true);
        mAppWidgetContainer.addWidget(statusWidget, index);
    }

    private void addWidgetsFromSettings() {
        if (mSafeModeEnabled || widgetsDisabled()) {
            addDefaultStatusWidget(0);
            return;
        }

        int insertionIndex = getInsertPageIndex();

        // Add user-selected widget
        final int[] widgets = mLockPatternUtils.getAppWidgets();

        if (widgets == null) {
            Log.d(TAG, "Problem reading widgets");
        } else {
            for (int i = widgets.length -1; i >= 0; i--) {
                if (widgets[i] == LockPatternUtils.ID_DEFAULT_STATUS_WIDGET) {
                    addDefaultStatusWidget(insertionIndex);
                } else {
                    // We add the widgets from left to right, starting after the first page after
                    // the add page. We count down, since the order will be persisted from right
                    // to left, starting after camera.
                    addWidget(widgets[i], insertionIndex, true);
                }
            }
        }
    }

    private int allocateIdForDefaultAppWidget() {
        int appWidgetId;
        Resources res = getContext().getResources();
        ComponentName defaultAppWidget = new ComponentName(
                res.getString(R.string.widget_default_package_name),
                res.getString(R.string.widget_default_class_name));

        // Note: we don't support configuring the widget
        appWidgetId = mAppWidgetHost.allocateAppWidgetId();

        try {
            mAppWidgetManager.bindAppWidgetId(appWidgetId, defaultAppWidget);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Error when trying to bind default AppWidget: " + e);
            mAppWidgetHost.deleteAppWidgetId(appWidgetId);
            appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        }
        return appWidgetId;
    }

    public void checkAppWidgetConsistency() {
        final int childCount = mAppWidgetContainer.getChildCount();
        boolean widgetPageExists = false;
        for (int i = 0; i < childCount; i++) {
            if (mAppWidgetContainer.isWidgetPage(i)) {
                widgetPageExists = true;
                break;
            }
        }
        if (!widgetPageExists) {
            final int insertPageIndex = getInsertPageIndex();

            final boolean userAddedWidgetsEnabled = !widgetsDisabled();

            boolean addedDefaultAppWidget = false;

            if (!mSafeModeEnabled) {
                if (userAddedWidgetsEnabled) {
                    int appWidgetId = allocateIdForDefaultAppWidget();
                    if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        addedDefaultAppWidget = addWidget(appWidgetId, insertPageIndex, true);
                    }
                } else {
                    // note: even if widgetsDisabledByDpm() returns true, we still bind/create
                    // the default appwidget if possible
                    int appWidgetId = mLockPatternUtils.getFallbackAppWidgetId();
                    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                        appWidgetId = allocateIdForDefaultAppWidget();
                        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            mLockPatternUtils.writeFallbackAppWidgetId(appWidgetId);
                        }
                    }
                    if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        addedDefaultAppWidget = addWidget(appWidgetId, insertPageIndex, false);
                        if (!addedDefaultAppWidget) {
                            mAppWidgetHost.deleteAppWidgetId(appWidgetId);
                            mLockPatternUtils.writeFallbackAppWidgetId(
                                    AppWidgetManager.INVALID_APPWIDGET_ID);
                        }
                    }
                }
            }

            // Use the built-in status/clock view if we can't inflate the default widget
            if (!addedDefaultAppWidget) {
                addDefaultStatusWidget(insertPageIndex);
            }

            // trigger DB updates only if user-added widgets are enabled
            if (!mSafeModeEnabled && userAddedWidgetsEnabled) {
                mAppWidgetContainer.onAddView(
                        mAppWidgetContainer.getChildAt(insertPageIndex), insertPageIndex);
            }
        }
    }

    private final Runnable mSwitchPageRunnable = new Runnable() {
        @Override
        public void run() {
           showAppropriateWidgetPage();
        }
    };

    static class SavedState extends BaseSavedState {
        int transportState;
        int appWidgetToShow = AppWidgetManager.INVALID_APPWIDGET_ID;
        Rect insets = new Rect();

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            this.transportState = in.readInt();
            this.appWidgetToShow = in.readInt();
            this.insets = in.readParcelable(null);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(this.transportState);
            out.writeInt(this.appWidgetToShow);
            out.writeParcelable(insets, 0);
        }

        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    @Override
    public Parcelable onSaveInstanceState() {
        if (DEBUG) Log.d(TAG, "onSaveInstanceState, tstate=" + mTransportState);
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        // If the transport is showing, force it to show it on restore.
        final boolean showing = mTransportControl != null
                && mAppWidgetContainer.getWidgetPageIndex(mTransportControl) >= 0;
        ss.transportState =  showing ? TRANSPORT_VISIBLE : mTransportState;
        ss.appWidgetToShow = mAppWidgetToShow;
        ss.insets.set(mInsets);
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        mTransportState = (ss.transportState);
        mAppWidgetToShow = ss.appWidgetToShow;
        setInsets(ss.insets);
        if (DEBUG) Log.d(TAG, "onRestoreInstanceState, transport=" + mTransportState);
        mSwitchPageRunnable.run();
    }

    @Override
    protected boolean fitSystemWindows(Rect insets) {
        setInsets(insets);
        return true;
    }

    private void setInsets(Rect insets) {
        mInsets.set(insets);
        if (mSlidingChallengeLayout != null) mSlidingChallengeLayout.setInsets(mInsets);
        if (mMultiPaneChallengeLayout != null) mMultiPaneChallengeLayout.setInsets(mInsets);

        final CameraWidgetFrame cameraWidget = findCameraPage();
        if (cameraWidget != null) cameraWidget.setInsets(mInsets);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (DEBUG) Log.d(TAG, "Window is " + (hasWindowFocus ? "focused" : "unfocused"));
        if (hasWindowFocus && mShowSecurityWhenReturn) {
            SlidingChallengeLayout slider =
                (SlidingChallengeLayout) findViewById(R.id.sliding_layout);
            if (slider != null) {
                slider.setHandleAlpha(1);
                slider.showChallenge(true);
            }
            mShowSecurityWhenReturn = false;
        }
    }

    private void showAppropriateWidgetPage() {
        final int state = mTransportState;
        final boolean transportAdded = ensureTransportPresentOrRemoved(state);
        final int pageToShow = getAppropriateWidgetPage(state);
        if (!transportAdded) {
            mAppWidgetContainer.setCurrentPage(pageToShow);
        } else if (state == TRANSPORT_VISIBLE) {
            // If the transport was just added, we need to wait for layout to happen before
            // we can set the current page.
            post(new Runnable() {
                @Override
                public void run() {
                    mAppWidgetContainer.setCurrentPage(pageToShow);
                }
            });
        }
    }

    /**
     * Examines the current state and adds the transport to the widget pager when the state changes.
     *
     * Showing the initial transport and keeping it around is a bit tricky because the signals
     * coming from music players aren't always clear. Here's how the states are handled:
     *
     * {@link TRANSPORT_GONE} means we have no reason to show the transport - remove it if present.
     *
     * {@link TRANSPORT_INVISIBLE} means we have potential to show the transport because a music
     * player is registered but not currently playing music (or we don't know the state yet). The
     * code adds it conditionally on play state.
     *
     * {@link #TRANSPORT_VISIBLE} means a music player is active and transport should be showing.
     *
     * Once the transport is showing, we always show it until keyguard is dismissed. This state is
     * maintained by onSave/RestoreInstanceState(). This state is cleared in
     * {@link KeyguardViewManager#hide} when keyguard is dismissed, which causes the transport to be
     * gone when keyguard is restarted until we get an update with the current state.
     *
     * @param state
     */
    private boolean ensureTransportPresentOrRemoved(int state) {
        final boolean showing = getWidgetPosition(R.id.keyguard_transport_control) != -1;
        final boolean visible = state == TRANSPORT_VISIBLE;
        final boolean shouldBeVisible = state == TRANSPORT_INVISIBLE && isMusicPlaying(state);
        if (!showing && (visible || shouldBeVisible)) {
            // insert to left of camera if it exists, otherwise after right-most widget
            int lastWidget = mAppWidgetContainer.getChildCount() - 1;
            int position = 0; // handle no widget case
            if (lastWidget >= 0) {
                position = mAppWidgetContainer.isCameraPage(lastWidget) ?
                        lastWidget : lastWidget + 1;
            }
            if (DEBUGXPORT) Log.v(TAG, "add transport at " + position);
            mAppWidgetContainer.addWidget(getOrCreateTransportControl(), position);
            return true;
        } else if (showing && state == TRANSPORT_GONE) {
            if (DEBUGXPORT) Log.v(TAG, "remove transport");
            mAppWidgetContainer.removeWidget(getOrCreateTransportControl());
            mTransportControl = null;
            KeyguardUpdateMonitor.getInstance(getContext()).dispatchSetBackground(null);
        }
        return false;
    }

    private CameraWidgetFrame findCameraPage() {
        for (int i = mAppWidgetContainer.getChildCount() - 1; i >= 0; i--) {
            if (mAppWidgetContainer.isCameraPage(i)) {
                return (CameraWidgetFrame) mAppWidgetContainer.getChildAt(i);
            }
        }
        return null;
    }

    boolean isMusicPage(int pageIndex) {
        return pageIndex >= 0 && pageIndex == getWidgetPosition(R.id.keyguard_transport_control);
    }

    private int getAppropriateWidgetPage(int musicTransportState) {
        // assumes at least one widget (besides camera + add)
        if (mAppWidgetToShow != AppWidgetManager.INVALID_APPWIDGET_ID) {
            final int childCount = mAppWidgetContainer.getChildCount();
            for (int i = 0; i < childCount; i++) {
                if (mAppWidgetContainer.getWidgetPageAt(i).getContentAppWidgetId()
                        == mAppWidgetToShow) {
                    return i;
                }
            }
            mAppWidgetToShow = AppWidgetManager.INVALID_APPWIDGET_ID;
        }
        // if music playing, show transport
        if (musicTransportState == TRANSPORT_VISIBLE) {
            if (DEBUG) Log.d(TAG, "Music playing, show transport");
            return mAppWidgetContainer.getWidgetPageIndex(getOrCreateTransportControl());
        }

        // else show the right-most widget (except for camera)
        int rightMost = mAppWidgetContainer.getChildCount() - 1;
        if (mAppWidgetContainer.isCameraPage(rightMost)) {
            rightMost--;
        }
        if (DEBUG) Log.d(TAG, "Show right-most page " + rightMost);
        return rightMost;
    }

    private void enableUserSelectorIfNecessary() {
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (um == null) {
            Throwable t = new Throwable();
            t.fillInStackTrace();
            Log.e(TAG, "user service is null.", t);
            return;
        }

        // if there are multiple users, we need to enable to multi-user switcher
        if (!um.isUserSwitcherEnabled()) {
            return;
        }

        final View multiUserView = findViewById(R.id.keyguard_user_selector);
        if (multiUserView == null) {
            if (DEBUG) Log.d(TAG, "can't find user_selector in layout.");
            return;
        }

        if (multiUserView instanceof KeyguardMultiUserSelectorView) {
            mKeyguardMultiUserSelectorView = (KeyguardMultiUserSelectorView) multiUserView;
            mKeyguardMultiUserSelectorView.setVisibility(View.VISIBLE);
            mKeyguardMultiUserSelectorView.addUsers(um.getUsers(true));
            UserSwitcherCallback callback = new UserSwitcherCallback() {
                @Override
                public void hideSecurityView(int duration) {
                    getSecurityContainer().animate().alpha(0).setDuration(duration);
                }

                @Override
                public void showSecurityView() {
                    getSecurityContainer().setAlpha(1.0f);
                }

                @Override
                public void showUnlockHint() {
                    if (getSecurityContainer() != null) {
                        getSecurityContainer().showUsabilityHint();
                    }
                }

                @Override
                public void userActivity() {
                    if (mViewMediatorCallback != null) {
                        mViewMediatorCallback.userActivity();
                    }
                }
            };
            mKeyguardMultiUserSelectorView.setCallback(callback);
        } else {
            Throwable t = new Throwable();
            t.fillInStackTrace();
            if (multiUserView == null) {
                Log.e(TAG, "could not find the user_selector.", t);
            } else {
                Log.e(TAG, "user_selector is the wrong type.", t);
            }
        }
    }

    @Override
    public void cleanUp() {
        // Make sure we let go of all widgets and their package contexts promptly. If we don't do
        // this, and the associated application is uninstalled, it can cause a soft reboot.
        int count = mAppWidgetContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            KeyguardWidgetFrame frame = mAppWidgetContainer.getWidgetPageAt(i);
            frame.removeAllViews();
        }
        getSecurityContainer().onPause(); // clean up any actions in progress
    }

    public void goToWidget(int appWidgetId) {
        mAppWidgetToShow = appWidgetId;
        mSwitchPageRunnable.run();
    }

    @Override
    protected void showBouncer(boolean show) {
        super.showBouncer(show);
        mViewStateManager.showBouncer(show);
    }

    @Override
    public void onExternalMotionEvent(MotionEvent event) {
        mAppWidgetContainer.handleExternalCameraEvent(event);
    }

    @Override
    protected void onCreateOptions(Bundle options) {
        if (options != null) {
            int widgetToShow = options.getInt(LockPatternUtils.KEYGUARD_SHOW_APPWIDGET,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            if (widgetToShow != AppWidgetManager.INVALID_APPWIDGET_ID) {
                goToWidget(widgetToShow);
            }
        }
    }

}
