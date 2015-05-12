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

package android.media.midi;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * This is an immutable class that describes the current status of a MIDI device's ports.
 */
public final class MidiDeviceStatus implements Parcelable {

    private static final String TAG = "MidiDeviceStatus";

    private final MidiDeviceInfo mDeviceInfo;
    // true if input ports are open
    private final boolean mInputPortOpen[];
    // open counts for output ports
    private final int mOutputPortOpenCount[];

    /**
     * @hide
     */
    public MidiDeviceStatus(MidiDeviceInfo deviceInfo, boolean inputPortOpen[],
            int outputPortOpenCount[]) {
        // MidiDeviceInfo is immutable so we can share references
        mDeviceInfo = deviceInfo;

        // make copies of the arrays
        mInputPortOpen = new boolean[inputPortOpen.length];
        System.arraycopy(inputPortOpen, 0, mInputPortOpen, 0, inputPortOpen.length);
        mOutputPortOpenCount = new int[outputPortOpenCount.length];
        System.arraycopy(outputPortOpenCount, 0, mOutputPortOpenCount, 0,
                outputPortOpenCount.length);
    }

    /**
     * Creates a MidiDeviceStatus with zero for all port open counts
     * @hide
     */
    public MidiDeviceStatus(MidiDeviceInfo deviceInfo) {
        mDeviceInfo = deviceInfo;
        mInputPortOpen = new boolean[deviceInfo.getInputPortCount()];
        mOutputPortOpenCount = new int[deviceInfo.getOutputPortCount()];
    }

    /**
     * Returns the {@link MidiDeviceInfo} of the device.
     *
     * @return the device info
     */
    public MidiDeviceInfo getDeviceInfo() {
        return mDeviceInfo;
    }

    /**
     * Returns true if an input port is open.
     * An input port can only be opened by one client at a time.
     *
     * @param portNumber the input port's port number
     * @return input port open status
     */
    public boolean isInputPortOpen(int portNumber) {
        return mInputPortOpen[portNumber];
    }

    /**
     * Returns the number of clients currently connected to the specified output port.
     * Unlike input ports, an output port can be opened by multiple clients at the same time.
     *
     * @param portNumber the output port's port number
     * @return output port open count
     */
    public int getOutputPortOpenCount(int portNumber) {
        return mOutputPortOpenCount[portNumber];
    }

    @Override
    public String toString() {
        int inputPortCount = mDeviceInfo.getInputPortCount();
        int outputPortCount = mDeviceInfo.getOutputPortCount();
        StringBuilder builder = new StringBuilder("mInputPortOpen=[");
        for (int i = 0; i < inputPortCount; i++) {
            builder.append(mInputPortOpen[i]);
            if (i < inputPortCount -1) {
                builder.append(",");
            }
        }
        builder.append("] mOutputPortOpenCount=[");
        for (int i = 0; i < outputPortCount; i++) {
            builder.append(mOutputPortOpenCount[i]);
            if (i < outputPortCount -1) {
                builder.append(",");
            }
        }
        builder.append("]");
        return builder.toString();
    }

    public static final Parcelable.Creator<MidiDeviceStatus> CREATOR =
        new Parcelable.Creator<MidiDeviceStatus>() {
        public MidiDeviceStatus createFromParcel(Parcel in) {
            ClassLoader classLoader = MidiDeviceInfo.class.getClassLoader();
            MidiDeviceInfo deviceInfo = in.readParcelable(classLoader);
            boolean[] inputPortOpen = in.createBooleanArray();
            int[] outputPortOpenCount = in.createIntArray();
            return new MidiDeviceStatus(deviceInfo, inputPortOpen, outputPortOpenCount);
        }

        public MidiDeviceStatus[] newArray(int size) {
            return new MidiDeviceStatus[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mDeviceInfo, flags);
        parcel.writeBooleanArray(mInputPortOpen);
        parcel.writeIntArray(mOutputPortOpenCount);
   }
}
