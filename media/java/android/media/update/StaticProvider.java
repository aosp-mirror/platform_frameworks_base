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

package android.media.update;

import android.annotation.Nullable;
import android.content.Context;
import android.media.IMediaSession2Callback;
import android.media.MediaController2;
import android.media.MediaPlayerBase;
import android.media.MediaSession2;
import android.media.MediaSessionService2;
import android.media.SessionToken;
import android.util.AttributeSet;
import android.widget.MediaControlView2;
import android.widget.VideoView2;

import java.util.concurrent.Executor;

/**
 * Interface for connecting the public API to an updatable implementation.
 *
 * This interface provides access to constructors and static methods that are otherwise not directly
 * accessible via an implementation object.
 *
 * @hide
 */
// TODO @SystemApi
public interface StaticProvider {
    MediaControlView2Provider createMediaControlView2(
            MediaControlView2 instance, ViewProvider superProvider);
    VideoView2Provider createVideoView2(
            VideoView2 instance, ViewProvider superProvider,
            @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes);

    MediaSession2Provider createMediaSession2(MediaSession2 mediaSession2, Context context,
            MediaPlayerBase player, String id, MediaSession2.SessionCallback callback);
    MediaSession2Provider.ControllerInfoProvider createMediaSession2ControllerInfoProvider(
            MediaSession2.ControllerInfo instance, Context context, int uid, int pid,
            String packageName, IMediaSession2Callback callback);
    MediaController2Provider createMediaController2(
            MediaController2 instance, Context context, SessionToken token,
            MediaController2.ControllerCallback callback, Executor executor);
    MediaSessionService2Provider createMediaSessionService2(
            MediaSessionService2 instance);
}
