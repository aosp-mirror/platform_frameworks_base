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

package android.media.tv;

import android.content.ComponentName;
import android.graphics.Rect;
import android.media.tv.ITvInputHardware;
import android.media.tv.ITvInputHardwareCallback;
import android.media.tv.ITvInputClient;
import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvInputInfo;
import android.net.Uri;
import android.view.Surface;

/**
 * Interface to the TV input manager service.
 * @hide
 */
interface ITvInputManager {
    List<TvInputInfo> getTvInputList(int userId);

    boolean getAvailability(in ITvInputClient client, in String inputId, int userId);

    void registerCallback(in ITvInputClient client, in String inputId, int userId);
    void unregisterCallback(in ITvInputClient client, in String inputId, int userId);

    void createSession(in ITvInputClient client, in String inputId, int seq, int userId);
    void releaseSession(in IBinder sessionToken, int userId);

    void setSurface(in IBinder sessionToken, in Surface surface, int userId);
    void setVolume(in IBinder sessionToken, float volume, int userId);
    void tune(in IBinder sessionToken, in Uri channelUri, int userId);

    void createOverlayView(in IBinder sessionToken, in IBinder windowToken, in Rect frame,
            int userId);
    void relayoutOverlayView(in IBinder sessionToken, in Rect frame, int userId);
    void removeOverlayView(in IBinder sessionToken, int userId);

    // For TV input hardware binding
    List<TvInputHardwareInfo> getHardwareList();
    ITvInputHardware acquireTvInputHardware(int deviceId, in ITvInputHardwareCallback callback,
            int userId);
    void releaseTvInputHardware(int deviceId, in ITvInputHardware hardware, int userId);
}
