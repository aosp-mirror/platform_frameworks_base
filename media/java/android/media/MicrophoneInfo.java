/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.util.Pair;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Class providing information on a microphone. It indicates the location and orientation of the
 * microphone on the device as well as useful information like frequency response and sensitivity.
 * It can be used by applications implementing special pre processing effects like noise suppression
 * of beam forming that need to know about precise microphone characteristics in order to adapt
 * their algorithms.
 */
public final class MicrophoneInfo {

    /**
     * A microphone that the location is unknown.
     */
    public static final int LOCATION_UNKNOWN = 0;

    /**
     * A microphone that locate on main body of the device.
     */
    public static final int LOCATION_MAINBODY = 1;

    /**
     * A microphone that locate on a movable main body of the device.
     */
    public static final int LOCATION_MAINBODY_MOVABLE = 2;

    /**
     * A microphone that locate on a peripheral.
     */
    public static final int LOCATION_PERIPHERAL = 3;

    /**
     * Unknown microphone directionality.
     */
    public static final int DIRECTIONALITY_UNKNOWN = 0;

    /**
     * Microphone directionality type: omni.
     */
    public static final int DIRECTIONALITY_OMNI = 1;

    /**
     * Microphone directionality type: bi-directional.
     */
    public static final int DIRECTIONALITY_BI_DIRECTIONAL = 2;

    /**
     * Microphone directionality type: cardioid.
     */
    public static final int DIRECTIONALITY_CARDIOID = 3;

    /**
     * Microphone directionality type: hyper cardioid.
     */
    public static final int DIRECTIONALITY_HYPER_CARDIOID = 4;

    /**
     * Microphone directionality type: super cardioid.
     */
    public static final int DIRECTIONALITY_SUPER_CARDIOID = 5;

    /**
     * The channel contains raw audio from this microphone.
     */
    public static final int CHANNEL_MAPPING_DIRECT = 1;

    /**
     * The channel contains processed audio from this microphone and possibly another microphone.
     */
    public static final int CHANNEL_MAPPING_PROCESSED = 2;

    /**
     * Value used for when the group of the microphone is unknown.
     */
    public static final int GROUP_UNKNOWN = -1;

    /**
     * Value used for when the index in the group of the microphone is unknown.
     */
    public static final int INDEX_IN_THE_GROUP_UNKNOWN = -1;

    /**
     * Value used for when the position of the microphone is unknown.
     */
    public static final Coordinate3F POSITION_UNKNOWN = new Coordinate3F(
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);

    /**
     * Value used for when the orientation of the microphone is unknown.
     */
    public static final Coordinate3F ORIENTATION_UNKNOWN = new Coordinate3F(0.0f, 0.0f, 0.0f);

    /**
     * Value used for when the sensitivity of the microphone is unknown.
     */
    public static final float SENSITIVITY_UNKNOWN = -Float.MAX_VALUE;

    /**
     * Value used for when the SPL of the microphone is unknown. This value could be used when
     * maximum SPL or minimum SPL is unknown.
     */
    public static final float SPL_UNKNOWN = -Float.MAX_VALUE;

