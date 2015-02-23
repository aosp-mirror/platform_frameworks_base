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
 *
 * CANDIDATE FOR PUBLIC API
 * @hide
 */
public interface MidiSender {
    /**
     * Called to connect a {@link MidiReceiver} to the sender
     *
     * @param receiver the receiver to connect
     */
    public void connect(MidiReceiver receiver);

    /**
     * Called to disconnect a {@link MidiReceiver} from the sender
     *
     * @param receiver the receiver to disconnect
     */
    public void disconnect(MidiReceiver receiver);
}
