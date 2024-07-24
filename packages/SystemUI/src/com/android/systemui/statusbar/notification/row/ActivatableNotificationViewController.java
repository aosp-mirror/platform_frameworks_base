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
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/**
 * Controller for {@link ActivatableNotificationView}
 */
public class ActivatableNotificationViewController
        extends ViewController<ActivatableNotificationView> {
    private final ExpandableOutlineViewController mExpandableOutlineViewController;
    private final AccessibilityManager mAccessibilityManager;
    private final FalsingManager mFalsingManager;
    private final TouchHandler mTouchHandler = new TouchHandler();

    @Inject
    public ActivatableNotificationViewController(ActivatableNotificationView view,
            ExpandableOutlineViewController expandableOutlineViewController,
            AccessibilityManager accessibilityManager, FalsingManager falsingManager) {
        super(view);
        mExpandableOutlineViewController = expandableOutlineViewController;
        mAccessibilityManager = accessibilityManager;
        mFalsingManager = falsingManager;
    }

    /**
     * Initialize the controller, setting up handlers and other behavior.
     */
    @Override
    public void onInit() {
        mExpandableOutlineViewController.init();
        mView.setOnTouchListener(mTouchHandler);
        mView.setTouchHandler(mTouchHandler);
    }

    @Override
    protected void onViewAttached() {

    }

    @Override
    protected void onViewDetached() {

    }

    class TouchHandler implements Gefingerpoken, View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            boolean result = false;
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                mView.setLastActionUpTime(ev.getEventTime());
            }
            // With a11y, just do nothing.
            if (mAccessibilityManager.isTouchExplorationEnabled()) {
                return false;
            }

            if (ev.getAction() == MotionEvent.ACTION_UP) {
                // If this is a false tap, capture the even so it doesn't result in a click.
                return mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY);
            }
            return result;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
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
