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

public class ModemProfile extends ComponentProfile {
    public float sleepMa;
    public float idleMa;
    public float scanningMa;
    public float rxMa;
    public float[] txMa;

    public float getSleepMa() {
        return sleepMa;
    }

    public float getIdleMa() {
        return idleMa;
    }

    public float getRxMa() {
        return rxMa;
    }

    public float[] getTxMa() {
        return Arrays.copyOf(txMa, txMa.length);
    }

    public float getScanningMa() {
        return scanningMa;
    }

    public static class Builder {
        private float mSleepMa;
        private float mIdleMa;
        private float mRxMa;
        private float[] mTxMa;
        private float mScanningMa;

        public Builder() {
        }

        public void setSleepMa(float value) throws ParseException {
            mSleepMa = value;
        }

        public void setIdleMa(float value) throws ParseException {
            mIdleMa = value;
        }

        public void setRxMa(float value) throws ParseException {
            mRxMa = value;
        }

        public void setTxMa(float[] value) throws ParseException {
            mTxMa = Arrays.copyOf(value, value.length);
        }

        public void setScanningMa(float value) throws ParseException {
            mScanningMa = value;
        }

        public ModemProfile build() throws ParseException {
            ModemProfile result = new ModemProfile();
            result.sleepMa = mSleepMa;
            result.idleMa = mIdleMa;
            result.rxMa = mRxMa;
            result.txMa = mTxMa == null ? new float[0] : mTxMa;
            result.scanningMa = mScanningMa;
            return result;
        }
    }
}

