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
import android.provider.DeviceConfig.Properties;
import android.util.Base64;
import android.util.Slog;
import android.util.StatsEvent;
import android.util.StatsLog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * System service manages media metrics.
 */
public final class MediaMetricsManagerService extends SystemService {
    private static final String TAG = "MediaMetricsManagerService";

    private static final String MEDIA_METRICS_MODE = "media_metrics_mode";
    private static final String PLAYER_METRICS_PER_APP_ATTRIBUTION_ALLOWLIST =
            "player_metrics_per_app_attribution_allowlist";
    private static final String PLAYER_METRICS_APP_ALLOWLIST = "player_metrics_app_allowlist";

    private static final String PLAYER_METRICS_PER_APP_ATTRIBUTION_BLOCKLIST =
            "player_metrics_per_app_attribution_blocklist";
    private static final String PLAYER_METRICS_APP_BLOCKLIST = "player_metrics_app_blocklist";

    private static final int MEDIA_METRICS_MODE_OFF = 0;
    private static final int MEDIA_METRICS_MODE_ON = 1;
    private static final int MEDIA_METRICS_MODE_BLOCKLIST = 2;
    private static final int MEDIA_METRICS_MODE_ALLOWLIST = 3;

    // Cascading logging levels. The higher value, the more constrains (less logging data).
    // The unused values between 2 consecutive levels are reserved for potential extra levels.
    private static final int LOGGING_LEVEL_EVERYTHING = 0;
    private static final int LOGGING_LEVEL_NO_UID = 1000;
    private static final int LOGGING_LEVEL_BLOCKED = 99999;

    private static final String FAILED_TO_GET = "failed_to_get";
    private final SecureRandom mSecureRandom;
    @GuardedBy("mLock")
    private Integer mMode = null;
    @GuardedBy("mLock")
    private List<String> mAllowlist = null;
    @GuardedBy("mLock")
    private List<String> mNoUidAllowlist = null;
    @GuardedBy("mLock")
    private List<String> mBlockList = null;
    @GuardedBy("mLock")
    private List<String> mNoUidBlocklist = null;
    private final Object mLock = new Object();
    private final Context mContext;

