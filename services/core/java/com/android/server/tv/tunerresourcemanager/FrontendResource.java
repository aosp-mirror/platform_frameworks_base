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
public final class FrontendResource extends TunerResourceBasic {

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

    private FrontendResource(Builder builder) {
        super(builder);
        this.mType = builder.mType;
        this.mExclusiveGroupId = builder.mExclusiveGroupId;
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
    public static class Builder extends TunerResourceBasic.Builder {
        @Type private int mType;
        private int mExclusiveGroupId;

        Builder(int id) {
            super(id);
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
        @Override
        public FrontendResource build() {
            FrontendResource frontendResource = new FrontendResource(this);
            return frontendResource;
        }
    }
}
