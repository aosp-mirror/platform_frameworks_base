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

import android.media.AudioAttributes;
import android.media.MediaItem2;
import android.media.MediaPlayerBase;
import android.media.MediaSession2;
import android.media.MediaSession2.Command;
import android.media.MediaSession2.CommandButton;
import android.media.MediaSession2.CommandGroup;
import android.media.MediaSession2.ControllerInfo;
import android.media.MediaSession2.PlaylistParam;
import android.media.SessionToken;
import android.media.VolumeProvider;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;

import java.util.List;

/**
 * @hide
 */
public interface MediaSession2Provider extends TransportControlProvider {
    void close_impl();
    void setPlayer_impl(MediaPlayerBase player);
    void setPlayer_impl(MediaPlayerBase player, VolumeProvider volumeProvider);
    MediaPlayerBase getPlayer_impl();
    SessionToken getToken_impl();
    List<ControllerInfo> getConnectedControllers_impl();
    void setCustomLayout_impl(ControllerInfo controller, List<CommandButton> layout);
    void setAudioAttributes_impl(AudioAttributes attributes);
    void setAudioFocusRequest_impl(int focusGain);

    void setAllowedCommands_impl(ControllerInfo controller, CommandGroup commands);
    void notifyMetadataChanged_impl();
    void sendCustomCommand_impl(ControllerInfo controller, Command command, Bundle args,
            ResultReceiver receiver);
    void sendCustomCommand_impl(Command command, Bundle args);
    void setPlaylist_impl(List<MediaItem2> playlist, MediaSession2.PlaylistParam param);

    /**
     * @hide
     */
    interface ControllerInfoProvider {
        String getPackageName_impl();
        int getUid_impl();
        boolean isTrusted_impl();
        int hashCode_impl();
        boolean equals_impl(ControllerInfoProvider obj);
    }
}
