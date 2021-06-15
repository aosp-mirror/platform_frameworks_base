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
import android.content.pm.PackageManager;
import android.media.metrics.IMediaMetricsManager;
import android.media.metrics.NetworkEvent;
import android.media.metrics.PlaybackErrorEvent;
import android.media.metrics.PlaybackMetrics;
import android.media.metrics.PlaybackStateEvent;
import android.media.metrics.TrackChangeEvent;
import android.os.Binder;
import android.provider.DeviceConfig;
import android.util.Base64;
import android.util.Slog;
import android.util.StatsEvent;
import android.util.StatsLog;

import com.android.server.SystemService;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * System service manages media metrics.
 */
public final class MediaMetricsManagerService extends SystemService {
    private static final String TAG = "MediaMetricsManagerService";
    // TODO: update these constants when finalized
    private static final String MEDIA_METRICS_MODE = "media_metrics_mode";
    private static final int MEDIA_METRICS_MODE_OFF = 0;
    private static final int MEDIA_METRICS_MODE_ON = 1;
    private final SecureRandom mSecureRandom;
    private List<String> mBlockList = new ArrayList<>();

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
            if (!shouldWriteStats()) {
                return;
            }
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
            if (!shouldWriteStats()) {
                return;
            }
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
            String id = Base64.encodeToString(
                    byteId, Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE);
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
            if (!shouldWriteStats()) {
                return;
            }
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
            if (!shouldWriteStats()) {
                return;
            }
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
            if (!shouldWriteStats()) {
                return;
            }
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

        private boolean shouldWriteStats() {
            int uid = Binder.getCallingUid();

            final long identity = Binder.clearCallingIdentity();
            int mode = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_MEDIA, MEDIA_METRICS_MODE, MEDIA_METRICS_MODE_OFF);
            Binder.restoreCallingIdentity(identity);

            if (mode != MEDIA_METRICS_MODE_ON) {
                return false;
            }
            if (mBlockList.isEmpty()) {
                return true;
            }

            PackageManager pm = getContext().getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null) {
                // The valid application UID range is from android.os.Process.FIRST_APPLICATION_UID
                // to android.os.Process.LAST_APPLICATION_UID.
                // UIDs outside this range will not have a package and will therefore be false.
                Slog.d(TAG, "null package from uid " + uid);
                return false;
            }
            // TODO: check calling package with allowlist/blocklist.
            return true;
        }
    }
}