    /** @hide */
    @IntDef(flag = true, prefix = { "LOCATION_" }, value = {
            LOCATION_UNKNOWN,
            LOCATION_MAINBODY,
            LOCATION_MAINBODY_MOVABLE,
            LOCATION_PERIPHERAL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MicrophoneLocation {}

    /** @hide */
    @IntDef(flag = true, prefix = { "DIRECTIONALITY_" }, value = {
            DIRECTIONALITY_UNKNOWN,
            DIRECTIONALITY_OMNI,
            DIRECTIONALITY_BI_DIRECTIONAL,
            DIRECTIONALITY_CARDIOID,
            DIRECTIONALITY_HYPER_CARDIOID,
            DIRECTIONALITY_SUPER_CARDIOID,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MicrophoneDirectionality {}

    private Coordinate3F mPosition;
    private Coordinate3F mOrientation;
    private String mDeviceId;
    private String mAddress;
    private List<Pair<Float, Float>> mFrequencyResponse;
    private List<Pair<Integer, Integer>> mChannelMapping;
    private float mMaxSpl;
    private float mMinSpl;
    private float mSensitivity;
    private int mLocation;
    private int mGroup; /* Usually 0 will be used for main body. */
    private int mIndexInTheGroup;
    private int mPortId; /* mPortId will correspond to the id in AudioPort */
    private int mType;
    private int mDirectionality;

    @UnsupportedAppUsage
    MicrophoneInfo(String deviceId, int type, String address, int location,
            int group, int indexInTheGroup, Coordinate3F position,
            Coordinate3F orientation, List<Pair<Float, Float>> frequencyResponse,
            List<Pair<Integer, Integer>> channelMapping, float sensitivity, float maxSpl,
            float minSpl, int directionality) {
        mDeviceId = deviceId;
        mType = type;
        mAddress = address;
        mLocation = location;
        mGroup = group;
        mIndexInTheGroup = indexInTheGroup;
        mPosition = position;
        mOrientation = orientation;
        mFrequencyResponse = frequencyResponse;
        mChannelMapping = channelMapping;
        mSensitivity = sensitivity;
        mMaxSpl = maxSpl;
        mMinSpl = minSpl;
        mDirectionality = directionality;
    }

    /**
     * Returns alphanumeric code that uniquely identifies the device.
     *
     * @return the description of the microphone
     */
    public String getDescription() {
        return mDeviceId;
    }

    /**
     * Returns The system unique device ID that corresponds to the id
     * returned by {@link AudioDeviceInfo#getId()}.
     *
     * @return the microphone's id
     */
    public int getId() {
        return mPortId;
    }

    /**
     * @hide
     * Returns the internal device type (e.g AudioSystem.DEVICE_IN_BUILTIN_MIC).
     * The internal device type could be used when getting microphone's port id
     * by matching type and address.
     *
     * @return the internal device type
     */
    public int getInternalDeviceType() {
        return mType;
    }

    /**
     * Returns the device type identifier of the microphone (e.g AudioDeviceInfo.TYPE_BUILTIN_MIC).
     *
     * @return the device type of the microphone
     */
    public int getType() {
        return AudioDeviceInfo.convertInternalDeviceToDeviceType(mType);
    }

    /**
     * Returns The "address" string of the microphone that corresponds to the
     * address returned by {@link AudioDeviceInfo#getAddress()}
     * @return the address of the microphone
     */
    public @NonNull String getAddress() {
        return mAddress;
    }

    /**
     * Returns the location of the microphone. The return value is
     * one of {@link #LOCATION_UNKNOWN}, {@link #LOCATION_MAINBODY},
     * {@link #LOCATION_MAINBODY_MOVABLE}, or {@link #LOCATION_PERIPHERAL}.
     *
     * @return the location of the microphone
     */
    public @MicrophoneLocation int getLocation() {
        return mLocation;
    }

    /**
     * Returns A device group id that can be used to group together microphones on the same
     * peripheral, attachments or logical groups. Main body is usually group 0.
     *
     * @return the group of the microphone or {@link #GROUP_UNKNOWN} if the group is unknown
     */
    public int getGroup() {
        return mGroup;
    }

    /**
     * Returns unique index for device within its group.
     *
     * @return the microphone's index in its group or {@link #INDEX_IN_THE_GROUP_UNKNOWN} if the
     * index in the group is unknown
     */
    public int getIndexInTheGroup() {
        return mIndexInTheGroup;
    }

    /**
     * Returns A {@link Coordinate3F} object that represents the geometric location of microphone
     * in meters, from bottom-left-back corner of appliance. X-axis, Y-axis and Z-axis show
     * as the x, y, z values.
     *
     * @return the geometric location of the microphone or {@link #POSITION_UNKNOWN} if the
     * geometric location is unknown
     */
    public Coordinate3F getPosition() {
        return mPosition;
    }

    /**
     * Returns A {@link Coordinate3F} object that represents the orientation of microphone.
     * X-axis, Y-axis and Z-axis show as the x, y, z value. The orientation will be normalized
     * such as sqrt(x^2 + y^2 + z^2) equals 1.
     *
     * @return the orientation of the microphone or {@link #ORIENTATION_UNKNOWN} if orientation
     * is unknown
     */
    public Coordinate3F getOrientation() {
        return mOrientation;
    }

    /**
     * Returns a {@link android.util.Pair} list of frequency responses.
     * For every {@link android.util.Pair} in the list, the first value represents frequency in Hz,
     * and the second value represents response in dB.
     *
     * @return the frequency response of the microphone
     */
    public List<Pair<Float, Float>> getFrequencyResponse() {
        return mFrequencyResponse;
    }

    /**
     * Returns a {@link android.util.Pair} list for channel mapping, which indicating how this
     * microphone is used by each channels or a capture stream. For each {@link android.util.Pair},
     * the first value is channel index, the second value is channel mapping type, which could be
     * either {@link #CHANNEL_MAPPING_DIRECT} or {@link #CHANNEL_MAPPING_PROCESSED}.
     * If a channel has contributions from more than one microphone, it is likely the HAL
     * did some extra processing to combine the sources, but this is to be inferred by the user.
     * Empty list when the MicrophoneInfo is returned by AudioManager.getMicrophones().
     * At least one entry when the MicrophoneInfo is returned by AudioRecord.getActiveMicrophones().
     *
     * @return a {@link android.util.Pair} list for channel mapping
     */
    public List<Pair<Integer, Integer>> getChannelMapping() {
        return mChannelMapping;
    }

    /**
     * Returns the level in dBFS produced by a 1000Hz tone at 94 dB SPL.
     *
     * @return the sensitivity of the microphone or {@link #SENSITIVITY_UNKNOWN} if the sensitivity
     * is unknown
     */
    public float getSensitivity() {
        return mSensitivity;
    }

    /**
     * Returns the level in dB of the maximum SPL supported by the device at 1000Hz.
     *
     * @return the maximum level in dB or {@link #SPL_UNKNOWN} if maximum SPL is unknown
     */
    public float getMaxSpl() {
        return mMaxSpl;
    }

    /**
     * Returns the level in dB of the minimum SPL that can be registered by the device at 1000Hz.
     *
     * @return the minimum level in dB or {@link #SPL_UNKNOWN} if minimum SPL is unknown
     */
    public float getMinSpl() {
        return mMinSpl;
    }

    /**
     * Returns the directionality of microphone. The return value is one of
     * {@link #DIRECTIONALITY_UNKNOWN}, {@link #DIRECTIONALITY_OMNI},
     * {@link #DIRECTIONALITY_BI_DIRECTIONAL}, {@link #DIRECTIONALITY_CARDIOID},
     * {@link #DIRECTIONALITY_HYPER_CARDIOID}, or {@link #DIRECTIONALITY_SUPER_CARDIOID}.
     *
     * @return the directionality of microphone
     */
    public @MicrophoneDirectionality int getDirectionality() {
        return mDirectionality;
    }

    /**
     * Set the port id for the device.
     * @hide
     */
    public void setId(int portId) {
        mPortId = portId;
    }

    /**
     * Set the channel mapping for the device.
     * @hide
     */
    public void setChannelMapping(List<Pair<Integer, Integer>> channelMapping) {
        mChannelMapping = channelMapping;
    }

    /* A class containing three float value to represent a 3D coordinate */
    public static final class Coordinate3F {
        public final float x;
        public final float y;
        public final float z;

        Coordinate3F(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Coordinate3F)) {
                return false;
            }
            Coordinate3F other = (Coordinate3F) obj;
            return this.x == other.x && this.y == other.y && this.z == other.z;
        }
    }
}
