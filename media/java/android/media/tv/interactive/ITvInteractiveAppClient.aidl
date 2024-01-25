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
import android.media.tv.AdBuffer;
import android.media.tv.AdRequest;
import android.media.tv.BroadcastInfoRequest;
import android.media.tv.TvRecordingInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.InputChannel;

/**
 * Interface a client of the ITvInteractiveAppManager implements, to identify itself and receive
 * information about changes to the state of each TV interactive application service.
 * @hide
 */
oneway interface ITvInteractiveAppClient {
    void onSessionCreated(in String iAppServiceId, IBinder token, in InputChannel channel, int seq);
    void onSessionReleased(int seq);
    void onLayoutSurface(int left, int top, int right, int bottom, int seq);
    void onBroadcastInfoRequest(in BroadcastInfoRequest request, int seq);
    void onRemoveBroadcastInfo(int id, int seq);
    void onSessionStateChanged(int state, int err, int seq);
    void onBiInteractiveAppCreated(in Uri biIAppUri, in String biIAppId, int seq);
    void onTeletextAppStateChanged(int state, int seq);
    void onAdBufferReady(in AdBuffer buffer, int seq);
    void onCommandRequest(in String cmdType, in Bundle parameters, int seq);
    void onTimeShiftCommandRequest(in String cmdType, in Bundle parameters, int seq);
    void onSetVideoBounds(in Rect rect, int seq);
    void onRequestCurrentVideoBounds(int seq);
    void onRequestCurrentChannelUri(int seq);
    void onRequestCurrentChannelLcn(int seq);
    void onRequestStreamVolume(int seq);
    void onRequestTrackInfoList(int seq);
    void onRequestSelectedTrackInfo(int seq);
    void onRequestCurrentTvInputId(int seq);
    void onRequestTimeShiftMode(int seq);
    void onRequestAvailableSpeeds(int seq);
    void onRequestStartRecording(in String requestId, in Uri programUri, int seq);
    void onRequestStopRecording(in String recordingId, int seq);
    void onRequestScheduleRecording(in String requestId, in String inputId, in Uri channelUri,
            in Uri programUri, in Bundle params, int seq);
    void onRequestScheduleRecording2(in String requestId, in String inputId, in Uri channelUri,
            long start, long duration, int repeat, in Bundle params, int seq);
    void onSetTvRecordingInfo(in String recordingId, in TvRecordingInfo recordingInfo, int seq);
    void onRequestTvRecordingInfo(in String recordingId, int seq);
    void onRequestTvRecordingInfoList(in int type, int seq);
    void onRequestSigning(in String id, in String algorithm, in String alias, in byte[] data,
            int seq);
    void onRequestCertificate(in String host, int port, int seq);
    void onAdRequest(in AdRequest request, int Seq);
}
