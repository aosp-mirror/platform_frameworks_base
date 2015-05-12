/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.midi;

import android.media.midi.MidiReceiver;
import android.util.Log;

import java.io.IOException;

/**
 * Convert stream of bytes to discrete messages.
 *
 * Parses the incoming bytes and then posts individual messages to the receiver
 * specified in the constructor. Short messages of 1-3 bytes will be complete.
 * System Exclusive messages may be posted in pieces.
 *
 * Resolves Running Status and
 * interleaved System Real-Time messages.
 */
public class MidiFramer extends MidiReceiver {

    public String TAG = "MidiFramer";
    private MidiReceiver mReceiver;
    private byte[] mBuffer = new byte[3];
    private int mCount;
    private int mRunningStatus;
    private int mNeeded;
    private boolean mInSysEx;

    public MidiFramer(MidiReceiver receiver) {
        mReceiver = receiver;
    }

    public static String formatMidiData(byte[] data, int offset, int count) {
        String text = "MIDI+" + offset + " : ";
        for (int i = 0; i < count; i++) {
            text += String.format("0x%02X, ", data[offset + i]);
        }
        return text;
    }

    /*
     * @see android.midi.MidiReceiver#onSend(byte[], int, int, long)
     */
    @Override
    public void onSend(byte[] data, int offset, int count, long timestamp)
            throws IOException {
        // Log.i(TAG, formatMidiData(data, offset, count));
        int sysExStartOffset = (mInSysEx ? offset : -1);

        for (int i = 0; i < count; i++) {
            int b = data[offset] & 0xFF;
            if (b >= 0x80) { // status byte?
                if (b < 0xF0) { // channel message?
                    mRunningStatus = (byte) b;
                    mCount = 1;
                    mNeeded = MidiConstants.getBytesPerMessage(b) - 1;
                } else if (b < 0xF8) { // system common?
                    if (b == 0xF0 /* SysEx Start */) {
                        // Log.i(TAG, "SysEx Start");
                        mInSysEx = true;
                        sysExStartOffset = offset;
                    } else if (b == 0xF7 /* SysEx End */) {
                        // Log.i(TAG, "SysEx End");
                        if (mInSysEx) {
                            mReceiver.send(data, sysExStartOffset,
                                offset - sysExStartOffset + 1, timestamp);
                            mInSysEx = false;
                            sysExStartOffset = -1;
                        }
                    } else {
                        mBuffer[0] = (byte) b;
                        mRunningStatus = 0;
                        mCount = 1;
                        mNeeded = MidiConstants.getBytesPerMessage(b) - 1;
                    }
                } else { // real-time?
                    // Single byte message interleaved with other data.
                    if (mInSysEx) {
                        mReceiver.send(data, sysExStartOffset,
                                offset - sysExStartOffset, timestamp);
                        sysExStartOffset = offset + 1;
                    }
                    mReceiver.send(data, offset, 1, timestamp);
                }
            } else { // data byte
                // Save SysEx data for SysEx End marker or end of buffer.
                if (!mInSysEx) {
                    mBuffer[mCount++] = (byte) b;
                    if (--mNeeded == 0) {
                        if (mRunningStatus != 0) {
                            mBuffer[0] = (byte) mRunningStatus;
                        }
                        mReceiver.send(mBuffer, 0, mCount, timestamp);
                        mNeeded = MidiConstants.getBytesPerMessage(mBuffer[0]) - 1;
                        mCount = 1;
                    }
                }
            }
            ++offset;
        }

        // send any accumulatedSysEx data
        if (sysExStartOffset >= 0 && sysExStartOffset < offset) {
            mReceiver.send(data, sysExStartOffset,
                    offset - sysExStartOffset, timestamp);
        }
    }

}
