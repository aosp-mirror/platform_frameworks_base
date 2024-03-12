/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware.devicestate;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;


/**
 * Information about the state of the device.
 *
 * @hide
 */
public final class DeviceStateInfo implements Parcelable {
    /** Bit that indicates the {@link #supportedStates} field has changed. */
    public static final int CHANGED_SUPPORTED_STATES = 1 << 0;

    /** Bit that indicates the {@link #baseState} field has changed. */
    public static final int CHANGED_BASE_STATE = 1 << 1;

    /** Bit that indicates the {@link #currentState} field has changed. */
    public static final int CHANGED_CURRENT_STATE = 1 << 2;

    @IntDef(prefix = {"CHANGED_"}, flag = true, value = {
            CHANGED_SUPPORTED_STATES,
            CHANGED_BASE_STATE,
            CHANGED_CURRENT_STATE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ChangeFlags {}

    /**
     * The list of states supported by the device.
     */
    @NonNull
    public final ArrayList<DeviceState> supportedStates;

    /**
     * The base (non-override) state of the device. The base state is the state of the device
     * ignoring any override requests made through a call to {@link DeviceStateManager#requestState(
     * DeviceStateRequest, Executor, DeviceStateRequest.Callback)}.
     */
    public final DeviceState baseState;

    /**
     * The state of the device.
     */
    public final DeviceState currentState;

    /**
     * Creates a new instance of {@link DeviceStateInfo}.
     * <p>
     * NOTE: Unlike {@link #DeviceStateInfo(DeviceStateInfo)}, this constructor does not copy the
     * supplied parameters.
     */
    public DeviceStateInfo(@NonNull List<DeviceState> supportedStates, DeviceState baseState,
            DeviceState state) {
        this.supportedStates = new ArrayList<>(supportedStates);
        this.baseState = baseState;
        this.currentState = state;
    }

    /**
     * Creates a new instance of {@link DeviceStateInfo} copying the fields of {@code info} into
     * the fields of the returned instance.
     */
    public DeviceStateInfo(@NonNull DeviceStateInfo info) {
        this(List.copyOf(info.supportedStates), info.baseState, info.currentState);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        DeviceStateInfo that = (DeviceStateInfo) other;
        return baseState.equals(that.baseState)
                &&  currentState.equals(that.currentState)
                && Objects.equals(supportedStates, that.supportedStates);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(baseState, currentState);
        result = 31 * result + supportedStates.hashCode();
        return result;
    }

    /** Returns a bitmask of the differences between this instance and {@code other}. */
    @ChangeFlags
    public int diff(@NonNull DeviceStateInfo other) {
        int diff = 0;
        if (!supportedStates.equals(other.supportedStates)) {
            diff |= CHANGED_SUPPORTED_STATES;
        }
        if (!baseState.equals(other.baseState)) {
            diff |= CHANGED_BASE_STATE;
        }
        if (!currentState.equals(other.currentState)) {
            diff |= CHANGED_CURRENT_STATE;
        }
        return diff;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(supportedStates.size());
        for (int i = 0; i < supportedStates.size(); i++) {
            dest.writeTypedObject(supportedStates.get(i).getConfiguration(), flags);
        }

        dest.writeTypedObject(baseState.getConfiguration(), flags);
        dest.writeTypedObject(currentState.getConfiguration(), flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<DeviceStateInfo> CREATOR = new Creator<>() {
        @Override
        public DeviceStateInfo createFromParcel(Parcel source) {
            final int numberOfSupportedStates = source.readInt();
            final ArrayList<DeviceState> supportedStates = new ArrayList<>(numberOfSupportedStates);
            for (int i = 0; i < numberOfSupportedStates; i++) {
                DeviceState.Configuration configuration = source.readTypedObject(
                        DeviceState.Configuration.CREATOR);
                supportedStates.add(i, new DeviceState(configuration));
            }

            final DeviceState baseState = new DeviceState(
                    source.readTypedObject(DeviceState.Configuration.CREATOR));
            final DeviceState currentState = new DeviceState(
                    source.readTypedObject(DeviceState.Configuration.CREATOR));

            return new DeviceStateInfo(supportedStates, baseState, currentState);
        }

        @Override
        public DeviceStateInfo[] newArray(int size) {
            return new DeviceStateInfo[size];
        }
    };
}
