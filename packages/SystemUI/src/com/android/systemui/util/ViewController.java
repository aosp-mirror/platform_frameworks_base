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

package com.android.systemui.util;

import android.view.View;
import android.view.View.OnAttachStateChangeListener;

/**
 * Utility class that handles view lifecycle events for View Controllers.
 *
 * Implementations should handle setup and teardown related activities inside of
 * {@link #onViewAttached()} and {@link  #onViewDetached()}.
 */
public abstract class ViewController {
    private final View mView;
    private boolean mInited;

    private OnAttachStateChangeListener mOnAttachStateListener = new OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
            ViewController.this.onViewAttached();
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            ViewController.this.onViewDetached();
        }
    };

    protected ViewController(View view) {
        mView = view;
    }

    /** Call immediately after constructing Controller in order to handle view lifecycle events. */
    public void init() {
        if (mInited) {
            return;
        }
        mInited = true;

        if (mView.isAttachedToWindow()) {
            mOnAttachStateListener.onViewAttachedToWindow(mView);
        }
        mView.addOnAttachStateChangeListener(mOnAttachStateListener);
    }

    /**
     * Called when the view is attached and a call to {@link #init()} has been made in either order.
     */
    protected abstract void onViewAttached();

    /**
     * Called when the view is detached.
     */
    protected abstract void onViewDetached();
}
