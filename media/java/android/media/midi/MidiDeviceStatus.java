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
 *
 * CANDIDATE FOR PUBLIC API
 * @hide
 */
public final class MidiDeviceStatus implements Parcelable {

    private static final String TAG = "MidiDeviceStatus";

    private final MidiDeviceInfo mDeviceInfo;
    // true if input ports are busy
    private final boolean mInputPortBusy[];
    // open counts for output ports
    private final int mOutputPortOpenCount[];

    /**
     * @hide
     */
    public MidiDeviceStatus(MidiDeviceInfo deviceInfo, boolean inputPortBusy[],
            int outputPortOpenCount[]) {
        // MidiDeviceInfo is immutable so we can share references
        mDeviceInfo = deviceInfo;

        // make copies of the arrays
        mInputPortBusy = new boolean[inputPortBusy.length];
        System.arraycopy(inputPortBusy, 0, mInputPortBusy, 0, inputPortBusy.length);
        mOutputPortOpenCount = new int[outputPortOpenCount.length];
        System.arraycopy(outputPortOpenCount, 0, mOutputPortOpenCount, 0,
                outputPortOpenCount.length);
    }

    /**
     * Creates a MidiDeviceStatus with false for all input port busy values
     * and zero for all output port open counts
     * @hide
     */
    public MidiDeviceStatus(MidiDeviceInfo deviceInfo) {
        mDeviceInfo = deviceInfo;
        mInputPortBusy = new boolean[deviceInfo.getInputPortCount()];
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
     * Returns true if an input port is busy.
     *
     * @param input port's port number
     * @return input port busy status
     */
    public boolean isInputPortBusy(int portNumber) {
        return mInputPortBusy[portNumber];
    }

    /**
     * Returns the open count for an output port.
     *
     * @param output port's port number
     * @return output port open count
     */
    public int getOutputPortOpenCount(int portNumber) {
        return mOutputPortOpenCount[portNumber];
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(mDeviceInfo.toString());
        int inputPortCount = mDeviceInfo.getInputPortCount();
        int outputPortCount = mDeviceInfo.getOutputPortCount();
        builder.append(" mInputPortBusy=[");
        for (int i = 0; i < inputPortCount; i++) {
            builder.append(mInputPortBusy[i]);
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
            boolean[] inputPortBusy = in.createBooleanArray();
            int[] outputPortOpenCount = in.createIntArray();
            return new MidiDeviceStatus(deviceInfo, inputPortBusy, outputPortOpenCount);
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
        parcel.writeBooleanArray(mInputPortBusy);
        parcel.writeIntArray(mOutputPortOpenCount);
   }
}
