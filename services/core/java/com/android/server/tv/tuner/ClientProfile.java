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

package com.android.server.tv.tuner;

/**
  * A client profile object used by the Tuner Resource Manager to record the registered clients'
  * information.
  *
  * @hide
  */
public final class ClientProfile {
    public static final int INVALID_GROUP_ID = -1;
    /**
     * Client id sent to the client when registering with
     * {@link #registerClientProfile(ResourceClientProfile, TunerResourceManagerCallback, int[])}
     */
    private final int mClientId;

    /**
     * see {@link ResourceClientProfile}
     */
    private final String mTvInputSessionId;

    /**
     * see {@link ResourceClientProfile}
     */
    private final int mUseCase;

    /**
     * Process id queried from {@link TvInputManager#}
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
     * Optional arbitrary priority value given by the client.
     *
     * <p>This value can override the default priorotiy calculated from
     * the client profile.
     */
    private int mPriority;

    private ClientProfile(ClientProfileBuilder builder) {
        this.mClientId = builder.mClientId;
        this.mTvInputSessionId = builder.mTvInputSessionId;
        this.mUseCase = builder.mUseCase;
        this.mProcessId = builder.mProcessId;
        this.mGroupId = builder.mGroupId;
        this.mNiceValue = builder.mNiceValue;
        this.mPriority = builder.mPriority;
    }

    public int getClientId() {
        return mClientId;
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

    @Override
    public String toString() {
        return "ClientProfile: " + this.mClientId + ", " + this.mTvInputSessionId + ", "
                + this.mUseCase + ", " + this.mProcessId;
    }

    public static class ClientProfileBuilder {
        private final int mClientId;
        private String mTvInputSessionId;
        private int mUseCase;
        private int mProcessId;
        private int mGroupId;
        private int mNiceValue;
        private int mPriority;

        ClientProfileBuilder(int clientId) {
            this.mClientId = clientId;
        }

        /**
          * Builder for {@link ClientProfile}.
          *
          * @param useCase the useCase of the client.
          */
        public ClientProfileBuilder useCase(int useCase) {
            this.mUseCase = useCase;
            return this;
        }

        /**
          * Builder for {@link ClientProfile}.
          *
          * @param tvInputSessionId the id of the tv input session.
          */
        public ClientProfileBuilder tvInputSessionId(String tvInputSessionId) {
            this.mTvInputSessionId = tvInputSessionId;
            return this;
        }

        /**
          * Builder for {@link ClientProfile}.
          *
          * @param processId the id of process.
          */
        public ClientProfileBuilder processId(int processId) {
            this.mProcessId = processId;
            return this;
        }


        /**
          * Builder for {@link ClientProfile}.
          *
          * @param groupId the id of the group that shares the same resource.
          */
        public ClientProfileBuilder groupId(int groupId) {
            this.mGroupId = groupId;
            return this;
        }

        /**
          * Builder for {@link ClientProfile}.
          *
          * @param niceValue the nice value of the client.
          */
        public ClientProfileBuilder niceValue(int niceValue) {
            this.mNiceValue = niceValue;
            return this;
        }

        /**
          * Builder for {@link ClientProfile}.
          *
          * @param priority the priority value of the client.
          */
        public ClientProfileBuilder priority(int priority) {
            this.mPriority = priority;
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
