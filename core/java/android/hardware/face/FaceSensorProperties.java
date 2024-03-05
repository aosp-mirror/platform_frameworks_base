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

package android.hardware.face;

import static android.hardware.biometrics.Flags.FLAG_FACE_BACKGROUND_AUTHENTICATION;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.SensorProperties;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Container for face sensor properties.
 * @hide
 */
@TestApi
@FlaggedApi(FLAG_FACE_BACKGROUND_AUTHENTICATION)
public class FaceSensorProperties extends SensorProperties {
    /**
     * @hide
     */
    public static final int TYPE_UNKNOWN = 0;

    /**
     * @hide
     */
    public static final int TYPE_RGB = 1;

    /**
     * @hide
     */
    public static final int TYPE_IR = 2;

    /**
     * @hide
     */
    @IntDef({TYPE_UNKNOWN,
            TYPE_RGB,
            TYPE_IR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SensorType {}

    @FaceSensorProperties.SensorType
    final int mSensorType;

    /**
     * @hide
     */
    public static FaceSensorProperties from(FaceSensorPropertiesInternal internalProp) {
        final List<ComponentInfo> componentInfo = new ArrayList<>();
        for (ComponentInfoInternal internalComp : internalProp.componentInfo) {
            componentInfo.add(ComponentInfo.from(internalComp));
        }
        return new FaceSensorProperties(internalProp.sensorId,
                internalProp.sensorStrength,
                componentInfo,
                internalProp.sensorType);
    }
    /**
     * @hide
     */
    public FaceSensorProperties(int sensorId, int sensorStrength,
            @NonNull List<ComponentInfo> componentInfo,
            @FaceSensorProperties.SensorType int sensorType) {
        super(sensorId, sensorStrength, componentInfo);
        mSensorType = sensorType;
    }

    /**
     * @hide
     * @return The sensor's type.
     */
    @FaceSensorProperties.SensorType
    public int getSensorType() {
        return mSensorType;
    }
}
