/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media.tv.ad;

import android.graphics.Rect;
import android.media.tv.TvTrackInfo;
import android.media.tv.ad.ITvAdClient;
import android.media.tv.ad.ITvAdManagerCallback;
import android.media.tv.ad.TvAdServiceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;

/**
 * Interface to the TV AD service.
 * @hide
 */
interface ITvAdManager {
    List<TvAdServiceInfo> getTvAdServiceList(int userId);
    void sendAppLinkCommand(String serviceId, in Bundle command, int userId);
    void createSession(
            in ITvAdClient client, in String serviceId, in String type, int seq, int userId);
    void releaseSession(in IBinder sessionToken, int userId);
    void startAdService(in IBinder sessionToken, int userId);
    void stopAdService(in IBinder sessionToken, int userId);
    void resetAdService(in IBinder sessionToken, int userId);
    void setSurface(in IBinder sessionToken, in Surface surface, int userId);
    void dispatchSurfaceChanged(in IBinder sessionToken, int format, int width, int height,
            int userId);

    void sendCurrentVideoBounds(in IBinder sessionToken, in Rect bounds, int userId);
    void sendCurrentChannelUri(in IBinder sessionToken, in Uri channelUri, int userId);
    void sendTrackInfoList(in IBinder sessionToken, in List<TvTrackInfo> tracks, int userId);
    void sendCurrentTvInputId(in IBinder sessionToken, in String inputId, int userId);
    void sendSigningResult(in IBinder sessionToken, in String signingId, in byte[] result,
            int userId);

    void notifyError(in IBinder sessionToken, in String errMsg, in Bundle params, int userId);
    void notifyTvMessage(in IBinder sessionToken, in int type, in Bundle data, int userId);

    void registerCallback(in ITvAdManagerCallback callback, int userId);
    void unregisterCallback(in ITvAdManagerCallback callback, int userId);

    void createMediaView(in IBinder sessionToken, in IBinder windowToken, in Rect frame,
            int userId);
    void relayoutMediaView(in IBinder sessionToken, in Rect frame, int userId);
    void removeMediaView(in IBinder sessionToken, int userId);

    void notifyTvInputSessionData(
            in IBinder sessionToken, in String type, in Bundle data, int userId);
}
