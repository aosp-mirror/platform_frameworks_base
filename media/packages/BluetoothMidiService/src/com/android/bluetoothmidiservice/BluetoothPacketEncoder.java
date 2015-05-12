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
    private byte mRunningStatus;

    private boolean mWritePending;

        private final Object mLock = new Object();

    // This receives normalized data from mMidiFramer and accumulates it into a packet buffer
    private final MidiReceiver mFramedDataReceiver = new MidiReceiver() {
        @Override
        public void onSend(byte[] msg, int offset, int count, long timestamp)
                throws IOException {

            synchronized (mLock) {
                int milliTimestamp = (int)(timestamp / MILLISECOND_NANOS) & MILLISECOND_MASK;
                byte status = msg[offset];
                boolean isSysExStart = (status == MidiConstants.STATUS_SYSTEM_EXCLUSIVE);
                boolean isSysExContinuation = ((status & 0x80) == 0);

                int bytesNeeded;
                if (isSysExStart || isSysExContinuation) {
                    // SysEx messages can be split into multiple packets
                    bytesNeeded = 1;
                } else {
                    bytesNeeded = count;
                }

                boolean needsTimestamp = (milliTimestamp != mPacketTimestamp);
                if (isSysExStart) {
                    // SysEx start byte must be preceded by a timestamp
                    needsTimestamp = true;
                } else if (isSysExContinuation) {
                    // SysEx continuation packets must not have timestamp byte
                    needsTimestamp = false;
                }
                if (needsTimestamp) bytesNeeded++;  // add one for timestamp byte
                if (status == mRunningStatus) bytesNeeded--;    // subtract one for status byte

                if (mAccumulatedBytes + bytesNeeded > mAccumulationBuffer.length) {
                    // write out our data if there is no more room
                    // if necessary, block until previous packet is sent
                    flushLocked(true);
                }

                // write the header if necessary
                if (appendHeader(milliTimestamp)) {
                     needsTimestamp = !isSysExContinuation;
                }

                // write new timestamp byte if necessary
                if (needsTimestamp) {
                    // timestamp byte with bits 0 - 6 of timestamp
                    mAccumulationBuffer[mAccumulatedBytes++] =
                            (byte)(0x80 | (milliTimestamp & 0x7F));
                    mPacketTimestamp = milliTimestamp;
                }

                if (isSysExStart || isSysExContinuation) {
                    // MidiFramer will end the packet with SysEx End if there is one in the buffer
                    boolean hasSysExEnd =
                            (msg[offset + count - 1] == MidiConstants.STATUS_END_SYSEX);
                    int remaining = (hasSysExEnd ? count - 1 : count);

                    while (remaining > 0) {
                        if (mAccumulatedBytes == mAccumulationBuffer.length) {
                            // write out our data if there is no more room
                            // if necessary, block until previous packet is sent
                            flushLocked(true);
                            appendHeader(milliTimestamp);
                        }

                        int copy = mAccumulationBuffer.length - mAccumulatedBytes;
                        if (copy > remaining) copy = remaining;
                        System.arraycopy(msg, offset, mAccumulationBuffer, mAccumulatedBytes, copy);
                        mAccumulatedBytes += copy;
                        offset += copy;
                        remaining -= copy;
                    }

                    if (hasSysExEnd) {
                        // SysEx End command must be preceeded by a timestamp byte
                        if (mAccumulatedBytes + 2 > mAccumulationBuffer.length) {
                            // write out our data if there is no more room
                            // if necessary, block until previous packet is sent
                            flushLocked(true);
                            appendHeader(milliTimestamp);
                        }
                        mAccumulationBuffer[mAccumulatedBytes++] =
                                (byte)(0x80 | (milliTimestamp & 0x7F));
                        mAccumulationBuffer[mAccumulatedBytes++] = MidiConstants.STATUS_END_SYSEX;
                    }
                } else {
                    // Non-SysEx message
                    if (status != mRunningStatus) {
                        mAccumulationBuffer[mAccumulatedBytes++] = status;
                        if (MidiConstants.allowRunningStatus(status)) {
                            mRunningStatus = status;
                        } else if (MidiConstants.cancelsRunningStatus(status)) {
                            mRunningStatus = 0;
                        }
                    }

                    // now copy data bytes
                    int dataLength = count - 1;
                    System.arraycopy(msg, offset + 1, mAccumulationBuffer, mAccumulatedBytes,
                            dataLength);
                    mAccumulatedBytes += dataLength;
                }

                // write the packet if possible, but do not block
                flushLocked(false);
            }
        }
    };

    private boolean appendHeader(int milliTimestamp) {
        // write header if we are starting a new packet
        if (mAccumulatedBytes == 0) {
            // header byte with timestamp bits 7 - 12
            mAccumulationBuffer[mAccumulatedBytes++] =
                    (byte)(0x80 | ((milliTimestamp >> 7) & 0x3F));
            mPacketTimestamp = milliTimestamp;
            return true;
        } else {
            return false;
        }
    }

    // MidiFramer for normalizing incoming data
    private final MidiFramer mMidiFramer = new MidiFramer(mFramedDataReceiver);

    public BluetoothPacketEncoder(PacketReceiver packetReceiver, int maxPacketSize) {
        mPacketReceiver = packetReceiver;
        mAccumulationBuffer = new byte[maxPacketSize];
    }

    @Override
    public void onSend(byte[] msg, int offset, int count, long timestamp)
            throws IOException {
        // normalize the data by passing it through a MidiFramer first
        mMidiFramer.send(msg, offset, count, timestamp);
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
