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
import android.media.DataSourceDesc;
import android.media.MediaItem2;
import android.media.MediaMetadata2;
import android.media.MediaPlayer2;
import android.media.SessionToken2;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.media.update.ApiLoader;
import android.media.update.VideoView2Provider;
import android.media.update.ViewGroupHelper;
import android.net.Uri;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

// TODO: Replace MediaSession wtih MediaSession2 once MediaSession2 is submitted.
/**
 * @hide
 * Displays a video file.  VideoView2 class is a View class which is wrapping {@link MediaPlayer2}
 * so that developers can easily implement a video rendering application.
 *
 * <p>
 * <em> Data sources that VideoView2 supports : </em>
 * VideoView2 can play video files and audio-only files as
 * well. It can load from various sources such as resources or content providers. The supported
 * media file formats are the same as {@link MediaPlayer2}.
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
 * <li> VideoView2 is integrated with MediaSession and so it responses with media key events.
 * A VideoView2 keeps a MediaSession instance internally and connects it to a corresponding
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
 */
public class VideoView2 extends ViewGroupHelper<VideoView2Provider> {
    /** @hide */
    @IntDef({
            VIEW_TYPE_TEXTUREVIEW,
            VIEW_TYPE_SURFACEVIEW
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ViewType {}

    /**
     * Indicates video is rendering on SurfaceView.
     *
     * @see #setViewType
     */
    public static final int VIEW_TYPE_SURFACEVIEW = 1;

    /**
     * Indicates video is rendering on TextureView.
     *
     * @see #setViewType
     */
    public static final int VIEW_TYPE_TEXTUREVIEW = 2;

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
        super((instance, superProvider, privateProvider) ->
                ApiLoader.getProvider().createVideoView2(
                        (VideoView2) instance, superProvider, privateProvider,
                        attrs, defStyleAttr, defStyleRes),
                context, attrs, defStyleAttr, defStyleRes);
        mProvider.initialize(attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Sets MediaControlView2 instance. It will replace the previously assigned MediaControlView2
     * instance if any.
     *
     * @param mediaControlView a media control view2 instance.
     * @param intervalMs a time interval in milliseconds until VideoView2 hides MediaControlView2.
     */
    public void setMediaControlView2(MediaControlView2 mediaControlView, long intervalMs) {
        mProvider.setMediaControlView2_impl(mediaControlView, intervalMs);
    }

    /**
     * Returns MediaControlView2 instance which is currently attached to VideoView2 by default or by
     * {@link #setMediaControlView2} method.
     */
    public MediaControlView2 getMediaControlView2() {
        return mProvider.getMediaControlView2_impl();
    }

    /**
     * Sets MediaMetadata2 instance. It will replace the previously assigned MediaMetadata2 instance
     * if any.
     *
     * @param metadata a MediaMetadata2 instance.
     * @hide
     */
    public void setMediaMetadata(MediaMetadata2 metadata) {
        mProvider.setMediaMetadata_impl(metadata);
    }

    /**
     * Returns MediaMetadata2 instance which is retrieved from MediaPlayer2 inside VideoView2 by
     * default or by {@link #setMediaMetadata} method.
     * @hide
     */
    public MediaMetadata2 getMediaMetadata() {
        // TODO: add to Javadoc whether this value can be null or not when integrating with
        // MediaSession2.
        return mProvider.getMediaMetadata_impl();
    }

    /**
     * Returns MediaController instance which is connected with MediaSession that VideoView2 is
     * using. This method should be called when VideoView2 is attached to window, or it throws
     * IllegalStateException, since internal MediaSession instance is not available until
     * this view is attached to window. Please check {@link android.view.View#isAttachedToWindow}
     * before calling this method.
     *
     * @throws IllegalStateException if interal MediaSession is not created yet.
     * @hide  TODO: remove
     */
    public MediaController getMediaController() {
        return mProvider.getMediaController_impl();
    }

    /**
     * Returns {@link android.media.SessionToken2} so that developers create their own
     * {@link android.media.MediaController2} instance. This method should be called when VideoView2
     * is attached to window, or it throws IllegalStateException.
     *
     * @throws IllegalStateException if interal MediaSession is not created yet.
     */
    public SessionToken2 getMediaSessionToken() {
        return mProvider.getMediaSessionToken_impl();
    }

    /**
     * Shows or hides closed caption or subtitles if there is any.
     * The first subtitle track will be chosen if there multiple subtitle tracks exist.
     * Default behavior of VideoView2 is not showing subtitle.
     * @param enable shows closed caption or subtitles if this value is true, or hides.
     */
    public void setSubtitleEnabled(boolean enable) {
        mProvider.setSubtitleEnabled_impl(enable);
    }

    /**
     * Returns true if showing subtitle feature is enabled or returns false.
     * Although there is no subtitle track or closed caption, it can return true, if the feature
     * has been enabled by {@link #setSubtitleEnabled}.
     */
    public boolean isSubtitleEnabled() {
        return mProvider.isSubtitleEnabled_impl();
    }

    /**
     * Sets playback speed.
     *
     * It is expressed as a multiplicative factor, where normal speed is 1.0f. If it is less than
     * or equal to zero, it will be just ignored and nothing will be changed. If it exceeds the
     * maximum speed that internal engine supports, system will determine best handling or it will
     * be reset to the normal speed 1.0f.
     * @param speed the playback speed. It should be positive.
     */
    // TODO: Support this via MediaController2.
    public void setSpeed(float speed) {
        mProvider.setSpeed_impl(speed);
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
     *                  {@link AudioManager#AUDIOFOCUS_NONE} to disable the use audio focus during
     *                  playback.
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
     *
     * @hide TODO remove
     */
    public void setVideoPath(String path) {
        mProvider.setVideoPath_impl(path);
    }

    /**
     * Sets video URI.
     *
     * @param uri the URI of the video.
     *
     * @hide TODO remove
     */
    public void setVideoUri(Uri uri) {
        mProvider.setVideoUri_impl(uri);
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
     *
     * @hide TODO remove
     */
    public void setVideoUri(Uri uri, Map<String, String> headers) {
        mProvider.setVideoUri_impl(uri, headers);
    }

    /**
     * Sets {@link MediaItem2} object to render using VideoView2. Alternative way to set media
     * object to VideoView2 is {@link #setDataSource}.
     * @param mediaItem the MediaItem2 to play
     * @see #setDataSource
     */
    public void setMediaItem(@NonNull MediaItem2 mediaItem) {
        mProvider.setMediaItem_impl(mediaItem);
    }

    /**
     * Sets {@link DataSourceDesc} object to render using VideoView2.
     * @param dataSource the {@link DataSourceDesc} object to play.
     * @see #setMediaItem
     */
    public void setDataSource(@NonNull DataSourceDesc dataSource) {
        mProvider.setDataSource_impl(dataSource);
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
     * Sets custom actions which will be shown as custom buttons in {@link MediaControlView2}.
     *
     * @param actionList A list of {@link PlaybackState.CustomAction}. The return value of
     *                   {@link PlaybackState.CustomAction#getIcon()} will be used to draw buttons
     *                   in {@link MediaControlView2}.
     * @param executor executor to run callbacks on.
     * @param listener A listener to be called when a custom button is clicked.
     * @hide  TODO remove
     */
    public void setCustomActions(List<PlaybackState.CustomAction> actionList,
            Executor executor, OnCustomActionListener listener) {
        mProvider.setCustomActions_impl(actionList, executor, listener);
    }

    /**
     * Registers a callback to be invoked when a view type change is done.
     * {@see #setViewType(int)}
     * @param l The callback that will be run
     * @hide
     */
    @VisibleForTesting
    public void setOnViewTypeChangedListener(OnViewTypeChangedListener l) {
        mProvider.setOnViewTypeChangedListener_impl(l);
    }

    /**
     * Registers a callback to be invoked when the fullscreen mode should be changed.
     * @param l The callback that will be run
     * @hide  TODO remove
     */
    public void setFullScreenRequestListener(OnFullScreenRequestListener l) {
        mProvider.setFullScreenRequestListener_impl(l);
    }

    /**
     * Interface definition of a callback to be invoked when the view type has been changed.
     *
     * @hide
     */
    @VisibleForTesting
    public interface OnViewTypeChangedListener {
        /**
         * Called when the view type has been changed.
         * @see #setViewType(int)
         * @param view the View whose view type is changed
         * @param viewType
         * <ul>
         * <li>{@link #VIEW_TYPE_SURFACEVIEW}
         * <li>{@link #VIEW_TYPE_TEXTUREVIEW}
         * </ul>
         */
        void onViewTypeChanged(View view, @ViewType int viewType);
    }

    /**
     * Interface definition of a callback to be invoked to inform the fullscreen mode is changed.
     * Application should handle the fullscreen mode accordingly.
     * @hide  TODO remove
     */
    public interface OnFullScreenRequestListener {
        /**
         * Called to indicate a fullscreen mode change.
         */
        void onFullScreenRequest(View view, boolean fullScreen);
    }

    /**
     * Interface definition of a callback to be invoked to inform that a custom action is performed.
     * @hide  TODO remove
     */
    public interface OnCustomActionListener {
        /**
         * Called to indicate that a custom action is performed.
         *
         * @param action The action that was originally sent in the
         *               {@link PlaybackState.CustomAction}.
         * @param extras Optional extras.
         */
        void onCustomAction(String action, Bundle extras);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mProvider.onLayout_impl(changed, l, t, r, b);
    }
}
