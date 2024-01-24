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

import android.media.AudioPresentation;
import android.media.tv.AdBuffer;
import android.media.tv.AdResponse;
import android.media.tv.AitInfo;
import android.media.tv.BroadcastInfoResponse;
import android.media.tv.ITvInputSession;
import android.net.Uri;
import android.media.tv.TvTrackInfo;
import android.os.Bundle;

/**
 * Helper interface for ITvInputSession to allow the TV input to notify the system service when a
 * new session has been created.
 * @hide
 */
oneway interface ITvInputSessionCallback {
    void onSessionCreated(ITvInputSession session, in IBinder hardwareSessionToken);
    void onSessionEvent(in String name, in Bundle args);
    void onChannelRetuned(in Uri channelUri);
    void onAudioPresentationsChanged(in List<AudioPresentation> tvAudioPresentations);
    void onAudioPresentationSelected(int presentationId, int programId);
    void onTracksChanged(in List<TvTrackInfo> tracks);
    void onTrackSelected(int type, in String trackId);
    void onVideoAvailable();
    void onVideoUnavailable(int reason);
    void onVideoFreezeUpdated(boolean isFrozen);
    void onContentAllowed();
    void onContentBlocked(in String rating);
    void onLayoutSurface(int left, int top, int right, int bottom);
    void onTimeShiftStatusChanged(int status);
    void onTimeShiftStartPositionChanged(long timeMs);
    void onTimeShiftCurrentPositionChanged(long timeMs);
    void onAitInfoUpdated(in AitInfo aitInfo);
    void onSignalStrength(int strength);
    void onCueingMessageAvailability(boolean available);
    void onTimeShiftMode(int mode);
    void onAvailableSpeeds(in float[] speeds);

    // For the recording session
    void onTuned(in Uri channelUri);
    void onRecordingStopped(in Uri recordedProgramUri);
    void onError(int error);

    // For broadcast info
    void onBroadcastInfoResponse(in BroadcastInfoResponse response);

    // For ad response
    void onAdResponse(in AdResponse response);
    void onAdBufferConsumed(in AdBuffer buffer);

    // For messages sent from the TV input
    void onTvMessage(int type, in Bundle data);
}
