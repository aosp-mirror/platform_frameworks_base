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

/**
 * This is an abstract base class that encodes MIDI data into a packet buffer.
 * PacketEncoder receives data via its {@link android.media.midi.MidiReceiver#onReceive} method
 * and notifies its client of packets to write via the {@link PacketEncoder.PacketReceiver}
 * interface.
 */
public abstract class PacketEncoder extends MidiReceiver {

    public interface PacketReceiver {
        /** Called to write an accumulated packet.
         * @param buffer the packet buffer to write
         * @param count the number of bytes in the packet buffer to write
         */
        public void writePacket(byte[] buffer, int count);
    }

    /**
     * Called to inform PacketEncoder when the previous write is complete.
     */
    abstract public void writeComplete();
}
