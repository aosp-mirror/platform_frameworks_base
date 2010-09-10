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

import java.util.HashMap;
import java.util.Iterator;

/**
 * This is an entity class used to pass required parameters to get
 * the necessary information to communicate with online DRM server
 *
 * An instance of this class is passed to {@link DrmManagerClient#acquireDrmInfo(DrmInfoRequest)}
 * to get the instance of {@link DrmInfo}
 *
 */
public class DrmInfoRequest {
    // Changes in following constants should be in sync with DrmInfoRequest.cpp
    /**
     * Constants defines the type of {@link DrmInfoRequest}
     */
    public static final int TYPE_REGISTRATION_INFO = 1;
    public static final int TYPE_UNREGISTRATION_INFO = 2;
    public static final int TYPE_RIGHTS_ACQUISITION_INFO = 3;
    public static final int TYPE_RIGHTS_ACQUISITION_PROGRESS_INFO = 4;

    /**
     * Key to pass the unique id for the account or the user
     */
    public static final String ACCOUNT_ID = "account_id";

    /**
     * Key to pass the unique id used for subscription
     */
    public static final String SUBSCRIPTION_ID = "subscription_id";

    private final int mInfoType;
    private final String mMimeType;
    private final HashMap<String, Object> mRequestInformation = new HashMap<String, Object>();

    /**
     * constructor to create DrmInfoRequest object with type and mimetype
     *
     * @param infoType Type of information
     * @param mimeType MIME type
     */
    public DrmInfoRequest(int infoType, String mimeType) {
        mInfoType = infoType;
        mMimeType = mimeType;
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
     * Returns Information type associated with this instance
     *
     * @return Information type
     */
    public int getInfoType() {
        return mInfoType;
    }

    /**
     * Adds optional information as <key, value> pair to this object.
     *
     * @param key Key to add
     * @param value Value to add
     */
    public void put(String key, Object value) {
        mRequestInformation.put(key, value);
    }

    /**
     * Retrieves the value of given key, if not found returns null
     *
     * @param key Key whose value to be retrieved
     * @return The value or null
     */
    public Object get(String key) {
        return mRequestInformation.get(key);
    }

    /**
     * Returns Iterator object to walk through the keys associated with this instance
     *
     * @return Iterator object
     */
    public Iterator<String> keyIterator() {
        return mRequestInformation.keySet().iterator();
    }

    /**
     * Returns Iterator object to walk through the values associated with this instance
     *
     * @return Iterator object
     */
    public Iterator<Object> iterator() {
        return mRequestInformation.values().iterator();
    }

    /**
     * Returns whether this instance is valid or not
     *
     * @return
     *     true if valid
     *     false if invalid
     */
    boolean isValid() {
        return (null != mMimeType && !mMimeType.equals("")
                && null != mRequestInformation && isValidType(mInfoType));
    }

    /* package */ static boolean isValidType(int infoType) {
        boolean isValid = false;

        switch (infoType) {
        case TYPE_REGISTRATION_INFO:
        case TYPE_UNREGISTRATION_INFO:
        case TYPE_RIGHTS_ACQUISITION_INFO:
        case TYPE_RIGHTS_ACQUISITION_PROGRESS_INFO:
            isValid = true;
            break;
        }
        return isValid;
    }
}

