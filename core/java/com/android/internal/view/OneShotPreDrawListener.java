/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.internal.view;

import android.view.View;
import android.view.ViewTreeObserver;

/**
 * An OnPreDrawListener that will remove itself after one OnPreDraw call. Typical
 * usage is:
 * <pre><code>
 *     OneShotPreDrawListener.add(view, () -> { view.doSomething(); })
 * </code></pre>
 * <p>
 * The listener will also remove itself from the ViewTreeObserver when the view
 * is detached from the view hierarchy. In that case, the Runnable will never be
 * executed.
 */
public class OneShotPreDrawListener implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {
    private final View mView;
    private ViewTreeObserver mViewTreeObserver;
    private final Runnable mRunnable;
    private final boolean mReturnValue;

    private OneShotPreDrawListener(View view, boolean returnValue, Runnable runnable) {
        mView = view;
        mViewTreeObserver = view.getViewTreeObserver();
        mRunnable = runnable;
        mReturnValue = returnValue;
    }

    /**
     * Creates a OneShotPreDrawListener and adds it to view's ViewTreeObserver. The
     * return value from the OnPreDrawListener is {@code true}.
     *
     * @param view The view whose ViewTreeObserver the OnPreDrawListener should listen.
     * @param runnable The Runnable to execute in the OnPreDraw (once)
     * @return The added OneShotPreDrawListener. It can be removed prior to
     * the onPreDraw by calling {@link #removeListener()}.
     */
    public static OneShotPreDrawListener add(View view, Runnable runnable) {
        return add(view, true, runnable);
    }

    /**
     * Creates a OneShotPreDrawListener and adds it to view's ViewTreeObserver.
     *
     * @param view The view whose ViewTreeObserver the OnPreDrawListener should listen.
     * @param returnValue The value to be returned from the OnPreDrawListener.
     * @param runnable The Runnable to execute in the OnPreDraw (once)
     * @return The added OneShotPreDrawListener. It can be removed prior to
     * the onPreDraw by calling {@link #removeListener()}.
     */
    public static OneShotPreDrawListener add(View view, boolean returnValue, Runnable runnable) {
        OneShotPreDrawListener listener = new OneShotPreDrawListener(view, returnValue, runnable);
        view.getViewTreeObserver().addOnPreDrawListener(listener);
        view.addOnAttachStateChangeListener(listener);
        return listener;
    }

    @Override
    public boolean onPreDraw() {
        removeListener();
        mRunnable.run();
        return mReturnValue;
    }

    /**
     * Removes the listener from the ViewTreeObserver. This is useful to call if the
     * callback should be removed prior to {@link #onPreDraw()}.
     */
    public void removeListener() {
        if (mViewTreeObserver.isAlive()) {
            mViewTreeObserver.removeOnPreDrawListener(this);
        } else {
            mView.getViewTreeObserver().removeOnPreDrawListener(this);
        }
        mView.removeOnAttachStateChangeListener(this);
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        mViewTreeObserver = v.getViewTreeObserver();
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        removeListener();
    }
}
