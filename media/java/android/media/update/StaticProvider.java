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
import android.app.PendingIntent;
import android.content.Context;
import android.media.DataSourceDesc;
import android.media.MediaBrowser2;
import android.media.MediaBrowser2.BrowserCallback;
import android.media.MediaController2;
import android.media.MediaController2.ControllerCallback;
import android.media.MediaItem2;
import android.media.MediaLibraryService2;
import android.media.MediaLibraryService2.MediaLibrarySession;
import android.media.MediaLibraryService2.MediaLibrarySessionCallback;
import android.media.MediaMetadata2;
import android.media.MediaPlayerInterface;
import android.media.MediaSession2;
import android.media.MediaSession2.SessionCallback;
import android.media.MediaSessionService2;
import android.media.SessionPlayer2;
import android.media.SessionToken2;
import android.media.VolumeProvider;
import android.media.update.MediaLibraryService2Provider.MediaLibrarySessionProvider;
import android.media.update.MediaSession2Provider.CommandProvider;
import android.media.update.MediaSession2Provider.ControllerInfoProvider;
import android.os.Bundle;
import android.os.IInterface;
import android.util.AttributeSet;
import android.widget.MediaControlView2;
import android.widget.VideoView2;

import java.util.concurrent.Executor;

/**
 * Interface for connecting the public API to an updatable implementation.
 *
 * This interface provides access to constructors and static methods that are otherwise not directly
 * accessible via an implementation object.
 * @hide
 */
public interface StaticProvider {
    MediaControlView2Provider createMediaControlView2(
            MediaControlView2 instance, ViewProvider superProvider);
    VideoView2Provider createVideoView2(
            VideoView2 instance, ViewProvider superProvider,
            @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes);

    MediaSession2Provider createMediaSession2(Context context, MediaSession2 instance,
            MediaPlayerInterface player, String id, VolumeProvider volumeProvider, int ratingType,
            PendingIntent sessionActivity, Executor executor, SessionCallback callback);
    ControllerInfoProvider createMediaSession2ControllerInfoProvider(Context context,
            MediaSession2.ControllerInfo instance, int uid, int pid,
            String packageName, IInterface callback);
    CommandProvider createMediaSession2Command(MediaSession2.Command instance,
            int commandCode, String action, Bundle extra);
    MediaSession2.Command fromBundle_MediaSession2Command(Context context, Bundle bundle);

    MediaController2Provider createMediaController2(Context context, MediaController2 instance,
            SessionToken2 token, Executor executor, ControllerCallback callback);

    MediaBrowser2Provider createMediaBrowser2(Context context, MediaBrowser2 instance,
            SessionToken2 token, Executor executor, BrowserCallback callback);

    MediaSessionService2Provider createMediaSessionService2(MediaSessionService2 instance);
    MediaSessionService2Provider createMediaLibraryService2(MediaLibraryService2 instance);

    MediaLibrarySessionProvider createMediaLibraryService2MediaLibrarySession(Context context,
            MediaLibrarySession instance, MediaPlayerInterface player, String id,
            VolumeProvider volumeProvider, int ratingType, PendingIntent sessionActivity,
            Executor executor, MediaLibrarySessionCallback callback);

    SessionToken2Provider createSessionToken2(Context context, SessionToken2 instance,
            String packageName, String serviceName, int uid);
    SessionToken2 SessionToken2_fromBundle(Context context, Bundle bundle);

    SessionPlayer2Provider createSessionPlayer2(Context context, SessionPlayer2 instance);

    MediaItem2Provider createMediaItem2Provider(Context context, MediaItem2 mediaItem2,
            String mediaId, DataSourceDesc dsd, MediaMetadata2 metadata, int flags);
    MediaItem2 fromBundle_MediaItem2(Context context, Bundle bundle);
}
