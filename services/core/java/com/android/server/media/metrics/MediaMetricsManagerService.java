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
import android.hardware.DataSpace;
import android.media.MediaMetrics;
import android.media.codec.Enums;
import android.media.metrics.BundleSession;
import android.media.metrics.EditingEndedEvent;
import android.media.metrics.IMediaMetricsManager;
import android.media.metrics.MediaItemInfo;
import android.media.metrics.NetworkEvent;
import android.media.metrics.PlaybackErrorEvent;
import android.media.metrics.PlaybackMetrics;
import android.media.metrics.PlaybackStateEvent;
import android.media.metrics.TrackChangeEvent;
import android.os.Binder;
import android.os.PersistableBundle;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Size;
import android.util.Slog;
import android.util.StatsEvent;
import android.util.StatsLog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

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

    private static final String mMetricsId = MediaMetrics.Name.METRICS_MANAGER;

    private static final String FAILED_TO_GET = "failed_to_get";

    private static final MediaItemInfo EMPTY_MEDIA_ITEM_INFO = new MediaItemInfo.Builder().build();
    private static final Pattern PATTERN_KNOWN_EDITING_LIBRARY_NAMES =
            Pattern.compile(
                    "androidx\\.media3:media3-(transformer|muxer):"
                            + "[\\d.]+(-(alpha|beta|rc)\\d\\d)?");
    private static final int DURATION_BUCKETS_BELOW_ONE_MINUTE = 8;
    private static final int DURATION_BUCKETS_COUNT = 13;
    private static final String AUDIO_MIME_TYPE_PREFIX = "audio/";
    private static final String VIDEO_MIME_TYPE_PREFIX = "video/";
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

        public void reportBundleMetrics(String sessionId, PersistableBundle metrics, int userId) {
            int level = loggingLevel();
            if (level == LOGGING_LEVEL_BLOCKED) {
                return;
            }

            int atomid = metrics.getInt(BundleSession.KEY_STATSD_ATOM);
            switch (atomid) {
                default:
                    return;
                // to be extended as we define statsd atoms
                case 322: // MediaPlaybackStateEvent
                    // pattern for the keys:
                    // <statsd event> - <fieldname>
                    // match types to what stats will want
                    String _sessionId = metrics.getString("playbackstateevent-sessionid");
                    int _state = metrics.getInt("playbackstateevent-state", -1);
                    long _lifetime = metrics.getLong("playbackstateevent-lifetime", -1);
                    if (_sessionId == null || _state < 0 || _lifetime < 0) {
                        Slog.d(TAG, "dropping incomplete data for atom 322: _sessionId: "
                                        + _sessionId + " _state: " + _state
                                        + " _lifetime: " + _lifetime);
                        return;
                    }
                    StatsEvent statsEvent = StatsEvent.newBuilder()
                            .setAtomId(322)
                            .writeString(_sessionId)
                            .writeInt(_state)
                            .writeLong(_lifetime)
                            .usePooledBuffer()
                            .build();
                    StatsLog.write(statsEvent);
                    return;
            }
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

            // Authorize these session ids in the native mediametrics service.
            new MediaMetrics.Item(mMetricsId)
                    .set(MediaMetrics.Property.EVENT, "create")
                    .set(MediaMetrics.Property.LOG_SESSION_ID, id)
                    .record();
            return id;
        }

        @Override
        public void releaseSessionId(String sessionId, int userId) {
            // De-authorize this session-id in the native mediametrics service.
            // TODO: plumbing to the native mediametrics service
            Slog.v(TAG, "Releasing sessionId " + sessionId + " for userId " + userId + " [NOP]");
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
        public String getTranscodingSessionId(int userId) {
            return getSessionIdInternal(userId);
        }

        @Override
        public String getEditingSessionId(int userId) {
            return getSessionIdInternal(userId);
        }

        @Override
        public String getBundleSessionId(int userId) {
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

        @Override
        public void reportEditingEndedEvent(String sessionId, EditingEndedEvent event, int userId) {
            int level = loggingLevel();
            if (level == LOGGING_LEVEL_BLOCKED) {
                return;
            }
            MediaItemInfo inputMediaItemInfo =
                    event.getInputMediaItemInfos().isEmpty()
                            ? EMPTY_MEDIA_ITEM_INFO
                            : event.getInputMediaItemInfos().get(0);
            @MediaItemInfo.DataType long inputDataTypes = inputMediaItemInfo.getDataTypes();
            String inputAudioSampleMimeType =
                    getFilteredFirstMimeType(
                            inputMediaItemInfo.getSampleMimeTypes(), AUDIO_MIME_TYPE_PREFIX);
            String inputVideoSampleMimeType =
                    getFilteredFirstMimeType(
                            inputMediaItemInfo.getSampleMimeTypes(), VIDEO_MIME_TYPE_PREFIX);
            Size inputVideoSize = inputMediaItemInfo.getVideoSize();
            int inputVideoResolution = getVideoResolutionEnum(inputVideoSize);
            if (inputVideoResolution == Enums.RESOLUTION_UNKNOWN) {
                // Try swapping width/height in case it's a portrait video.
                inputVideoResolution =
                        getVideoResolutionEnum(
                                new Size(inputVideoSize.getHeight(), inputVideoSize.getWidth()));
            }
            List<String> inputCodecNames = inputMediaItemInfo.getCodecNames();
            String inputFirstCodecName = !inputCodecNames.isEmpty() ? inputCodecNames.get(0) : "";
            String inputSecondCodecName = inputCodecNames.size() > 1 ? inputCodecNames.get(1) : "";

            MediaItemInfo outputMediaItemInfo =
                    event.getOutputMediaItemInfo() == null
                            ? EMPTY_MEDIA_ITEM_INFO
                            : event.getOutputMediaItemInfo();
            @MediaItemInfo.DataType long outputDataTypes = outputMediaItemInfo.getDataTypes();
            String outputAudioSampleMimeType =
                    getFilteredFirstMimeType(
                            outputMediaItemInfo.getSampleMimeTypes(), AUDIO_MIME_TYPE_PREFIX);
            String outputVideoSampleMimeType =
                    getFilteredFirstMimeType(
                            outputMediaItemInfo.getSampleMimeTypes(), VIDEO_MIME_TYPE_PREFIX);
            Size outputVideoSize = outputMediaItemInfo.getVideoSize();
            int outputVideoResolution = getVideoResolutionEnum(outputVideoSize);
            if (outputVideoResolution == Enums.RESOLUTION_UNKNOWN) {
                // Try swapping width/height in case it's a portrait video.
                outputVideoResolution =
                        getVideoResolutionEnum(
                                new Size(outputVideoSize.getHeight(), outputVideoSize.getWidth()));
            }
            List<String> outputCodecNames = outputMediaItemInfo.getCodecNames();
            String outputFirstCodecName =
                    !outputCodecNames.isEmpty() ? outputCodecNames.get(0) : "";
            String outputSecondCodecName =
                    outputCodecNames.size() > 1 ? outputCodecNames.get(1) : "";
            @EditingEndedEvent.OperationType long operationTypes = event.getOperationTypes();
            StatsEvent statsEvent =
                    StatsEvent.newBuilder()
                            .setAtomId(798)
                            .writeString(sessionId)
                            .writeInt(event.getFinalState())
                            .writeFloat(event.getFinalProgressPercent())
                            .writeInt(event.getErrorCode())
                            .writeLong(event.getTimeSinceCreatedMillis())
                            .writeBoolean(
                                    (operationTypes
                                                    & EditingEndedEvent
                                                            .OPERATION_TYPE_VIDEO_TRANSCODE)
                                            != 0)
                            .writeBoolean(
                                    (operationTypes
                                                    & EditingEndedEvent
                                                            .OPERATION_TYPE_AUDIO_TRANSCODE)
                                            != 0)
                            .writeBoolean(
                                    (operationTypes & EditingEndedEvent.OPERATION_TYPE_VIDEO_EDIT)
                                            != 0)
                            .writeBoolean(
                                    (operationTypes & EditingEndedEvent.OPERATION_TYPE_AUDIO_EDIT)
                                            != 0)
                            .writeBoolean(
                                    (operationTypes
                                                    & EditingEndedEvent
                                                            .OPERATION_TYPE_VIDEO_TRANSMUX)
                                            != 0)
                            .writeBoolean(
                                    (operationTypes
                                                    & EditingEndedEvent
                                                            .OPERATION_TYPE_AUDIO_TRANSMUX)
                                            != 0)
                            .writeBoolean(
                                    (operationTypes & EditingEndedEvent.OPERATION_TYPE_PAUSED) != 0)
                            .writeBoolean(
                                    (operationTypes & EditingEndedEvent.OPERATION_TYPE_RESUMED)
                                            != 0)
                            .writeString(getFilteredLibraryName(event.getExporterName()))
                            .writeString(getFilteredLibraryName(event.getMuxerName()))
                            .writeInt(getThroughputFps(event))
                            .writeInt(event.getInputMediaItemInfos().size())
                            .writeInt(inputMediaItemInfo.getSourceType())
                            .writeBoolean((inputDataTypes & MediaItemInfo.DATA_TYPE_IMAGE) != 0)
                            .writeBoolean((inputDataTypes & MediaItemInfo.DATA_TYPE_VIDEO) != 0)
                            .writeBoolean((inputDataTypes & MediaItemInfo.DATA_TYPE_AUDIO) != 0)
                            .writeBoolean((inputDataTypes & MediaItemInfo.DATA_TYPE_METADATA) != 0)
                            .writeBoolean((inputDataTypes & MediaItemInfo.DATA_TYPE_DEPTH) != 0)
                            .writeBoolean((inputDataTypes & MediaItemInfo.DATA_TYPE_GAIN_MAP) != 0)
                            .writeBoolean(
                                    (inputDataTypes & MediaItemInfo.DATA_TYPE_HIGH_FRAME_RATE) != 0)
                            .writeBoolean(
                                    (inputDataTypes
                                                    & MediaItemInfo
                                                            .DATA_TYPE_SPEED_SETTING_CUE_POINTS)
                                            != 0)
                            .writeBoolean((inputDataTypes & MediaItemInfo.DATA_TYPE_GAPLESS) != 0)
                            .writeBoolean(
                                    (inputDataTypes & MediaItemInfo.DATA_TYPE_SPATIAL_AUDIO) != 0)
                            .writeBoolean(
                                    (inputDataTypes
                                                    & MediaItemInfo
                                                            .DATA_TYPE_HIGH_DYNAMIC_RANGE_VIDEO)
                                            != 0)
                            .writeLong(
                                    getBucketedDurationMillis(
                                            inputMediaItemInfo.getDurationMillis()))
                            .writeLong(
                                    getBucketedDurationMillis(
                                            inputMediaItemInfo.getClipDurationMillis()))
                            .writeString(
                                    getFilteredMimeType(inputMediaItemInfo.getContainerMimeType()))
                            .writeString(inputAudioSampleMimeType)
                            .writeString(inputVideoSampleMimeType)
                            .writeInt(getCodecEnum(inputVideoSampleMimeType))
                            .writeInt(
                                    getFilteredAudioSampleRateHz(
                                            inputMediaItemInfo.getAudioSampleRateHz()))
                            .writeInt(inputMediaItemInfo.getAudioChannelCount())
                            .writeLong(inputMediaItemInfo.getAudioSampleCount())
                            .writeInt(inputVideoSize.getWidth())
                            .writeInt(inputVideoSize.getHeight())
                            .writeInt(inputVideoResolution)
                            .writeInt(getVideoResolutionAspectRatioEnum(inputVideoSize))
                            .writeInt(inputMediaItemInfo.getVideoDataSpace())
                            .writeInt(
                                    getVideoHdrFormatEnum(
                                            inputMediaItemInfo.getVideoDataSpace(),
                                            inputVideoSampleMimeType))
                            .writeInt(Math.round(inputMediaItemInfo.getVideoFrameRate()))
                            .writeInt(getVideoFrameRateEnum(inputMediaItemInfo.getVideoFrameRate()))
                            .writeString(inputFirstCodecName)
                            .writeString(inputSecondCodecName)
                            .writeBoolean((outputDataTypes & MediaItemInfo.DATA_TYPE_IMAGE) != 0)
                            .writeBoolean((outputDataTypes & MediaItemInfo.DATA_TYPE_VIDEO) != 0)
                            .writeBoolean((outputDataTypes & MediaItemInfo.DATA_TYPE_AUDIO) != 0)
                            .writeBoolean((outputDataTypes & MediaItemInfo.DATA_TYPE_METADATA) != 0)
                            .writeBoolean((outputDataTypes & MediaItemInfo.DATA_TYPE_DEPTH) != 0)
                            .writeBoolean((outputDataTypes & MediaItemInfo.DATA_TYPE_GAIN_MAP) != 0)
                            .writeBoolean(
                                    (outputDataTypes & MediaItemInfo.DATA_TYPE_HIGH_FRAME_RATE)
                                            != 0)
                            .writeBoolean(
                                    (outputDataTypes
                                                    & MediaItemInfo
                                                            .DATA_TYPE_SPEED_SETTING_CUE_POINTS)
                                            != 0)
                            .writeBoolean((outputDataTypes & MediaItemInfo.DATA_TYPE_GAPLESS) != 0)
                            .writeBoolean(
                                    (outputDataTypes & MediaItemInfo.DATA_TYPE_SPATIAL_AUDIO) != 0)
                            .writeBoolean(
                                    (outputDataTypes
                                                    & MediaItemInfo
                                                            .DATA_TYPE_HIGH_DYNAMIC_RANGE_VIDEO)
                                            != 0)
                            .writeLong(
                                    getBucketedDurationMillis(
                                            outputMediaItemInfo.getDurationMillis()))
                            .writeLong(
                                    getBucketedDurationMillis(
                                            outputMediaItemInfo.getClipDurationMillis()))
                            .writeString(
                                    getFilteredMimeType(outputMediaItemInfo.getContainerMimeType()))
                            .writeString(outputAudioSampleMimeType)
                            .writeString(outputVideoSampleMimeType)
                            .writeInt(getCodecEnum(outputVideoSampleMimeType))
                            .writeInt(
                                    getFilteredAudioSampleRateHz(
                                            outputMediaItemInfo.getAudioSampleRateHz()))
                            .writeInt(outputMediaItemInfo.getAudioChannelCount())
                            .writeLong(outputMediaItemInfo.getAudioSampleCount())
                            .writeInt(outputVideoSize.getWidth())
                            .writeInt(outputVideoSize.getHeight())
                            .writeInt(outputVideoResolution)
                            .writeInt(getVideoResolutionAspectRatioEnum(outputVideoSize))
                            .writeInt(outputMediaItemInfo.getVideoDataSpace())
                            .writeInt(
                                    getVideoHdrFormatEnum(
                                            outputMediaItemInfo.getVideoDataSpace(),
                                            outputVideoSampleMimeType))
                            .writeInt(Math.round(outputMediaItemInfo.getVideoFrameRate()))
                            .writeInt(
                                    getVideoFrameRateEnum(outputMediaItemInfo.getVideoFrameRate()))
                            .writeString(outputFirstCodecName)
                            .writeString(outputSecondCodecName)
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
                    Slog.v(TAG, "Logging level blocked: MEDIA_METRICS_MODE_OFF");
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
                            Slog.v(TAG, "Logging level blocked: Failed to get "
                                    + "PLAYER_METRICS_APP_BLOCKLIST.");
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
                            Slog.v(TAG, "Logging level blocked: Failed to get "
                                    + "PLAYER_METRICS_PER_APP_ATTRIBUTION_BLOCKLIST.");
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
                            Slog.v(TAG, "Logging level blocked: Failed to get "
                                    + "PLAYER_METRICS_PER_APP_ATTRIBUTION_ALLOWLIST.");
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
                            Slog.v(TAG, "Logging level blocked: Failed to get "
                                    + "PLAYER_METRICS_APP_ALLOWLIST.");
                            return LOGGING_LEVEL_BLOCKED;
                        }
                    }
                    level = loggingLevelInternal(
                            packages, mAllowlist, PLAYER_METRICS_APP_ALLOWLIST);
                    if (level != null) {
                        return level;
                    }
                    // Not detected in any allowlist. Block.
                    Slog.v(TAG, "Logging level blocked: Not detected in any allowlist.");
                    return LOGGING_LEVEL_BLOCKED;
                }
            }
            // Blocked by default.
            Slog.v(TAG, "Logging level blocked: Blocked by default.");
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

    private static String getFilteredLibraryName(String libraryName) {
        if (TextUtils.isEmpty(libraryName)) {
            return "";
        }
        if (!PATTERN_KNOWN_EDITING_LIBRARY_NAMES.matcher(libraryName).matches()) {
            return "";
        }
        return libraryName;
    }

    private static int getThroughputFps(EditingEndedEvent event) {
        MediaItemInfo outputMediaItemInfo = event.getOutputMediaItemInfo();
        if (outputMediaItemInfo == null) {
            return -1;
        }
        long videoSampleCount = outputMediaItemInfo.getVideoSampleCount();
        if (videoSampleCount == MediaItemInfo.VALUE_UNSPECIFIED) {
            return -1;
        }
        long elapsedTimeMs = event.getTimeSinceCreatedMillis();
        if (elapsedTimeMs == EditingEndedEvent.TIME_SINCE_CREATED_UNKNOWN) {
            return -1;
        }
        return (int)
                Math.min(Integer.MAX_VALUE, Math.round(1000.0 * videoSampleCount / elapsedTimeMs));
    }

    private static long getBucketedDurationMillis(long durationMillis) {
        if (durationMillis == MediaItemInfo.VALUE_UNSPECIFIED || durationMillis <= 0) {
            return -1;
        }
        // Bucket values in an exponential distribution to reduce the precision that's stored:
        // bucket index -> range -> bucketed duration
        // 1 -> [0, 469 ms) -> 235 ms
        // 2 -> [469 ms, 938 ms) -> 469 ms
        // 3 -> [938 ms, 1875 ms) -> 938 ms
        // 4 -> [1875 ms, 3750 ms) -> 1875 ms
        // 5 -> [3750 ms, 7500 ms) -> 3750 ms
        // [...]
        // 13 -> [960000 ms, max) -> 960000 ms
        int bucketIndex =
                (int)
                        Math.floor(
                                DURATION_BUCKETS_BELOW_ONE_MINUTE
                                        + Math.log((durationMillis + 1) / 60_000.0) / Math.log(2));
        // Clamp to range [0, DURATION_BUCKETS_COUNT].
        bucketIndex = Math.min(DURATION_BUCKETS_COUNT, Math.max(0, bucketIndex));
        // Map back onto the representative value for the bucket.
        return (long)
                Math.ceil(Math.pow(2, bucketIndex - DURATION_BUCKETS_BELOW_ONE_MINUTE) * 60_000.0);
    }

    /**
     * Returns the first entry in {@code mimeTypes} with the given prefix, if it matches the
     * filtering allowlist. If no entries match the prefix or if the first matching entry is not on
     * the allowlist, returns an empty string.
     */
    private static String getFilteredFirstMimeType(List<String> mimeTypes, String prefix) {
        int size = mimeTypes.size();
        for (int i = 0; i < size; i++) {
            String mimeType = mimeTypes.get(i);
            if (mimeType.startsWith(prefix)) {
                return getFilteredMimeType(mimeType);
            }
        }
        return "";
    }

    private static String getFilteredMimeType(String mimeType) {
        if (TextUtils.isEmpty(mimeType)) {
            return "";
        }
        // Discard all inputs that aren't allowlisted MIME types.
        return switch (mimeType) {
            case "video/mp4",
                            "video/x-matroska",
                            "video/webm",
                            "video/3gpp",
                            "video/avc",
                            "video/hevc",
                            "video/x-vnd.on2.vp8",
                            "video/x-vnd.on2.vp9",
                            "video/av01",
                            "video/mp2t",
                            "video/mp4v-es",
                            "video/mpeg",
                            "video/x-flv",
                            "video/dolby-vision",
                            "video/raw",
                            "audio/mp4",
                            "audio/mp4a-latm",
                            "audio/x-matroska",
                            "audio/webm",
                            "audio/mpeg",
                            "audio/mpeg-L1",
                            "audio/mpeg-L2",
                            "audio/ac3",
                            "audio/eac3",
                            "audio/eac3-joc",
                            "audio/av4",
                            "audio/true-hd",
                            "audio/vnd.dts",
                            "audio/vnd.dts.hd",
                            "audio/vorbis",
                            "audio/opus",
                            "audio/flac",
                            "audio/ogg",
                            "audio/wav",
                            "audio/midi",
                            "audio/raw",
                            "application/mp4",
                            "application/webm",
                            "application/x-matroska",
                            "application/dash+xml",
                            "application/x-mpegURL",
                            "application/vnd.ms-sstr+xml" ->
                    mimeType;
            default -> "";
        };
    }

    private static int getCodecEnum(String mimeType) {
        if (TextUtils.isEmpty(mimeType)) {
            return Enums.CODEC_UNKNOWN;
        }
        return switch (mimeType) {
            case "video/avc" -> Enums.CODEC_AVC;
            case "video/hevc" -> Enums.CODEC_HEVC;
            case "video/x-vnd.on2.vp8" -> Enums.CODEC_VP8;
            case "video/x-vnd.on2.vp9" -> Enums.CODEC_VP9;
            case "video/av01" -> Enums.CODEC_AV1;
            default -> Enums.CODEC_UNKNOWN;
        };
    }

    private static int getFilteredAudioSampleRateHz(int sampleRateHz) {
        return switch (sampleRateHz) {
            case 8000, 11025, 16000, 22050, 44100, 48000, 96000, 192000 -> sampleRateHz;
            default -> -1;
        };
    }

    private static int getVideoResolutionEnum(Size size) {
        int width = size.getWidth();
        int height = size.getHeight();
        if (width == 352 && height == 640) {
            return Enums.RESOLUTION_352X640;
        } else if (width == 360 && height == 640) {
            return Enums.RESOLUTION_360X640;
        } else if (width == 480 && height == 640) {
            return Enums.RESOLUTION_480X640;
        } else if (width == 480 && height == 854) {
            return Enums.RESOLUTION_480X854;
        } else if (width == 540 && height == 960) {
            return Enums.RESOLUTION_540X960;
        } else if (width == 576 && height == 1024) {
            return Enums.RESOLUTION_576X1024;
        } else if (width == 1280 && height == 720) {
            return Enums.RESOLUTION_720P_HD;
        } else if (width == 1920 && height == 1080) {
            return Enums.RESOLUTION_1080P_FHD;
        } else if (width == 1440 && height == 2560) {
            return Enums.RESOLUTION_1440X2560;
        } else if (width == 3840 && height == 2160) {
            return Enums.RESOLUTION_4K_UHD;
        } else if (width == 7680 && height == 4320) {
            return Enums.RESOLUTION_8K_UHD;
        } else {
            return Enums.RESOLUTION_UNKNOWN;
        }
    }

    private static int getVideoResolutionAspectRatioEnum(Size size) {
        int width = size.getWidth();
        int height = size.getHeight();
        if (width <= 0 || height <= 0) {
            return android.media.editing.Enums.RESOLUTION_ASPECT_RATIO_UNSPECIFIED;
        } else if (width < height) {
            return android.media.editing.Enums.RESOLUTION_ASPECT_RATIO_PORTRAIT;
        } else if (height < width) {
            return android.media.editing.Enums.RESOLUTION_ASPECT_RATIO_LANDSCAPE;
        } else {
            return android.media.editing.Enums.RESOLUTION_ASPECT_RATIO_SQUARE;
        }
    }

    private static int getVideoHdrFormatEnum(int dataSpace, String mimeType) {
        if (dataSpace == DataSpace.DATASPACE_UNKNOWN) {
            return Enums.HDR_FORMAT_UNKNOWN;
        }
        if (mimeType.equals("video/dolby-vision")) {
            return Enums.HDR_FORMAT_DOLBY_VISION;
        }
        int standard = DataSpace.getStandard(dataSpace);
        int transfer = DataSpace.getTransfer(dataSpace);
        if (standard == DataSpace.STANDARD_BT2020 && transfer == DataSpace.TRANSFER_HLG) {
            return Enums.HDR_FORMAT_HLG;
        }
        if (standard == DataSpace.STANDARD_BT2020 && transfer == DataSpace.TRANSFER_ST2084) {
            // We don't currently distinguish HDR10+ from HDR10.
            return Enums.HDR_FORMAT_HDR10;
        }
        return Enums.HDR_FORMAT_NONE;
    }

    private static int getVideoFrameRateEnum(float frameRate) {
        int frameRateInt = Math.round(frameRate);
        return switch (frameRateInt) {
            case 24 -> Enums.FRAMERATE_24;
            case 25 -> Enums.FRAMERATE_25;
            case 30 -> Enums.FRAMERATE_30;
            case 50 -> Enums.FRAMERATE_50;
            case 60 -> Enums.FRAMERATE_60;
            case 120 -> Enums.FRAMERATE_120;
            case 240 -> Enums.FRAMERATE_240;
            case 480 -> Enums.FRAMERATE_480;
            case 960 -> Enums.FRAMERATE_960;
            default -> Enums.FRAMERATE_UNKNOWN;
        };
    }
}
