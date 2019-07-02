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

package com.android.powermodel.component;

import java.util.Arrays;

import com.android.powermodel.ComponentProfile;
import com.android.powermodel.ParseException;

public class GpsProfile extends ComponentProfile {
    public float onMa;
    public float[] signalQualityMa;

    public static class Builder {
        private float onMa;
        private float[] mSignalQualityMa;

        public Builder() {
        }

        public void setOnMa(float value) throws ParseException {
            onMa = value;
        }

        public void setSignalMa(float[] value) throws ParseException {
            mSignalQualityMa = value;
        }

        public GpsProfile build() throws ParseException {
            GpsProfile result = new GpsProfile();
            result.onMa = onMa;
            result.signalQualityMa = mSignalQualityMa == null
                    ? new float[0]
                    : Arrays.copyOf(mSignalQualityMa, mSignalQualityMa.length);
            return result;
        }
    }
}

