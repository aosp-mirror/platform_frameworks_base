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

import android.media.tv.tunerresourcemanager.TunerResourceManager;

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
     * The handle of the primary frontend resource
     */
    private long mPrimaryUsingFrontendHandle = TunerResourceManager.INVALID_RESOURCE_HANDLE;

    /**
     * List of the frontend handles that are used by the current client.
     */
    private Set<Long> mUsingFrontendHandles = new HashSet<>();

    /**
     * List of the client ids that share frontend with the current client.
     */
    private Set<Integer> mShareFeClientIds = new HashSet<>();

    private Set<Long> mUsingDemuxHandles = new HashSet<>();

    /**
     * Client id sharee that has shared frontend with the current client.
     */
    private Integer mShareeFeClientId = INVALID_RESOURCE_ID;

    /**
     * List of the Lnb handles that are used by the current client.
     */
    private Set<Long> mUsingLnbHandles = new HashSet<>();

    /**
     * List of the Cas system ids that are used by the current client.
     */
    private int mUsingCasSystemId = INVALID_RESOURCE_ID;

    /**
     * CiCam id that is used by the client.
     */
    private int mUsingCiCamId = INVALID_RESOURCE_ID;

    /**
     * If the priority is overwritten through
     * {@link TunerResourceManagerService#setPriority(int, int)}.
     */
    private boolean mIsPriorityOverwritten = false;

    /**
     * Optional arbitrary priority value given by the client.
     *
     * <p>This value can override the default priorotiy calculated from
     * the client profile.
     */
    private int mPriority;

    /**
     * If resource holder retains ownership of the resource in a challenge scenario then value is
     * true.
     */
    private boolean mResourceOwnershipRetention;

    private ClientProfile(Builder builder) {
        this.mId = builder.mId;
        this.mTvInputSessionId = builder.mTvInputSessionId;
        this.mUseCase = builder.mUseCase;
        this.mProcessId = builder.mProcessId;
        this.mResourceOwnershipRetention = builder.mResourceOwnershipRetention;
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

    /**
     * Returns true when the resource holder retains ownership of the resource in a challenge
     * scenario.
     */
    public boolean resourceOwnershipRetentionEnabled() {
        return mResourceOwnershipRetention;
    }

    /**
     * If the client priority is overwrttien.
     */
    public boolean isPriorityOverwritten() {
        return mIsPriorityOverwritten;
    }

    public int getGroupId() {
        return mGroupId;
    }

    public int getPriority() {
        return mPriority - mNiceValue;
    }

    public void setGroupId(int groupId) {
        mGroupId = groupId;
    }

    public void setPriority(int priority) {
        if (priority < 0) {
            return;
        }
        mPriority = priority;
    }

    /**
     * Overwrite the client priority.
     */
    public void overwritePriority(int priority) {
        if (priority < 0) {
            return;
        }
        mIsPriorityOverwritten = true;
        mPriority = priority;
    }

    public void setNiceValue(int niceValue) {
        mNiceValue = niceValue;
    }

    /**
     * Determines whether the resource holder retains ownership of the resource during a challenge
     * scenario, when both resource holder and resource challenger have same processId and same
     * priority.
     *
     * @param enabled Set to {@code true} to allow the resource holder to retain ownership,
     *     or false to allow the resource challenger to acquire the resource.
     *     If not explicitly set, enabled is set to {@code false}.
     */
    public void setResourceOwnershipRetention(boolean enabled) {
        mResourceOwnershipRetention = enabled;
    }

    /**
     * Set when the client starts to use a frontend.
     *
     * @param frontendHandle being used.
     */
    public void useFrontend(long frontendHandle) {
        mUsingFrontendHandles.add(frontendHandle);
    }

    /**
     * Set the primary frontend used by the client
     *
     * @param frontendHandle being used.
     */
    public void setPrimaryFrontend(long frontendHandle) {
        mPrimaryUsingFrontendHandle = frontendHandle;
    }

    /**
     * Get the primary frontend used by the client
     */
    public long getPrimaryFrontend() {
        return mPrimaryUsingFrontendHandle;
    }

    /**
     * Update the set of client that share frontend with the current client.
     *
     * @param clientId the client to share the fe with the current client.
     */
    public void shareFrontend(int clientId) {
        mShareFeClientIds.add(clientId);
    }

    /**
     * Remove the given client id from the share frontend client id set.
     *
     * @param clientId the client to stop sharing the fe with the current client.
     */
    public void stopSharingFrontend(int clientId) {
        mShareFeClientIds.remove(clientId);
    }

    public Set<Long> getInUseFrontendHandles() {
        return mUsingFrontendHandles;
    }

    public Set<Integer> getShareFeClientIds() {
        return mShareFeClientIds;
    }

    public Integer getShareeFeClientId() {
        return mShareeFeClientId;
    }

    public void setShareeFeClientId(Integer shareeFeClientId) {
        mShareeFeClientId = shareeFeClientId;
    }

    /**
     * Called when the client released a frontend.
     */
    public void releaseFrontend() {
        mUsingFrontendHandles.clear();
        mShareFeClientIds.clear();
        mShareeFeClientId = INVALID_RESOURCE_ID;
        mPrimaryUsingFrontendHandle = TunerResourceManager.INVALID_RESOURCE_HANDLE;
    }

    /**
     * Set when the client starts to use a Demux.
     *
     * @param demuxHandle the demux being used.
     */
    public void useDemux(long demuxHandle) {
        mUsingDemuxHandles.add(demuxHandle);
    }

    /**
     * Get the set of demux handles in use.
     */
    public Set<Long> getInUseDemuxHandles() {
        return mUsingDemuxHandles;
    }

    /**
     * Called when the client released a Demux.
     *
     * @param demuxHandle the demux handl being released.
     */
    public void releaseDemux(long demuxHandle) {
        mUsingDemuxHandles.remove(demuxHandle);
    }

    /**
     * Set when the client starts to use an Lnb.
     *
     * @param lnbHandle being used.
     */
    public void useLnb(long lnbHandle) {
        mUsingLnbHandles.add(lnbHandle);
    }

    public Set<Long> getInUseLnbHandles() {
        return mUsingLnbHandles;
    }

    /**
     * Called when the client released an lnb.
     *
     * @param lnbHandle being released.
     */
    public void releaseLnb(long lnbHandle) {
        mUsingLnbHandles.remove(lnbHandle);
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
     * Set when the client starts to connect to a CiCam.
     *
     * @param ciCamId ciCam being used.
     */
    public void useCiCam(int ciCamId) {
        mUsingCiCamId = ciCamId;
    }

    public int getInUseCiCamId() {
        return mUsingCiCamId;
    }

    /**
     * Called when the client disconnect to a CiCam.
     */
    public void releaseCiCam() {
        mUsingCiCamId = INVALID_RESOURCE_ID;
    }

    /**
     * Called to reclaim all the resources being used by the current client.
     */
    public void reclaimAllResources() {
        mUsingFrontendHandles.clear();
        mShareFeClientIds.clear();
        mPrimaryUsingFrontendHandle = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        mUsingLnbHandles.clear();
        mUsingCasSystemId = INVALID_RESOURCE_ID;
        mUsingCiCamId = INVALID_RESOURCE_ID;
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
        private boolean mResourceOwnershipRetention = false;

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
         * Builder for {@link ClientProfile}.
         *
         * @param enabled the determining factor for resource ownership during challenger scenario.
         *     The default behavior favors the resource challenger and grants them ownership of
         *     the resource if resourceOwnershipRetention is not explicitly set to true.
         */
        public Builder resourceOwnershipRetention(boolean enabled) {
            this.mResourceOwnershipRetention = enabled;
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
