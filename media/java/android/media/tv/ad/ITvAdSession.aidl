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
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;

/**
 * Sub-interface of ITvAdService.aidl which is created per session and has its own context.
 * @hide
 */
oneway interface ITvAdSession {
    void release();
    void startAdService();
    void stopAdService();
    void resetAdService();
    void setSurface(in Surface surface);
    void dispatchSurfaceChanged(int format, int width, int height);

    void sendCurrentVideoBounds(in Rect bounds);
    void sendCurrentChannelUri(in Uri channelUri);
    void sendTrackInfoList(in List<TvTrackInfo> tracks);
    void sendCurrentTvInputId(in String inputId);
    void sendSigningResult(in String signingId, in byte[] result);

    void notifyError(in String errMsg, in Bundle params);
    void notifyTvMessage(int type, in Bundle data);

    void createMediaView(in IBinder windowToken, in Rect frame);
    void relayoutMediaView(in Rect frame);
    void removeMediaView();

    void notifyTvInputSessionData(in String type, in Bundle data);
}
