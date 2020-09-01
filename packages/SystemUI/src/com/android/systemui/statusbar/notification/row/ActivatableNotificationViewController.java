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

package com.android.systemui.statusbar.notification.row;

import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import com.android.systemui.Gefingerpoken;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.phone.DoubleTapHelper;

import javax.inject.Inject;

/**
 * Controller for {@link ActivatableNotificationView}
 */
public class ActivatableNotificationViewController {
    private final ActivatableNotificationView mView;
    private final ExpandableOutlineViewController mExpandableOutlineViewController;
    private final AccessibilityManager mAccessibilityManager;
    private final FalsingManager mFalsingManager;
    private DoubleTapHelper mDoubleTapHelper;
    private boolean mNeedsDimming;

    private TouchHandler mTouchHandler = new TouchHandler();

    @Inject
    public ActivatableNotificationViewController(ActivatableNotificationView view,
            ExpandableOutlineViewController expandableOutlineViewController,
            AccessibilityManager accessibilityManager, FalsingManager falsingManager) {
        mView = view;
        mExpandableOutlineViewController = expandableOutlineViewController;
        mAccessibilityManager = accessibilityManager;
        mFalsingManager = falsingManager;

        mView.setOnActivatedListener(new ActivatableNotificationView.OnActivatedListener() {
            @Override
            public void onActivated(ActivatableNotificationView view) {
                mFalsingManager.onNotificationActive();
            }

            @Override
            public void onActivationReset(ActivatableNotificationView view) {
            }
        });
    }

    /**
     * Initialize the controller, setting up handlers and other behavior.
     */
    public void init() {
        mExpandableOutlineViewController.init();
        mDoubleTapHelper = new DoubleTapHelper(mView, (active) -> {
            if (active) {
                mView.makeActive();
                mFalsingManager.onNotificationActive();
            } else {
                mView.makeInactive(true /* animate */);
            }
        }, mView::performClick, mView::handleSlideBack,
                mFalsingManager::onNotificationDoubleTap);
        mView.setOnTouchListener(mTouchHandler);
        mView.setTouchHandler(mTouchHandler);
        mView.setOnDimmedListener(dimmed -> {
            mNeedsDimming = dimmed;
        });
        mView.setAccessibilityManager(mAccessibilityManager);
    }

    class TouchHandler implements Gefingerpoken, View.OnTouchListener {
        private boolean mBlockNextTouch;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            boolean result;
            if (mBlockNextTouch) {
                mBlockNextTouch = false;
                return true;
            }
            if (mNeedsDimming && !mAccessibilityManager.isTouchExplorationEnabled()
                    && mView.isInteractive()) {
                if (mNeedsDimming && !mView.isDimmed()) {
                    // We're actually dimmed, but our content isn't dimmable,
                    // let's ensure we have a ripple
                    return false;
                }
                result = mDoubleTapHelper.onTouchEvent(ev, mView.getActualHeight());
            } else {
                return false;
            }
            return result;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (mNeedsDimming && ev.getActionMasked() == MotionEvent.ACTION_DOWN
                    && mView.disallowSingleClick(ev)
                    && !mAccessibilityManager.isTouchExplorationEnabled()) {
                if (!mView.isActive()) {
                    return true;
                } else if (!mDoubleTapHelper.isWithinDoubleTapSlop(ev)) {
                    mBlockNextTouch = true;
                    mView.makeInactive(true /* animate */);
                    return true;
                }
            }
            return false;
        }

        /**
         * Use {@link #onTouch(View, MotionEvent) instead}.
         */
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            return false;
        }
    }
}
