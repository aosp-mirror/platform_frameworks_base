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

// TODO: Use @link tag to refer MediaPlayer2 in docs once MediaPlayer2.java is submitted. Same to
// MediaSession2.
// TODO: change the reference from MediaPlayer to MediaPlayer2.
/**
 * Displays a video file.  VideoView2 class is a View class which is wrapping MediaPlayer2 so that
 * developers can easily implement a video rendering application.
 *
 * <p>
 * <em> Data sources that VideoView2 supports : </em>
 * VideoView2 can play video files and audio-only fiels as
 * well. It can load from various sources such as resources or content providers. The supported
 * media file formats are the same as MediaPlayer2.
 *
 * <p>
 * <em> View type can be selected : </em>
 * VideoView2 can render videos on top of TextureView as well as
 * SurfaceView selectively. The default is SurfaceView and it can be changed using
 * {@link #setViewType(int)} method. Using SurfaceView is recommended in most cases for saving
 * battery. TextureView might be preferred for supporting various UIs such as animation and
 * translucency.
 *
 * <p>
 * <em> Differences between {@link VideoView} class : </em>
 * VideoView2 covers and inherits the most of
 * VideoView's functionalities. The main differences are
 * <ul>
 * <li> VideoView2 inherits FrameLayout and renders videos using SurfaceView and TextureView
 * selectively while VideoView inherits SurfaceView class.
 * <li> VideoView2 is integrated with MediaControlView2 and a default MediaControlView2 instance is
 * attached to VideoView2 by default. If a developer does not want to use the default
 * MediaControlView2, needs to set enableControlView attribute to false. For instance,
 * <pre>
 * &lt;VideoView2
 *     android:id="@+id/video_view"
 *     xmlns:widget="http://schemas.android.com/apk/com.android.media.update"
 *     widget:enableControlView="false" /&gt;
 * </pre>
 * If a developer wants to attach a customed MediaControlView2, then set enableControlView attribute
 * to false and assign the customed media control widget using {@link #setMediaControlView2}.
 * <li> VideoView2 is integrated with MediaPlayer2 while VideoView is integrated with MediaPlayer.
 * <li> VideoView2 is integrated with MediaSession2 and so it responses with media key events.
 * A VideoView2 keeps a MediaSession2 instance internally and connects it to a corresponding
 * MediaControlView2 instance.
 * </p>
 * </ul>
 *
 * <p>
 * <em> Audio focus and audio attributes : </em>
 * By default, VideoView2 requests audio focus with
 * {@link AudioManager#AUDIOFOCUS_GAIN}. Use {@link #setAudioFocusRequest(int)} to change this
 * behavior. The default {@link AudioAttributes} used during playback have a usage of
 * {@link AudioAttributes#USAGE_MEDIA} and a content type of
 * {@link AudioAttributes#CONTENT_TYPE_MOVIE}, use {@link #setAudioAttributes(AudioAttributes)} to
 * modify them.
 *
 * <p>
 * Note: VideoView2 does not retain its full state when going into the background. In particular, it
 * does not restore the current play state, play position, selected tracks. Applications should save
 * and restore these on their own in {@link android.app.Activity#onSaveInstanceState} and
 * {@link android.app.Activity#onRestoreInstanceState}.
 *
 * @hide
 */
