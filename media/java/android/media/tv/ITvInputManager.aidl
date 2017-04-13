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
import android.content.Intent;
import android.graphics.Rect;
import android.media.PlaybackParams;
import android.media.tv.DvbDeviceInfo;
import android.media.tv.ITvInputClient;
import android.media.tv.ITvInputHardware;
import android.media.tv.ITvInputHardwareCallback;
import android.media.tv.ITvInputManagerCallback;
import android.media.tv.TvContentRatingSystemInfo;
import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvStreamConfig;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

/**
 * Interface to the TV input manager service.
 * @hide
 */
interface ITvInputManager {
    List<TvInputInfo> getTvInputList(int userId);
    TvInputInfo getTvInputInfo(in String inputId, int userId);
    void updateTvInputInfo(in TvInputInfo inputInfo, int userId);
    int getTvInputState(in String inputId, int userId);

    List<TvContentRatingSystemInfo> getTvContentRatingSystemList(int userId);

    void registerCallback(in ITvInputManagerCallback callback, int userId);
    void unregisterCallback(in ITvInputManagerCallback callback, int userId);

    boolean isParentalControlsEnabled(int userId);
    void setParentalControlsEnabled(boolean enabled, int userId);
    boolean isRatingBlocked(in String rating, int userId);
    List<String> getBlockedRatings(int userId);
    void addBlockedRating(in String rating, int userId);
    void removeBlockedRating(in String rating, int userId);

    void createSession(in ITvInputClient client, in String inputId, boolean isRecordingSession,
            int seq, int userId);
    void releaseSession(in IBinder sessionToken, int userId);

    void setMainSession(in IBinder sessionToken, int userId);
    void setSurface(in IBinder sessionToken, in Surface surface, int userId);
    void dispatchSurfaceChanged(in IBinder sessionToken, int format, int width, int height,
            int userId);
    void setVolume(in IBinder sessionToken, float volume, int userId);
    void tune(in IBinder sessionToken, in Uri channelUri, in Bundle params, int userId);
    void setCaptionEnabled(in IBinder sessionToken, boolean enabled, int userId);
    void selectTrack(in IBinder sessionToken, int type, in String trackId, int userId);

    void sendAppPrivateCommand(in IBinder sessionToken, in String action, in Bundle data,
            int userId);

    void createOverlayView(in IBinder sessionToken, in IBinder windowToken, in Rect frame,
            int userId);
    void relayoutOverlayView(in IBinder sessionToken, in Rect frame, int userId);
    void removeOverlayView(in IBinder sessionToken, int userId);

    void unblockContent(in IBinder sessionToken, in String unblockedRating, int userId);

    void timeShiftPlay(in IBinder sessionToken, in Uri recordedProgramUri, int userId);
    void timeShiftPause(in IBinder sessionToken, int userId);
    void timeShiftResume(in IBinder sessionToken, int userId);
    void timeShiftSeekTo(in IBinder sessionToken, long timeMs, int userId);
    void timeShiftSetPlaybackParams(in IBinder sessionToken, in PlaybackParams params, int userId);
    void timeShiftEnablePositionTracking(in IBinder sessionToken, boolean enable, int userId);

    // For the recording session
    void startRecording(in IBinder sessionToken, in Uri programUri, int userId);
    void stopRecording(in IBinder sessionToken, int userId);

    // For TV input hardware binding
    List<TvInputHardwareInfo> getHardwareList();
    ITvInputHardware acquireTvInputHardware(int deviceId, in ITvInputHardwareCallback callback,
            in TvInputInfo info, int userId);
    void releaseTvInputHardware(int deviceId, in ITvInputHardware hardware, int userId);

    // For TV input capturing
    List<TvStreamConfig> getAvailableTvStreamConfigList(in String inputId, int userId);
    boolean captureFrame(in String inputId, in Surface surface, in TvStreamConfig config,
            int userId);
    boolean isSingleSessionActive(int userId);

    // For DVB device binding
    List<DvbDeviceInfo> getDvbDeviceList();
    ParcelFileDescriptor openDvbDevice(in DvbDeviceInfo info, int device);

    // For preview channels and programs
    void sendTvInputNotifyIntent(in Intent intent, int userId);
    void requestChannelBrowsable(in Uri channelUri, int userId);
}
