/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.voiceinteraction;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.service.voice.HotwordAudioStream.KEY_AUDIO_STREAM_COPY_BUFFER_LENGTH_BYTES;

import static com.android.internal.util.FrameworkStatsLog.HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__CLOSE_ERROR_FROM_SYSTEM;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__EMPTY_AUDIO_STREAM_LIST;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__ENDED;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__ILLEGAL_COPY_BUFFER_SIZE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__INTERRUPTED_EXCEPTION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__NO_PERMISSION;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__STARTED;
import static com.android.server.voiceinteraction.HotwordDetectionConnection.DEBUG;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.service.voice.HotwordAudioStream;
import android.service.voice.HotwordDetectedResult;
import android.util.Slog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Copies the audio streams in {@link HotwordDetectedResult}s. This allows the system to manage the
 * lifetime of the {@link ParcelFileDescriptor}s and ensures that the flow of data is in the right
 * direction from the {@link android.service.voice.HotwordDetectionService} to the client (i.e., the
 * voice interactor).
 *
 * @hide
 */
final class HotwordAudioStreamCopier {

    private static final String TAG = "HotwordAudioStreamCopier";
    private static final String OP_MESSAGE = "Streaming hotword audio to VoiceInteractionService";
    private static final String TASK_ID_PREFIX = "HotwordDetectedResult@";
    private static final String THREAD_NAME_PREFIX = "Copy-";

    // Corresponds to the OS pipe capacity in bytes
    private static final int MAX_COPY_BUFFER_LENGTH_BYTES = 65_536;
    private static final int DEFAULT_COPY_BUFFER_LENGTH_BYTES = 32_768;

    private final AppOpsManager mAppOpsManager;
    private final int mDetectorType;
    private final int mVoiceInteractorUid;
    private final String mVoiceInteractorPackageName;
    private final String mVoiceInteractorAttributionTag;
    private final ExecutorService mExecutorService = Executors.newCachedThreadPool();

    HotwordAudioStreamCopier(@NonNull AppOpsManager appOpsManager, int detectorType,
            int voiceInteractorUid, @NonNull String voiceInteractorPackageName,
            @NonNull String voiceInteractorAttributionTag) {
        mAppOpsManager = appOpsManager;
        mDetectorType = detectorType;
        mVoiceInteractorUid = voiceInteractorUid;
        mVoiceInteractorPackageName = voiceInteractorPackageName;
        mVoiceInteractorAttributionTag = voiceInteractorAttributionTag;
    }

