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
import android.media.MediaBrowser2;
import android.media.MediaBrowser2.BrowserCallback;
import android.media.MediaController2;
import android.media.MediaController2.ControllerCallback;
import android.media.MediaItem2;
import android.media.MediaLibraryService2;
import android.media.MediaLibraryService2.LibraryRoot;
import android.media.MediaLibraryService2.MediaLibrarySession;
import android.media.MediaLibraryService2.MediaLibrarySession.MediaLibrarySessionCallback;
import android.media.MediaMetadata2;
import android.media.MediaPlaylistAgent;
import android.media.MediaSession2;
import android.media.MediaSession2.SessionCallback;
import android.media.MediaSessionService2;
import android.media.MediaSessionService2.MediaNotification;
import android.media.Rating2;
import android.media.SessionCommand2;
import android.media.SessionCommandGroup2;
import android.media.SessionToken2;
import android.media.VolumeProvider2;
import android.media.update.MediaLibraryService2Provider.LibraryRootProvider;
import android.media.update.MediaSession2Provider.BuilderBaseProvider;
import android.media.update.MediaSession2Provider.CommandButtonProvider;
import android.media.update.MediaSession2Provider.CommandGroupProvider;
import android.media.update.MediaSession2Provider.CommandProvider;
import android.media.update.MediaSession2Provider.ControllerInfoProvider;
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

    CommandProvider createMediaSession2Command(SessionCommand2 instance,
            int commandCode, String action, Bundle extra);
    SessionCommand2 fromBundle_MediaSession2Command(Bundle bundle);
    CommandGroupProvider createMediaSession2CommandGroup(SessionCommandGroup2 instance,
            SessionCommandGroup2 others);
    SessionCommandGroup2 fromBundle_MediaSession2CommandGroup(Bundle bundle);
    ControllerInfoProvider createMediaSession2ControllerInfo(Context context,
            MediaSession2.ControllerInfo instance, int uid, int pid,
            String packageName, IInterface callback);
    CommandButtonProvider.BuilderProvider createMediaSession2CommandButtonBuilder(
            MediaSession2.CommandButton.Builder instance);
    BuilderBaseProvider<MediaSession2, SessionCallback> createMediaSession2Builder(
            Context context, MediaSession2.Builder instance);

    MediaController2Provider createMediaController2(Context context, MediaController2 instance,
            SessionToken2 token, Executor executor, ControllerCallback callback);

    MediaBrowser2Provider createMediaBrowser2(Context context, MediaBrowser2 instance,
            SessionToken2 token, Executor executor, BrowserCallback callback);

    MediaSessionService2Provider createMediaSessionService2(MediaSessionService2 instance);
    MediaNotificationProvider createMediaSessionService2MediaNotification(
            MediaNotification mediaNotification, int notificationId, Notification notification);

    MediaSessionService2Provider createMediaLibraryService2(MediaLibraryService2 instance);
    BuilderBaseProvider<MediaLibrarySession, MediaLibrarySessionCallback>
        createMediaLibraryService2Builder(
            MediaLibraryService2 service, MediaLibrarySession.Builder instance,
            Executor callbackExecutor, MediaLibrarySessionCallback callback);
    LibraryRootProvider createMediaLibraryService2LibraryRoot(LibraryRoot instance, String rootId,
            Bundle extras);

    SessionToken2Provider createSessionToken2(Context context, SessionToken2 instance,
            String packageName, String serviceName, int uid);
    SessionToken2 fromBundle_SessionToken2(Bundle bundle);

    MediaItem2Provider.BuilderProvider createMediaItem2Builder(MediaItem2.Builder instance,
            int flags);
    MediaItem2 fromBundle_MediaItem2(Bundle bundle);

    VolumeProvider2Provider createVolumeProvider2(VolumeProvider2 instance, int controlType,
            int maxVolume, int currentVolume);

    MediaMetadata2 fromBundle_MediaMetadata2(Bundle bundle);
    MediaMetadata2Provider.BuilderProvider createMediaMetadata2Builder(
            MediaMetadata2.Builder instance);
    MediaMetadata2Provider.BuilderProvider createMediaMetadata2Builder(
            MediaMetadata2.Builder instance, MediaMetadata2 source);

    Rating2 newUnratedRating_Rating2(int ratingStyle);
    Rating2 fromBundle_Rating2(Bundle bundle);
    Rating2 newHeartRating_Rating2(boolean hasHeart);
    Rating2 newThumbRating_Rating2(boolean thumbIsUp);
    Rating2 newStarRating_Rating2(int starRatingStyle, float starRating);
    Rating2 newPercentageRating_Rating2(float percent);

    MediaPlaylistAgentProvider createMediaPlaylistAgent(MediaPlaylistAgent instance);
}
