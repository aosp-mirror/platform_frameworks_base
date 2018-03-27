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

import android.app.PendingIntent;
import android.media.AudioFocusRequest;
import android.media.MediaItem2;
import android.media.MediaMetadata2;
import android.media.MediaPlayerBase;
import android.media.MediaPlaylistAgent;
import android.media.MediaSession2;
import android.media.SessionCommand2;
import android.media.MediaSession2.CommandButton;
import android.media.MediaSession2.CommandButton.Builder;
import android.media.SessionCommandGroup2;
import android.media.MediaSession2.ControllerInfo;
import android.media.MediaSession2.OnDataSourceMissingHelper;
import android.media.MediaSession2.SessionCallback;
import android.media.SessionToken2;
import android.media.VolumeProvider2;
import android.os.Bundle;
import android.os.ResultReceiver;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * @hide
 */
public interface MediaSession2Provider extends TransportControlProvider {
    void close_impl();
    void updatePlayer_impl(MediaPlayerBase player, MediaPlaylistAgent playlistAgent,
            VolumeProvider2 volumeProvider);
    MediaPlayerBase getPlayer_impl();
    MediaMetadata2 getPlaylistMetadata_impl();
    void updatePlaylistMetadata_impl(MediaMetadata2 metadata);
    MediaPlaylistAgent getPlaylistAgent_impl();
    VolumeProvider2 getVolumeProvider_impl();
    SessionToken2 getToken_impl();
    List<ControllerInfo> getConnectedControllers_impl();
    void setCustomLayout_impl(ControllerInfo controller, List<CommandButton> layout);
    void setAudioFocusRequest_impl(AudioFocusRequest afr);
    void setAllowedCommands_impl(ControllerInfo controller, SessionCommandGroup2 commands);
    void sendCustomCommand_impl(ControllerInfo controller, SessionCommand2 command, Bundle args,
            ResultReceiver receiver);
    void sendCustomCommand_impl(SessionCommand2 command, Bundle args);
    void addPlaylistItem_impl(int index, MediaItem2 item);
    void removePlaylistItem_impl(MediaItem2 item);
    void replacePlaylistItem_impl(int index, MediaItem2 item);
    List<MediaItem2> getPlaylist_impl();
    void setPlaylist_impl(List<MediaItem2> list, MediaMetadata2 metadata);
    MediaItem2 getCurrentPlaylistItem_impl();
    void notifyError_impl(int errorCode, Bundle extras);
    int getPlayerState_impl();
    long getCurrentPosition_impl();
    long getBufferedPosition_impl();
    void setOnDataSourceMissingHelper_impl(OnDataSourceMissingHelper helper);
    void clearOnDataSourceMissingHelper_impl();

    // TODO(jaewan): Rename and move provider
    interface CommandProvider {
        int getCommandCode_impl();
        String getCustomCommand_impl();
        Bundle getExtras_impl();
        Bundle toBundle_impl();

        boolean equals_impl(Object ob);
        int hashCode_impl();
    }

    // TODO(jaewan): Rename and move provider
    interface CommandGroupProvider {
        void addCommand_impl(SessionCommand2 command);
        void addAllPredefinedCommands_impl();
        void removeCommand_impl(SessionCommand2 command);
        boolean hasCommand_impl(SessionCommand2 command);
        boolean hasCommand_impl(int code);
        Set<SessionCommand2> getCommands_impl();
        Bundle toBundle_impl();
    }

    interface CommandButtonProvider {
        SessionCommand2 getCommand_impl();
        int getIconResId_impl();
        String getDisplayName_impl();
        Bundle getExtras_impl();
        boolean isEnabled_impl();

        interface BuilderProvider {
            Builder setCommand_impl(SessionCommand2 command);
            Builder setIconResId_impl(int resId);
            Builder setDisplayName_impl(String displayName);
            Builder setEnabled_impl(boolean enabled);
            Builder setExtras_impl(Bundle extras);
            CommandButton build_impl();
        }
    }

    interface ControllerInfoProvider {
        String getPackageName_impl();
        int getUid_impl();
        boolean isTrusted_impl();
        int hashCode_impl();
        boolean equals_impl(Object obj);
        String toString_impl();
    }

    interface BuilderBaseProvider<T extends MediaSession2, C extends SessionCallback> {
        void setPlayer_impl(MediaPlayerBase player);
        void setPlaylistAgent_impl(MediaPlaylistAgent playlistAgent);
        void setVolumeProvider_impl(VolumeProvider2 volumeProvider);
        void setSessionActivity_impl(PendingIntent pi);
        void setId_impl(String id);
        void setSessionCallback_impl(Executor executor, C callback);
        T build_impl();
    }
}