    /**
     * Starts copying the audio streams in the given {@link HotwordDetectedResult}.
     * <p>
     * The returned {@link HotwordDetectedResult} is identical the one that was passed in, except
     * that the {@link ParcelFileDescriptor}s within {@link HotwordDetectedResult#getAudioStreams()}
     * are replaced with descriptors from pipes managed by {@link HotwordAudioStreamCopier}. The
     * returned value should be passed on to the client (i.e., the voice interactor).
     * </p>
     *
     * @throws IOException If there was an error creating the managed pipe.
     */
    @NonNull
    public HotwordDetectedResult startCopyingAudioStreams(@NonNull HotwordDetectedResult result)
            throws IOException {
        List<HotwordAudioStream> audioStreams = result.getAudioStreams();
        if (audioStreams.isEmpty()) {
            HotwordMetricsLogger.writeAudioEgressEvent(mDetectorType,
                    HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__EMPTY_AUDIO_STREAM_LIST,
                    mVoiceInteractorUid, /* streamSizeBytes= */ 0, /* bundleSizeBytes= */ 0,
                    /* streamCount= */ 0);
            return result;
        }

        final int audioStreamCount = audioStreams.size();
        List<HotwordAudioStream> newAudioStreams = new ArrayList<>(audioStreams.size());
        List<CopyTaskInfo> copyTaskInfos = new ArrayList<>(audioStreams.size());
        int totalMetadataBundleSizeBytes = 0;
        int totalInitialAudioSizeBytes = 0;
        for (HotwordAudioStream audioStream : audioStreams) {
            ParcelFileDescriptor[] clientPipe = ParcelFileDescriptor.createReliablePipe();
            ParcelFileDescriptor clientAudioSource = clientPipe[0];
            ParcelFileDescriptor clientAudioSink = clientPipe[1];
            HotwordAudioStream newAudioStream =
                    audioStream.buildUpon().setAudioStreamParcelFileDescriptor(
                            clientAudioSource).build();
            newAudioStreams.add(newAudioStream);

            int copyBufferLength = DEFAULT_COPY_BUFFER_LENGTH_BYTES;
            PersistableBundle metadata = audioStream.getMetadata();
            totalMetadataBundleSizeBytes += HotwordDetectedResult.getParcelableSize(metadata);
            if (metadata.containsKey(KEY_AUDIO_STREAM_COPY_BUFFER_LENGTH_BYTES)) {
                copyBufferLength = metadata.getInt(KEY_AUDIO_STREAM_COPY_BUFFER_LENGTH_BYTES, -1);
                if (copyBufferLength < 1 || copyBufferLength > MAX_COPY_BUFFER_LENGTH_BYTES) {
                    HotwordMetricsLogger.writeAudioEgressEvent(mDetectorType,
                            HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__ILLEGAL_COPY_BUFFER_SIZE,
                            mVoiceInteractorUid, /* streamSizeBytes= */ 0, /* bundleSizeBytes= */ 0,
                            audioStreamCount);
                    Slog.w(TAG, "Attempted to set an invalid copy buffer length ("
                            + copyBufferLength + ") for: " + audioStream);
                    copyBufferLength = DEFAULT_COPY_BUFFER_LENGTH_BYTES;
                } else if (DEBUG) {
                    Slog.i(TAG, "Copy buffer length set to " + copyBufferLength + " for: "
                            + audioStream);
                }
            }

            // We are including the non-streamed initial audio
            // (HotwordAudioStream.getInitialAudio()) bytes in the "stream" size metrics.
            totalInitialAudioSizeBytes += audioStream.getInitialAudio().length;

            ParcelFileDescriptor serviceAudioSource =
                    audioStream.getAudioStreamParcelFileDescriptor();
            copyTaskInfos.add(new CopyTaskInfo(serviceAudioSource, clientAudioSink,
                    copyBufferLength));
        }

        String resultTaskId = TASK_ID_PREFIX + System.identityHashCode(result);
        mExecutorService.execute(
                new HotwordDetectedResultCopyTask(resultTaskId, copyTaskInfos,
                        totalMetadataBundleSizeBytes, totalInitialAudioSizeBytes));

        return result.buildUpon().setAudioStreams(newAudioStreams).build();
    }

    private static class CopyTaskInfo {
        private final ParcelFileDescriptor mSource;
        private final ParcelFileDescriptor mSink;
        private final int mCopyBufferLength;

        CopyTaskInfo(ParcelFileDescriptor source, ParcelFileDescriptor sink, int copyBufferLength) {
            mSource = source;
            mSink = sink;
            mCopyBufferLength = copyBufferLength;
        }
    }

    private class HotwordDetectedResultCopyTask implements Runnable {
        private final String mResultTaskId;
        private final List<CopyTaskInfo> mCopyTaskInfos;
        private final int mTotalMetadataSizeBytes;
        private final int mTotalInitialAudioSizeBytes;
        private final ExecutorService mExecutorService = Executors.newCachedThreadPool();

        HotwordDetectedResultCopyTask(String resultTaskId, List<CopyTaskInfo> copyTaskInfos,
                int totalMetadataSizeBytes, int totalInitialAudioSizeBytes) {
            mResultTaskId = resultTaskId;
            mCopyTaskInfos = copyTaskInfos;
            mTotalMetadataSizeBytes = totalMetadataSizeBytes;
            mTotalInitialAudioSizeBytes = totalInitialAudioSizeBytes;
        }

