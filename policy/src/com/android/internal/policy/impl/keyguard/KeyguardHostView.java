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

package com.android.internal.policy.impl.keyguard;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.RemoteViews.OnClickHandler;

import com.android.internal.R;
import com.android.internal.policy.impl.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.internal.widget.LockPatternUtils;

import java.io.File;
import java.util.List;

public class KeyguardHostView extends KeyguardViewBase {
    private static final String TAG = "KeyguardHostView";

    // Use this to debug all of keyguard
    public static boolean DEBUG = KeyguardViewMediator.DEBUG;

    // Found in KeyguardAppWidgetPickActivity.java
    static final int APPWIDGET_HOST_ID = 0x4B455947;

    private final int MAX_WIDGETS = 5;

    private AppWidgetHost mAppWidgetHost;
    private AppWidgetManager mAppWidgetManager;
    private KeyguardWidgetPager mAppWidgetContainer;
    private KeyguardSecurityViewFlipper mSecurityViewContainer;
    private KeyguardSelectorView mKeyguardSelectorView;
    private KeyguardTransportControlView mTransportControl;
    private boolean mIsVerifyUnlockOnly;
    private boolean mEnableFallback; // TODO: This should get the value from KeyguardPatternView
    private SecurityMode mCurrentSecuritySelection = SecurityMode.Invalid;
    private int mAppWidgetToShow;

    private boolean mCheckAppWidgetConsistencyOnBootCompleted = false;

    protected OnDismissAction mDismissAction;

    protected int mFailedAttempts;
    private LockPatternUtils mLockPatternUtils;

    private KeyguardSecurityModel mSecurityModel;
    private KeyguardViewStateManager mViewStateManager;

    boolean mPersitentStickyWidgetLoaded = false;

    private Rect mTempRect = new Rect();

    private int mDisabledFeatures;

    private boolean mCameraDisabled;

    private boolean mSafeModeEnabled;

    /*package*/ interface TransportCallback {
        void onListenerDetached();
        void onListenerAttached();
        void onPlayStateChanged();
    }

    /*package*/ interface UserSwitcherCallback {
        void hideSecurityView(int duration);
        void showSecurityView();
        void showUnlockHint();
        void userActivity();
    }

    /*package*/ interface OnDismissAction {
        /* returns true if the dismiss should be deferred */
        boolean onDismiss();
    }

    public KeyguardHostView(Context context) {
        this(context, null);
    }

    public KeyguardHostView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(context);
        mAppWidgetHost = new AppWidgetHost(
                context, APPWIDGET_HOST_ID, mOnClickHandler, Looper.myLooper());
        mAppWidgetManager = AppWidgetManager.getInstance(mContext);
        mSecurityModel = new KeyguardSecurityModel(context);

        mViewStateManager = new KeyguardViewStateManager(this);

        DevicePolicyManager dpm =
            (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            mDisabledFeatures = getDisabledFeatures(dpm);
            mCameraDisabled = dpm.getCameraDisabled(null);
        }

        mSafeModeEnabled = LockPatternUtils.isSafeModeEnabled();

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

