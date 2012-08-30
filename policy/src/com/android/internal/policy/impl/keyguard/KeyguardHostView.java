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

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ViewFlipper;
import android.widget.RemoteViews.OnClickHandler;

import com.android.internal.policy.impl.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.R;

import java.io.File;
import java.util.ArrayList;

public class KeyguardHostView extends KeyguardViewBase {
    // Use this to debug all of keyguard
    public static boolean DEBUG;

    static final int APPWIDGET_HOST_ID = 0x4B455947;
    private static final String KEYGUARD_WIDGET_PREFS = "keyguard_widget_prefs";

    // time after launching EmergencyDialer before the screen goes blank.
    private static final int EMERGENCY_CALL_TIMEOUT = 10000;

    // intent action for launching emergency dialer activity.
    static final String ACTION_EMERGENCY_DIAL = "com.android.phone.EmergencyDialer.DIAL";

    private static final String TAG = "KeyguardViewHost";

    private static final int SECURITY_SELECTOR_ID = R.id.keyguard_selector_view;
    private static final int SECURITY_PATTERN_ID = R.id.keyguard_pattern_view;
    private static final int SECURITY_PASSWORD_ID = R.id.keyguard_password_view;
    private static final int SECURITY_BIOMETRIC_ID = R.id.keyguard_face_unlock_view;
    private static final int SECURITY_SIM_PIN_ID = R.id.keyguard_sim_pin_view;
    private static final int SECURITY_SIM_PUK_ID = R.id.keyguard_sim_puk_view;
    private static final int SECURITY_ACCOUNT_ID = R.id.keyguard_account_view;

    private AppWidgetHost mAppWidgetHost;
    private ViewGroup mAppWidgetContainer;
    private ViewFlipper mViewFlipper;
    private Button mEmergencyDialerButton;
    private boolean mEnableMenuKey;
    private boolean mScreenOn;
    private boolean mIsVerifyUnlockOnly;
    private int mCurrentSecurityId = SECURITY_SELECTOR_ID;

    // KeyguardSecurityViews
    final private int [] mViewIds = {
        SECURITY_SELECTOR_ID,
        SECURITY_PATTERN_ID,
        SECURITY_PASSWORD_ID,
        SECURITY_BIOMETRIC_ID,
        SECURITY_SIM_PIN_ID,
        SECURITY_SIM_PUK_ID,
        SECURITY_ACCOUNT_ID,
    };

    private ArrayList<View> mViews = new ArrayList<View>(mViewIds.length);

    protected Runnable mLaunchRunnable;

    protected int mFailedAttempts;
    private LockPatternUtils mLockPatternUtils;

    private KeyguardSecurityModel mSecurityModel;

    public KeyguardHostView(Context context) {
        this(context, null);
    }

    public KeyguardHostView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAppWidgetHost = new AppWidgetHost(mContext, APPWIDGET_HOST_ID, mOnClickHandler);
        mSecurityModel = new KeyguardSecurityModel(mContext);

