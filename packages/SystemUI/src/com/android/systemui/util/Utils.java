/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.util;

import android.view.View;

import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.statusbar.CommandQueue;

import java.util.List;
import java.util.function.Consumer;

public class Utils {

    /**
     * Allows lambda iteration over a list. It is done in reverse order so it is safe
     * to add or remove items during the iteration.  Skips over null items.
     */
    public static <T> void safeForeach(List<T> list, Consumer<T> c) {
        for (int i = list.size() - 1; i >= 0; i--) {
            T item = list.get(i);
            if (item != null) {
                c.accept(item);
            }
        }
    }

    /**
     * Sets the visibility of an UI element according to the DISABLE_* flags in
     * {@link android.app.StatusBarManager}.
     */
    public static class DisableStateTracker implements CommandQueue.Callbacks,
            View.OnAttachStateChangeListener {
        private final int mMask1;
        private final int mMask2;
        private View mView;
        private boolean mDisabled;

        public DisableStateTracker(int disableMask, int disable2Mask) {
            mMask1 = disableMask;
            mMask2 = disable2Mask;
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            mView = v;
            SysUiServiceProvider.getComponent(v.getContext(), CommandQueue.class)
                    .addCallback(this);
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            SysUiServiceProvider.getComponent(mView.getContext(), CommandQueue.class)
                    .removeCallback(this);
            mView = null;
        }

        /**
         * Sets visibility of this {@link View} given the states passed from
         * {@link com.android.systemui.statusbar.CommandQueue.Callbacks#disable(int, int, int)}.
         */
        @Override
        public void disable(int displayId, int state1, int state2, boolean animate) {
            if (displayId != mView.getDisplay().getDisplayId()) {
                return;
            }
            final boolean disabled = ((state1 & mMask1) != 0) || ((state2 & mMask2) != 0);
            if (disabled == mDisabled) return;
            mDisabled = disabled;
            mView.setVisibility(disabled ? View.GONE : View.VISIBLE);
        }

        /** @return {@code true} if and only if this {@link View} is currently disabled */
        public boolean isDisabled() {
            return mDisabled;
        }
    }
}
