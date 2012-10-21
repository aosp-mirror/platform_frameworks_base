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
import android.app.admin.DevicePolicyManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
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
import android.widget.TextView;
import android.widget.ViewFlipper;

import com.android.internal.R;
import com.android.internal.policy.impl.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.internal.widget.LockPatternUtils;

import java.io.File;
import java.util.List;

public class KeyguardHostView extends KeyguardViewBase {
    private static final String TAG = "KeyguardViewHost";

    // Use this to debug all of keyguard
    public static boolean DEBUG;

    // also referenced in SecuritySettings.java
    static final int APPWIDGET_HOST_ID = 0x4B455947;

    // transport control states
    private static final int TRANSPORT_GONE = 0;
    private static final int TRANSPORT_INVISIBLE = 1;
    private static final int TRANSPORT_VISIBLE = 2;

    private AppWidgetHost mAppWidgetHost;
    private KeyguardWidgetPager mAppWidgetContainer;
    private ViewFlipper mSecurityViewContainer;
    private KeyguardSelectorView mKeyguardSelectorView;
    private KeyguardTransportControlView mTransportControl;
    private boolean mEnableMenuKey;
    private boolean mIsVerifyUnlockOnly;
    private boolean mEnableFallback; // TODO: This should get the value from KeyguardPatternView
    private SecurityMode mCurrentSecuritySelection = SecurityMode.Invalid;

    protected Runnable mLaunchRunnable;

    protected int mFailedAttempts;
    private LockPatternUtils mLockPatternUtils;

    private KeyguardSecurityModel mSecurityModel;
    private KeyguardViewStateManager mViewStateManager;

    private Rect mTempRect = new Rect();
    private int mTransportState = TRANSPORT_GONE;

    /*package*/ interface TransportCallback {
        void onListenerDetached();
        void onListenerAttached();
        void onPlayStateChanged();
    }

    /*package*/ interface UserSwitcherCallback {
        void hideSecurityView(int duration);
        void showSecurityView();
        void showUnlockHint();
    }

    public KeyguardHostView(Context context) {
        this(context, null);
    }

    public KeyguardHostView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(context);
        mAppWidgetHost = new AppWidgetHost(
                context, APPWIDGET_HOST_ID, mOnClickHandler, Looper.myLooper());
        mSecurityModel = new KeyguardSecurityModel(context);

        // The following enables the MENU key to work for testing automation
        mEnableMenuKey = shouldEnableMenuKey();
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

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
            if (mAppWidgetContainer.getChildAt(i).getId() == id) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onFinishInflate() {
        // Grab instances of and make any necessary changes to the main layouts. Create
        // view state manager and wire up necessary listeners / callbacks.
        mAppWidgetContainer = (KeyguardWidgetPager) findViewById(R.id.app_widget_container);
        mAppWidgetContainer.setVisibility(VISIBLE);
        mAppWidgetContainer.setCallbacks(mWidgetCallbacks);
        mAppWidgetContainer.setMinScale(0.5f);

        addDefaultWidgets();
        maybePopulateWidgets();

        mViewStateManager = new KeyguardViewStateManager();
        SlidingChallengeLayout challengeLayout =
                (SlidingChallengeLayout) findViewById(R.id.sliding_layout);
        challengeLayout.setOnChallengeScrolledListener(mViewStateManager);
        mAppWidgetContainer.setViewStateManager(mViewStateManager);

        mViewStateManager.setPagedView(mAppWidgetContainer);
        mViewStateManager.setSlidingChallenge(challengeLayout);

        mSecurityViewContainer = (ViewFlipper) findViewById(R.id.view_flipper);
        mKeyguardSelectorView = (KeyguardSelectorView) findViewById(R.id.keyguard_selector_view);


        updateSecurityViews();
        if (!(mContext instanceof Activity)) {
            setSystemUiVisibility(getSystemUiVisibility() | View.STATUS_BAR_DISABLE_BACK);
        }

        if (KeyguardUpdateMonitor.getInstance(mContext).getIsFirstBoot()) {
            showPrimarySecurityScreen(false);
        }
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
        disableStatusViewInteraction();
        post(mSwitchPageRunnable);
    }

