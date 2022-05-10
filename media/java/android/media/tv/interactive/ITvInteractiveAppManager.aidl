/*
 * Copyright 2021 The Android Open Source Project
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

package android.media.tv.interactive;

import android.graphics.Rect;
import android.media.tv.AdResponse;
import android.media.tv.BroadcastInfoResponse;
import android.media.tv.TvTrackInfo;
import android.media.tv.interactive.AppLinkInfo;
import android.media.tv.interactive.ITvInteractiveAppClient;
import android.media.tv.interactive.ITvInteractiveAppManagerCallback;
import android.media.tv.interactive.TvInteractiveAppServiceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;

/**
 * Interface to the TV interactive app service.
 * @hide
 */
interface ITvInteractiveAppManager {
    List<TvInteractiveAppServiceInfo> getTvInteractiveAppServiceList(int userId);
    void registerAppLinkInfo(String tiasId, in AppLinkInfo info, int userId);
    void unregisterAppLinkInfo(String tiasId, in AppLinkInfo info, int userId);
    void sendAppLinkCommand(String tiasId, in Bundle command, int userId);
    void startInteractiveApp(in IBinder sessionToken, int userId);
    void stopInteractiveApp(in IBinder sessionToken, int userId);
    void resetInteractiveApp(in IBinder sessionToken, int userId);
    void createBiInteractiveApp(
            in IBinder sessionToken, in Uri biIAppUri, in Bundle params, int userId);
    void destroyBiInteractiveApp(in IBinder sessionToken, in String biIAppId, int userId);
    void setTeletextAppEnabled(in IBinder sessionToken, boolean enable, int userId);
    void sendCurrentChannelUri(in IBinder sessionToken, in Uri channelUri, int userId);
    void sendCurrentChannelLcn(in IBinder sessionToken, int lcn, int userId);
    void sendStreamVolume(in IBinder sessionToken, float volume, int userId);
    void sendTrackInfoList(in IBinder sessionToken, in List<TvTrackInfo> tracks, int userId);
    void sendCurrentTvInputId(in IBinder sessionToken, in String inputId, int userId);
    void sendSigningResult(in IBinder sessionToken, in String signingId, in byte[] result,
            int userId);
    void notifyError(in IBinder sessionToken, in String errMsg, in Bundle params, int userId);
    void createSession(in ITvInteractiveAppClient client, in String iAppServiceId, int type,
            int seq, int userId);
    void releaseSession(in IBinder sessionToken, int userId);
    void notifyTuned(in IBinder sessionToken, in Uri channelUri, int userId);
    void notifyTrackSelected(in IBinder sessionToken, int type, in String trackId, int userId);
    void notifyTracksChanged(in IBinder sessionToken, in List<TvTrackInfo> tracks, int userId);
    void notifyVideoAvailable(in IBinder sessionToken, int userId);
    void notifyVideoUnavailable(in IBinder sessionToken, int reason, int userId);
    void notifyContentAllowed(in IBinder sessionToken, int userId);
    void notifyContentBlocked(in IBinder sessionToken, in String rating, int userId);
    void notifySignalStrength(in IBinder sessionToken, int stength, int userId);
    void setSurface(in IBinder sessionToken, in Surface surface, int userId);
    void dispatchSurfaceChanged(in IBinder sessionToken, int format, int width, int height,
            int userId);
    void notifyBroadcastInfoResponse(in IBinder sessionToken, in BroadcastInfoResponse response,
            int UserId);
    void notifyAdResponse(in IBinder sessionToken, in AdResponse response, int UserId);

    void createMediaView(in IBinder sessionToken, in IBinder windowToken, in Rect frame,
            int userId);
    void relayoutMediaView(in IBinder sessionToken, in Rect frame, int userId);
    void removeMediaView(in IBinder sessionToken, int userId);

    void registerCallback(in ITvInteractiveAppManagerCallback callback, int userId);
    void unregisterCallback(in ITvInteractiveAppManagerCallback callback, int userId);
}
