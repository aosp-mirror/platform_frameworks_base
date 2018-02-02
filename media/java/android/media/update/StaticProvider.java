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
import android.app.Notification;
import android.content.Context;
import android.media.DataSourceDesc;
import android.media.MediaBrowser2;
import android.media.MediaBrowser2.BrowserCallback;
import android.media.MediaController2;
import android.media.MediaController2.ControllerCallback;
import android.media.MediaItem2;
import android.media.MediaLibraryService2;
import android.media.MediaLibraryService2.LibraryRoot;
import android.media.MediaLibraryService2.MediaLibrarySession;
import android.media.MediaLibraryService2.MediaLibrarySessionBuilder;
import android.media.MediaLibraryService2.MediaLibrarySessionCallback;
import android.media.MediaMetadata2;
import android.media.MediaPlayerInterface;
import android.media.MediaSession2;
import android.media.MediaSession2.CommandButton.Builder;
import android.media.MediaSession2.PlaylistParams;
import android.media.MediaSession2.SessionCallback;
import android.media.MediaSessionService2;
import android.media.MediaSessionService2.MediaNotification;
import android.media.PlaybackState2;
import android.media.Rating2;
import android.media.SessionPlayer2;
import android.media.SessionToken2;
import android.media.VolumeProvider2;
import android.media.update.MediaLibraryService2Provider.LibraryRootProvider;
import android.media.update.MediaSession2Provider.BuilderBaseProvider;
import android.media.update.MediaSession2Provider.CommandButtonProvider.BuilderProvider;
import android.media.update.MediaSession2Provider.CommandGroupProvider;
import android.media.update.MediaSession2Provider.CommandProvider;
import android.media.update.MediaSession2Provider.ControllerInfoProvider;
import android.media.update.MediaSession2Provider.PlaylistParamsProvider;
import android.media.update.MediaSessionService2Provider.MediaNotificationProvider;
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
    MediaControlView2Provider createMediaControlView2(MediaControlView2 instance,
            ViewGroupProvider superProvider, ViewGroupProvider privateProvider,
            @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes);
    VideoView2Provider createVideoView2(VideoView2 instance,
            ViewGroupProvider superProvider, ViewGroupProvider privateProvider,
            @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes);

    CommandProvider createMediaSession2Command(MediaSession2.Command instance,
            int commandCode, String action, Bundle extra);
    MediaSession2.Command fromBundle_MediaSession2Command(Context context, Bundle bundle);
    CommandGroupProvider createMediaSession2CommandGroup(Context context,
            MediaSession2.CommandGroup instance, MediaSession2.CommandGroup others);
    MediaSession2.CommandGroup fromBundle_MediaSession2CommandGroup(Context context, Bundle bundle);
    ControllerInfoProvider createMediaSession2ControllerInfo(Context context,
            MediaSession2.ControllerInfo instance, int uid, int pid,
            String packageName, IInterface callback);
    PlaylistParamsProvider createMediaSession2PlaylistParams(Context context,
            PlaylistParams playlistParams, int repeatMode, int shuffleMode,
            MediaMetadata2 playlistMetadata);
    PlaylistParams fromBundle_PlaylistParams(Context context, Bundle bundle);
    BuilderProvider createMediaSession2CommandButtonBuilder(Context context, Builder builder);
    BuilderBaseProvider<MediaSession2, SessionCallback> createMediaSession2Builder(
            Context context, MediaSession2.Builder instance, MediaPlayerInterface player);

    MediaController2Provider createMediaController2(Context context, MediaController2 instance,
            SessionToken2 token, Executor executor, ControllerCallback callback);

    MediaBrowser2Provider createMediaBrowser2(Context context, MediaBrowser2 instance,
            SessionToken2 token, Executor executor, BrowserCallback callback);

    MediaSessionService2Provider createMediaSessionService2(MediaSessionService2 instance);
    MediaNotificationProvider createMediaSessionService2MediaNotification(Context context,
            MediaNotification mediaNotification, int notificationId, Notification notification);

    MediaSessionService2Provider createMediaLibraryService2(MediaLibraryService2 instance);
    BuilderBaseProvider<MediaLibrarySession, MediaLibrarySessionCallback>
        createMediaLibraryService2Builder(
            Context context, MediaLibrarySessionBuilder instance, MediaPlayerInterface player,
            Executor callbackExecutor, MediaLibrarySessionCallback callback);
    LibraryRootProvider createMediaLibraryService2LibraryRoot(Context context, LibraryRoot instance,
            String rootId, Bundle extras);

    SessionToken2Provider createSessionToken2(Context context, SessionToken2 instance,
            String packageName, String serviceName, int uid);
    SessionToken2 SessionToken2_fromBundle(Context context, Bundle bundle);

    SessionPlayer2Provider createSessionPlayer2(Context context, SessionPlayer2 instance);

    MediaItem2Provider createMediaItem2(Context context, MediaItem2 mediaItem2,
            String mediaId, DataSourceDesc dsd, MediaMetadata2 metadata, int flags);
    MediaItem2 fromBundle_MediaItem2(Context context, Bundle bundle);

    VolumeProvider2Provider createVolumeProvider2(Context context, VolumeProvider2 instance,
            int controlType, int maxVolume, int currentVolume);

    MediaMetadata2 fromBundle_MediaMetadata2(Context context, Bundle bundle);
    MediaMetadata2Provider.BuilderProvider createMediaMetadata2Builder(
            Context context, MediaMetadata2.Builder builder);
    MediaMetadata2Provider.BuilderProvider createMediaMetadata2Builder(
            Context context, MediaMetadata2.Builder builder, MediaMetadata2 source);

    Rating2 newUnratedRating_Rating2(Context context, int ratingStyle);
    Rating2 fromBundle_Rating2(Context context, Bundle bundle);
    Rating2 newHeartRating_Rating2(Context context, boolean hasHeart);
    Rating2 newThumbRating_Rating2(Context context, boolean thumbIsUp);
    Rating2 newStarRating_Rating2(Context context, int starRatingStyle, float starRating);
    Rating2 newPercentageRating_Rating2(Context context, float percent);

    PlaybackState2Provider createPlaybackState2(Context context, PlaybackState2 instance, int state,
            long position, long updateTime, float speed, long bufferedPosition, long activeItemId,
            CharSequence error);
    PlaybackState2 fromBundle_PlaybackState2(Context context, Bundle bundle);
}
