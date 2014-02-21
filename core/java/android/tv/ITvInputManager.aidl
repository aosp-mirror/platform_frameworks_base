/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.tv;

import android.content.ComponentName;
import android.net.Uri;
import android.tv.ITvInputClient;
import android.tv.TvInputInfo;
import android.view.Surface;

/**
 * Interface to the TV input manager service.
 * @hide
 */
interface ITvInputManager {
    List<TvInputInfo> getTvInputList(int userId);

    boolean getAvailability(in ITvInputClient client, in ComponentName name, int userId);

    void registerCallback(in ITvInputClient client, in ComponentName name, int userId);
    void unregisterCallback(in ITvInputClient client, in ComponentName name, int userId);

    void createSession(in ITvInputClient client, in ComponentName name, int seq, int userId);
    void releaseSession(in IBinder sessionToken, int userId);

    void setSurface(in IBinder sessionToken, in Surface surface, int userId);
    void setVolume(in IBinder sessionToken, float volume, int userId);
    void tune(in IBinder sessionToken, in Uri channelUri, int userId);
}