    private void disableStatusViewInteraction() {
        // Disable all user interaction on status view. This is done to prevent falsing in the
        // pocket from triggering useractivity and prevents 3rd party replacement widgets
        // from responding to user interaction while in this position.
        View statusView = findViewById(R.id.keyguard_status_view);
        if (statusView instanceof KeyguardWidgetFrame) {
            ((KeyguardWidgetFrame) statusView).setDisableUserInteraction(true);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAppWidgetHost.stopListening();
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
            if (mViewMediatorCallback != null) {
                mViewMediatorCallback.userActivity();
            }
        }

        @Override
        public void onUserActivityTimeoutChanged() {
            if (mViewMediatorCallback != null) {
                mViewMediatorCallback.onUserActivityTimeoutChanged();
            }
        }
    };

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
        public void setOnDismissRunnable(Runnable runnable) {
            KeyguardHostView.this.setOnDismissRunnable(runnable);
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

            case Password: {
                    final boolean isPin = mLockPatternUtils.getKeyguardStoredPasswordQuality() ==
                        DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
                    messageId = isPin ? R.string.kg_too_many_failed_pin_attempts_dialog_message
                            : R.string.kg_too_many_failed_password_attempts_dialog_message;
                }
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
        if (!turningOff && KeyguardUpdateMonitor.getInstance(mContext).isAlternateUnlockEnabled()) {
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
            KeyguardUpdateMonitor.getInstance(mContext).setIsFirstBoot(false);

            // If there's a pending runnable because the user interacted with a widget
            // and we're leaving keyguard, then run it.
            if (mLaunchRunnable != null) {
                mLaunchRunnable.run();
                mLaunchRunnable = null;
            }
            if (mViewMediatorCallback != null) {
                mViewMediatorCallback.keyguardDone(true);
            }
        }
    }

    private OnClickHandler mOnClickHandler = new OnClickHandler() {
        @Override
        public boolean onClickHandler(final View view,
                final android.app.PendingIntent pendingIntent,
                final Intent fillInIntent) {
            if (pendingIntent.isActivity()) {
                setOnDismissRunnable(new Runnable() {
                    public void run() {
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
                    }
                });

                mCallback.dismiss(false);
                return true;
            } else {
                return super.onClickHandler(view, pendingIntent, fillInIntent);
            }
        };
    };