    private KeyguardUpdateMonitorCallback mUpdateMonitorCallbacks =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onBootCompleted() {
            if (mCheckAppWidgetConsistencyOnBootCompleted) {
                checkAppWidgetConsistency();
                mSwitchPageRunnable.run();
                mCheckAppWidgetConsistencyOnBootCompleted = false;
            }
        }
    };

    private SlidingChallengeLayout mSlidingChallengeLayout;

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean result = super.onTouchEvent(ev);
        mTempRect.set(0, 0, 0, 0);
        offsetRectIntoDescendantCoords(mSecurityViewContainer, mTempRect);
        ev.offsetLocation(mTempRect.left, mTempRect.top);
        result = mSecurityViewContainer.dispatchTouchEvent(ev) || result;
        ev.offsetLocation(-mTempRect.left, -mTempRect.top);
        return result;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.keyguardDoneDrawing();
        }
    }

    private int getWidgetPosition(int id) {
        final int children = mAppWidgetContainer.getChildCount();
        for (int i = 0; i < children; i++) {
            if (mAppWidgetContainer.getWidgetPageAt(i).getContent().getId() == id) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onFinishInflate() {
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

        ChallengeLayout challenge = mSlidingChallengeLayout != null ? mSlidingChallengeLayout :
            (ChallengeLayout) findViewById(R.id.multi_pane_challenge);
        challenge.setOnBouncerStateChangedListener(mViewStateManager);
        mAppWidgetContainer.setBouncerAnimationDuration(challenge.getBouncerAnimationDuration());
        mViewStateManager.setPagedView(mAppWidgetContainer);
        mViewStateManager.setChallengeLayout(challenge);
        mSecurityViewContainer = (KeyguardSecurityViewFlipper) findViewById(R.id.view_flipper);
        mKeyguardSelectorView = (KeyguardSelectorView) findViewById(R.id.keyguard_selector_view);
        mViewStateManager.setSecurityViewContainer(mSecurityViewContainer);

        if (!(mContext instanceof Activity)) {
            setSystemUiVisibility(getSystemUiVisibility() | View.STATUS_BAR_DISABLE_BACK);
        }

        addDefaultWidgets();

        addWidgetsFromSettings();
        if (numWidgets() >= MAX_WIDGETS) {
            setAddWidgetEnabled(false);
        }
        checkAppWidgetConsistency();
        mSwitchPageRunnable.run();
        // This needs to be called after the pages are all added.
        mViewStateManager.showUsabilityHints();

        showPrimarySecurityScreen(false);
        updateSecurityViews();
    }

    private int getDisabledFeatures(DevicePolicyManager dpm) {
        int disabledFeatures = DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE;
        if (dpm != null) {
            final int currentUser = mLockPatternUtils.getCurrentUser();
            disabledFeatures = dpm.getKeyguardDisabledFeatures(null, currentUser);
        }
        return disabledFeatures;
    }

    private boolean widgetsDisabledByDpm() {
        return (mDisabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL) != 0;
    }

    private boolean cameraDisabledByDpm() {
        return mCameraDisabled
                || (mDisabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0;
    }

    private void updateSecurityViews() {
        int children = mSecurityViewContainer.getChildCount();
        for (int i = 0; i < children; i++) {
            updateSecurityView(mSecurityViewContainer.getChildAt(i));
        }
    }

    private void updateSecurityView(View view) {
        if (view instanceof KeyguardSecurityView) {
            KeyguardSecurityView ksv = (KeyguardSecurityView) view;
            ksv.setKeyguardCallback(mCallback);
            ksv.setLockPatternUtils(mLockPatternUtils);
            if (mViewStateManager.isBouncing()) {
                ksv.showBouncer(0);
            } else {
                ksv.hideBouncer(0);
            }
        } else {
            Log.w(TAG, "View " + view + " is not a KeyguardSecurityView");
        }
    }

    void setLockPatternUtils(LockPatternUtils utils) {
        mSecurityModel.setLockPatternUtils(utils);
        mLockPatternUtils = utils;
        updateSecurityViews();
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

    private AppWidgetHost getAppWidgetHost() {
        return mAppWidgetHost;
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
            if (numWidgets() >= MAX_WIDGETS) {
                setAddWidgetEnabled(false);
            }
        };

        @Override
        public void onRemoveView(View v) {
            if (numWidgets() < MAX_WIDGETS) {
                setAddWidgetEnabled(true);
            }
        }
    };

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

    private KeyguardSecurityCallback mCallback = new KeyguardSecurityCallback() {

        public void userActivity(long timeout) {
            if (mViewMediatorCallback != null) {
                mViewMediatorCallback.userActivity(timeout);
            }
        }

        public void dismiss(boolean authenticated) {
            showNextSecurityScreenOrFinish(authenticated);
        }

        public boolean isVerifyUnlockOnly() {
            return mIsVerifyUnlockOnly;
        }

        public void reportSuccessfulUnlockAttempt() {
            KeyguardUpdateMonitor.getInstance(mContext).clearFailedUnlockAttempts();
            mLockPatternUtils.reportSuccessfulPasswordAttempt();
        }

        public void reportFailedUnlockAttempt() {
            if (mCurrentSecuritySelection == SecurityMode.Biometric) {
                KeyguardUpdateMonitor.getInstance(mContext).reportFailedBiometricUnlockAttempt();
            } else {
                KeyguardHostView.this.reportFailedUnlockAttempt();
            }
        }

        public int getFailedAttempts() {
            return KeyguardUpdateMonitor.getInstance(mContext).getFailedUnlockAttempts();
        }

        @Override
        public void showBackupSecurity() {
            KeyguardHostView.this.showBackupSecurityScreen();
        }

        @Override
        public void setOnDismissAction(OnDismissAction action) {
            KeyguardHostView.this.setOnDismissAction(action);
        }

    };

    private void showDialog(String title, String message) {
        final AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(title)
            .setMessage(message)
            .setNeutralButton(com.android.internal.R.string.ok, null)
            .create();
        if (!(mContext instanceof Activity)) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        }
        dialog.show();
    }

    private void showTimeoutDialog() {
        int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        int messageId = 0;

        switch (mSecurityModel.getSecurityMode()) {
            case Pattern:
                messageId = R.string.kg_too_many_failed_pattern_attempts_dialog_message;
                break;
            case PIN:
                messageId = R.string.kg_too_many_failed_pin_attempts_dialog_message;
                break;
            case Password:
                messageId = R.string.kg_too_many_failed_password_attempts_dialog_message;
                break;
        }

        if (messageId != 0) {
            final String message = mContext.getString(messageId,
                    KeyguardUpdateMonitor.getInstance(mContext).getFailedUnlockAttempts(),
                    timeoutInSeconds);
            showDialog(null, message);
        }
    }

    private void showAlmostAtWipeDialog(int attempts, int remaining) {
        int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        String message = mContext.getString(R.string.kg_failed_attempts_almost_at_wipe,
                attempts, remaining);
        showDialog(null, message);
    }

    private void showWipeDialog(int attempts) {
        String message = mContext.getString(R.string.kg_failed_attempts_now_wiping, attempts);
        showDialog(null, message);
    }

    private void showAlmostAtAccountLoginDialog() {
        final int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        final int count = LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET
                - LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;
        String message = mContext.getString(R.string.kg_failed_attempts_almost_at_login,
                count, LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT, timeoutInSeconds);
        showDialog(null, message);
    }

    private void reportFailedUnlockAttempt() {
        final KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
        final int failedAttempts = monitor.getFailedUnlockAttempts() + 1; // +1 for this time

        if (DEBUG) Log.d(TAG, "reportFailedPatternAttempt: #" + failedAttempts);

        SecurityMode mode = mSecurityModel.getSecurityMode();
        final boolean usingPattern = mode == KeyguardSecurityModel.SecurityMode.Pattern;

        final int failedAttemptsBeforeWipe = mLockPatternUtils.getDevicePolicyManager()
                .getMaximumFailedPasswordsForWipe(null, mLockPatternUtils.getCurrentUser());

        final int failedAttemptWarning = LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET
                - LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;

        final int remainingBeforeWipe = failedAttemptsBeforeWipe > 0 ?
                (failedAttemptsBeforeWipe - failedAttempts)
                : Integer.MAX_VALUE; // because DPM returns 0 if no restriction

        boolean showTimeout = false;
        if (remainingBeforeWipe < LockPatternUtils.FAILED_ATTEMPTS_BEFORE_WIPE_GRACE) {
            // If we reach this code, it means the user has installed a DevicePolicyManager
            // that requests device wipe after N attempts.  Once we get below the grace
            // period, we'll post this dialog every time as a clear warning until the
            // bombshell hits and the device is wiped.
            if (remainingBeforeWipe > 0) {
                showAlmostAtWipeDialog(failedAttempts, remainingBeforeWipe);
            } else {
                // Too many attempts. The device will be wiped shortly.
                Slog.i(TAG, "Too many unlock attempts; device will be wiped!");
                showWipeDialog(failedAttempts);
            }
        } else {
            showTimeout =
                (failedAttempts % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT) == 0;
            if (usingPattern && mEnableFallback) {
                if (failedAttempts == failedAttemptWarning) {
                    showAlmostAtAccountLoginDialog();
                    showTimeout = false; // don't show both dialogs
                } else if (failedAttempts >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET) {
                    mLockPatternUtils.setPermanentlyLocked(true);
                    showSecurityScreen(SecurityMode.Account);
                    // don't show timeout dialog because we show account unlock screen next
                    showTimeout = false;
                }
            }
        }
        monitor.reportFailedUnlockAttempt();
        mLockPatternUtils.reportFailedPasswordAttempt();
        if (showTimeout) {
            showTimeoutDialog();
        }
    }

    /**
     * Shows the primary security screen for the user. This will be either the multi-selector
     * or the user's security method.
     * @param turningOff true if the device is being turned off
     */
    void showPrimarySecurityScreen(boolean turningOff) {
        SecurityMode securityMode = mSecurityModel.getSecurityMode();
        if (DEBUG) Log.v(TAG, "showPrimarySecurityScreen(turningOff=" + turningOff + ")");
        if (!turningOff &&
                KeyguardUpdateMonitor.getInstance(mContext).isAlternateUnlockEnabled()) {
            // If we're not turning off, then allow biometric alternate.
            // We'll reload it when the device comes back on.
            securityMode = mSecurityModel.getAlternateFor(securityMode);
        }
        showSecurityScreen(securityMode);
    }

    /**
     * Shows the backup security screen for the current security mode.  This could be used for
     * password recovery screens but is currently only used for pattern unlock to show the
     * account unlock screen and biometric unlock to show the user's normal unlock.
     */
    private void showBackupSecurityScreen() {
        if (DEBUG) Log.d(TAG, "showBackupSecurity()");
        SecurityMode backup = mSecurityModel.getBackupSecurityMode(mCurrentSecuritySelection);
        showSecurityScreen(backup);
    }

    public boolean showNextSecurityScreenIfPresent() {
        SecurityMode securityMode = mSecurityModel.getSecurityMode();
        // Allow an alternate, such as biometric unlock
        securityMode = mSecurityModel.getAlternateFor(securityMode);
        if (SecurityMode.None == securityMode) {
            return false;
        } else {
            showSecurityScreen(securityMode); // switch to the alternate security view
            return true;
        }
    }

    private void showNextSecurityScreenOrFinish(boolean authenticated) {
        if (DEBUG) Log.d(TAG, "showNextSecurityScreenOrFinish(" + authenticated + ")");
        boolean finish = false;
        if (SecurityMode.None == mCurrentSecuritySelection) {
            SecurityMode securityMode = mSecurityModel.getSecurityMode();
            // Allow an alternate, such as biometric unlock
            securityMode = mSecurityModel.getAlternateFor(securityMode);
            if (SecurityMode.None == securityMode) {
                finish = true; // no security required
            } else {
                showSecurityScreen(securityMode); // switch to the alternate security view
            }
        } else if (authenticated) {
            switch (mCurrentSecuritySelection) {
                case Pattern:
                case Password:
                case PIN:
                case Account:
                case Biometric:
                    finish = true;
                    break;

                case SimPin:
                case SimPuk:
                    // Shortcut for SIM PIN/PUK to go to directly to user's security screen or home
                    SecurityMode securityMode = mSecurityModel.getSecurityMode();
                    if (securityMode != SecurityMode.None) {
                        showSecurityScreen(securityMode);
                    } else {
                        finish = true;
                    }
                    break;

                default:
                    Log.v(TAG, "Bad security screen " + mCurrentSecuritySelection + ", fail safe");
                    showPrimarySecurityScreen(false);
                    break;
            }
        } else {
            showPrimarySecurityScreen(false);
        }
        if (finish) {
            // If the alternate unlock was suppressed, it can now be safely
            // enabled because the user has left keyguard.
            KeyguardUpdateMonitor.getInstance(mContext).setAlternateUnlockEnabled(true);

            // If there's a pending runnable because the user interacted with a widget
            // and we're leaving keyguard, then run it.
            boolean deferKeyguardDone = false;
            if (mDismissAction != null) {
                deferKeyguardDone = mDismissAction.onDismiss();
                mDismissAction = null;
            }
            if (mViewMediatorCallback != null) {
                if (deferKeyguardDone) {
                    mViewMediatorCallback.keyguardDonePending();
                } else {
                    mViewMediatorCallback.keyguardDone(true);
                }
            }
        } else {
            mViewStateManager.showBouncer(true);
        }
    }

    private OnClickHandler mOnClickHandler = new OnClickHandler() {
        @Override
        public boolean onClickHandler(final View view,
                final android.app.PendingIntent pendingIntent,
                final Intent fillInIntent) {
            if (pendingIntent.isActivity()) {
                setOnDismissAction(new OnDismissAction() {
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

                if (mViewStateManager.isChallengeShowing()) {
                    mViewStateManager.showBouncer(true);
                } else {
                    mCallback.dismiss(false);
                }
                return true;
            } else {
                return super.onClickHandler(view, pendingIntent, fillInIntent);
            }
        };
    };

    // Used to ignore callbacks from methods that are no longer current (e.g. face unlock).
    // This avoids unwanted asynchronous events from messing with the state.
    private KeyguardSecurityCallback mNullCallback = new KeyguardSecurityCallback() {

        @Override
        public void userActivity(long timeout) {
        }

        @Override
        public void showBackupSecurity() {
        }

        @Override
        public void setOnDismissAction(OnDismissAction action) {
        }

        @Override
        public void reportSuccessfulUnlockAttempt() {
        }

        @Override
        public void reportFailedUnlockAttempt() {
        }

        @Override
        public boolean isVerifyUnlockOnly() {
            return false;
        }

        @Override
        public int getFailedAttempts() {
            return 0;
        }

        @Override
        public void dismiss(boolean securityVerified) {
        }
    };

    protected boolean mShowSecurityWhenReturn;

    @Override
    public void reset() {
        mIsVerifyUnlockOnly = false;
        mAppWidgetContainer.setCurrentPage(getWidgetPosition(R.id.keyguard_status_view));
    }

    /**
     * Sets an action to perform when keyguard is dismissed.
     * @param action
     */
    protected void setOnDismissAction(OnDismissAction action) {
        mDismissAction = action;
    }

    private KeyguardSecurityView getSecurityView(SecurityMode securityMode) {
        final int securityViewIdForMode = getSecurityViewIdForMode(securityMode);
        KeyguardSecurityView view = null;
        final int children = mSecurityViewContainer.getChildCount();
        for (int child = 0; child < children; child++) {
            if (mSecurityViewContainer.getChildAt(child).getId() == securityViewIdForMode) {
                view = ((KeyguardSecurityView)mSecurityViewContainer.getChildAt(child));
                break;
            }
        }
        int layoutId = getLayoutIdFor(securityMode);
        if (view == null && layoutId != 0) {
            final LayoutInflater inflater = LayoutInflater.from(mContext);
            if (DEBUG) Log.v(TAG, "inflating id = " + layoutId);
            View v = inflater.inflate(layoutId, mSecurityViewContainer, false);
            mSecurityViewContainer.addView(v);
            updateSecurityView(v);
            view = (KeyguardSecurityView)v;
        }

        if (view instanceof KeyguardSelectorView) {
            KeyguardSelectorView selectorView = (KeyguardSelectorView) view;
            View carrierText = selectorView.findViewById(R.id.keyguard_selector_fade_container);
            selectorView.setCarrierArea(carrierText);
        }

        return view;
    }

    /**
     * Switches to the given security view unless it's already being shown, in which case
     * this is a no-op.
     *
     * @param securityMode
     */
    private void showSecurityScreen(SecurityMode securityMode) {
        if (DEBUG) Log.d(TAG, "showSecurityScreen(" + securityMode + ")");

        if (securityMode == mCurrentSecuritySelection) return;

        KeyguardSecurityView oldView = getSecurityView(mCurrentSecuritySelection);
        KeyguardSecurityView newView = getSecurityView(securityMode);

        // Enter full screen mode if we're in SIM or Account screen
        boolean fullScreenEnabled = getResources().getBoolean(
                com.android.internal.R.bool.kg_sim_puk_account_full_screen);
        boolean isSimOrAccount = securityMode == SecurityMode.SimPin
                || securityMode == SecurityMode.SimPuk
                || securityMode == SecurityMode.Account;
        mAppWidgetContainer.setVisibility(
                isSimOrAccount && fullScreenEnabled ? View.GONE : View.VISIBLE);

        if (mSlidingChallengeLayout != null) {
            mSlidingChallengeLayout.setChallengeInteractive(!fullScreenEnabled);
        }

        // Emulate Activity life cycle
        if (oldView != null) {
            oldView.onPause();
            oldView.setKeyguardCallback(mNullCallback); // ignore requests from old view
        }
        newView.onResume(KeyguardSecurityView.VIEW_REVEALED);
        newView.setKeyguardCallback(mCallback);

        final boolean needsInput = newView.needsInput();
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.setNeedsInput(needsInput);
        }

        // Find and show this child.
        final int childCount = mSecurityViewContainer.getChildCount();

        mSecurityViewContainer.setInAnimation(
                AnimationUtils.loadAnimation(mContext, R.anim.keyguard_security_fade_in));
        mSecurityViewContainer.setOutAnimation(
                AnimationUtils.loadAnimation(mContext, R.anim.keyguard_security_fade_out));
        final int securityViewIdForMode = getSecurityViewIdForMode(securityMode);
        for (int i = 0; i < childCount; i++) {
            if (mSecurityViewContainer.getChildAt(i).getId() == securityViewIdForMode) {
                mSecurityViewContainer.setDisplayedChild(i);
                break;
            }
        }

        if (securityMode == SecurityMode.None) {
            // Discard current runnable if we're switching back to the selector view
            setOnDismissAction(null);
        }
        mCurrentSecuritySelection = securityMode;
    }

    @Override
    public void onScreenTurnedOn() {
        if (DEBUG) Log.d(TAG, "screen on, instance " + Integer.toHexString(hashCode()));
        showPrimarySecurityScreen(false);
        getSecurityView(mCurrentSecuritySelection).onResume(KeyguardSecurityView.SCREEN_ON);

        // This is a an attempt to fix bug 7137389 where the device comes back on but the entire
        // layout is blank but forcing a layout causes it to reappear (e.g. with with
        // hierarchyviewer).
        requestLayout();

        if (mViewStateManager != null) {
            mViewStateManager.showUsabilityHints();
        }
    }

    @Override
    public void onScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, String.format("screen off, instance %s at %s",
                Integer.toHexString(hashCode()), SystemClock.uptimeMillis()));
        // Once the screen turns off, we no longer consider this to be first boot and we want the
        // biometric unlock to start next time keyguard is shown.
        KeyguardUpdateMonitor.getInstance(mContext).setAlternateUnlockEnabled(true);
        // We use mAppWidgetToShow to show a particular widget after you add it-- once the screen
        // turns off we reset that behavior
        clearAppWidgetToShow();
        checkAppWidgetConsistency();
        showPrimarySecurityScreen(true);
        getSecurityView(mCurrentSecuritySelection).onPause();
        CameraWidgetFrame cameraPage = findCameraPage();
        if (cameraPage != null) {
            cameraPage.onScreenTurnedOff();
        }
    }

    public void clearAppWidgetToShow() {
        mAppWidgetToShow = AppWidgetManager.INVALID_APPWIDGET_ID;
    }

    @Override
    public void show() {
        if (DEBUG) Log.d(TAG, "show()");
        showPrimarySecurityScreen(false);
    }

    private boolean isSecure() {
        SecurityMode mode = mSecurityModel.getSecurityMode();
        switch (mode) {
            case Pattern:
                return mLockPatternUtils.isLockPatternEnabled();
            case Password:
            case PIN:
                return mLockPatternUtils.isLockPasswordEnabled();
            case SimPin:
            case SimPuk:
            case Account:
                return true;
            case None:
                return false;
            default:
                throw new IllegalStateException("Unknown security mode " + mode);
        }
    }

    @Override
    public void wakeWhenReadyTq(int keyCode) {
        if (DEBUG) Log.d(TAG, "onWakeKey");
        if (keyCode == KeyEvent.KEYCODE_MENU && isSecure()) {
            if (DEBUG) Log.d(TAG, "switching screens to unlock screen because wake key was MENU");
            showSecurityScreen(SecurityMode.None);
        } else {
            if (DEBUG) Log.d(TAG, "poking wake lock immediately");
        }
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.wakeUp();
        }
    }

    @Override
    public void verifyUnlock() {
        SecurityMode securityMode = mSecurityModel.getSecurityMode();
        if (securityMode == KeyguardSecurityModel.SecurityMode.None) {
            if (mViewMediatorCallback != null) {
                mViewMediatorCallback.keyguardDone(true);
            }
        } else if (securityMode != KeyguardSecurityModel.SecurityMode.Pattern
                && securityMode != KeyguardSecurityModel.SecurityMode.PIN
                && securityMode != KeyguardSecurityModel.SecurityMode.Password) {
            // can only verify unlock when in pattern/password mode
            if (mViewMediatorCallback != null) {
                mViewMediatorCallback.keyguardDone(false);
            }
        } else {
            // otherwise, go to the unlock screen, see if they can verify it
            mIsVerifyUnlockOnly = true;
            showSecurityScreen(securityMode);
        }
    }

    private int getSecurityViewIdForMode(SecurityMode securityMode) {
        switch (securityMode) {
            case None: return R.id.keyguard_selector_view;
            case Pattern: return R.id.keyguard_pattern_view;
            case PIN: return R.id.keyguard_pin_view;
            case Password: return R.id.keyguard_password_view;
            case Biometric: return R.id.keyguard_face_unlock_view;
            case Account: return R.id.keyguard_account_view;
            case SimPin: return R.id.keyguard_sim_pin_view;
            case SimPuk: return R.id.keyguard_sim_puk_view;
        }
        return 0;
    }

    private int getLayoutIdFor(SecurityMode securityMode) {
        switch (securityMode) {
            case None: return R.layout.keyguard_selector_view;
            case Pattern: return R.layout.keyguard_pattern_view;
            case PIN: return R.layout.keyguard_pin_view;
            case Password: return R.layout.keyguard_password_view;
            case Biometric: return R.layout.keyguard_face_unlock_view;
            case Account: return R.layout.keyguard_account_view;
            case SimPin: return R.layout.keyguard_sim_pin_view;
            case SimPuk: return R.layout.keyguard_sim_puk_view;
            default:
                return 0;
        }
    }

    private boolean addWidget(int appId, int pageIndex, boolean updateDbIfFailed) {
        AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appId);
        if (appWidgetInfo != null) {
            AppWidgetHostView view = getAppWidgetHost().createView(mContext, appId, appWidgetInfo);
            addWidget(view, pageIndex);
            return true;
        } else {
            if (updateDbIfFailed) {
                Log.w(TAG, "AppWidgetInfo for app widget id " + appId + " was null, deleting");
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

    private final KeyguardActivityLauncher mActivityLauncher = new KeyguardActivityLauncher() {
        @Override
        Context getContext() {
            return mContext;
        }

        @Override
        KeyguardSecurityCallback getCallback() {
            return mCallback;
        }

        @Override
        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
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


    private void setAddWidgetEnabled(boolean clickable) {
        View addWidget = mAppWidgetContainer.findViewById(R.id.keyguard_add_widget);
        if (addWidget != null) {
            View addWidgetButton = addWidget.findViewById(R.id.keyguard_add_widget_view);
            addWidgetButton.setEnabled(clickable);
        }
    }

    private void addDefaultWidgets() {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        inflater.inflate(R.layout.keyguard_transport_control_view, this, true);

        if (!mSafeModeEnabled && !widgetsDisabledByDpm()) {
            View addWidget = inflater.inflate(R.layout.keyguard_add_widget, this, false);
            mAppWidgetContainer.addWidget(addWidget, 0);
            View addWidgetButton = addWidget.findViewById(R.id.keyguard_add_widget_view);
            addWidgetButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Pass in an invalid widget id... the picker will allocate an ID for us
                    mActivityLauncher.launchWidgetPicker(AppWidgetManager.INVALID_APPWIDGET_ID);
                }
            });
        }

        // We currently disable cameras in safe mode because we support loading 3rd party
        // cameras we can't trust.  TODO: plumb safe mode into camera creation code and only
        // inflate system-provided camera?
        if (!mSafeModeEnabled && !cameraDisabledByDpm()
                && mContext.getResources().getBoolean(R.bool.kg_enable_camera_default_widget)) {
            View cameraWidget =
                    CameraWidgetFrame.create(mContext, mCameraWidgetCallbacks, mActivityLauncher);
            if (cameraWidget != null) {
                mAppWidgetContainer.addWidget(cameraWidget);
            }
        }

        enableUserSelectorIfNecessary();
        initializeTransportControl();
    }

    private boolean removeTransportFromWidgetPager() {
        int page = getWidgetPosition(R.id.keyguard_transport_control);
        if (page != -1) {
            mAppWidgetContainer.removeWidget(mTransportControl);

            // XXX keep view attached so we still get show/hide events from AudioManager
            KeyguardHostView.this.addView(mTransportControl);
            mTransportControl.setVisibility(View.GONE);
            mViewStateManager.setTransportState(KeyguardViewStateManager.TRANSPORT_GONE);
            return true;
        }
        return false;
    }

    private void addTransportToWidgetPager() {
        if (getWidgetPosition(R.id.keyguard_transport_control) == -1) {
            KeyguardHostView.this.removeView(mTransportControl);
            // insert to left of camera if it exists, otherwise after right-most widget
            int lastWidget = mAppWidgetContainer.getChildCount() - 1;
            int position = 0; // handle no widget case
            if (lastWidget >= 0) {
                position = mAppWidgetContainer.isCameraPage(lastWidget) ?
                        lastWidget : lastWidget + 1;
            }
            mAppWidgetContainer.addWidget(mTransportControl, position);
            mTransportControl.setVisibility(View.VISIBLE);
        }
    }

    private void initializeTransportControl() {
        mTransportControl =
            (KeyguardTransportControlView) findViewById(R.id.keyguard_transport_control);
        mTransportControl.setVisibility(View.GONE);

        // This code manages showing/hiding the transport control. We keep it around and only
        // add it to the hierarchy if it needs to be present.
        if (mTransportControl != null) {
            mTransportControl.setKeyguardCallback(new TransportCallback() {
                @Override
                public void onListenerDetached() {
                    if (removeTransportFromWidgetPager()) {
                        mTransportControl.post(mSwitchPageRunnable);
                    }
                }

                @Override
                public void onListenerAttached() {
                    // Transport will be added when playstate changes...
                    mTransportControl.post(mSwitchPageRunnable);
                }

                @Override
                public void onPlayStateChanged() {
                    mTransportControl.post(mSwitchPageRunnable);
                }
            });
        }
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
        if (mSafeModeEnabled || widgetsDisabledByDpm()) {
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
        // Since this method may bind a widget (which we can't do until boot completed) we
        // may have to defer it until after boot complete.
        if (!KeyguardUpdateMonitor.getInstance(mContext).hasBootCompleted()) {
            mCheckAppWidgetConsistencyOnBootCompleted = true;
            return;
        }
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

            final boolean userAddedWidgetsEnabled = !widgetsDisabledByDpm();
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

    Runnable mSwitchPageRunnable = new Runnable() {
        @Override
        public void run() {
           showAppropriateWidgetPage();
        }
    };

    static class SavedState extends BaseSavedState {
        int transportState;
        int appWidgetToShow = AppWidgetManager.INVALID_APPWIDGET_ID;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            this.transportState = in.readInt();
            this.appWidgetToShow = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(this.transportState);
            out.writeInt(this.appWidgetToShow);
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
        if (DEBUG) Log.d(TAG, "onSaveInstanceState");
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        ss.transportState = mViewStateManager.getTransportState();
        ss.appWidgetToShow = mAppWidgetToShow;
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (DEBUG) Log.d(TAG, "onRestoreInstanceState");
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        mViewStateManager.setTransportState(ss.transportState);
        mAppWidgetToShow = ss.appWidgetToShow;
        post(mSwitchPageRunnable);
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
        int state = mViewStateManager.getTransportState();
        boolean isMusicPlaying = mTransportControl.isMusicPlaying()
                || state == KeyguardViewStateManager.TRANSPORT_VISIBLE;
        if (isMusicPlaying) {
            mViewStateManager.setTransportState(KeyguardViewStateManager.TRANSPORT_VISIBLE);
            addTransportToWidgetPager();
        } else if (state == KeyguardViewStateManager.TRANSPORT_VISIBLE) {
            mViewStateManager.setTransportState(KeyguardViewStateManager.TRANSPORT_INVISIBLE);
        }
        int pageToShow = getAppropriateWidgetPage(isMusicPlaying);
        mAppWidgetContainer.setCurrentPage(pageToShow);
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

    private int getAppropriateWidgetPage(boolean isMusicPlaying) {
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
        if (isMusicPlaying) {
            if (DEBUG) Log.d(TAG, "Music playing, show transport");
            return mAppWidgetContainer.getWidgetPageIndex(mTransportControl);
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
        if (!UserManager.supportsMultipleUsers()) {
            return; // device doesn't support multi-user mode
        }
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (um == null) {
            Throwable t = new Throwable();
            t.fillInStackTrace();
            Log.e(TAG, "user service is null.", t);
            return;
        }

        // if there are multiple users, we need to enable to multi-user switcher
        final List<UserInfo> users = um.getUsers(true);
        if (users == null) {
            Throwable t = new Throwable();
            t.fillInStackTrace();
            Log.e(TAG, "list of users is null.", t);
            return;
        }

        final View multiUserView = findViewById(R.id.keyguard_user_selector);
        if (multiUserView == null) {
            Throwable t = new Throwable();
            t.fillInStackTrace();
            Log.e(TAG, "can't find user_selector in layout.", t);
            return;
        }

        if (users.size() > 1) {
            if (multiUserView instanceof KeyguardMultiUserSelectorView) {
                KeyguardMultiUserSelectorView multiUser =
                        (KeyguardMultiUserSelectorView) multiUserView;
                multiUser.setVisibility(View.VISIBLE);
                multiUser.addUsers(users);
                UserSwitcherCallback callback = new UserSwitcherCallback() {
                    @Override
                    public void hideSecurityView(int duration) {
                        mSecurityViewContainer.animate().alpha(0).setDuration(duration);
                    }

                    @Override
                    public void showSecurityView() {
                        mSecurityViewContainer.setAlpha(1.0f);
                    }

                    @Override
                    public void showUnlockHint() {
                        if (mKeyguardSelectorView != null) {
                            mKeyguardSelectorView.showUsabilityHint();
                        }
                    }

                    @Override
                    public void userActivity() {
                        if (mViewMediatorCallback != null) {
                            mViewMediatorCallback.userActivity();
                        }
                    }
                };
                multiUser.setCallback(callback);
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
    }

    @Override
    public void cleanUp() {

    }

    /**
     * In general, we enable unlocking the insecure keyguard with the menu key. However, there are
     * some cases where we wish to disable it, notably when the menu button placement or technology
     * is prone to false positives.
     *
     * @return true if the menu key should be enabled
     */
    private static final String ENABLE_MENU_KEY_FILE = "/data/local/enable_menu_key";
    private boolean shouldEnableMenuKey() {
        final Resources res = getResources();
        final boolean configDisabled = res.getBoolean(
                com.android.internal.R.bool.config_disableMenuKeyInLockScreen);
        final boolean isTestHarness = ActivityManager.isRunningInTestHarness();
        final boolean fileOverride = (new File(ENABLE_MENU_KEY_FILE)).exists();
        return !configDisabled || isTestHarness || fileOverride;
    }



    public void goToUserSwitcher() {
        mAppWidgetContainer.setCurrentPage(getWidgetPosition(R.id.keyguard_multi_user_selector));
    }

    public void goToWidget(int appWidgetId) {
        mAppWidgetToShow = appWidgetId;
        mSwitchPageRunnable.run();
    }

    public boolean handleMenuKey() {
        // The following enables the MENU key to work for testing automation
        if (shouldEnableMenuKey()) {
            showNextSecurityScreenOrFinish(false);
            return true;
        }
        return false;
    }

    public boolean handleBackKey() {
        if (mCurrentSecuritySelection != SecurityMode.None) {
            mCallback.dismiss(false);
            return true;
        }
        return false;
    }

    /**
     *  Dismisses the keyguard by going to the next screen or making it gone.
     */
    public void dismiss() {
        showNextSecurityScreenOrFinish(false);
    }

    public void showAssistant() {
        final Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
          .getAssistIntent(mContext, UserHandle.USER_CURRENT);

        if (intent == null) return;

        final ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                R.anim.keyguard_action_assist_enter, R.anim.keyguard_action_assist_exit,
                getHandler(), null);

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        mActivityLauncher.launchActivityWithAnimation(
                intent, false, opts.toBundle(), null, null);
    }
}
