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

import android.annotation.SystemApi;
import android.media.AudioAttributes;
import android.media.DataSourceDesc;
import android.media.MediaItem2;
import android.media.MediaMetadata2;
import android.media.MediaPlayerBase;
import android.media.SessionToken2;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.media.session.MediaSession;
import android.net.Uri;
import android.util.AttributeSet;
import android.widget.MediaControlView2;
import android.widget.VideoView2;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Interface for connecting the public API to an updatable implementation.
 *
 * Each instance object is connected to one corresponding updatable object which implements the
 * runtime behavior of that class. There should a corresponding provider method for all public
 * methods.
 *
 * All methods behave as per their namesake in the public API.
 *
 * @see android.widget.VideoView2
 *
 * @hide
 */
// TODO @SystemApi
public interface VideoView2Provider extends ViewGroupProvider {
    void initialize(AttributeSet attrs, int defStyleAttr, int defStyleRes);

    void setMediaControlView2_impl(MediaControlView2 mediaControlView, long intervalMs);
    void setMediaMetadata_impl(MediaMetadata2 metadata);
    /**
     * @hide TODO: remove
     */
    MediaController getMediaController_impl();
    SessionToken2 getMediaSessionToken_impl();
    MediaControlView2 getMediaControlView2_impl();
    MediaMetadata2 getMediaMetadata_impl();
    void setSubtitleEnabled_impl(boolean enable);
    boolean isSubtitleEnabled_impl();
    // TODO: remove setSpeed_impl once MediaController2 is ready.
    void setSpeed_impl(float speed);
    void setAudioFocusRequest_impl(int focusGain);
    void setAudioAttributes_impl(AudioAttributes attributes);
    void setVideoPath_impl(String path);
    /**
     * @hide TODO: remove
     */
    void setVideoUri_impl(Uri uri);
    /**
     * @hide TODO: remove
     */
    void setVideoUri_impl(Uri uri, Map<String, String> headers);
    void setMediaItem_impl(MediaItem2 mediaItem);
    void setDataSource_impl(DataSourceDesc dsd);
    void setViewType_impl(int viewType);
    int getViewType_impl();
    /**
     * @hide TODO: remove
     */
    void setCustomActions_impl(List<PlaybackState.CustomAction> actionList,
            Executor executor, VideoView2.OnCustomActionListener listener);
    /**
     * @hide
     */
    @VisibleForTesting
    void setOnViewTypeChangedListener_impl(VideoView2.OnViewTypeChangedListener l);
    /**
     * @hide TODO: remove
     */
    void setFullScreenRequestListener_impl(VideoView2.OnFullScreenRequestListener l);
}
