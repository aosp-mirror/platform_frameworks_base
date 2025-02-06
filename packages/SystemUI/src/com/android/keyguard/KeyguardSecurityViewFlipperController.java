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

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

import static com.android.systemui.flags.Flags.LOCKSCREEN_ENABLE_LANDSCAPE;

import android.util.Log;
import android.view.LayoutInflater;

import androidx.asynclayoutinflater.view.AsyncLayoutInflater;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardInputViewController.Factory;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.dagger.KeyguardBouncerScope;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.res.R;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

/**
 * Controller for a {@link KeyguardSecurityViewFlipper}.
 */
@KeyguardBouncerScope
public class KeyguardSecurityViewFlipperController
        extends ViewController<KeyguardSecurityViewFlipper> {

    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardSecurityView";

    private final List<KeyguardInputViewController<KeyguardInputView>> mChildren =
            new ArrayList<>();
    private final LayoutInflater mLayoutInflater;
    private final AsyncLayoutInflater mAsyncLayoutInflater;
    private final EmergencyButtonController.Factory mEmergencyButtonControllerFactory;
    private final Factory mKeyguardSecurityViewControllerFactory;
    private final FeatureFlags mFeatureFlags;
    private final List<OnViewInflatedCallback> mOnViewInflatedListeners = new ArrayList<>();
    private final Set<SecurityMode> mSecurityModeInProgress = new HashSet<>();

    @Inject
    protected KeyguardSecurityViewFlipperController(KeyguardSecurityViewFlipper view,
            LayoutInflater layoutInflater,
            AsyncLayoutInflater asyncLayoutInflater,
            KeyguardInputViewController.Factory keyguardSecurityViewControllerFactory,
            EmergencyButtonController.Factory emergencyButtonControllerFactory,
            FeatureFlags featureFlags) {
        super(view);
        mKeyguardSecurityViewControllerFactory = keyguardSecurityViewControllerFactory;
        mLayoutInflater = layoutInflater;
        mEmergencyButtonControllerFactory = emergencyButtonControllerFactory;
        mAsyncLayoutInflater = asyncLayoutInflater;
        mFeatureFlags = featureFlags;
    }

    @Override
    protected void onViewAttached() {

    }

    @Override
    protected void onViewDetached() {

    }

    public void reset() {
        for (KeyguardInputViewController<KeyguardInputView> child : mChildren) {
            child.reset();
        }
    }

    /** Handles density or font scale changes. */
    public void clearViews() {
        mView.removeAllViews();
        mChildren.clear();
    }


    @VisibleForTesting
    void getSecurityView(SecurityMode securityMode,
            KeyguardSecurityCallback keyguardSecurityCallback,
            OnViewInflatedCallback onViewInflatedCallback) {
        for (KeyguardInputViewController<KeyguardInputView> child : mChildren) {
            if (child.getSecurityMode() == securityMode) {
                onViewInflatedCallback.onViewInflated(child);
                return;
            }
        }

        // Prevent multiple inflations for the same security mode. Instead, add callback to a list
        // and then notify each in order when the view is inflated.
        synchronized (mOnViewInflatedListeners) {
            mOnViewInflatedListeners.add(onViewInflatedCallback);
        }
        if (!mSecurityModeInProgress.contains(securityMode)) {
            mSecurityModeInProgress.add(securityMode);
            asynchronouslyInflateView(securityMode, keyguardSecurityCallback);
        }
    }

    /**
     * Asynchronously inflate view and then add it to view flipper on the main thread when complete.
     *
     * OnInflateFinishedListener will be called on the main thread.
     *
     * @param securityMode
     * @param keyguardSecurityCallback
     */
    private void asynchronouslyInflateView(SecurityMode securityMode,
            KeyguardSecurityCallback keyguardSecurityCallback) {
        int layoutId = mFeatureFlags.isEnabled(LOCKSCREEN_ENABLE_LANDSCAPE)
                ? getLayoutIdFor(securityMode) : getLegacyLayoutIdFor(securityMode);
        if (layoutId != 0) {
            if (DEBUG) {
                Log.v(TAG, "inflating on bg thread id = " + layoutId + " .");
            }
            mAsyncLayoutInflater.inflate(layoutId, mView,
                    (view, resId, parent) -> {
                        mView.addView(view);
                        mSecurityModeInProgress.remove(securityMode);
                        KeyguardInputViewController<KeyguardInputView> childController =
                                mKeyguardSecurityViewControllerFactory.create(
                                        (KeyguardInputView) view,
                                        securityMode, keyguardSecurityCallback);
                        childController.init();
                        mChildren.add(childController);

                        List<OnViewInflatedCallback> callbacks;
                        synchronized (mOnViewInflatedListeners) {
                            callbacks = new ArrayList<>(mOnViewInflatedListeners);
                            mOnViewInflatedListeners.clear();
                        }
                        for (OnViewInflatedCallback callback : callbacks) {
                            callback.onViewInflated(childController);
                        }

                        // Single bouncer constrains are default
                        if (mFeatureFlags.isEnabled(LOCKSCREEN_ENABLE_LANDSCAPE)) {
                            boolean useSplitBouncer =
                                    getResources().getBoolean(R.bool.update_bouncer_constraints)
                                        && getResources().getConfiguration().orientation
                                            == ORIENTATION_LANDSCAPE;
                            updateConstraints(useSplitBouncer);
                        }
                    });
        }
    }

    private int getLayoutIdFor(SecurityMode securityMode) {
        // TODO (b/297863911, b/297864907) - implement motion layout for other bouncers
        switch (securityMode) {
            case Pattern: return R.layout.keyguard_pattern_motion_layout;
            case PIN: return R.layout.keyguard_pin_motion_layout;
            case Password: return R.layout.keyguard_password_motion_layout;
            case SimPin: return R.layout.keyguard_sim_pin_view;
            case SimPuk: return R.layout.keyguard_sim_puk_view;
            default:
                return 0;
        }
    }

    private int getLegacyLayoutIdFor(SecurityMode securityMode) {
        switch (securityMode) {
            case Pattern: return R.layout.keyguard_pattern_view;
            case PIN: return R.layout.keyguard_pin_view;
            case Password: return R.layout.keyguard_password_view;
            case SimPin: return R.layout.keyguard_sim_pin_view;
            case SimPuk: return R.layout.keyguard_sim_puk_view;
            default:
                return 0;
        }
    }

    /** Updates the keyguard view's constraints (single or split constraints).
     *  Split constraints are only used for small landscape screens.
     *  Only called when flag LANDSCAPE_ENABLE_LOCKSCREEN is enabled. */
    public void updateConstraints(boolean useSplitBouncer) {
        mView.updateConstraints(useSplitBouncer);
    }

    /** Makes the supplied child visible if it is contained win this view, */
    public void show(KeyguardInputViewController<KeyguardInputView> childController) {
        int index = childController.getIndexIn(mView);
        if (index != -1) {
            mView.setDisplayedChild(index);
        }
    }

    /** Listener to when view has finished inflation. */
    public interface OnViewInflatedCallback {
        /** Notifies that view has been inflated */
        void onViewInflated(KeyguardInputViewController<KeyguardInputView> controller);
    }
}