    /**
     * Initializes the playback metrics manager service.
     *
     * @param context The system server context.
     */
    public MediaMetricsManagerService(Context context) {
        super(context);
        mContext = context;
        mSecureRandom = new SecureRandom();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_METRICS_SERVICE, new BinderService());
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_MEDIA,
                mContext.getMainExecutor(),
                this::updateConfigs);
    }

    private void updateConfigs(Properties properties) {
        synchronized (mLock) {
            mMode = properties.getInt(
                    MEDIA_METRICS_MODE,
                    MEDIA_METRICS_MODE_BLOCKLIST);
            List<String> newList = getListLocked(PLAYER_METRICS_APP_ALLOWLIST);
            if (newList != null || mMode != MEDIA_METRICS_MODE_ALLOWLIST) {
                // don't overwrite the list if the mode IS MEDIA_METRICS_MODE_ALLOWLIST
                // but failed to get
                mAllowlist = newList;
            }
            newList = getListLocked(PLAYER_METRICS_PER_APP_ATTRIBUTION_ALLOWLIST);
            if (newList != null || mMode != MEDIA_METRICS_MODE_ALLOWLIST) {
                mNoUidAllowlist = newList;
            }
            newList = getListLocked(PLAYER_METRICS_APP_BLOCKLIST);
            if (newList != null || mMode != MEDIA_METRICS_MODE_BLOCKLIST) {
                mBlockList = newList;
            }
            newList = getListLocked(PLAYER_METRICS_PER_APP_ATTRIBUTION_BLOCKLIST);
            if (newList != null || mMode != MEDIA_METRICS_MODE_BLOCKLIST) {
                mNoUidBlocklist = newList;
            }
        }
    }

    @GuardedBy("mLock")
    private List<String> getListLocked(String listName) {
        final long identity = Binder.clearCallingIdentity();
        String listString = FAILED_TO_GET;
        try {
            listString = DeviceConfig.getString(
                    DeviceConfig.NAMESPACE_MEDIA, listName, FAILED_TO_GET);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (listString.equals(FAILED_TO_GET)) {
            Slog.d(TAG, "failed to get " + listName + " from DeviceConfig");
            return null;
        }
        String[] pkgArr = listString.split(",");
        return Arrays.asList(pkgArr);
    }

    private final class BinderService extends IMediaMetricsManager.Stub {
        @Override
        public void reportPlaybackMetrics(String sessionId, PlaybackMetrics metrics, int userId) {
            int level = loggingLevel();
            if (level == LOGGING_LEVEL_BLOCKED) {
                return;
            }
            StatsEvent statsEvent = StatsEvent.newBuilder()
                    .setAtomId(320)
                    .writeInt(level == LOGGING_LEVEL_EVERYTHING ? Binder.getCallingUid() : 0)
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
            int level = loggingLevel();
            if (level == LOGGING_LEVEL_BLOCKED) {
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
            byte[] byteId = new byte[12]; // 96 bits (128 bits when expanded to Base64 string)
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
            int level = loggingLevel();
            if (level == LOGGING_LEVEL_BLOCKED) {
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
            int level = loggingLevel();
            if (level == LOGGING_LEVEL_BLOCKED) {
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
            int level = loggingLevel();
            if (level == LOGGING_LEVEL_BLOCKED) {
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

        private int loggingLevel() {
            synchronized (mLock) {
                int uid = Binder.getCallingUid();

                if (mMode == null) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        mMode = DeviceConfig.getInt(
                            DeviceConfig.NAMESPACE_MEDIA,
                            MEDIA_METRICS_MODE,
                            MEDIA_METRICS_MODE_BLOCKLIST);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }

                if (mMode == MEDIA_METRICS_MODE_ON) {
                    return LOGGING_LEVEL_EVERYTHING;
                }
                if (mMode == MEDIA_METRICS_MODE_OFF) {
                    return LOGGING_LEVEL_BLOCKED;
                }

                PackageManager pm = getContext().getPackageManager();
                String[] packages = pm.getPackagesForUid(uid);
                if (packages == null || packages.length == 0) {
                    // The valid application UID range is from
                    // android.os.Process.FIRST_APPLICATION_UID to
                    // android.os.Process.LAST_APPLICATION_UID.
                    // UIDs outside this range will not have a package.
                    Slog.d(TAG, "empty package from uid " + uid);
                    // block the data if the mode is MEDIA_METRICS_MODE_ALLOWLIST
                    return mMode == MEDIA_METRICS_MODE_BLOCKLIST
                            ? LOGGING_LEVEL_NO_UID : LOGGING_LEVEL_BLOCKED;
                }
                if (mMode == MEDIA_METRICS_MODE_BLOCKLIST) {
                    if (mBlockList == null) {
                        mBlockList = getListLocked(PLAYER_METRICS_APP_BLOCKLIST);
                        if (mBlockList == null) {
                            // failed to get the blocklist. Block it.
                            return LOGGING_LEVEL_BLOCKED;
                        }
                    }
                    Integer level = loggingLevelInternal(
                            packages, mBlockList, PLAYER_METRICS_APP_BLOCKLIST);
                    if (level != null) {
                        return level;
                    }
                    if (mNoUidBlocklist == null) {
                        mNoUidBlocklist =
                                getListLocked(PLAYER_METRICS_PER_APP_ATTRIBUTION_BLOCKLIST);
                        if (mNoUidBlocklist == null) {
                            // failed to get the blocklist. Block it.
                            return LOGGING_LEVEL_BLOCKED;
                        }
                    }
                    level = loggingLevelInternal(
                            packages,
                            mNoUidBlocklist,
                            PLAYER_METRICS_PER_APP_ATTRIBUTION_BLOCKLIST);
                    if (level != null) {
                        return level;
                    }
                    // Not detected in any blocklist. Log everything.
                    return LOGGING_LEVEL_EVERYTHING;
                }
                if (mMode == MEDIA_METRICS_MODE_ALLOWLIST) {
                    if (mNoUidAllowlist == null) {
                        mNoUidAllowlist =
                                getListLocked(PLAYER_METRICS_PER_APP_ATTRIBUTION_ALLOWLIST);
                        if (mNoUidAllowlist == null) {
                            // failed to get the allowlist. Block it.
                            return LOGGING_LEVEL_BLOCKED;
                        }
                    }
                    Integer level = loggingLevelInternal(
                            packages,
                            mNoUidAllowlist,
                            PLAYER_METRICS_PER_APP_ATTRIBUTION_ALLOWLIST);
                    if (level != null) {
                        return level;
                    }
                    if (mAllowlist == null) {
                        mAllowlist = getListLocked(PLAYER_METRICS_APP_ALLOWLIST);
                        if (mAllowlist == null) {
                            // failed to get the allowlist. Block it.
                            return LOGGING_LEVEL_BLOCKED;
                        }
                    }
                    level = loggingLevelInternal(
                            packages, mAllowlist, PLAYER_METRICS_APP_ALLOWLIST);
                    if (level != null) {
                        return level;
                    }
                    // Not detected in any allowlist. Block.
                    return LOGGING_LEVEL_BLOCKED;
                }
            }
            // Blocked by default.
            return LOGGING_LEVEL_BLOCKED;
        }

        private Integer loggingLevelInternal(
                String[] packages, List<String> cached, String listName) {
            if (inList(packages, cached)) {
                return listNameToLoggingLevel(listName);
            }
            return null;
        }

        private boolean inList(String[] packages, List<String> arr) {
            for (String p : packages) {
                for (String element : arr) {
                    if (p.equals(element)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private int listNameToLoggingLevel(String listName) {
            switch (listName) {
                case PLAYER_METRICS_APP_BLOCKLIST:
                    return LOGGING_LEVEL_BLOCKED;
                case PLAYER_METRICS_APP_ALLOWLIST:
                    return LOGGING_LEVEL_EVERYTHING;
                case PLAYER_METRICS_PER_APP_ATTRIBUTION_ALLOWLIST:
                case PLAYER_METRICS_PER_APP_ATTRIBUTION_BLOCKLIST:
                    return LOGGING_LEVEL_NO_UID;
                default:
                    return LOGGING_LEVEL_BLOCKED;
            }
        }
    }
}
