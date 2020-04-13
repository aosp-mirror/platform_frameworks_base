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

import static android.media.tv.tunerresourcemanager.TunerResourceManager.INVALID_OWNER_ID;

/**
 * A Tuner resource basic object used by the Tuner Resource Manager to record the resource
 * information.
 *
 * @hide
 */
public class TunerResourceBasic {
    /**
     * Id of the current resource. Should not be changed and should be aligned with the driver level
     * implementation.
     */
    final int mId;

    /**
     * If the current resource is in use.
     */
    boolean mIsInUse;

    /**
     * The owner client's id if this resource is occupied.
     */
    int mOwnerClientId = INVALID_OWNER_ID;

    TunerResourceBasic(Builder builder) {
        this.mId = builder.mId;
    }

    public int getId() {
        return mId;
    }

    public boolean isInUse() {
        return mIsInUse;
    }

    public int getOwnerClientId() {
        return mOwnerClientId;
    }

    /**
     * Set an owner client on the resource.
     *
     * @param ownerClientId the id of the owner client.
     */
    public void setOwner(int ownerClientId) {
        mIsInUse = true;
        mOwnerClientId = ownerClientId;
    }

    /**
     * Remove an owner client from the resource.
     */
    public void removeOwner() {
        mIsInUse = false;
        mOwnerClientId = INVALID_OWNER_ID;
    }

    /**
     * Builder class for {@link TunerResourceBasic}.
     */
    public static class Builder {
        private final int mId;

        Builder(int id) {
            this.mId = id;
        }

        /**
         * Build a {@link TunerResourceBasic}.
         *
         * @return {@link TunerResourceBasic}.
         */
        public TunerResourceBasic build() {
            TunerResourceBasic resource = new TunerResourceBasic(this);
            return resource;
        }
    }
}
