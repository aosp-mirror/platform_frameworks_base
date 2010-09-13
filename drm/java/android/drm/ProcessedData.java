/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.drm;

/**
 * This is an entity class which wraps the result of transaction between
 * device and online DRM server by using {@link DrmManagerClient#processDrmInfo(DrmInfo)}
 *
 * In license acquisition scenario this class would hold the binary data
 * of rights information.
 *
 */
public class ProcessedData {
    private final byte[] mData;
    private String mAccountId = "_NO_USER";
    private String mSubscriptionId = "";

    /**
     * constructor to create ProcessedData object with given parameters
     *
     * @param data Rights data
     * @param accountId Account Id of the user
     */
    /* package */ ProcessedData(byte[] data, String accountId) {
        mData = data;
        mAccountId = accountId;
    }

    /**
     * constructor to create ProcessedData object with given parameters
     *
     * @param data Rights data
     * @param accountId Account Id of the user
     * @param subscriptionId Subscription Id of the user
     */
    /* package */ ProcessedData(byte[] data, String accountId, String subscriptionId) {
        mData = data;
        mAccountId = accountId;
        mSubscriptionId = subscriptionId;
    }

    /**
     * Returns the processed data as a result.
     *
     * @return Rights data associated
     */
    public byte[] getData() {
        return mData;
    }

    /**
     * Returns the account-id associated with this object
     *
     * @return Account Id associated
     */
    public String getAccountId() {
        return mAccountId;
    }

    /**
     * Returns the subscription-id associated with this object
     *
     * @return Subscription Id associated
     */
    public String getSubscriptionId() {
        return mSubscriptionId;
    }
}

