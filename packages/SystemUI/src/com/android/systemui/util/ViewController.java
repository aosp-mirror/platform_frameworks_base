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

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;

/**
 * Utility class that handles view lifecycle events for View Controllers.
 *
 * Implementations should handle setup and teardown related activities inside of
 * {@link #onViewAttached()} and {@link  #onViewDetached()}. Be sure to call {@link #init()} on
 * any child controllers that this uses. This can be done in {@link #onInit()} if the
 * controllers are injected, or right after creation time of the child controller.
 *
 * Tip: View "attachment" happens top down - parents are notified that they are attached before
 * any children. That means that if you call a method on a child controller in
 * {@link #onViewAttached()}, the child controller may not have had its onViewAttach method
 * called, so it may not be fully set up.
 *
 * As such, make sure that methods on your controller are safe to call _before_ its {@link #init()}
 * and {@link #onViewAttached()} methods are called. Specifically, if your controller must call
 * {@link View#findViewById(int)} on its root view to setup member variables, do so in its
 * constructor. Save {@link #onViewAttached()} for things that can happen post-construction - adding
 * listeners, dynamically changing content, or other runtime decisions.
 *
 * @param <T> View class that this ViewController is for.
 */
public abstract class ViewController<T extends View> {
    protected final T mView;
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

    protected ViewController(T view) {
        mView = view;
    }

    /**
     * Call immediately after constructing Controller in order to handle view lifecycle events.
     *
     * Generally speaking, you don't want to override this method. Instead, override
     * {@link #onInit()} as a way to have an run-once idempotent method that you can use for
     * setup of your ViewController.
     */
    public void init() {
        if (mInited) {
            return;
        }
        onInit();
        mInited = true;

        if (isAttachedToWindow()) {
            mOnAttachStateListener.onViewAttachedToWindow(mView);
        }
        addOnAttachStateChangeListener(mOnAttachStateListener);
    }

    /**
     * Run once when {@link #init()} is called.
     *
     * Override this to perform idempotent, one-time setup that your controller needs. It will
     * be called before {@link #onViewAttached()}.
     */
    protected void onInit() {}

    protected Context getContext() {
        return mView.getContext();
    }

    protected Resources getResources() {
        return mView.getResources();
    }

    public boolean isAttachedToWindow() {
        return mView != null && mView.isAttachedToWindow();
    }

    /** Add an OnAttachStateListener to the view. Does nothing if the view is null. */
    public void addOnAttachStateChangeListener(View.OnAttachStateChangeListener listener) {
        if (mView != null) {
            mView.addOnAttachStateChangeListener(listener);
        }
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
