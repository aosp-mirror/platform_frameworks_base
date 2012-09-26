/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.media;

import android.net.Uri;
import android.os.UserHandle;

/**
 * @hide
 */
interface IRingtonePlayer {
    /** Used for Ringtone.java playback */
    void play(IBinder token, in Uri uri, int streamType);
    void stop(IBinder token);
    boolean isPlaying(IBinder token);

    /** Used for Notification sound playback. */
    void playAsync(in Uri uri, in UserHandle user, boolean looping, int streamType);
    void stopAsync();
}
