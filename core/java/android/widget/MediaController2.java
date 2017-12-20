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
 * limitations under the License.
 */

package android.widget;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.media.session.MediaController;
import android.media.update.ApiLoader;
import android.media.update.MediaController2Provider;
import android.media.update.ViewProvider;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;

/**
 * TODO PUBLIC API
 * @hide
 */
public class MediaController2 extends FrameLayout {
    private final MediaController2Provider mProvider;

    public MediaController2(@NonNull Context context) {
        this(context, null);
    }

    public MediaController2(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MediaController2(@NonNull Context context, @Nullable AttributeSet attrs,
                            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MediaController2(@NonNull Context context, @Nullable AttributeSet attrs,
                            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mProvider = ApiLoader.getProvider(context)
                .createMediaController2(this, new SuperProvider());
    }

    public void setController(MediaController controller) {
        mProvider.setController_impl(controller);
    }

    public void setAnchorView(View view) {
        mProvider.setAnchorView_impl(view);
    }

    public void show() {
        mProvider.show_impl();
    }

    public void show(int timeout) {
        mProvider.show_impl(timeout);
    }

    public boolean isShowing() {
        return mProvider.isShowing_impl();
    }

    public void hide() {
        mProvider.hide_impl();
    }

    public void setPrevNextListeners(OnClickListener next, OnClickListener prev) {
        mProvider.setPrevNextListeners_impl(next, prev);
    }

    public void showCCButton() {
        mProvider.showCCButton_impl();
    }

    public boolean isPlaying() {
        return mProvider.isPlaying_impl();
    }

    public int getCurrentPosition() {
        return mProvider.getCurrentPosition_impl();
    }

    public int getBufferPercentage() {
        return mProvider.getBufferPercentage_impl();
    }

    public boolean canPause() {
        return mProvider.canPause_impl();
    }

    public boolean canSeekBackward() {
        return mProvider.canSeekBackward_impl();
    }

    public boolean canSeekForward() {
        return mProvider.canSeekForward_impl();
    }

    public void showSubtitle() {
        mProvider.showSubtitle_impl();
    }

    public void hideSubtitle() {
        mProvider.hideSubtitle_impl();
    }

    @Override
    protected void onAttachedToWindow() {
        mProvider.onAttachedToWindow_impl();
    }

    @Override
    protected void onDetachedFromWindow() {
        mProvider.onDetachedFromWindow_impl();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mProvider.onLayout_impl(changed, left, top, right, bottom);
    }

    @Override
    public void draw(Canvas canvas) {
        mProvider.draw_impl(canvas);
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mProvider.onKeyDown_impl(keyCode, event);
    }

    @Override
    public void onFinishInflate() {
        mProvider.onFinishInflate_impl();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mProvider.dispatchKeyEvent_impl(event);
    }

    @Override
    public void setEnabled(boolean enabled) {
        mProvider.setEnabled_impl(enabled);
    }

    private class SuperProvider implements ViewProvider {
        @Override
        public void onAttachedToWindow_impl() {
            MediaController2.super.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow_impl() {
            MediaController2.super.onDetachedFromWindow();
        }

        @Override
        public void onLayout_impl(boolean changed, int left, int top, int right, int bottom) {
            MediaController2.super.onLayout(changed, left, top, right, bottom);
        }

        @Override
        public void draw_impl(Canvas canvas) {
            MediaController2.super.draw(canvas);
        }

        @Override
        public CharSequence getAccessibilityClassName_impl() {
            return MediaController2.super.getAccessibilityClassName();
        }

        @Override
        public boolean onTouchEvent_impl(MotionEvent ev) {
            return MediaController2.super.onTouchEvent(ev);
        }

        @Override
        public boolean onTrackballEvent_impl(MotionEvent ev) {
            return MediaController2.super.onTrackballEvent(ev);
        }

        @Override
        public boolean onKeyDown_impl(int keyCode, KeyEvent event) {
            return MediaController2.super.onKeyDown(keyCode, event);
        }

        @Override
        public void onFinishInflate_impl() {
            MediaController2.super.onFinishInflate();
        }

        @Override
        public boolean dispatchKeyEvent_impl(KeyEvent event) {
            return MediaController2.super.dispatchKeyEvent(event);
        }

        @Override
        public void setEnabled_impl(boolean enabled) {
            MediaController2.super.setEnabled(enabled);
        }
    }
}
