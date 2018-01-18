/*
 * Copyright 2018 The Android Open Source Project
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

package android.media.update;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * Helper class for connecting the public API to an updatable implementation.
 *
 * @see ViewProvider
 *
 * @hide
 */
public abstract class FrameLayoutHelper<T extends ViewProvider> extends FrameLayout {
    /** @hide */
    final public T mProvider;

    /** @hide */
    public FrameLayoutHelper(ProviderCreator<T> creator,
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mProvider = creator.createProvider(this, new SuperProvider());
    }

    /** @hide */
    // TODO @SystemApi
    public T getProvider() {
        return mProvider;
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return mProvider.getAccessibilityClassName_impl();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return mProvider.onTouchEvent_impl(ev);
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        return mProvider.onTrackballEvent_impl(ev);
    }

    @Override
    public void onFinishInflate() {
        mProvider.onFinishInflate_impl();
    }

    @Override
    public void setEnabled(boolean enabled) {
        mProvider.setEnabled_impl(enabled);
    }

    @Override
    protected void onAttachedToWindow() {
        mProvider.onAttachedToWindow_impl();
    }

    @Override
    protected void onDetachedFromWindow() {
        mProvider.onDetachedFromWindow_impl();
    }

    /** @hide */
    public class SuperProvider implements ViewProvider {
        @Override
        public CharSequence getAccessibilityClassName_impl() {
            return FrameLayoutHelper.super.getAccessibilityClassName();
        }

        @Override
        public boolean onTouchEvent_impl(MotionEvent ev) {
            return FrameLayoutHelper.super.onTouchEvent(ev);
        }

        @Override
        public boolean onTrackballEvent_impl(MotionEvent ev) {
            return FrameLayoutHelper.super.onTrackballEvent(ev);
        }

        @Override
        public void onFinishInflate_impl() {
            FrameLayoutHelper.super.onFinishInflate();
        }

        @Override
        public void setEnabled_impl(boolean enabled) {
            FrameLayoutHelper.super.setEnabled(enabled);
        }

        @Override
        public void onAttachedToWindow_impl() {
            FrameLayoutHelper.super.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow_impl() {
            FrameLayoutHelper.super.onDetachedFromWindow();
        }
    }

    /** @hide */
    @FunctionalInterface
    public interface ProviderCreator<U extends ViewProvider> {
        U createProvider(FrameLayoutHelper<U> instance, ViewProvider superProvider);
    }
}
