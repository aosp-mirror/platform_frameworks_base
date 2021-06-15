/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.media.metrics;

import android.content.Context;
import android.media.metrics.IMediaMetricsManager;
import android.media.metrics.NetworkEvent;
import android.media.metrics.PlaybackErrorEvent;
import android.media.metrics.PlaybackMetrics;
import android.media.metrics.PlaybackStateEvent;
import android.media.metrics.TrackChangeEvent;
import android.os.Binder;
import android.util.Base64;
import android.util.StatsEvent;
import android.util.StatsLog;

import com.android.server.SystemService;

import java.security.SecureRandom;

/**
 * System service manages media metrics.
 */
public final class MediaMetricsManagerService extends SystemService {
    private final SecureRandom mSecureRandom;

    /**
     * Initializes the playback metrics manager service.
     *
     * @param context The system server context.
     */
    public MediaMetricsManagerService(Context context) {
        super(context);
        mSecureRandom = new SecureRandom();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_METRICS_SERVICE, new BinderService());
    }

    private final class BinderService extends IMediaMetricsManager.Stub {
        @Override
        public void reportPlaybackMetrics(String sessionId, PlaybackMetrics metrics, int userId) {
            StatsEvent statsEvent = StatsEvent.newBuilder()
                    .setAtomId(320)
                    .writeInt(Binder.getCallingUid())
                    .writeString(sessionId)
                    .writeLong(metrics.getMediaDurationMillis())
                    .writeInt(metrics.getStreamSource())
                    .writeInt(metrics.getStreamType())
                    .writeInt(metrics.getPlaybackType())
                    .writeInt(metrics.getDrmType())
                    .writeInt(metrics.getContentType())
                    .writeString(metrics.getPlayerName())
                    .writeString(metrics.getPlayerVersion())
                    .writeByteArray(new byte[0]) // TODO: write experiments proto
                    .writeInt(metrics.getVideoFramesPlayed())
                    .writeInt(metrics.getVideoFramesDropped())
                    .writeInt(metrics.getAudioUnderrunCount())
                    .writeLong(metrics.getNetworkBytesRead())
                    .writeLong(metrics.getLocalBytesRead())
                    .writeLong(metrics.getNetworkTransferDurationMillis())
                    // Raw bytes type not allowed in atoms
                    .writeString(Base64.encodeToString(metrics.getDrmSessionId(), Base64.DEFAULT))
                    .usePooledBuffer()
                    .build();
            StatsLog.write(statsEvent);
        }

        @Override
        public void reportPlaybackStateEvent(
                String sessionId, PlaybackStateEvent event, int userId) {
            StatsEvent statsEvent = StatsEvent.newBuilder()
                    .setAtomId(322)
                    .writeString(sessionId)
                    .writeInt(event.getState())
                    .writeLong(event.getTimeSinceCreatedMillis())
                    .usePooledBuffer()
                    .build();
            StatsLog.write(statsEvent);
        }

        private String getSessionIdInternal(int userId) {
            byte[] byteId = new byte[16]; // 128 bits
            mSecureRandom.nextBytes(byteId);
            String id = Base64.encodeToString(byteId, Base64.DEFAULT);
            return id;
        }

        @Override
        public String getPlaybackSessionId(int userId) {
            return getSessionIdInternal(userId);
        }

        @Override
        public String getRecordingSessionId(int userId) {
            return getSessionIdInternal(userId);
        }

        @Override
        public void reportPlaybackErrorEvent(
                String sessionId, PlaybackErrorEvent event, int userId) {
            StatsEvent statsEvent = StatsEvent.newBuilder()
                    .setAtomId(323)
                    .writeString(sessionId)
                    .writeString(event.getExceptionStack())
                    .writeInt(event.getErrorCode())
                    .writeInt(event.getSubErrorCode())
                    .writeLong(event.getTimeSinceCreatedMillis())
                    .usePooledBuffer()
                    .build();
            StatsLog.write(statsEvent);
        }

        public void reportNetworkEvent(
                String sessionId, NetworkEvent event, int userId) {
            StatsEvent statsEvent = StatsEvent.newBuilder()
                    .setAtomId(321)
                    .writeString(sessionId)
                    .writeInt(event.getNetworkType())
                    .writeLong(event.getTimeSinceCreatedMillis())
                    .usePooledBuffer()
                    .build();
            StatsLog.write(statsEvent);
        }

        @Override
        public void reportTrackChangeEvent(
                String sessionId, TrackChangeEvent event, int userId) {
            StatsEvent statsEvent = StatsEvent.newBuilder()
                    .setAtomId(324)
                    .writeString(sessionId)
                    .writeInt(event.getTrackState())
                    .writeInt(event.getTrackChangeReason())
                    .writeString(event.getContainerMimeType())
                    .writeString(event.getSampleMimeType())
                    .writeString(event.getCodecName())
                    .writeInt(event.getBitrate())
                    .writeLong(event.getTimeSinceCreatedMillis())
                    .writeInt(event.getTrackType())
                    .writeString(event.getLanguage())
                    .writeString(event.getLanguageRegion())
                    .writeInt(event.getChannelCount())
                    .writeInt(event.getAudioSampleRate())
                    .writeInt(event.getWidth())
                    .writeInt(event.getHeight())
                    .writeFloat(event.getVideoFrameRate())
                    .usePooledBuffer()
                    .build();
            StatsLog.write(statsEvent);
        }
    }
}
