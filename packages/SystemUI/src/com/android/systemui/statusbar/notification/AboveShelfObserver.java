/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.statusbar.ExpandableNotificationRow;

/**
 * An observer that listens to the above shelf state and can notify listeners
 */
public class AboveShelfObserver implements AboveShelfChangedListener {

    private final ViewGroup mHostLayout;
    private boolean mHasViewsAboveShelf = false;
    private HasViewAboveShelfChangedListener mListener;

    public AboveShelfObserver(ViewGroup hostLayout) {
        mHostLayout = hostLayout;
    }

    public void setListener(HasViewAboveShelfChangedListener listener) {
        mListener = listener;
    }

    @Override
    public void onAboveShelfStateChanged(boolean aboveShelf) {
        boolean hasViewsAboveShelf = aboveShelf;
        if (!hasViewsAboveShelf && mHostLayout != null) {
            int n = mHostLayout.getChildCount();
            for (int i = 0; i < n; i++) {
                View child = mHostLayout.getChildAt(i);
                if (child instanceof ExpandableNotificationRow) {
                    if (((ExpandableNotificationRow) child).isAboveShelf()) {
                        hasViewsAboveShelf = true;
                        break;
                    }
                }
            }
        }
        if (mHasViewsAboveShelf != hasViewsAboveShelf) {
            mHasViewsAboveShelf = hasViewsAboveShelf;
            if (mListener != null) {
                mListener.onHasViewsAboveShelfChanged(hasViewsAboveShelf);
            }
        }
    }

    @VisibleForTesting
    boolean hasViewsAboveShelf() {
        return mHasViewsAboveShelf;
    }

    public interface HasViewAboveShelfChangedListener {
        void onHasViewsAboveShelfChanged(boolean hasViewsAboveShelf);
    }
}
