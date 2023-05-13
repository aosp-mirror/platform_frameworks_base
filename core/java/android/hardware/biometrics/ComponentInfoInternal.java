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

package android.hardware.biometrics;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The internal class for storing the component info for a subsystem of the biometric sensor,
 * as defined in {@link android.hardware.biometrics.common.ComponentInfo}.
 * @hide
 */
public class ComponentInfoInternal implements Parcelable {

    @NonNull public final String componentId;
    @NonNull public final String hardwareVersion;
    @NonNull public final String firmwareVersion;
    @NonNull public final String serialNumber;
    @NonNull public final String softwareVersion;

    /**
     * Constructs a {@link ComponentInfoInternal} from another instance.
     * @hide
     */
    public static ComponentInfoInternal from(@NonNull ComponentInfoInternal comp) {
        return new ComponentInfoInternal(comp.componentId, comp.hardwareVersion,
                comp.firmwareVersion, comp.serialNumber, comp.softwareVersion);
    }

    /**
     * @hide
     */
    public ComponentInfoInternal(@NonNull String componentId, @NonNull String hardwareVersion,
            @NonNull String firmwareVersion, @NonNull String serialNumber,
            @NonNull String softwareVersion) {
        this.componentId = componentId;
        this.hardwareVersion = hardwareVersion;
        this.firmwareVersion = firmwareVersion;
        this.serialNumber = serialNumber;
        this.softwareVersion = softwareVersion;
    }

    protected ComponentInfoInternal(Parcel in) {
        componentId = in.readString();
        hardwareVersion = in.readString();
        firmwareVersion = in.readString();
        serialNumber = in.readString();
        softwareVersion = in.readString();
    }

    public static final Creator<ComponentInfoInternal> CREATOR =
            new Creator<ComponentInfoInternal>() {
        @Override
        public ComponentInfoInternal createFromParcel(Parcel in) {
            return new ComponentInfoInternal(in);
        }

        @Override
        public ComponentInfoInternal[] newArray(int size) {
            return new ComponentInfoInternal[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(componentId);
        dest.writeString(hardwareVersion);
        dest.writeString(firmwareVersion);
        dest.writeString(serialNumber);
        dest.writeString(softwareVersion);
    }

    @Override
    public String toString() {
        return "ComponentId: " + componentId
                + ", HardwareVersion: " + hardwareVersion
                + ", FirmwareVersion: " + firmwareVersion
                + ", SerialNumber " + serialNumber
                + ", SoftwareVersion: " + softwareVersion;
    }
}
