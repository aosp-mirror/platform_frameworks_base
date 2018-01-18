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

package android.widget;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.update.ApiLoader;
import android.media.update.VideoView2Provider;
import android.media.update.ViewProvider;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;

/**
 * TODO PUBLIC API
 * @hide
 */
public class VideoView2 extends FrameLayout {
    @IntDef({
            VIEW_TYPE_TEXTUREVIEW,
            VIEW_TYPE_SURFACEVIEW
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ViewType {}
    public static final int VIEW_TYPE_SURFACEVIEW = 1;
    public static final int VIEW_TYPE_TEXTUREVIEW = 2;

    private final VideoView2Provider mProvider;

    /**
     * @hide
     */
    public VideoView2(@NonNull Context context) {
        this(context, null);
    }

    /**
     * @hide
     */
    public VideoView2(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * @hide
     */
    public VideoView2(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    /**
     * @hide
     */
    public VideoView2(
            @NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mProvider = ApiLoader.getProvider(context).createVideoView2(this, new SuperProvider());
    }

    /**
     * @hide
     */
    public VideoView2Provider getProvider() {
        return mProvider;
    }

    /**
     * @hide
     */
    public void start() {
        mProvider.start_impl();
    }

    /**
     * @hide
     */
    public void pause() {
        mProvider.pause_impl();
    }

    /**
     * @hide
     */
    public int getDuration() {
        return mProvider.getDuration_impl();
    }

    /**
     * @hide
     */
    public int getCurrentPosition() {
        return mProvider.getCurrentPosition_impl();
    }

    /**
     * @hide
     */
    public void seekTo(int msec) {
        mProvider.seekTo_impl(msec);
    }

    /**
     * @hide
     */
    public boolean isPlaying() {
        return mProvider.isPlaying_impl();
    }

    /**
     * @hide
     */
    public int getBufferPercentage() {
        return mProvider.getBufferPercentage_impl();
    }

    /**
     * @hide
     */
    public int getAudioSessionId() {
        return mProvider.getAudioSessionId_impl();
    }

    /**
     * @hide
     */
    public void showSubtitle() {
        mProvider.showSubtitle_impl();
    }

    /**
     * @hide
     */
    public void hideSubtitle() {
        mProvider.hideSubtitle_impl();
    }

    /**
     * @hide
     */
    public void setAudioFocusRequest(int focusGain) {
        mProvider.setAudioFocusRequest_impl(focusGain);
    }

    /**
     * @hide
     */
    public void setAudioAttributes(@NonNull AudioAttributes attributes) {
        mProvider.setAudioAttributes_impl(attributes);
    }

    /**
     * @hide
     */
    public void setVideoPath(String path) {
        mProvider.setVideoPath_impl(path);
    }

    /**
     * @hide
     */
    public void setVideoURI(Uri uri) {
        mProvider.setVideoURI_impl(uri);
    }

    /**
     * @hide
     */
    public void setVideoURI(Uri uri, Map<String, String> headers) {
        mProvider.setVideoURI_impl(uri, headers);
    }

    /**
     * @hide
     */
    public void setMediaController2(MediaController2 controllerView) {
        mProvider.setMediaController2_impl(controllerView);
    }

    /**
     * @hide
     */
    public void setViewType(@ViewType int viewType) {
        mProvider.setViewType_impl(viewType);
    }

    /**
     * @hide
     */
    @ViewType
    public int getViewType() {
        return mProvider.getViewType_impl();
    }

    /**
     * @hide
     */
    public void stopPlayback() {
        mProvider.stopPlayback_impl();
    }

    /**
     * @hide
     */
    public void setOnPreparedListener(MediaPlayer.OnPreparedListener l) {
        mProvider.setOnPreparedListener_impl(l);
    }

    /**
     * @hide
     */
    public void setOnCompletionListener(MediaPlayer.OnCompletionListener l) {
        mProvider.setOnCompletionListener_impl(l);
    }

    /**
     * @hide
     */
    public void setOnErrorListener(MediaPlayer.OnErrorListener l) {
        mProvider.setOnErrorListener_impl(l);
    }

    /**
     * @hide
     */
    public void setOnInfoListener(MediaPlayer.OnInfoListener l) {
        mProvider.setOnInfoListener_impl(l);
    }

    /**
     * @hide
     */
    public void setOnViewTypeChangedListener(OnViewTypeChangedListener l) {
        mProvider.setOnViewTypeChangedListener_impl(l);
    }

    /**
     * @hide
     */
    public interface OnViewTypeChangedListener {
        /**
         * @hide
         */
        void onViewTypeChanged(@ViewType int viewType);
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
            VideoView2.super.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow_impl() {
            VideoView2.super.onDetachedFromWindow();
        }

        @Override
        public void onLayout_impl(boolean changed, int left, int top, int right, int bottom) {
            VideoView2.super.onLayout(changed, left, top, right, bottom);
        }

        @Override
        public void draw_impl(Canvas canvas) {
            VideoView2.super.draw(canvas);
        }

        @Override
        public CharSequence getAccessibilityClassName_impl() {
            return VideoView2.super.getAccessibilityClassName();
        }

        @Override
        public boolean onTouchEvent_impl(MotionEvent ev) {
            return VideoView2.super.onTouchEvent(ev);
        }

        @Override
        public boolean onTrackballEvent_impl(MotionEvent ev) {
            return VideoView2.super.onTrackballEvent(ev);
        }

        @Override
        public boolean onKeyDown_impl(int keyCode, KeyEvent event) {
            return VideoView2.super.onKeyDown(keyCode, event);
        }

        @Override
        public void onFinishInflate_impl() {
            VideoView2.super.onFinishInflate();
        }

        @Override
        public boolean dispatchKeyEvent_impl(KeyEvent event) {
            return VideoView2.super.dispatchKeyEvent(event);
        }

        @Override
        public void setEnabled_impl(boolean enabled) {
            VideoView2.super.setEnabled(enabled);
        }
    }
}
