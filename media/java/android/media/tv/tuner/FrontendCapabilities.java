/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner;

/**
 * Frontend Capabilities.
 * @hide
 */
public class FrontendCapabilities {
    /** Analog Capabilities. */
    public class Analog extends FrontendCapabilities {
        private final int mTypeCap;
        private final int mSifStandardCap;

        Analog(int typeCap, int sifStandardCap) {
            mTypeCap = typeCap;
            mSifStandardCap = sifStandardCap;
        }
        /**
         * Gets type capability.
         */
        public int getTypeCapability() {
            return mTypeCap;
        }
        /** Gets SIF standard capability. */
        public int getSifStandardCapability() {
            return mSifStandardCap;
        }
    }

    /** ATSC Capabilities. */
    public class Atsc extends FrontendCapabilities {
        private final int mModulationCap;

        Atsc(int modulationCap) {
            mModulationCap = modulationCap;
        }
        /** Gets modulation capability. */
        public int getModulationCapability() {
            return mModulationCap;
        }
    }

    /** ATSC-3 Capabilities. */
    public class Atsc3 extends FrontendCapabilities {
        private final int mBandwidthCap;
        private final int mModulationCap;
        private final int mTimeInterleaveModeCap;
        private final int mCodeRateCap;
        private final int mFecCap;
        private final int mDemodOutputFormatCap;

        Atsc3(int bandwidthCap, int modulationCap, int timeInterleaveModeCap, int codeRateCap,
                int fecCap, int demodOutputFormatCap) {
            mBandwidthCap = bandwidthCap;
            mModulationCap = modulationCap;
            mTimeInterleaveModeCap = timeInterleaveModeCap;
            mCodeRateCap = codeRateCap;
            mFecCap = fecCap;
            mDemodOutputFormatCap = demodOutputFormatCap;
        }

        /** Gets bandwidth capability. */
        public int getBandwidthCapability() {
            return mBandwidthCap;
        }
        /** Gets modulation capability. */
        public int getModulationCapability() {
            return mModulationCap;
        }
        /** Gets time interleave mod capability. */
        public int getTimeInterleaveModeCapability() {
            return mTimeInterleaveModeCap;
        }
        /** Gets code rate capability. */
        public int getCodeRateCapability() {
            return mCodeRateCap;
        }
        /** Gets FEC capability. */
        public int getFecCapability() {
            return mFecCap;
        }
        /** Gets demodulator output format capability. */
        public int getDemodOutputFormatCapability() {
            return mDemodOutputFormatCap;
        }
    }

    /** DVBS Capabilities. */
    public class Dvbs extends FrontendCapabilities {
        private final int mModulationCap;
        private final long mInnerFecCap;
        private final int mStandard;

        Dvbs(int modulationCap, long innerFecCap, int standard) {
            mModulationCap = modulationCap;
            mInnerFecCap = innerFecCap;
            mStandard = standard;
        }

        /** Gets modulation capability. */
        public int getModulationCapability() {
            return mModulationCap;
        }
        /** Gets inner FEC capability. */
        public long getInnerFecCapability() {
            return mInnerFecCap;
        }
        /** Gets DVBS standard capability. */
        public int getStandardCapability() {
            return mStandard;
        }
    }

    /** DVBC Capabilities. */
    public class Dvbc extends FrontendCapabilities {
        private final int mModulationCap;
        private final int mFecCap;
        private final int mAnnexCap;

        Dvbc(int modulationCap, int fecCap, int annexCap) {
            mModulationCap = modulationCap;
            mFecCap = fecCap;
            mAnnexCap = annexCap;
        }

        /** Gets modulation capability. */
        public int getModulationCapability() {
            return mModulationCap;
        }
        /** Gets FEC capability. */
        public int getFecCapability() {
            return mFecCap;
        }
        /** Gets annex capability. */
        public int getAnnexCapability() {
            return mAnnexCap;
        }
    }

