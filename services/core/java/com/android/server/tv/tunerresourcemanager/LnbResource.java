/*
 * Copyright 2020 The Android Open Source Project
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
 * An Lnb resource object used by the Tuner Resource Manager to record the tuner Lnb
 * information.
 *
 * @hide
 */
public final class LnbResource extends TunerResourceBasic {

    private LnbResource(Builder builder) {
        super(builder);
    }

    @Override
    public String toString() {
        return "LnbResource[handle=" + this.mHandle
                + ", isInUse=" + this.mIsInUse + ", ownerClientId=" + this.mOwnerClientId + "]";
    }

    /**
     * Builder class for {@link LnbResource}.
     */
    public static class Builder extends TunerResourceBasic.Builder {

        Builder(int handle) {
            super(handle);
        }

        /**
         * Build a {@link LnbResource}.
         *
         * @return {@link LnbResource}.
         */
        @Override
        public LnbResource build() {
            LnbResource lnb = new LnbResource(this);
            return lnb;
        }
    }
}
