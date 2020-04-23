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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A Cas resource object used by the Tuner Resource Manager to record the cas
 * information.
 *
 * @hide
 */
public final class CasResource {

    private final int mSystemId;

    private int mMaxSessionNum;

    private int mAvailableSessionNum;

    /**
     * The owner clients' ids when part of the Cas is occupied.
     */
    private Map<Integer, Integer> mOwnerClientIdsToSessionNum = new HashMap<>();

    private CasResource(Builder builder) {
        this.mSystemId = builder.mSystemId;
        this.mMaxSessionNum = builder.mMaxSessionNum;
        this.mAvailableSessionNum = builder.mMaxSessionNum;
    }

    public int getSystemId() {
        return mSystemId;
    }

    public int getMaxSessionNum() {
        return mMaxSessionNum;
    }

    public int getUsedSessionNum() {
        return (mMaxSessionNum - mAvailableSessionNum);
    }

    public boolean isFullyUsed() {
        return mAvailableSessionNum == 0;
    }

    /**
     * Update max session number.
     *
     * @param maxSessionNum the new max session num.
     */
    public void updateMaxSessionNum(int maxSessionNum) {
        mAvailableSessionNum = Math.max(
                0, mAvailableSessionNum + (maxSessionNum - mMaxSessionNum));
        mMaxSessionNum = maxSessionNum;
    }

    /**
     * Set an owner for the cas
     *
     * @param ownerId the client id of the owner.
     */
    public void setOwner(int ownerId) {
        int sessionNum = mOwnerClientIdsToSessionNum.get(ownerId) == null
                ? 1 : (mOwnerClientIdsToSessionNum.get(ownerId) + 1);
        mOwnerClientIdsToSessionNum.put(ownerId, sessionNum);
        mAvailableSessionNum--;
    }

    /**
     * Remove an owner of the Cas.
     *
     * @param ownerId the removing client id of the owner.
     */
    public void removeOwner(int ownerId) {
        mAvailableSessionNum += mOwnerClientIdsToSessionNum.get(ownerId);
        mOwnerClientIdsToSessionNum.remove(ownerId);
    }

    public Set<Integer> getOwnerClientIds() {
        return mOwnerClientIdsToSessionNum.keySet();
    }

    @Override
    public String toString() {
        return "CasResource[systemId=" + this.mSystemId
                + ", isFullyUsed=" + (this.mAvailableSessionNum == 0)
                + ", maxSessionNum=" + this.mMaxSessionNum
                + ", ownerClients=" + ownersMapToString() + "]";
    }

    /**
     * Builder class for {@link CasResource}.
     */
    public static class Builder {

        private int mSystemId;
        private int mMaxSessionNum;

        Builder(int systemId) {
            this.mSystemId = systemId;
        }

        /**
         * Builder for {@link CasResource}.
         *
         * @param maxSessionNum the max session num the current Cas has.
         */
        public Builder maxSessionNum(int maxSessionNum) {
            this.mMaxSessionNum = maxSessionNum;
            return this;
        }

        /**
         * Build a {@link CasResource}.
         *
         * @return {@link CasResource}.
         */
        public CasResource build() {
            CasResource cas = new CasResource(this);
            return cas;
        }
    }

    private String ownersMapToString() {
        StringBuilder string = new StringBuilder("{");
        for (int clienId : mOwnerClientIdsToSessionNum.keySet()) {
            string.append(" clientId=")
                  .append(clienId)
                  .append(", owns session num=")
                  .append(mOwnerClientIdsToSessionNum.get(clienId))
                  .append(",");
        }
        return string.append("}").toString();
    }
}
