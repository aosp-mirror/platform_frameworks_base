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

import android.graphics.Rect;
import android.media.AudioPresentation;
import android.media.PlaybackParams;
import android.media.tv.AdBuffer;
import android.media.tv.AdRequest;
import android.media.tv.BroadcastInfoRequest;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;

/**
 * Sub-interface of ITvInputService which is created per session and has its own context.
 * @hide
 */
oneway interface ITvInputSession {
    void release();

    void setMain(boolean isMain);
    void setSurface(in Surface surface);
    void dispatchSurfaceChanged(int format, int width, int height);
    // TODO: Remove this once it becomes irrelevant for applications to handle audio focus. The plan
    // is to introduce some new concepts that will solve a number of problems in audio policy today.
    void setVolume(float volume);
    void tune(in Uri channelUri, in Bundle params);
    void setCaptionEnabled(boolean enabled);
    void selectAudioPresentation(int presentationId, int programId);
    void selectTrack(int type, in String trackId);

    void setInteractiveAppNotificationEnabled(boolean enable);

    void appPrivateCommand(in String action, in Bundle data);

    void createOverlayView(in IBinder windowToken, in Rect frame);
    void relayoutOverlayView(in Rect frame);
    void removeOverlayView();

    void unblockContent(in String unblockedRating);

    void timeShiftPlay(in Uri recordedProgramUri);
    void timeShiftPause();
    void timeShiftResume();
    void timeShiftSeekTo(long timeMs);
    void timeShiftSetPlaybackParams(in PlaybackParams params);
    void timeShiftSetMode(int mode);
    void timeShiftEnablePositionTracking(boolean enable);

    void startPlayback();
    void stopPlayback(int mode);

    // For the recording session
    void startRecording(in Uri programUri, in Bundle params);
    void stopRecording();
    void pauseRecording(in Bundle params);
    void resumeRecording(in Bundle params);

    // For broadcast info
    void requestBroadcastInfo(in BroadcastInfoRequest request);
    void removeBroadcastInfo(int id);

    // For ad request
    void requestAd(in AdRequest request);
    void notifyAdBufferReady(in AdBuffer buffer);

    // For TV messages
    void notifyTvMessage(int type, in Bundle data);
    void setTvMessageEnabled(int type, boolean enabled);
}
