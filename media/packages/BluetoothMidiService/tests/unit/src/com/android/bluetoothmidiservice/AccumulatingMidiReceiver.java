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

package com.android.bluetoothmidiservice;

import android.media.midi.MidiReceiver;
import android.util.Log;

import com.android.internal.midi.MidiFramer;

import java.util.ArrayList;

class AccumulatingMidiReceiver extends MidiReceiver {
    private static final String TAG = "AccumulatingMidiReceiver";
    ArrayList<byte[]> mBuffers = new ArrayList<byte[]>();
    ArrayList<Long> mTimestamps = new ArrayList<Long>();

    public void onSend(byte[] buffer, int offset, int count, long timestamp) {
        Log.d(TAG, "onSend() passed " + MidiFramer.formatMidiData(buffer, offset, count));
        byte[] actualRow = new byte[count];
        System.arraycopy(buffer, offset, actualRow, 0, count);
        mBuffers.add(actualRow);
        mTimestamps.add(timestamp);
    }

    byte[][] getBuffers() {
        return mBuffers.toArray(new byte[mBuffers.size()][]);
    }

    Long[] getTimestamps() {
        return mTimestamps.toArray(new Long[mTimestamps.size()]);
    }
}

