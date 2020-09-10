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

import java.util.HashSet;
import java.util.Set;

/**
  * A client profile object used by the Tuner Resource Manager to record the registered clients'
  * information.
  *
  * @hide
  */
public final class ClientProfile {

    public static final int INVALID_GROUP_ID = -1;
    public static final int INVALID_RESOURCE_ID = -1;

    /**
     * Client id sent to the client when registering with
     * {@link #registerClientProfile(ResourceClientProfile, TunerResourceManagerCallback, int[])}
     */
    private final int mId;

    /**
     * see {@link ResourceClientProfile}
     */
    private final String mTvInputSessionId;

    /**
     * see {@link ResourceClientProfile}
     */
    private final int mUseCase;

    /**
     * Process id queried from {@link TvInputManager#getPid(String)}.
     */
    private final int mProcessId;

    /**
     * All the clients that share the same resource would be under the same group id.
     *
     * <p>If a client's resource is to be reclaimed, all other clients under the same group id
     * also lose their resources.
     */
    private int mGroupId = INVALID_GROUP_ID;
    /**
     * Optional nice value for TRM to reduce client’s priority.
     */
    private int mNiceValue;

    /**
     * List of the frontend ids that are used by the current client.
     */
    private Set<Integer> mUsingFrontendIds = new HashSet<>();

    /**
     * List of the Lnb ids that are used by the current client.
     */
    private Set<Integer> mUsingLnbIds = new HashSet<>();

    /**
     * List of the Cas system ids that are used by the current client.
     */
    private int mUsingCasSystemId = INVALID_RESOURCE_ID;

    /**
     * Optional arbitrary priority value given by the client.
     *
     * <p>This value can override the default priorotiy calculated from
     * the client profile.
     */
    private int mPriority;

    private ClientProfile(Builder builder) {
        this.mId = builder.mId;
        this.mTvInputSessionId = builder.mTvInputSessionId;
        this.mUseCase = builder.mUseCase;
        this.mProcessId = builder.mProcessId;
    }

    public int getId() {
        return mId;
    }

    public String getTvInputSessionId() {
        return mTvInputSessionId;
    }

    public int getUseCase() {
        return mUseCase;
    }

    public int getProcessId() {
        return mProcessId;
    }

    public int getGroupId() {
        return mGroupId;
    }

    public int getPriority() {
        return mPriority;
    }

    public int getNiceValue() {
        return mNiceValue;
    }

    public void setGroupId(int groupId) {
        mGroupId = groupId;
    }

    public void setPriority(int priority) {
        mPriority = priority;
    }

    public void setNiceValue(int niceValue) {
        mNiceValue = niceValue;
    }

    /**
     * Set when the client starts to use a frontend.
     *
     * @param frontendId being used.
     */
    public void useFrontend(int frontendId) {
        mUsingFrontendIds.add(frontendId);
    }

    public Set<Integer> getInUseFrontendIds() {
        return mUsingFrontendIds;
    }

    /**
     * Called when the client released a frontend.
     *
     * @param frontendId being released.
     */
    public void releaseFrontend(int frontendId) {
        mUsingFrontendIds.remove(frontendId);
    }

    /**
     * Set when the client starts to use an Lnb.
     *
     * @param lnbId being used.
     */
    public void useLnb(int lnbId) {
        mUsingLnbIds.add(lnbId);
    }

    public Set<Integer> getInUseLnbIds() {
        return mUsingLnbIds;
    }

    /**
     * Called when the client released an lnb.
     *
     * @param lnbId being released.
     */
    public void releaseLnb(int lnbId) {
        mUsingLnbIds.remove(lnbId);
    }

    /**
     * Set when the client starts to use a Cas system.
     *
     * @param casSystemId cas being used.
     */
    public void useCas(int casSystemId) {
        mUsingCasSystemId = casSystemId;
    }

    public int getInUseCasSystemId() {
        return mUsingCasSystemId;
    }

    /**
     * Called when the client released a Cas System.
     */
    public void releaseCas() {
        mUsingCasSystemId = INVALID_RESOURCE_ID;
    }

    /**
     * Called to reclaim all the resources being used by the current client.
     */
    public void reclaimAllResources() {
        mUsingFrontendIds.clear();
        mUsingLnbIds.clear();
        mUsingCasSystemId = INVALID_RESOURCE_ID;
    }

    @Override
    public String toString() {
        return "ClientProfile[id=" + this.mId + ", tvInputSessionId=" + this.mTvInputSessionId
                + ", useCase=" + this.mUseCase + ", processId=" + this.mProcessId + "]";
    }

    /**
    * Builder class for {@link ClientProfile}.
    */
    public static class Builder {
        private final int mId;
        private String mTvInputSessionId;
        private int mUseCase;
        private int mProcessId;

        Builder(int id) {
            this.mId = id;
        }

        /**
          * Builder for {@link ClientProfile}.
          *
          * @param useCase the useCase of the client.
          */
        public Builder useCase(int useCase) {
            this.mUseCase = useCase;
            return this;
        }

        /**
          * Builder for {@link ClientProfile}.
          *
          * @param tvInputSessionId the id of the tv input session.
          */
        public Builder tvInputSessionId(String tvInputSessionId) {
            this.mTvInputSessionId = tvInputSessionId;
            return this;
        }

        /**
          * Builder for {@link ClientProfile}.
          *
          * @param processId the id of process.
          */
        public Builder processId(int processId) {
            this.mProcessId = processId;
            return this;
        }

        /**
          * Build a {@link ClientProfile}.
          *
          * @return {@link ClientProfile}.
          */
        public ClientProfile build() {
            ClientProfile clientProfile = new ClientProfile(this);
            return clientProfile;
        }
    }
}
