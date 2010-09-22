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

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;

/**
 * This is an entity class in which necessary information required to transact
 * between device and online DRM server is described. DRM Framework achieves
 * server registration, license acquisition and any other server related transaction
 * by passing an instance of this class to {@link DrmManagerClient#processDrmInfo(DrmInfo)}.
 *
 * Caller can retrieve the {@link DrmInfo} instance by using
 * {@link DrmManagerClient#acquireDrmInfo(DrmInfoRequest)}
 * by passing {@link DrmInfoRequest} instance.
 *
 */
public class DrmInfo {
    private byte[] mData;
    private final String mMimeType;
    private final int mInfoType;
    // It would be used to add attributes specific to
    // DRM scheme such as account id, path or multiple path's
    private final HashMap<String, Object> mAttributes = new HashMap<String, Object>();

    /**
     * constructor to create DrmInfo object with given parameters
     *
     * @param infoType Type of information
     * @param data Trigger data
     * @param mimeType MIME type
     */
    public DrmInfo(int infoType, byte[] data, String mimeType) {
        mInfoType = infoType;
        mMimeType = mimeType;
        mData = data;
    }

    /**
     * constructor to create DrmInfo object with given parameters
     *
     * @param infoType Type of information
     * @param path Trigger data
     * @param mimeType MIME type
     */
    public DrmInfo(int infoType, String path, String mimeType) {
        mInfoType = infoType;
        mMimeType = mimeType;
        try {
            mData = DrmUtils.readBytes(path);
        } catch (IOException e) {
            // As the given path is invalid,
            // set mData = null, so that further processDrmInfo()
            // call would fail with IllegalArgumentException because of mData = null
            mData = null;
        }
    }

    /**
     * Adds optional information as <key, value> pair to this object
     *
     * @param key Key to add
     * @param value Value to add
     *     To put custom object into DrmInfo, custom object has to
     *     override toString() implementation.
     */
    public void put(String key, Object value) {
        mAttributes.put(key, value);
    }

    /**
     * Retrieves the value of given key, if not found returns null
     *
     * @param key Key whose value to be retrieved
     * @return The value or null
     */
    public Object get(String key) {
        return mAttributes.get(key);
    }

    /**
     * Returns Iterator object to walk through the keys associated with this instance
     *
     * @return Iterator object
     */
    public Iterator<String> keyIterator() {
        return mAttributes.keySet().iterator();
    }

    /**
     * Returns Iterator object to walk through the values associated with this instance
     *
     * @return Iterator object
     */
    public Iterator<Object> iterator() {
        return mAttributes.values().iterator();
    }

    /**
     * Returns the trigger data associated with this object
     *
     * @return Trigger data
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
     * Returns information type associated with this instance
     *
     * @return Information type
     */
    public int getInfoType() {
        return mInfoType;
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
                && null != mData && mData.length > 0 && DrmInfoRequest.isValidType(mInfoType));
    }
}