public class VideoView2 extends FrameLayout {
    /** @hide */
    @IntDef({
            VIEW_TYPE_TEXTUREVIEW,
            VIEW_TYPE_SURFACEVIEW
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ViewType {}

    public static final int VIEW_TYPE_SURFACEVIEW = 1;
    public static final int VIEW_TYPE_TEXTUREVIEW = 2;

    private final VideoView2Provider mProvider;

    public VideoView2(@NonNull Context context) {
        this(context, null);
    }

    public VideoView2(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VideoView2(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

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
     * Sets MediaControlView2 instance. It will replace the previously assigned MediaControlView2
     * instance if any.
     *
     * @param mediaControlView a media control view2 instance.
     */
    public void setMediaControlView2(MediaControlView2 mediaControlView) {
        mProvider.setMediaControlView2_impl(mediaControlView);
    }

    /**
     * Returns MediaControlView2 instance which is currently attached to VideoView2 by default or by
     * {@link #setMediaControlView2} method.
     */
    public MediaControlView2 getMediaControlView2() {
        return mProvider.getMediaControlView2_impl();
    }

    /**
     * Starts playback with the media contents specified by {@link #setVideoURI} and
     * {@link #setVideoPath}.
     * If it has been paused, this method will resume playback from the current position.
     */
    public void start() {
        mProvider.start_impl();
    }

    /**
     * Pauses playback.
     */
    public void pause() {
        mProvider.pause_impl();
    }

    /**
     * Gets the duration of the media content specified by #setVideoURI and #setVideoPath
     * in milliseconds.
     */
    public int getDuration() {
        return mProvider.getDuration_impl();
    }

    /**
     * Gets current playback position in milliseconds.
     */
    public int getCurrentPosition() {
        return mProvider.getCurrentPosition_impl();
    }

    // TODO: mention about key-frame related behavior.
    /**
     * Moves the media by specified time position.
     * @param msec the offset in milliseconds from the start to seek to.
     */
    public void seekTo(int msec) {
        mProvider.seekTo_impl(msec);
    }

    /**
     * Says if the media is currently playing.
     * @return true if the media is playing, false if it is not (eg. paused or stopped).
     */
    public boolean isPlaying() {
        return mProvider.isPlaying_impl();
    }

    // TODO: check what will return if it is a local media.
    /**
     * Gets the percentage (0-100) of the content that has been buffered or played so far.
     */
    public int getBufferPercentage() {
        return mProvider.getBufferPercentage_impl();
    }

    /**
     * Returns the audio session ID.
     */
    public int getAudioSessionId() {
        return mProvider.getAudioSessionId_impl();
    }

    /**
     * Starts rendering closed caption or subtitles if there is any. The first subtitle track will
     * be chosen by default if there multiple subtitle tracks exist.
     */
    public void showSubtitle() {
        mProvider.showSubtitle_impl();
    }

    /**
     * Stops showing closed captions or subtitles.
     */
    public void hideSubtitle() {
        mProvider.hideSubtitle_impl();
    }

    /**
     * Sets full screen mode.
     */
    public void setFullScreen(boolean fullScreen) {
        mProvider.setFullScreen_impl(fullScreen);
    }

    // TODO: This should be revised after integration with MediaPlayer2.
    /**
     * Sets playback speed.
     *
     * It is expressed as a multiplicative factor, where normal speed is 1.0f. If it is less than
     * or equal to zero, it will be just ignored and nothing will be changed. If it exceeds the
     * maximum speed that internal engine supports, system will determine best handling or it will
     * be reset to the normal speed 1.0f.
     * @param speed the playback speed. It should be positive.
     */
    public void setSpeed(float speed) {
        mProvider.setSpeed_impl(speed);
    }

    /**
     * Returns current speed setting.
     *
     * If setSpeed() has never been called, returns the default value 1.0f.
     * @return current speed setting
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
     */
    public void setAudioFocusRequest(int focusGain) {
        mProvider.setAudioFocusRequest_impl(focusGain);
    }

    /**
     * Sets the {@link AudioAttributes} to be used during the playback of the video.
     *
     * @param attributes non-null <code>AudioAttributes</code>.
     */
    public void setAudioAttributes(@NonNull AudioAttributes attributes) {
        mProvider.setAudioAttributes_impl(attributes);
    }

    /**
     * Sets video path.
     *
     * @param path the path of the video.
     */
    public void setVideoPath(String path) {
        mProvider.setVideoPath_impl(path);
    }

    /**
     * Sets video URI.
     *
     * @param uri the URI of the video.
     */
    public void setVideoURI(Uri uri) {
        mProvider.setVideoURI_impl(uri);
    }

    /**
     * Sets video URI using specific headers.
     *
     * @param uri     the URI of the video.
     * @param headers the headers for the URI request.
     *                Note that the cross domain redirection is allowed by default, but that can be
     *                changed with key/value pairs through the headers parameter with
     *                "android-allow-cross-domain-redirect" as the key and "0" or "1" as the value
     *                to disallow or allow cross domain redirection.
     */
    public void setVideoURI(Uri uri, Map<String, String> headers) {
        mProvider.setVideoURI_impl(uri, headers);
    }

    /**
     * Selects which view will be used to render video between SurfacView and TextureView.
     *
     * @param viewType the view type to render video
     * <ul>
     * <li>{@link #VIEW_TYPE_SURFACEVIEW}
     * <li>{@link #VIEW_TYPE_TEXTUREVIEW}
     * </ul>
     */
    public void setViewType(@ViewType int viewType) {
        mProvider.setViewType_impl(viewType);
    }

    /**
     * Returns view type.
     *
     * @return view type. See {@see setViewType}.
     */
    @ViewType
    public int getViewType() {
        return mProvider.getViewType_impl();
    }

    /**
     * Stops playback and release all the resources. This should be called whenever a VideoView2
     * instance is no longer to be used.
     */
    public void stopPlayback() {
        mProvider.stopPlayback_impl();
    }

    /**
     * Registers a callback to be invoked when the media file is loaded and ready to go.
     *
     * @param l the callback that will be run.
     */
    public void setOnPreparedListener(OnPreparedListener l) {
        mProvider.setOnPreparedListener_impl(l);
    }

    /**
     * Registers a callback to be invoked when the end of a media file has been reached during
     * playback.
     *
     * @param l the callback that will be run.
     */
    public void setOnCompletionListener(OnCompletionListener l) {
        mProvider.setOnCompletionListener_impl(l);
    }

    /**
     * Registers a callback to be invoked when an error occurs during playback or setup.  If no
     * listener is specified, or if the listener returned false, VideoView2 will inform the user of
     * any errors.
     *
     * @param l The callback that will be run
     */
    public void setOnErrorListener(OnErrorListener l) {
        mProvider.setOnErrorListener_impl(l);
    }

    /**
     * Registers a callback to be invoked when an informational event occurs during playback or
     * setup.
     *
     * @param l The callback that will be run
     */
    public void setOnInfoListener(OnInfoListener l) {
        mProvider.setOnInfoListener_impl(l);
    }

    /**
     * Registers a callback to be invoked when a view type change is done.
     * {@see #setViewType(int)}
     * @param l The callback that will be run
     */
    public void setOnViewTypeChangedListener(OnViewTypeChangedListener l) {
        mProvider.setOnViewTypeChangedListener_impl(l);
    }

    /**
     * Registers a callback to be invoked when the fullscreen mode should be changed.
     */
    public void setFullScreenChangedListener(OnFullScreenChangedListener l) {
        mProvider.setFullScreenChangedListener_impl(l);
    }

    /**
     * Interface definition of a callback to be invoked when the viw type has been changed.
     */
    public interface OnViewTypeChangedListener {
        /**
         * Called when the view type has been changed.
         * @see #setViewType(int)
         * @param viewType
         * <ul>
         * <li>{@link #VIEW_TYPE_SURFACEVIEW}
         * <li>{@link #VIEW_TYPE_TEXTUREVIEW}
         * </ul>
         */
        void onViewTypeChanged(@ViewType int viewType);
    }

    /**
     * Interface definition of a callback to be invoked when the media source is ready for playback.
     */
    public interface OnPreparedListener {
        /**
         * Called when the media file is ready for playback.
         */
        void onPrepared();
    }

    /**
     * Interface definition for a callback to be invoked when playback of a media source has
     * completed.
     */
    public interface OnCompletionListener {
        /**
         * Called when the end of a media source is reached during playback.
         */
        void onCompletion();
    }

    /**
     * Interface definition of a callback to be invoked when there has been an error during an
     * asynchronous operation.
     */
    public interface OnErrorListener {
        // TODO: Redefine error codes.
        /**
         * Called to indicate an error.
         * @param what the type of error that has occurred
         * @param extra an extra code, specific to the error.
         * @return true if the method handled the error, false if it didn't.
         * @see MediaPlayer#OnErrorListener
         */
        boolean onError(int what, int extra);
    }

    /**
     * Interface definition of a callback to be invoked to communicate some info and/or warning
     * about the media or its playback.
     */
    public interface OnInfoListener {
        /**
         * Called to indicate an info or a warning.
         * @param what the type of info or warning.
         * @param extra an extra code, specific to the info.
         *
         * @see MediaPlayer#OnInfoListener
         */
        void onInfo(int what, int extra);
    }

    /**
     * Interface definition of a callback to be invoked to inform the fullscreen mode is changed.
     */
    public interface OnFullScreenChangedListener {
        /**
         * Called to indicate a fullscreen mode change.
         */
        void onFullScreenChanged(boolean fullScreen);
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
            VideoView2.super.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow_impl() {
            VideoView2.super.onDetachedFromWindow();
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
