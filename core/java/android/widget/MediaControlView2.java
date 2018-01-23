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
import android.media.session.MediaController;
import android.media.update.ApiLoader;
import android.media.update.MediaControlView2Provider;
import android.media.update.ViewProvider;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * TODO PUBLIC API
 * @hide
 */
public class MediaControlView2 extends FrameLayout {
    private final MediaControlView2Provider mProvider;

    public MediaControlView2(@NonNull Context context) {
        this(context, null);
    }

    public MediaControlView2(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MediaControlView2(@NonNull Context context, @Nullable AttributeSet attrs,
                            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MediaControlView2(@NonNull Context context, @Nullable AttributeSet attrs,
                            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mProvider = ApiLoader.getProvider(context)
                .createMediaControlView2(this, new SuperProvider());
    }

    public MediaControlView2Provider getProvider() {
        return mProvider;
    }

    /**
     * TODO: add docs
     */
    public void setController(MediaController controller) {
        mProvider.setController_impl(controller);
    }

    /**
     * TODO: add docs
     */
    public void show() {
        mProvider.show_impl();
    }

    /**
     * TODO: add docs
     */
    public void show(int timeout) {
        mProvider.show_impl(timeout);
    }

    /**
     * TODO: add docs
     */
    public boolean isShowing() {
        return mProvider.isShowing_impl();
    }

    /**
     * TODO: add docs
     */
    public void hide() {
        mProvider.hide_impl();
    }

    /**
     * TODO: add docs
     */
    public void showCCButton() {
        mProvider.showCCButton_impl();
    }

    /**
     * TODO: add docs
     */
    public boolean isPlaying() {
        return mProvider.isPlaying_impl();
    }

    /**
     * TODO: add docs
     */
    public int getCurrentPosition() {
        return mProvider.getCurrentPosition_impl();
    }

    /**
     * TODO: add docs
     */
    public int getBufferPercentage() {
        return mProvider.getBufferPercentage_impl();
    }

    /**
     * TODO: add docs
     */
    public boolean canPause() {
        return mProvider.canPause_impl();
    }

    /**
     * TODO: add docs
     */
    public boolean canSeekBackward() {
        return mProvider.canSeekBackward_impl();
    }

    /**
     * TODO: add docs
     */
    public boolean canSeekForward() {
        return mProvider.canSeekForward_impl();
    }

    /**
     * TODO: add docs
     */
    public void showSubtitle() {
        mProvider.showSubtitle_impl();
    }

    /**
     * TODO: add docs
     */
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
            MediaControlView2.super.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow_impl() {
            MediaControlView2.super.onDetachedFromWindow();
        }

        @Override
        public CharSequence getAccessibilityClassName_impl() {
            return MediaControlView2.super.getAccessibilityClassName();
        }

        @Override
        public boolean onTouchEvent_impl(MotionEvent ev) {
            return MediaControlView2.super.onTouchEvent(ev);
        }

        @Override
        public boolean onTrackballEvent_impl(MotionEvent ev) {
            return MediaControlView2.super.onTrackballEvent(ev);
        }

        @Override
        public boolean onKeyDown_impl(int keyCode, KeyEvent event) {
            return MediaControlView2.super.onKeyDown(keyCode, event);
        }

        @Override
        public void onFinishInflate_impl() {
            MediaControlView2.super.onFinishInflate();
        }

        @Override
        public boolean dispatchKeyEvent_impl(KeyEvent event) {
            return MediaControlView2.super.dispatchKeyEvent(event);
        }

        @Override
        public void setEnabled_impl(boolean enabled) {
            MediaControlView2.super.setEnabled(enabled);
        }
    }
}
