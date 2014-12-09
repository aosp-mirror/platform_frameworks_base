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

package android.midi;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * This class represents a MIDI input or output port
 *
 * @hide
 */
public class MidiPort {

    protected final int mPortNumber;

  /* package */ MidiPort(int portNumber) {
        mPortNumber = portNumber;
    }

    /**
     * Returns the port number of this port
     *
     * @return the port's port number
     */
    public int getPortNumber() {
        return mPortNumber;
    }
}
