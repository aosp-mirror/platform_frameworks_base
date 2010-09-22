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

import java.io.File;
import java.io.IOException;

/**
 * This is an entity class which wraps the license information which was
 * retrieved from the online DRM server.
 *
 * Caller can instantiate {@link DrmRights} by
 * invoking {@link DrmRights#DrmRights(ProcessedData, String)}
 * constructor by using the result of {@link DrmManagerClient#processDrmInfo(DrmInfo)} interface.
 * Caller can also instantiate {@link DrmRights} using the file path
 * which contains rights information.
 *
 */
public class DrmRights {
    private byte[] mData;
    private String mMimeType;
    private String mAccountId = "_NO_USER";
    private String mSubscriptionId = "";

    /**
     * constructor to create DrmRights object with given parameters
     *
     * @param rightsFilePath Path of the file containing rights data
     * @param mimeType MIME type
     */
    public DrmRights(String rightsFilePath, String mimeType) {
        File file = new File(rightsFilePath);
        instantiate(file, mimeType);
    }

    /**
     * constructor to create DrmRights object with given parameters
     *
     * @param rightsFilePath Path of the file containing rights data
     * @param mimeType MIME type
     * @param accountId Account Id of the user
     */
    public DrmRights(String rightsFilePath, String mimeType, String accountId) {
        this(rightsFilePath, mimeType);

        if (null != accountId && !accountId.equals("")) {
            mAccountId = accountId;
        }
    }

    /**
     * constructor to create DrmRights object with given parameters
     *
     * @param rightsFilePath Path of the file containing rights data
     * @param mimeType MIME type
     * @param accountId Account Id of the user
     * @param subscriptionId Subscription Id of the user
     */
    public DrmRights(
            String rightsFilePath, String mimeType, String accountId, String subscriptionId) {
        this(rightsFilePath, mimeType);

        if (null != accountId && !accountId.equals("")) {
            mAccountId = accountId;
        }

        if (null != subscriptionId && !subscriptionId.equals("")) {
            mSubscriptionId = subscriptionId;
        }
    }

    /**
     * constructor to create DrmRights object with given parameters
     *
     * @param rightsFile File containing rights data
     * @param mimeType MIME type
     */
    public DrmRights(File rightsFile, String mimeType) {
        instantiate(rightsFile, mimeType);
    }

    private void instantiate(File rightsFile, String mimeType) {
        try {
            mData = DrmUtils.readBytes(rightsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

        mMimeType = mimeType;
    }

    /**
     * constructor to create DrmRights object with given parameters
     * The user can pass String or binary data<p>
     * Usage:<p>
     *        i)  String(e.g. data is instance of String):<br>
     *            - new DrmRights(data.getBytes(), mimeType)<p>
     *        ii) Binary data<br>
     *            - new DrmRights(binaryData[], mimeType)<br>
     *
     * @param data Processed data
     * @param mimeType MIME type
     */
    public DrmRights(ProcessedData data, String mimeType) {
        mData = data.getData();

        String accountId = data.getAccountId();
        if (null != accountId && !accountId.equals("")) {
            mAccountId = accountId;
        }

        String subscriptionId = data.getSubscriptionId();
        if (null != subscriptionId && !subscriptionId.equals("")) {
            mSubscriptionId = subscriptionId;
        }

        mMimeType = mimeType;
    }

    /**
     * Returns the rights data associated with this object
     *
     * @return Rights data
     */
    public byte[] getData() {
        return mData;
    }

    /**
     * Returns the mimetype associated with this object
     *
     * @return MIME type
     */
    public String getMimeType() {
        return mMimeType;
    }

    /**
     * Returns the account-id associated with this object
     *
     * @return Account Id
     */
    public String getAccountId() {
        return mAccountId;
    }

    /**
     * Returns the subscription-id associated with this object
     *
     * @return Subscription Id
     */
    public String getSubscriptionId() {
        return mSubscriptionId;
    }

    /**
     * Returns whether this instance is valid or not
     *
     * @return
     *     true if valid
     *     false if invalid
     */
    /*package*/ boolean isValid() {
        return (null != mMimeType && !mMimeType.equals("")
                && null != mData && mData.length > 0);
    }
}

