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
import android.media.AudioPresentation;
import android.media.tv.AdBuffer;
import android.media.tv.AdResponse;
import android.media.tv.AitInfo;
import android.media.tv.BroadcastInfoResponse;
import android.media.tv.ITvInputSession;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.InputChannel;

/**
 * Interface a client of the ITvInputManager implements, to identify itself and receive information
 * about changes to the state of each TV input service.
 * @hide
 */
oneway interface ITvInputClient {
    void onSessionCreated(in String inputId, IBinder token, in InputChannel channel, int seq);
    void onSessionReleased(int seq);
    void onSessionEvent(in String name, in Bundle args, int seq);
    void onChannelRetuned(in Uri channelUri, int seq);
    void onAudioPresentationsChanged(in List<AudioPresentation> AudioPresentations, int seq);
    void onAudioPresentationSelected(int presentationId, int programId, int seq);
    void onTracksChanged(in List<TvTrackInfo> tracks, int seq);
    void onTrackSelected(int type, in String trackId, int seq);
    void onVideoAvailable(int seq);
    void onVideoUnavailable(int reason, int seq);
    void onVideoFreezeUpdated(boolean isFrozen, int seq);
    void onContentAllowed(int seq);
    void onContentBlocked(in String rating, int seq);
    void onLayoutSurface(int left, int top, int right, int bottom, int seq);
    void onTimeShiftStatusChanged(int status, int seq);
    void onTimeShiftStartPositionChanged(long timeMs, int seq);
    void onTimeShiftCurrentPositionChanged(long timeMs, int seq);
    void onAitInfoUpdated(in AitInfo aitInfo, int seq);
    void onSignalStrength(int stength, int seq);
    void onCueingMessageAvailability(boolean available, int seq);
    void onTimeShiftMode(int mode, int seq);
    void onAvailableSpeeds(in float[] speeds, int seq);
    void onTvMessage(int type, in Bundle data, int seq);

    void onTuned(in Uri channelUri, int seq);
    // For the recording session
    void onRecordingStopped(in Uri recordedProgramUri, int seq);
    void onError(int error, int seq);

    // For broadcast info
    void onBroadcastInfoResponse(in BroadcastInfoResponse response, int seq);

    // For ad response
    void onAdResponse(in AdResponse response, int seq);
    void onAdBufferConsumed(in AdBuffer buffer, int seq);
}