    /** DVBT Capabilities. */
    public class Dvbt extends FrontendCapabilities {
        private final int mTransmissionModeCap;
        private final int mBandwidthCap;
        private final int mConstellationCap;
        private final int mCoderateCap;
        private final int mHierarchyCap;
        private final int mGuardIntervalCap;
        private final boolean mIsT2Supported;
        private final boolean mIsMisoSupported;

        Dvbt(int transmissionModeCap, int bandwidthCap, int constellationCap, int coderateCap,
                int hierarchyCap, int guardIntervalCap, boolean isT2Supported,
                boolean isMisoSupported) {
            mTransmissionModeCap = transmissionModeCap;
            mBandwidthCap = bandwidthCap;
            mConstellationCap = constellationCap;
            mCoderateCap = coderateCap;
            mHierarchyCap = hierarchyCap;
            mGuardIntervalCap = guardIntervalCap;
            mIsT2Supported = isT2Supported;
            mIsMisoSupported = isMisoSupported;
        }

        /** Gets transmission mode capability. */
        public int getTransmissionModeCapability() {
            return mTransmissionModeCap;
        }
        /** Gets bandwidth capability. */
        public int getBandwidthCapability() {
            return mBandwidthCap;
        }
        /** Gets constellation capability. */
        public int getConstellationCapability() {
            return mConstellationCap;
        }
        /** Gets code rate capability. */
        public int getCodeRateCapability() {
            return mCoderateCap;
        }
        /** Gets hierarchy capability. */
        public int getHierarchyCapability() {
            return mHierarchyCap;
        }
        /** Gets guard interval capability. */
        public int getGuardIntervalCapability() {
            return mGuardIntervalCap;
        }
        /** Returns whether T2 is supported. */
        public boolean getIsT2Supported() {
            return mIsT2Supported;
        }
        /** Returns whether MISO is supported. */
        public boolean getIsMisoSupported() {
            return mIsMisoSupported;
        }
    }

    /** ISDBS Capabilities. */
    public class Isdbs extends FrontendCapabilities {
        private final int mModulationCap;
        private final int mCoderateCap;

        Isdbs(int modulationCap, int coderateCap) {
            mModulationCap = modulationCap;
            mCoderateCap = coderateCap;
        }

        /** Gets modulation capability. */
        public int getModulationCapability() {
            return mModulationCap;
        }
        /** Gets code rate capability. */
        public int getCodeRateCapability() {
            return mCoderateCap;
        }
    }

    /** ISDBS-3 Capabilities. */
    public class Isdbs3 extends FrontendCapabilities {
        private final int mModulationCap;
        private final int mCoderateCap;

        Isdbs3(int modulationCap, int coderateCap) {
            mModulationCap = modulationCap;
            mCoderateCap = coderateCap;
        }

        /** Gets modulation capability. */
        public int getModulationCapability() {
            return mModulationCap;
        }
        /** Gets code rate capability. */
        public int getCodeRateCapability() {
            return mCoderateCap;
        }
    }

    /** ISDBC Capabilities. */
    public class Isdbc extends FrontendCapabilities {
        private final int mModeCap;
        private final int mBandwidthCap;
        private final int mModulationCap;
        private final int mCoderateCap;
        private final int mGuardIntervalCap;

        Isdbc(int modeCap, int bandwidthCap, int modulationCap, int coderateCap,
                int guardIntervalCap) {
            mModeCap = modeCap;
            mBandwidthCap = bandwidthCap;
            mModulationCap = modulationCap;
            mCoderateCap = coderateCap;
            mGuardIntervalCap = guardIntervalCap;
        }

        /** Gets mode capability. */
        public int getModeCapability() {
            return mModeCap;
        }
        /** Gets bandwidth capability. */
        public int getBandwidthCapability() {
            return mBandwidthCap;
        }
        /** Gets modulation capability. */
        public int getModulationCapability() {
            return mModulationCap;
        }
        /** Gets code rate capability. */
        public int getCodeRateCapability() {
            return mCoderateCap;
        }
        /** Gets guard interval capability. */
        public int getGuardIntervalCapability() {
            return mGuardIntervalCap;
        }
    }
}
