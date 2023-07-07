/*
 * Copyright 2022 The Android Open Source Project
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
package com.android.server.tv.tunerresourcemanager;

/**
 * A demux resource object used by the Tuner Resource Manager to record the tuner Demux
 * information.
 *
 * @hide
 */
public final class DemuxResource extends TunerResourceBasic {

    private final int mFilterTypes;

    private DemuxResource(Builder builder) {
        super(builder);
        this.mFilterTypes = builder.mFilterTypes;
    }

    public int getFilterTypes() {
        return mFilterTypes;
    }

    @Override
    public String toString() {
        return "DemuxResource[handle=" + this.mHandle + ", filterTypes="
                + this.mFilterTypes + ", isInUse=" + this.mIsInUse
                + ", ownerClientId=" + this.mOwnerClientId + "]";
    }

    /**
     * Returns true if the desired {@link DemuxFilterMainTypes}  is supported.
     */
    public boolean hasSufficientCaps(int desiredCaps) {
        return desiredCaps == (desiredCaps & mFilterTypes);
    }

    /**
     * Returns the number of supported {@link DemuxFilterMainTypes}.
     */
    public int getNumOfCaps() {
        int mask = 1;
        int numOfCaps = 0;
        for (int i = 0; i < Integer.SIZE; i++) {
            if ((mFilterTypes & mask) == mask) {
                numOfCaps = numOfCaps + 1;
            }
            mask = mask << 1;
        }
        return numOfCaps;
    }

    /**
     * Builder class for {@link DemuxResource}.
     */
    public static class Builder extends TunerResourceBasic.Builder {
        private int mFilterTypes;

        Builder(int handle) {
            super(handle);
        }

        /**
         * Builder for {@link DemuxResource}.
         *
         * @param filterTypes the supported {@link DemuxFilterMainTypes}
         */
        public Builder filterTypes(int filterTypes) {
            this.mFilterTypes = filterTypes;
            return this;
        }

        /**
         * Build a {@link DemuxResource}.
         *
         * @return {@link DemuxResource}.
         */
        @Override
        public DemuxResource build() {
            DemuxResource demux = new DemuxResource(this);
            return demux;
        }
    }
}
