/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Mixing audio and video tracks
 */
public class ScreenRecordingMuxer {
    // size of a memory page for cache coherency
    private static final int BUFFER_SIZE = 1024 * 4096;
    private String[] mFiles;
    private String mOutFile;
    private int mFormat;
    private ArrayMap<Pair<MediaExtractor, Integer>, Integer> mExtractorIndexToMuxerIndex
            = new ArrayMap<>();
    private ArrayList<MediaExtractor> mExtractors = new ArrayList<>();

    private static String TAG = "ScreenRecordingMuxer";
    public ScreenRecordingMuxer(@MediaMuxer.Format int format, String outfileName,
            String... inputFileNames) {
        mFiles = inputFileNames;
        mOutFile = outfileName;
        mFormat = format;
        Log.d(TAG, "out: " + mOutFile + " , in: " + mFiles[0]);
    }

    /**
     * RUN IN THE BACKGROUND THREAD!
     */
    public void mux() throws IOException {
        MediaMuxer muxer = null;
        muxer = new MediaMuxer(mOutFile, mFormat);
        // Add extractors
        for (String file: mFiles) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(file);
            } catch (IOException e) {
                Log.e(TAG, "error creating extractor: " + file);
                e.printStackTrace();
                continue;
            }
            Log.d(TAG, file + " track count: " + extractor.getTrackCount());
            mExtractors.add(extractor);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                int muxId = muxer.addTrack(extractor.getTrackFormat(i));
                Log.d(TAG, "created extractor format" + extractor.getTrackFormat(i).toString());
                mExtractorIndexToMuxerIndex.put(Pair.create(extractor, i), muxId);
            }
        }

        muxer.start();
        for (Pair<MediaExtractor, Integer> pair: mExtractorIndexToMuxerIndex.keySet()) {
            MediaExtractor extractor = pair.first;
            extractor.selectTrack(pair.second);
            int muxId = mExtractorIndexToMuxerIndex.get(pair);
            Log.d(TAG, "track format: " + extractor.getTrackFormat(pair.second));
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int offset;
            while (true) {
                offset = buffer.arrayOffset();
                info.size = extractor.readSampleData(buffer, offset);
                if (info.size < 0) break;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(muxId, buffer, info);
                extractor.advance();
            }
        }

        for (MediaExtractor extractor: mExtractors) {
            extractor.release();
        }
        muxer.stop();
        muxer.release();
    }
}
