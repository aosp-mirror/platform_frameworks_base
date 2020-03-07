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

import android.media.tv.tuner.frontend.FrontendSettings.Type;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * A frontend resource object used by the Tuner Resource Manager to record the tuner frontend
 * information.
 *
 * @hide
 */
public final class FrontendResource {
    public static final int INVALID_OWNER_ID = -1;

    /**
     * Id of the current frontend. Should not be changed and should be aligned with the driver level
     * implementation.
     */
    private final int mId;

    /**
     * see {@link android.media.tv.tuner.frontend.FrontendSettings.Type}
     */
    @Type private final int mType;

    /**
     * The exclusive group id of the FE. FEs under the same id can't be used at the same time.
     */
    private final int mExclusiveGroupId;

    /**
     * An array to save all the FE ids under the same exclisive group.
     */
    private Set<Integer> mExclusiveGroupMemberFeIds = new HashSet<>();

    /**
     * If the current resource is in use. Once resources under the same exclusive group id is in use
     * all other resources in the same group would be considered in use.
     */
    private boolean mIsInUse;

    /**
     * The owner client's id if this resource is occupied. Owner of the resource under the same
     * exclusive group id would be considered as the whole group's owner.
     */
    private int mOwnerClientId = INVALID_OWNER_ID;

    private FrontendResource(Builder builder) {
        this.mId = builder.mId;
        this.mType = builder.mType;
        this.mExclusiveGroupId = builder.mExclusiveGroupId;
    }

    public int getId() {
        return mId;
    }

    public int getType() {
        return mType;
    }

    public int getExclusiveGroupId() {
        return mExclusiveGroupId;
    }

    public Set<Integer> getExclusiveGroupMemberFeIds() {
        return mExclusiveGroupMemberFeIds;
    }

    /**
     * Add one id into the exclusive group member id collection.
     *
     * @param id the id to be added.
     */
    public void addExclusiveGroupMemberFeId(int id) {
        mExclusiveGroupMemberFeIds.add(id);
    }

    /**
     * Add one id collection to the exclusive group member id collection.
     *
     * @param ids the id collection to be added.
     */
    public void addExclusiveGroupMemberFeIds(Collection<Integer> ids) {
        mExclusiveGroupMemberFeIds.addAll(ids);
    }

    /**
     * Remove one id from the exclusive group member id collection.
     *
     * @param id the id to be removed.
     */
    public void removeExclusiveGroupMemberFeId(int id) {
        mExclusiveGroupMemberFeIds.remove(id);
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

    @Override
    public String toString() {
        return "FrontendResource[id=" + this.mId + ", type=" + this.mType
                + ", exclusiveGId=" + this.mExclusiveGroupId + ", exclusiveGMemeberIds="
                + this.mExclusiveGroupMemberFeIds
                + ", isInUse=" + this.mIsInUse + ", ownerClientId=" + this.mOwnerClientId + "]";
    }

    /**
     * Builder class for {@link FrontendResource}.
     */
    public static class Builder {
        private final int mId;
        @Type private int mType;
        private int mExclusiveGroupId;

        Builder(int id) {
            this.mId = id;
        }

        /**
         * Builder for {@link FrontendResource}.
         *
         * @param type the type of the frontend. See {@link Type}
         */
        public Builder type(@Type int type) {
            this.mType = type;
            return this;
        }

        /**
         * Builder for {@link FrontendResource}.
         *
         * @param exclusiveGroupId the id of exclusive group.
         */
        public Builder exclusiveGroupId(int exclusiveGroupId) {
            this.mExclusiveGroupId = exclusiveGroupId;
            return this;
        }

        /**
         * Build a {@link FrontendResource}.
         *
         * @return {@link FrontendResource}.
         */
        public FrontendResource build() {
            FrontendResource frontendResource = new FrontendResource(this);
            return frontendResource;
        }
    }
}
