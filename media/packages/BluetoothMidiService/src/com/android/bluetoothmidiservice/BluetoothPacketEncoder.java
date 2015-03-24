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

package com.android.bluetoothmidiservice;

import android.media.midi.MidiReceiver;

import com.android.internal.midi.MidiConstants;
import com.android.internal.midi.MidiFramer;

import java.io.IOException;

/**
 * This class accumulates MIDI messages to form a MIDI packet.
 */
public class BluetoothPacketEncoder extends PacketEncoder {

    private static final String TAG = "BluetoothPacketEncoder";

    private static final long MILLISECOND_NANOS = 1000000L;

    // mask for generating 13 bit timestamps
    private static final int MILLISECOND_MASK = 0x1FFF;

    private final PacketReceiver mPacketReceiver;

    // buffer for accumulating messages to write
    private final byte[] mAccumulationBuffer;
    // number of bytes currently in mAccumulationBuffer
    private int mAccumulatedBytes;
    // timestamp for first message in current packet
    private int mPacketTimestamp;
    // current running status, or zero if none
    private int mRunningStatus;

    private boolean mWritePending;

        private final Object mLock = new Object();

    // This receives normalized data from mMidiFramer and accumulates it into a packet buffer
    private final MidiReceiver mFramedDataReceiver = new MidiReceiver() {
        @Override
        public void onReceive(byte[] msg, int offset, int count, long timestamp)
                throws IOException {

            int milliTimestamp = (int)(timestamp / MILLISECOND_NANOS) & MILLISECOND_MASK;
            int status = msg[0] & 0xFF;

            synchronized (mLock) {
                boolean needsTimestamp = (milliTimestamp != mPacketTimestamp);
                int bytesNeeded = count;
                if (needsTimestamp) bytesNeeded++;  // add one for timestamp byte
                if (status == mRunningStatus) bytesNeeded--;    // subtract one for status byte

                if (mAccumulatedBytes + bytesNeeded > mAccumulationBuffer.length) {
                    // write out our data if there is no more room
                    // if necessary, block until previous packet is sent
                    flushLocked(true);
                }

                // write header if we are starting a new packet
                if (mAccumulatedBytes == 0) {
                    // header byte with timestamp bits 7 - 12
                    mAccumulationBuffer[mAccumulatedBytes++] = (byte)(0x80 | (milliTimestamp >> 7));
                    mPacketTimestamp = milliTimestamp;
                    needsTimestamp = true;
                }

                // write new timestamp byte and status byte if necessary
                if (needsTimestamp) {
                    // timestamp byte with bits 0 - 6 of timestamp
                    mAccumulationBuffer[mAccumulatedBytes++] =
                            (byte)(0x80 | (milliTimestamp & 0x7F));
                    mPacketTimestamp = milliTimestamp;
                }

                if (status != mRunningStatus) {
                    mAccumulationBuffer[mAccumulatedBytes++] = (byte)status;
                    if (MidiConstants.allowRunningStatus(status)) {
                        mRunningStatus = status;
                    } else if (MidiConstants.allowRunningStatus(status)) {
                        mRunningStatus = 0;
                    }
                }

                // now copy data bytes
                int dataLength = count - 1;
                System.arraycopy(msg, 1, mAccumulationBuffer, mAccumulatedBytes, dataLength);
                // FIXME - handle long SysEx properly
                mAccumulatedBytes += dataLength;

                // write the packet if possible, but do not block
                flushLocked(false);
            }
        }
    };

    // MidiFramer for normalizing incoming data
    private final MidiFramer mMidiFramer = new MidiFramer(mFramedDataReceiver);

    public BluetoothPacketEncoder(PacketReceiver packetReceiver, int maxPacketSize) {
        mPacketReceiver = packetReceiver;
        mAccumulationBuffer = new byte[maxPacketSize];
    }

    @Override
    public void onReceive(byte[] msg, int offset, int count, long timestamp)
            throws IOException {
        // normalize the data by passing it through a MidiFramer first
        mMidiFramer.sendWithTimestamp(msg, offset, count, timestamp);
    }

    @Override
    public void writeComplete() {
        synchronized (mLock) {
            mWritePending = false;
            flushLocked(false);
            mLock.notify();
        }
    }

    private void flushLocked(boolean canBlock) {
        if (mWritePending && !canBlock) {
            return;
        }

        while (mWritePending && mAccumulatedBytes > 0) {
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                // try again
                continue;
            }
        }

        if (mAccumulatedBytes > 0) {
            mPacketReceiver.writePacket(mAccumulationBuffer, mAccumulatedBytes);
            mAccumulatedBytes = 0;
            mPacketTimestamp = 0;
            mRunningStatus = 0;
            mWritePending = true;
        }
    }
}
