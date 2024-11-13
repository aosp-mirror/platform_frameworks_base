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
 * A CiCam resource object used by the Tuner Resource Manager to record the CiCam
 * information.
 *
 * @hide
 */
public final class CiCamResource extends CasResource {
    private CiCamResource(Builder builder) {
        super(builder);
    }

    @Override
    public String toString() {
        return "CiCamResource[systemId=" + this.getSystemId()
                + ", isFullyUsed=" + (this.isFullyUsed())
                + ", maxSessionNum=" + this.getMaxSessionNum()
                + ", ownerClients=" + ownersMapToString() + "]";
    }

    public int getCiCamId() {
        return this.getSystemId();
    }

    /**
     * Builder class for {@link CiCamResource}.
     */
    public static class Builder extends CasResource.Builder {
        Builder(int handle, int systemId) {
            super(handle, systemId);
        }

        /**
         * Builder for {@link CasResource}.
         *
         * @param maxSessionNum the max session num the current Cas has.
         */
        public Builder maxSessionNum(int maxSessionNum) {
            super.mMaxSessionNum = maxSessionNum;
            return this;
        }

        /**
         * Build a {@link CiCamResource}.
         *
         * @return {@link CiCamResource}.
         */
        @Override
        public CiCamResource build() {
            CiCamResource ciCam = new CiCamResource(this);
            return ciCam;
        }
    }
}
