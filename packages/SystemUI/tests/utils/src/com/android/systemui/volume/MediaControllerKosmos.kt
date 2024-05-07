/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume

import android.content.packageManager
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever

private const val LOCAL_PACKAGE = "local.test.pkg"
var Kosmos.localPlaybackInfo by
    Kosmos.Fixture {
        MediaController.PlaybackInfo(
            MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL,
            VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
            10,
            3,
            AudioAttributes.Builder().build(),
            "",
        )
    }
var Kosmos.localPlaybackStateBuilder by Kosmos.Fixture { PlaybackState.Builder() }
var Kosmos.localMediaController: MediaController by
    Kosmos.Fixture {
        val appInfo: ApplicationInfo = mock {
            whenever(loadLabel(any())).thenReturn("local_media_controller_label")
        }
        whenever(packageManager.getApplicationInfo(eq(LOCAL_PACKAGE), any<Int>()))
            .thenReturn(appInfo)

        val localSessionToken: MediaSession.Token = MediaSession.Token(0, mock {})
        mock {
            whenever(packageName).thenReturn(LOCAL_PACKAGE)
            whenever(playbackInfo).thenReturn(localPlaybackInfo)
            whenever(playbackState).thenReturn(localPlaybackStateBuilder.build())
            whenever(sessionToken).thenReturn(localSessionToken)
        }
    }

private const val REMOTE_PACKAGE = "remote.test.pkg"
var Kosmos.remotePlaybackInfo by
    Kosmos.Fixture {
        MediaController.PlaybackInfo(
            MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE,
            VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
            10,
            7,
            AudioAttributes.Builder().build(),
            "",
        )
    }
var Kosmos.remotePlaybackStateBuilder by Kosmos.Fixture { PlaybackState.Builder() }
var Kosmos.remoteMediaController: MediaController by
    Kosmos.Fixture {
        val appInfo: ApplicationInfo = mock {
            whenever(loadLabel(any())).thenReturn("remote_media_controller_label")
        }
        whenever(packageManager.getApplicationInfo(eq(REMOTE_PACKAGE), any<Int>()))
            .thenReturn(appInfo)

        val remoteSessionToken: MediaSession.Token = MediaSession.Token(0, mock {})
        mock {
            whenever(packageName).thenReturn(REMOTE_PACKAGE)
            whenever(playbackInfo).thenReturn(remotePlaybackInfo)
            whenever(playbackState).thenReturn(remotePlaybackStateBuilder.build())
            whenever(sessionToken).thenReturn(remoteSessionToken)
        }
    }
