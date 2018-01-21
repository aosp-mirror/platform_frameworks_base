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
import android.media.AudioAttributes;
import android.media.AudioManager;
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

        mProvider = ApiLoader.getProvider(context).createVideoView2(this, new SuperProvider(),
                attrs, defStyleAttr, defStyleRes);
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
    public void setMediaControlView2(MediaControlView2 mediaControlView) {
        mProvider.setMediaControlView2_impl(mediaControlView);
    }

    /**
     * @hide
     */
    public MediaControlView2 getMediaControlView2() {
        return mProvider.getMediaControlView2_impl();
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
     * Sets playback speed.
     *
     * It is expressed as a multiplicative factor, where normal speed is 1.0f. If it is less than
     * or equal to zero, it will be just ignored and nothing will be changed. If it exceeds the
     * maximum speed that internal engine supports, system will determine best handling or it will
     * be reset to the normal speed 1.0f.
     * TODO: This should be revised after integration with MediaPlayer2.
     * @param speed the playback speed. It should be positive.
     * @hide
     */
    public void setSpeed(float speed) {
        mProvider.setSpeed_impl(speed);
    }

    /**
     * Returns current speed setting.
     *
     * If setSpeed() has never been called, returns the default value 1.0f.
     * @return current speed setting
     * @hide
     */
    public float getSpeed() {
        return mProvider.getSpeed_impl();
    }

    /**
     * Sets which type of audio focus will be requested during the playback, or configures playback
     * to not request audio focus. Valid values for focus requests are
     * {@link AudioManager#AUDIOFOCUS_GAIN}, {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT},
     * {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK}, and
     * {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE}. Or use
     * {@link AudioManager#AUDIOFOCUS_NONE} to express that audio focus should not be
     * requested when playback starts. You can for instance use this when playing a silent animation
     * through this class, and you don't want to affect other audio applications playing in the
     * background.
     *
     * @param focusGain the type of audio focus gain that will be requested, or
     *     {@link AudioManager#AUDIOFOCUS_NONE} to disable the use audio focus during playback.
     *
     * @hide
     */
    public void setAudioFocusRequest(int focusGain) {
        mProvider.setAudioFocusRequest_impl(focusGain);
    }

    /**
     * Sets the {@link AudioAttributes} to be used during the playback of the video.
     *
     * @param attributes non-null <code>AudioAttributes</code>.
     *
     * @hide
     */
    public void setAudioAttributes(@NonNull AudioAttributes attributes) {
        mProvider.setAudioAttributes_impl(attributes);
    }

    /**
     * Sets video path.
     *
     * @param path the path of the video.
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
    public void setOnPreparedListener(OnPreparedListener l) {
        mProvider.setOnPreparedListener_impl(l);
    }

    /**
     * @hide
     */
    public void setOnCompletionListener(OnCompletionListener l) {
        mProvider.setOnCompletionListener_impl(l);
    }

    /**
     * @hide
     */
    public void setOnErrorListener(OnErrorListener l) {
        mProvider.setOnErrorListener_impl(l);
    }

    /**
     * @hide
     */
    public void setOnInfoListener(OnInfoListener l) {
        mProvider.setOnInfoListener_impl(l);
    }

    /**
     * @hide
     */
    public void setOnViewTypeChangedListener(OnViewTypeChangedListener l) {
        mProvider.setOnViewTypeChangedListener_impl(l);
    }

    /**
     * Interface definition of a callback to be invoked when the viw type has been changed.
     * @hide
     */
    public interface OnViewTypeChangedListener {
        /**
         * Called when the view type has been changed.
         * @see VideoView2#setViewType(int)
         */
        void onViewTypeChanged(@ViewType int viewType);
    }

    /**
     * @hide
     */
    public interface OnPreparedListener {
        /**
         * Called when the media file is ready for playback.
         */
        void onPrepared();
    }

    /**
     * @hide
     */
    public interface OnCompletionListener {
        /**
         * Called when the end of a media source is reached during playback.
         */
        void onCompletion();
    }

    /**
     * @hide
     */
    public interface OnErrorListener {
        /**
         * Called to indicate an error.
         */
        boolean onError(int what, int extra);
    }

    /**
     * @hide
     */
    public interface OnInfoListener {
        /**
         * Called to indicate an info or a warning.
         * @see MediaPlayer#OnInfoListener
         *
         * @param what the type of info or warning.
         * @param extra an extra code, specific to the info.
         */
        void onInfo(int what, int extra);
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
