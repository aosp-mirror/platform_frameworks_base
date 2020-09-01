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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;

/**
 * An entity class that describes the information required to send transactions
 * between a device and an online DRM server. The DRM framework achieves
 * server registration, license acquisition, and any other server-related transactions
 * by passing an instance of this class to {@link DrmManagerClient#processDrmInfo}.
 *<p>
 * The caller can retrieve the {@link DrmInfo} instance by passing a {@link DrmInfoRequest}
 * instance to {@link DrmManagerClient#acquireDrmInfo}.
 *
 * @deprecated Please use {@link android.media.MediaDrm}
 */
@Deprecated
public class DrmInfo {
    private byte[] mData;
    private final String mMimeType;
    private final int mInfoType;
    // It would be used to add attributes specific to
    // DRM scheme such as account id, path or multiple path's
    private final HashMap<String, Object> mAttributes = new HashMap<String, Object>();

    /**
     * Creates a <code>DrmInfo</code> object with the given parameters.
     *
     * @param infoType The type of information.
     * @param data The trigger data.
     * @param mimeType The MIME type.
     */
    public DrmInfo(int infoType, byte[] data, String mimeType) {
        mInfoType = infoType;
        mMimeType = mimeType;
        mData = data;
        if (!isValid()) {
            final String msg = "infoType: " + infoType + "," +
                               "mimeType: " + mimeType + "," +
                               "data: " + Arrays.toString(data);

            throw new IllegalArgumentException(msg);
        }
    }

    /**
     * Creates a <code>DrmInfo</code> object with the given parameters.
     *
     * @param infoType The type of information.
     * @param path The trigger data.
     * @param mimeType The MIME type.
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
        if (!isValid()) {
            final String msg = "infoType: " + infoType + "," +
                               "mimeType: " + mimeType + "," +
                               "data: " + Arrays.toString(mData);

            throw new IllegalArgumentException();
        }
    }

    /**
     * Adds optional information as key-value pairs to this object. To add a custom object
     * to the <code>DrmInfo</code> object, you must override the {@link #toString} implementation.
     *
     * @param key Key to add.
     * @param value Value to add.
     *
     */
    public void put(String key, Object value) {
        mAttributes.put(key, value);
    }

    /**
     * Retrieves the value of a given key.
     *
     * @param key The key whose value is being retrieved.
     *
     * @return The value of the key being retrieved. Returns null if the key cannot be found.
     */
    public Object get(String key) {
        return mAttributes.get(key);
    }

    /**
     * Retrieves an iterator object that you can use to iterate over the keys associated with
     * this <code>DrmInfo</code> object.
     *
     * @return The iterator object.
     */
    public Iterator<String> keyIterator() {
        return mAttributes.keySet().iterator();
    }

    /**
     * Retrieves an iterator object that you can use to iterate over the values associated with
     * this <code>DrmInfo</code> object.
     *
     * @return The iterator object.
     */
    public Iterator<Object> iterator() {
        return mAttributes.values().iterator();
    }

    /**
     * Retrieves the trigger data associated with this object.
     *
     * @return The trigger data.
     */
    public byte[] getData() {
        return mData;
    }

    /**
     * Retrieves the MIME type associated with this object.
     *
     * @return The MIME type.
     */
    public String getMimeType() {
        return mMimeType;
    }

    /**
     * Retrieves the information type associated with this object.
     *
     * @return The information type.
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