        @Override
        public void run() {
            Thread.currentThread().setName(THREAD_NAME_PREFIX + mResultTaskId);
            int size = mCopyTaskInfos.size();
            List<SingleAudioStreamCopyTask> tasks = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                CopyTaskInfo copyTaskInfo = mCopyTaskInfos.get(i);
                String streamTaskId = mResultTaskId + "@" + i;
                tasks.add(new SingleAudioStreamCopyTask(streamTaskId, copyTaskInfo.mSource,
                        copyTaskInfo.mSink, copyTaskInfo.mCopyBufferLength, mDetectorType,
                        mVoiceInteractorUid));
            }

            if (mAppOpsManager.startOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO_HOTWORD,
                    mVoiceInteractorUid, mVoiceInteractorPackageName,
                    mVoiceInteractorAttributionTag, OP_MESSAGE) == MODE_ALLOWED) {
                try {
                    HotwordMetricsLogger.writeAudioEgressEvent(mDetectorType,
                            HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__STARTED,
                            mVoiceInteractorUid, mTotalInitialAudioSizeBytes,
                            mTotalMetadataSizeBytes, size);
                    // TODO(b/244599891): Set timeout, close after inactivity
                    mExecutorService.invokeAll(tasks);

                    // We are including the non-streamed initial audio
                    // (HotwordAudioStream.getInitialAudio()) bytes in the "stream" size metrics.
                    int totalStreamSizeBytes = mTotalInitialAudioSizeBytes;
                    for (SingleAudioStreamCopyTask task : tasks) {
                        totalStreamSizeBytes += task.mTotalCopiedBytes;
                    }

                    Slog.i(TAG, mResultTaskId + ": Task was completed. Total bytes egressed: "
                            + totalStreamSizeBytes + " (including " + mTotalInitialAudioSizeBytes
                            + " bytes NOT streamed), total metadata bundle size bytes: "
                            + mTotalMetadataSizeBytes);
                    HotwordMetricsLogger.writeAudioEgressEvent(mDetectorType,
                            HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__ENDED,
                            mVoiceInteractorUid, totalStreamSizeBytes, mTotalMetadataSizeBytes,
                            size);
                } catch (InterruptedException e) {
                    // We are including the non-streamed initial audio
                    // (HotwordAudioStream.getInitialAudio()) bytes in the "stream" size metrics.
                    int totalStreamSizeBytes = mTotalInitialAudioSizeBytes;
                    for (SingleAudioStreamCopyTask task : tasks) {
                        totalStreamSizeBytes += task.mTotalCopiedBytes;
                    }

                    HotwordMetricsLogger.writeAudioEgressEvent(mDetectorType,
                            HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__INTERRUPTED_EXCEPTION,
                            mVoiceInteractorUid, totalStreamSizeBytes, mTotalMetadataSizeBytes,
                            size);
                    Slog.i(TAG, mResultTaskId + ": Task was interrupted. Total bytes egressed: "
                            + totalStreamSizeBytes + " (including " + mTotalInitialAudioSizeBytes
                            + " bytes NOT streamed), total metadata bundle size bytes: "
                            + mTotalMetadataSizeBytes);
                    bestEffortPropagateError(e.getMessage());
                } finally {
                    mAppOpsManager.finishOp(AppOpsManager.OPSTR_RECORD_AUDIO_HOTWORD,
                            mVoiceInteractorUid, mVoiceInteractorPackageName,
                            mVoiceInteractorAttributionTag);
                }
            } else {
                HotwordMetricsLogger.writeAudioEgressEvent(mDetectorType,
                        HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__NO_PERMISSION,
                        mVoiceInteractorUid, /* streamSizeBytes= */ 0, /* bundleSizeBytes= */ 0,
                        size);
                bestEffortPropagateError(
                        "Failed to obtain RECORD_AUDIO_HOTWORD permission for voice interactor with"
                                + " uid=" + mVoiceInteractorUid
                                + " packageName=" + mVoiceInteractorPackageName
                                + " attributionTag=" + mVoiceInteractorAttributionTag);
            }
        }

        private void bestEffortPropagateError(@NonNull String errorMessage) {
            try {
                for (CopyTaskInfo copyTaskInfo : mCopyTaskInfos) {
                    copyTaskInfo.mSource.closeWithError(errorMessage);
                    copyTaskInfo.mSink.closeWithError(errorMessage);
                }
                HotwordMetricsLogger.writeAudioEgressEvent(mDetectorType,
                        HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__CLOSE_ERROR_FROM_SYSTEM,
                        mVoiceInteractorUid, /* streamSizeBytes= */ 0, /* bundleSizeBytes= */ 0,
                        mCopyTaskInfos.size());
            } catch (IOException e) {
                Slog.e(TAG, mResultTaskId + ": Failed to propagate error", e);
            }
        }
    }

    private static class SingleAudioStreamCopyTask implements Callable<Void> {
        private final String mStreamTaskId;
        private final ParcelFileDescriptor mAudioSource;
        private final ParcelFileDescriptor mAudioSink;
        private final int mCopyBufferLength;
        private final int mDetectorType;
        private final int mUid;

        private volatile int mTotalCopiedBytes = 0;

        SingleAudioStreamCopyTask(String streamTaskId, ParcelFileDescriptor audioSource,
                ParcelFileDescriptor audioSink, int copyBufferLength, int detectorType, int uid) {
            mStreamTaskId = streamTaskId;
            mAudioSource = audioSource;
            mAudioSink = audioSink;
            mCopyBufferLength = copyBufferLength;
            mDetectorType = detectorType;
            mUid = uid;
        }

        @Override
        public Void call() throws Exception {
            Thread.currentThread().setName(THREAD_NAME_PREFIX + mStreamTaskId);

            // Note: We are intentionally NOT using try-with-resources here. If we did,
            // the ParcelFileDescriptors will be automatically closed WITHOUT errors before we go
            // into the IOException-catch block. We want to propagate the error while closing the
            // PFDs.
            InputStream fis = null;
            OutputStream fos = null;
            try {
                fis = new ParcelFileDescriptor.AutoCloseInputStream(mAudioSource);
                fos = new ParcelFileDescriptor.AutoCloseOutputStream(mAudioSink);
                byte[] buffer = new byte[mCopyBufferLength];
                while (true) {
                    if (Thread.interrupted()) {
                        Slog.e(TAG,
                                mStreamTaskId + ": SingleAudioStreamCopyTask task was interrupted");
                        break;
                    }

                    int bytesRead = fis.read(buffer);
                    if (bytesRead < 0) {
                        Slog.i(TAG, mStreamTaskId + ": Reached end of audio stream");
                        break;
                    }
                    if (bytesRead > 0) {
                        if (DEBUG) {
                            // TODO(b/244599440): Add proper logging
                            Slog.d(TAG, mStreamTaskId + ": Copied " + bytesRead
                                    + " bytes from audio stream. First 20 bytes=" + Arrays.toString(
                                    Arrays.copyOfRange(buffer, 0, 20)));
                        }
                        fos.write(buffer, 0, bytesRead);
                        mTotalCopiedBytes += bytesRead;
                    }
                    // TODO(b/244599891): Close PFDs after inactivity
                }
            } catch (IOException e) {
                mAudioSource.closeWithError(e.getMessage());
                mAudioSink.closeWithError(e.getMessage());
                Slog.e(TAG, mStreamTaskId + ": Failed to copy audio stream", e);
                HotwordMetricsLogger.writeAudioEgressEvent(mDetectorType,
                        HOTWORD_AUDIO_EGRESS_EVENT_REPORTED__EVENT__CLOSE_ERROR_FROM_SYSTEM,
                        mUid, /* streamSizeBytes= */ 0, /* bundleSizeBytes= */ 0,
                        /* streamCount= */ 0);
            } finally {
                if (fis != null) {
                    fis.close();
                }
                if (fos != null) {
                    fos.close();
                }
            }

            return null;
        }
    }

}
