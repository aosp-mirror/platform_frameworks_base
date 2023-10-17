/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.media.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;

/**
 * Helper class with assertion methods on mock {@link MediaPlayer} instances.
 */
public final class MediaPlayerTestHelper {

    /** Verify this local media player mock instance was started. */
    public static void verifyPlayerStarted(MediaPlayer mockMediaPlayer) {
        verify(mockMediaPlayer).setOnCompletionListener(any());
        verify(mockMediaPlayer).start();
    }

    /** Verify this local media player mock instance was stopped and released. */
    public static void verifyPlayerStopped(MediaPlayer mockMediaPlayer) {
        verify(mockMediaPlayer).stop();
        verify(mockMediaPlayer).setOnCompletionListener(isNull());
        verify(mockMediaPlayer).reset();
        verify(mockMediaPlayer).release();
    }

    /** Verify this local media player mock instance was setup with given attributes. */
    public static void verifyPlayerSetup(Context context, MediaPlayer mockPlayer,
            Uri expectedUri, AudioAttributes expectedAudioAttributes) throws Exception {
        verify(mockPlayer).setDataSource(context, expectedUri);
        verify(mockPlayer).setAudioAttributes(expectedAudioAttributes);
        verify(mockPlayer).setPreferredDevice(null);
        verify(mockPlayer).prepare();
    }

    /** Verify this local media player mock instance was setup with given fallback attributes. */
    public static void verifyPlayerFallbackSetup(MediaPlayer mockPlayer,
            AssetFileDescriptor afd, AudioAttributes expectedAudioAttributes) throws Exception {
        // This is very specific but it's a simple way to test that the test resource matches.
        if (afd.getDeclaredLength() < 0) {
            verify(mockPlayer).setDataSource(afd.getFileDescriptor());
        } else {
            verify(mockPlayer).setDataSource(afd.getFileDescriptor(),
                    afd.getStartOffset(),
                    afd.getDeclaredLength());
        }
        verify(mockPlayer).setAudioAttributes(expectedAudioAttributes);
        verify(mockPlayer).setPreferredDevice(null);
        verify(mockPlayer).prepare();
    }

    private MediaPlayerTestHelper() {
    }
}