    private KeyguardStatusViewManager mKeyguardStatusViewManager;

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
        public void setOnDismissRunnable(Runnable runnable) {
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

    @Override
    public void reset() {
        mIsVerifyUnlockOnly = false;
        mAppWidgetContainer.setCurrentPage(getWidgetPosition(R.id.keyguard_status_view));
    }

    /**
     *  Sets a runnable to run when keyguard is dismissed
     * @param runnable
     */
    protected void setOnDismissRunnable(Runnable runnable) {
        mLaunchRunnable = runnable;
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
        boolean simPukFullScreen = getResources().getBoolean(
                com.android.internal.R.bool.kg_sim_puk_account_full_screen);
        int layoutId = getLayoutIdFor(securityMode);
        if (view == null && layoutId != 0) {
            final LayoutInflater inflater = LayoutInflater.from(mContext);
            if (DEBUG) Log.v(TAG, "inflating id = " + layoutId);
            View v = inflater.inflate(layoutId, this, false);
            mSecurityViewContainer.addView(v);
            updateSecurityView(v);

            view = (KeyguardSecurityView) v;
            TextView navigationText = ((TextView) findViewById(R.id.keyguard_message_area));

            // Some devices can fit a navigation area, others cannot. On devices that cannot,
            // we display the security message in status area.
            if (navigationText != null) {
                view.setSecurityMessageDisplay(new KeyguardNavigationManager(navigationText));
            } else {
                view.setSecurityMessageDisplay(mKeyguardStatusViewManager);
            }
        }

        if (securityMode == SecurityMode.SimPin || securityMode == SecurityMode.SimPuk ||
            securityMode == SecurityMode.Account) {
            if (simPukFullScreen) {
                mAppWidgetContainer.setVisibility(View.GONE);
            }
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

        // Emulate Activity life cycle
        if (oldView != null) {
            oldView.onPause();
            oldView.setKeyguardCallback(mNullCallback); // ignore requests from old view
        }
        newView.onResume();
        newView.setKeyguardCallback(mCallback);

        final boolean needsInput = newView.needsInput();
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.setNeedsInput(needsInput);
        }

        // Find and show this child.
        final int childCount = mSecurityViewContainer.getChildCount();

        // Do flip animation to the next screen
        if (false) {
            mSecurityViewContainer.setInAnimation(
                    AnimationUtils.loadAnimation(mContext, R.anim.keyguard_security_animate_in));
            mSecurityViewContainer.setOutAnimation(
                    AnimationUtils.loadAnimation(mContext, R.anim.keyguard_security_animate_out));
        }
        final int securityViewIdForMode = getSecurityViewIdForMode(securityMode);
        for (int i = 0; i < childCount; i++) {
            if (mSecurityViewContainer.getChildAt(i).getId() == securityViewIdForMode) {
                mSecurityViewContainer.setDisplayedChild(i);
                break;
            }
        }

        if (securityMode == SecurityMode.None) {
            // Discard current runnable if we're switching back to the selector view
            setOnDismissRunnable(null);
        }
        mCurrentSecuritySelection = securityMode;
    }

    @Override
    public void onScreenTurnedOn() {
        if (DEBUG) Log.d(TAG, "screen on");
        showPrimarySecurityScreen(false);
        getSecurityView(mCurrentSecuritySelection).onResume();

        // This is a an attempt to fix bug 7137389 where the device comes back on but the entire
        // layout is blank but forcing a layout causes it to reappear (e.g. with with
        // hierarchyviewer).
        requestLayout();
    }

    @Override
    public void onScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, "screen off");
        showPrimarySecurityScreen(true);
        getSecurityView(mCurrentSecuritySelection).onPause();
    }

    @Override
    public void show() {
        onScreenTurnedOn();
    }

    private boolean isSecure() {
        SecurityMode mode = mSecurityModel.getSecurityMode();
        switch (mode) {
            case Pattern:
                return mLockPatternUtils.isLockPatternEnabled();
            case Password:
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
            case Password: return R.layout.keyguard_password_view;
            case Biometric: return R.layout.keyguard_face_unlock_view;
            case Account: return R.layout.keyguard_account_view;
            case SimPin: return R.layout.keyguard_sim_pin_view;
            case SimPuk: return R.layout.keyguard_sim_puk_view;
            default:
                return 0;
        }
    }

    private void addWidget(int appId, int pageIndex) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(mContext);
        AppWidgetProviderInfo appWidgetInfo = appWidgetManager.getAppWidgetInfo(appId);
        if (appWidgetInfo != null) {
            AppWidgetHostView view = getAppWidgetHost().createView(mContext, appId, appWidgetInfo);
            addWidget(view, pageIndex);
        } else {
            Log.w(TAG, "AppWidgetInfo was null; not adding widget id " + appId);
        }
    }

    private void addDefaultWidgets() {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        inflater.inflate(R.layout.keyguard_transport_control_view, this, true);

        View addWidget = inflater.inflate(R.layout.keyguard_add_widget, null, true);
        mAppWidgetContainer.addWidget(addWidget);
        View statusWidget = inflater.inflate(R.layout.keyguard_status_view, null, true);
        mAppWidgetContainer.addWidget(statusWidget);
        View cameraWidget = inflater.inflate(R.layout.keyguard_camera_widget, null, true);
        mAppWidgetContainer.addWidget(cameraWidget);

        inflateAndAddUserSelectorWidgetIfNecessary();
        initializeTransportControl();
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
                    int page = getWidgetPosition(R.id.keyguard_transport_control);
                    if (page != -1) {
                        mAppWidgetContainer.removeView(mTransportControl);
                        // XXX keep view attached so we still get show/hide events from AudioManager
                        KeyguardHostView.this.addView(mTransportControl);
                        mTransportControl.setVisibility(View.GONE);
                        mTransportState = TRANSPORT_GONE;
                        mTransportControl.post(mSwitchPageRunnable);
                    }
                }

                @Override
                public void onListenerAttached() {
                    if (getWidgetPosition(R.id.keyguard_transport_control) == -1) {
                        KeyguardHostView.this.removeView(mTransportControl);
                        mAppWidgetContainer.addView(mTransportControl, 0);
                        mTransportControl.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onPlayStateChanged() {
                    mTransportControl.post(mSwitchPageRunnable);
                }
            });
        }

        mKeyguardStatusViewManager = ((KeyguardStatusView)
                findViewById(R.id.keyguard_status_view_face_palm)).getManager();

    }

    private void maybePopulateWidgets() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            final int currentUser = mLockPatternUtils.getCurrentUser();
            final int disabledFeatures = dpm.getKeyguardDisabledFeatures(null, currentUser);
            if ((disabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL) != 0) {
                Log.v(TAG, "Keyguard widgets disabled because of device policy admin");
                return;
            }
        }

        View addWidget = mAppWidgetContainer.findViewById(R.id.keyguard_add_widget);
        int addPageIndex = mAppWidgetContainer.indexOfChild(addWidget);
        // This shouldn't happen, but just to be safe!
        if (addPageIndex < 0) {
            addPageIndex = 0;
        }

        // Add user-selected widget
        final int[] widgets = mLockPatternUtils.getUserDefinedWidgets();
        for (int i = widgets.length -1; i >= 0; i--) {
            if (widgets[i] != -1) {
                // We add the widgets from left to right, starting after the first page after
                // the add page. We count down, since the order will be persisted from right
                // to left, starting after camera.
                addWidget(widgets[i], addPageIndex + 1);
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

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            this.transportState = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(this.transportState);
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
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        ss.transportState = mTransportState;
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
        mTransportState = ss.transportState;
        post(mSwitchPageRunnable);
    }

    private void showAppropriateWidgetPage() {

        // The following sets the priority for showing widgets. Transport should be shown if
        // music is playing, followed by the multi-user widget if enabled, followed by the
        // status widget.
        final int pageToShow;
        if (mTransportControl.isMusicPlaying() || mTransportState == TRANSPORT_VISIBLE) {
            mTransportState = TRANSPORT_VISIBLE;
            pageToShow = mAppWidgetContainer.indexOfChild(mTransportControl);
        } else {
            UserManager mUm = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            final View multiUserView = findViewById(R.id.keyguard_multi_user_selector);
            final int multiUserPosition = mAppWidgetContainer.indexOfChild(multiUserView);
            if (multiUserPosition != -1 && mUm.getUsers(true).size() > 1) {
                pageToShow = multiUserPosition;
            } else {
                final View statusView = findViewById(R.id.keyguard_status_view);
                pageToShow = mAppWidgetContainer.indexOfChild(statusView);
            }
            if (mTransportState == TRANSPORT_VISIBLE) {
                mTransportState = TRANSPORT_INVISIBLE;
            }
        }
        mAppWidgetContainer.setCurrentPage(pageToShow);
    }

    private void inflateAndAddUserSelectorWidgetIfNecessary() {
        // if there are multiple users, we need to add the multi-user switcher widget to the
        // keyguard.
        UserManager mUm = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        List<UserInfo> users = mUm.getUsers(true);

        if (users.size() > 1) {
            KeyguardWidgetFrame userSwitcher = (KeyguardWidgetFrame)
                LayoutInflater.from(mContext).inflate(R.layout.keyguard_multi_user_selector_widget,
                        mAppWidgetContainer, false);

            // add the switcher in the first position
            mAppWidgetContainer.addView(userSwitcher, getWidgetPosition(R.id.keyguard_status_view));
            KeyguardMultiUserSelectorView multiUser = (KeyguardMultiUserSelectorView)
                    userSwitcher.getChildAt(0);

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
                        mKeyguardSelectorView.ping();
                    }
                }

            };
            multiUser.setCallback(callback);
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && mEnableMenuKey) {
            showNextSecurityScreenOrFinish(false);
            return true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }

    public void goToUserSwitcher() {
        mAppWidgetContainer.setCurrentPage(getWidgetPosition(R.id.keyguard_multi_user_selector));
    }

    public boolean handleBackKey() {
        if (mCurrentSecuritySelection != SecurityMode.None) {
            mCallback.dismiss(false);
            return true;
        }
        return false;
    }

}
