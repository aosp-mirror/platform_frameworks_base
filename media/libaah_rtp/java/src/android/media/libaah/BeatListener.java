/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.media.libaah;

import android.util.Log;

public interface BeatListener {

    public static class BeatInfo {

        public volatile long timestamp;
        public volatile float beatValue;
        public volatile float smoothedBeatValue;
        public volatile int sequenceNumber;

        public BeatInfo() {
            this(0, 0, 0, 0);
        }

        public BeatInfo(long t, float b, float sb, int s) {
            timestamp = t;
            beatValue = b;
            smoothedBeatValue = sb;
            sequenceNumber = s;
        }

        public BeatInfo(BeatInfo other) {
            copyFrom(other);
        }

        public void copyFrom(BeatInfo other) {
            timestamp = other.timestamp;
            beatValue = other.beatValue;
            smoothedBeatValue = other.smoothedBeatValue;
            sequenceNumber = other.sequenceNumber;
        }

    };

    void onBeat(short count, BeatInfo[] info);

    void onFlush();
}
