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
import android.media.tv.interactive.ITvInteractiveAppSession;
import android.net.Uri;
import android.os.Bundle;

/**
 * Helper interface for ITvInteractiveAppSession to allow TvInteractiveAppService to notify the
 * system service when there is a related event.
 * @hide
 */
oneway interface ITvInteractiveAppSessionCallback {
    void onSessionCreated(in ITvInteractiveAppSession session);
    void onLayoutSurface(int left, int top, int right, int bottom);
    void onBroadcastInfoRequest(in BroadcastInfoRequest request);
    void onRemoveBroadcastInfo(int id);
    void onSessionStateChanged(int state, int err);
    void onBiInteractiveAppCreated(in Uri biIAppUri, in String biIAppId);
    void onTeletextAppStateChanged(int state);
    void onAdBufferReady(in AdBuffer buffer);
    void onCommandRequest(in String cmdType, in Bundle parameters);
    void onTimeShiftCommandRequest(in String cmdType, in Bundle parameters);
    void onSetVideoBounds(in Rect rect);
    void onRequestCurrentVideoBounds();
    void onRequestCurrentChannelUri();
    void onRequestCurrentChannelLcn();
    void onRequestStreamVolume();
    void onRequestTrackInfoList();
    void onRequestCurrentTvInputId();
    void onRequestTimeShiftMode();
    void onRequestAvailableSpeeds();
    void onRequestSelectedTrackInfo();
    void onRequestStartRecording(in String requestId, in Uri programUri);
    void onRequestStopRecording(in String recordingId);
    void onRequestScheduleRecording(in String requestId, in String inputId, in Uri channelUri,
            in Uri programUri, in Bundle params);
    void onRequestScheduleRecording2(in String requestId, in String inputId, in Uri channelUri,
            long start, long duration, int repeat, in Bundle params);
    void onSetTvRecordingInfo(in String recordingId, in TvRecordingInfo recordingInfo);
    void onRequestTvRecordingInfo(in String recordingId);
    void onRequestTvRecordingInfoList(in int type);
    void onRequestSigning(in String id, in String algorithm, in String alias, in byte[] data);
    void onRequestSigning2(in String id, in String algorithm, in String host, int port, in byte[] data);
    void onRequestCertificate(in String host, int port);
    void onAdRequest(in AdRequest request);
}
