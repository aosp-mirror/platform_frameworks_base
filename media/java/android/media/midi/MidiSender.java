/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.midi;

/**
 * Interface provided by a device to allow attaching
 * MidiReceivers to a MIDI device.
 */
abstract public class MidiSender {

    /**
     * Connects a {@link MidiReceiver} to the sender
     *
     * @param receiver the receiver to connect
     */
    public void connect(MidiReceiver receiver) {
        if (receiver == null) {
            throw new NullPointerException("receiver null in MidiSender.connect");
        }
        onConnect(receiver);
    }

    /**
     * Disconnects a {@link MidiReceiver} from the sender
     *
     * @param receiver the receiver to disconnect
     */
    public void disconnect(MidiReceiver receiver) {
        if (receiver == null) {
            throw new NullPointerException("receiver null in MidiSender.disconnect");
        }
        onDisconnect(receiver);
    }

    /**
     * Called to connect a {@link MidiReceiver} to the sender
     *
     * @param receiver the receiver to connect
     */
    abstract public void onConnect(MidiReceiver receiver);

    /**
     * Called to disconnect a {@link MidiReceiver} from the sender
     *
     * @param receiver the receiver to disconnect
     */
    abstract public void onDisconnect(MidiReceiver receiver);
}
