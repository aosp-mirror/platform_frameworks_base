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

import android.content.res.ColorStateList;
import android.service.trust.TrustAgentService;
import android.util.Log;
import android.util.MathUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.dagger.KeyguardBouncerScope;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for a {@link KeyguardHostView}. */
@KeyguardBouncerScope
public class KeyguardHostViewController extends ViewController<KeyguardHostView> {
    private static final String TAG = "KeyguardViewBase";

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardSecurityContainerController mKeyguardSecurityContainerController;
    private final LockPatternUtils mLockPatternUtils;
    private final ViewMediatorCallback mViewMediatorCallback;

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onUserSwitchComplete(int userId) {
                    mView.getSecurityContainer().showPrimarySecurityScreen(false /* turning off */);
                }

                @Override
                public void onTrustGrantedWithFlags(int flags, int userId) {
                    if (userId != KeyguardUpdateMonitor.getCurrentUser()) return;
                    boolean bouncerVisible = mView.isVisibleToUser();
                    boolean initiatedByUser =
                            (flags & TrustAgentService.FLAG_GRANT_TRUST_INITIATED_BY_USER) != 0;
                    boolean dismissKeyguard =
                            (flags & TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD) != 0;

                    if (initiatedByUser || dismissKeyguard) {
                        if (mViewMediatorCallback.isScreenOn()
                                && (bouncerVisible || dismissKeyguard)) {
                            if (!bouncerVisible) {
                                // The trust agent dismissed the keyguard without the user proving
                                // that they are present (by swiping up to show the bouncer). That's
                                // fine if the user proved presence via some other way to the trust
                                //agent.
                                Log.i(TAG, "TrustAgent dismissed Keyguard.");
                            }
                            mView.dismiss(false /* authenticated */, userId,
                                    /* bypassSecondaryLockScreen */ false);
                        } else {
                            mViewMediatorCallback.playTrustedSound();
                        }
                    }
                }
            };

    @Inject
    public KeyguardHostViewController(KeyguardHostView view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardSecurityContainerController keyguardSecurityContainerController,
            LockPatternUtils lockPatternUtils,
            ViewMediatorCallback viewMediatorCallback) {
        super(view);
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardSecurityContainerController = keyguardSecurityContainerController;
        mLockPatternUtils = lockPatternUtils;
        mViewMediatorCallback = viewMediatorCallback;
    }

    /** Initialize the Controller. */
    public void init() {
        super.init();
        mView.setLockPatternUtils(mLockPatternUtils);
        mView.setViewMediatorCallback(mViewMediatorCallback);
        mKeyguardSecurityContainerController.init();
    }

    @Override
    protected void onViewAttached() {
        mKeyguardUpdateMonitor.registerCallback(mUpdateCallback);
    }

    @Override
    protected void onViewDetached() {
        mKeyguardUpdateMonitor.removeCallback(mUpdateCallback);
    }

     /** Called before this view is being removed. */
    public void cleanUp() {
        mKeyguardSecurityContainerController.onPause();
    }

    public void resetSecurityContainer() {
        mView.resetSecurityContainer();
    }

    public boolean dismiss(int activeUserId) {
        return mView.dismiss(activeUserId);
    }

    public void onResume() {
        mView.onResume();
    }

    public CharSequence getAccessibilityTitleForCurrentMode() {
        return mView.getAccessibilityTitleForCurrentMode();
    }

    public void showErrorMessage(CharSequence customMessage) {
        mView.showErrorMessage(customMessage);
    }

    public void appear(int statusBarHeight) {
        // We might still be collapsed and the view didn't have time to layout yet or still
        // be small, let's wait on the predraw to do the animation in that case.
        if (mView.getHeight() != 0 && mView.getHeight() != statusBarHeight) {
            mView.startAppearAnimation();
        } else {
            mView.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            mView.getViewTreeObserver().removeOnPreDrawListener(this);
                            mView.startAppearAnimation();
                            return true;
                        }
                    });
            mView.requestLayout();
        }
    }

    public void showPromptReason(int reason) {
        mView.showPromptReason(reason);
    }

    public void showMessage(String message, ColorStateList colorState) {
        mView.showMessage(message, colorState);
    }

    public void setOnDismissAction(ActivityStarter.OnDismissAction action, Runnable cancelAction) {
        mView.setOnDismissAction(action, cancelAction);
    }

    public void cancelDismissAction() {
        mView.cancelDismissAction();
    }

    public void startDisappearAnimation(Runnable runnable) {
        mView.startDisappearAnimation(runnable);
    }

    public void onPause() {
        mView.onPause();
    }

    public void showPrimarySecurityScreen() {
        mView.showPrimarySecurityScreen();
    }

    public void setExpansion(float fraction) {
        float alpha = MathUtils.map(KeyguardBouncer.ALPHA_EXPANSION_THRESHOLD, 1, 1, 0, fraction);
        mView.setAlpha(MathUtils.constrain(alpha, 0f, 1f));
        mView.setTranslationY(fraction * mView.getHeight());
    }

    public void onStartingToHide() {
        mView.onStartingToHide();
    }

    public boolean hasDismissActions() {
        return mView.hasDismissActions();
    }

    public SecurityMode getCurrentSecurityMode() {
        return mView.getCurrentSecurityMode();
    }

    public int getTop() {
        int top = mView.getTop();
        // The password view has an extra top padding that should be ignored.
        if (getCurrentSecurityMode() == SecurityMode.Password) {
            View messageArea = mView.findViewById(R.id.keyguard_message_area);
            top += messageArea.getTop();
        }
        return top;
    }

    public boolean handleBackKey() {
        return mView.handleBackKey();
    }

    public boolean shouldEnableMenuKey() {
        return mView.shouldEnableMenuKey();
    }

    public boolean interceptMediaKey(KeyEvent event) {
        return mView.interceptMediaKey(event);
    }

    public void finish(boolean strongAuth, int currentUser) {
        mView.finish(strongAuth, currentUser);
    }


}