        // The following enables the MENU key to work for testing automation
        mEnableMenuKey = shouldEnableMenuKey();
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        mCallback.keyguardDoneDrawing();
    }

    @Override
    protected void onFinishInflate() {
        mAppWidgetContainer = (ViewGroup) findViewById(R.id.app_widget_container);
        mAppWidgetContainer.setVisibility(VISIBLE);

        // View Flipper
        mViewFlipper = (ViewFlipper) findViewById(R.id.view_flipper);
        mViewFlipper.setInAnimation(AnimationUtils.loadAnimation(mContext,
                R.anim.keyguard_security_animate_in));
        mViewFlipper.setOutAnimation(AnimationUtils.loadAnimation(mContext,
                R.anim.keyguard_security_animate_out));

        // Initialize all security views
        for (int i = 0; i < mViewIds.length; i++) {
            View view = findViewById(mViewIds[i]);
            mViews.add(view);
            if (view != null) {
                ((KeyguardSecurityView) view).setKeyguardCallback(mCallback);
            } else {
                Log.v("*********", "Can't find view id " + mViewIds[i]);
            }
        }

        // Enable emergency dialer button
        mEmergencyDialerButton = (Button) findViewById(R.id.emergency_call_button);
        mEmergencyDialerButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                takeEmergencyCallAction();
            }
        });
    }

    void setLockPatternUtils(LockPatternUtils utils) {
        mSecurityModel.setLockPatternUtils(utils);
        mLockPatternUtils = utils;
        for (int i = 0; i < mViews.size(); i++) {
            KeyguardSecurityView ksv = (KeyguardSecurityView) mViews.get(i);
            if (ksv != null) {
                ksv.setLockPatternUtils(utils);
            } else {
                Log.w(TAG, "**** ksv was null at " + i);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAppWidgetHost.startListening();
        populateWidgets();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAppWidgetHost.stopListening();
    }

    AppWidgetHost getAppWidgetHost() {
        return mAppWidgetHost;
    }

    void addWidget(AppWidgetHostView view) {
        mAppWidgetContainer.addView(view);
    }

    private KeyguardSecurityCallback mCallback = new KeyguardSecurityCallback() {

        public void userActivity(long timeout) {
            mViewMediatorCallback.pokeWakelock(timeout);
        }

        public void dismiss(boolean authenticated) {
            showNextSecurityScreenOrFinish(authenticated);
        }

        public boolean isVerifyUnlockOnly() {
            // TODO
            return false;
        }

        public void reportSuccessfulUnlockAttempt() {
            KeyguardUpdateMonitor.getInstance(mContext).clearFailedAttempts();
        }

        public void reportFailedUnlockAttempt() {
            // TODO: handle biometric attempt differently.
            KeyguardUpdateMonitor.getInstance(mContext).reportFailedAttempt();
        }

        public int getFailedAttempts() {
            return KeyguardUpdateMonitor.getInstance(mContext).getFailedAttempts();
        }

        public void showBackupUnlock() {
            // TODO
        }

        public void keyguardDoneDrawing() {
            mViewMediatorCallback.keyguardDoneDrawing();
        }

    };

    public void takeEmergencyCallAction() {
        mCallback.userActivity(EMERGENCY_CALL_TIMEOUT);
        if (TelephonyManager.getDefault().getCallState()
                == TelephonyManager.CALL_STATE_OFFHOOK) {
            mLockPatternUtils.resumeCall();
        } else {
            Intent intent = new Intent(ACTION_EMERGENCY_DIAL);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            getContext().startActivity(intent);
        }
    }

    protected void showNextSecurityScreenOrFinish(boolean authenticated) {
        boolean finish = false;
        if (SECURITY_SELECTOR_ID == mCurrentSecurityId) {
            int realSecurityId = getSecurityViewIdForMode(mSecurityModel.getSecurityMode());
            if (realSecurityId == mCurrentSecurityId) {
                finish = true; // no security required
            } else {
                showSecurityScreen(realSecurityId); // switch to the "real" security view
            }
        } else if (authenticated) {
            if ((mCurrentSecurityId == SECURITY_PATTERN_ID
                || mCurrentSecurityId == SECURITY_PASSWORD_ID
                || mCurrentSecurityId == SECURITY_ACCOUNT_ID)) {
                finish = true;
            }
        } else {
            // Not authenticated but we were asked to dismiss so go back to selector screen.
            showSecurityScreen(SECURITY_SELECTOR_ID);
        }
        if (finish) {
            // If there's a pending runnable because the user interacted with a widget
            // and we're leaving keyguard, then run it.
            if (mLaunchRunnable != null) {
                mLaunchRunnable.run();
                mViewFlipper.setDisplayedChild(0);
                mLaunchRunnable = null;
            }
            mViewMediatorCallback.keyguardDone(true);
        }
    }

    private OnClickHandler mOnClickHandler = new OnClickHandler() {
        @Override
        public boolean onClickHandler(final View view,
                final android.app.PendingIntent pendingIntent,
                final Intent fillInIntent) {
            if (pendingIntent.isActivity()) {
                mLaunchRunnable = new Runnable() {
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
                };

                mCallback.dismiss(false);
                return true;
            } else {
                return super.onClickHandler(view, pendingIntent, fillInIntent);
            }
        };
    };

    @Override
    public void reset() {
        requestFocus();
    }

    private KeyguardSecurityView getSecurityView(int securitySelectorId) {
        final int children = mViewFlipper.getChildCount();
        for (int child = 0; child < children; child++) {
            if (mViewFlipper.getChildAt(child).getId() == securitySelectorId) {
                return ((KeyguardSecurityView)mViewFlipper.getChildAt(child));
            }
        }
        return null;
    }

    private void showSecurityScreen(int securityViewId) {

        if (securityViewId == mCurrentSecurityId) return;

        KeyguardSecurityView oldView = getSecurityView(mCurrentSecurityId);
        KeyguardSecurityView newView = getSecurityView(securityViewId);

        // Emulate Activity life cycle
        oldView.onPause();
        newView.onResume();

        mViewMediatorCallback.setNeedsInput(newView.needsInput());
        mCurrentSecurityId = securityViewId;

        // Find and show this child.
        final int childCount = mViewFlipper.getChildCount();
        for (int i = 0; i < childCount; i++) {
            if (securityViewId == mViewFlipper.getChildAt(i).getId()) {
                mViewFlipper.setDisplayedChild(i);
                break;
            }
        }
    }

    @Override
    public void onScreenTurnedOn() {
        if (DEBUG) Log.d(TAG, "screen on");
        mScreenOn = true;
        showSecurityScreen(mCurrentSecurityId);
    }

    @Override
    public void onScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, "screen off");
        mScreenOn = false;
        showSecurityScreen(SECURITY_SELECTOR_ID);
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
            showSecurityScreen(SECURITY_SELECTOR_ID);
            mViewMediatorCallback.pokeWakelock();
        } else {
            if (DEBUG) Log.d(TAG, "poking wake lock immediately");
            mViewMediatorCallback.pokeWakelock();
        }
    }

    @Override
    public void verifyUnlock() {
        SecurityMode securityMode = mSecurityModel.getSecurityMode();
        if (securityMode == KeyguardSecurityModel.SecurityMode.None) {
            mViewMediatorCallback.keyguardDone(true);
        } else if (securityMode != KeyguardSecurityModel.SecurityMode.Pattern
                && securityMode != KeyguardSecurityModel.SecurityMode.Password) {
            // can only verify unlock when in pattern/password mode
            mViewMediatorCallback.keyguardDone(false);
        } else {
            // otherwise, go to the unlock screen, see if they can verify it
            mIsVerifyUnlockOnly = true;
            showSecurityScreen(getSecurityViewIdForMode(securityMode));
        }
    }

    private int getSecurityViewIdForMode(SecurityMode securityMode) {
        switch (securityMode) {
            case None: return SECURITY_SELECTOR_ID;
            case Pattern: return SECURITY_PATTERN_ID;
            case Password: return SECURITY_PASSWORD_ID;
            case Biometric: return SECURITY_BIOMETRIC_ID;
            case Account: return SECURITY_ACCOUNT_ID;
            case SimPin: return SECURITY_SIM_PIN_ID;
            case SimPuk: return SECURITY_SIM_PUK_ID;
        }
        return 0;
    }

    private void addWidget(int appId) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(mContext);
        AppWidgetProviderInfo appWidgetInfo = appWidgetManager.getAppWidgetInfo(appId);
        AppWidgetHostView view = getAppWidgetHost().createView(mContext, appId, appWidgetInfo);
        addWidget(view);
    }

    private void populateWidgets() {
        SharedPreferences prefs = mContext.getSharedPreferences(
                KEYGUARD_WIDGET_PREFS, Context.MODE_PRIVATE);
        for (String key : prefs.getAll().keySet()) {
            int appId = prefs.getInt(key, -1);
            if (appId != -1) {
                Log.w(TAG, "populate: adding " + key);
                addWidget(appId);
            } else {
                Log.w(TAG, "populate: can't find " + key);
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
        final boolean configDisabled = res.getBoolean(R.bool.config_disableMenuKeyInLockScreen);
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

}
