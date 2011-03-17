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

/**
 * This is the base class which would be used to notify the caller
 * about any event occurred in DRM framework.
 *
 */
public class DrmEvent {
    /**
     * Constant field signifies that all the rights information associated with
     * all DRM schemes are removed successfully
     */
    public static final int TYPE_ALL_RIGHTS_REMOVED = 1001;
    /**
     * Constant field signifies that given information is processed successfully
     */
    public static final int TYPE_DRM_INFO_PROCESSED = 1002;

    public static final String DRM_INFO_STATUS_OBJECT = "drm_info_status_object";
    public static final String DRM_INFO_OBJECT = "drm_info_object";

    private final int mUniqueId;
    private final int mType;
    private String mMessage = "";

    private HashMap<String, Object> mAttributes = new HashMap<String, Object>();

    /**
     * constructor for DrmEvent class
     *
     * @param uniqueId Unique session identifier
     * @param type Type of information
     * @param message Message description
     * @param attributes Attributes for extensible information
     */
    protected DrmEvent(int uniqueId, int type, String message,
                            HashMap<String, Object> attributes) {
        mUniqueId = uniqueId;
        mType = type;

        if (null != message) {
            mMessage = message;
        }

        if (null != attributes) {
            mAttributes = attributes;
        }
    }

    /**
     * constructor for DrmEvent class
     *
     * @param uniqueId Unique session identifier
     * @param type Type of information
     * @param message Message description
     */
    protected DrmEvent(int uniqueId, int type, String message) {
        mUniqueId = uniqueId;
        mType = type;

        if (null != message) {
            mMessage = message;
        }
    }

    /**
     * Returns the Unique Id associated with this object
     *
     * @return Unique Id
     */
    public int getUniqueId() {
        return mUniqueId;
    }

    /**
     * Returns the Type of information associated with this object
     *
     * @return Type of information
     */
    public int getType() {
        return mType;
    }

    /**
     * Returns the message description associated with this object
     *
     * @return message description
     */
    public String getMessage() {
        return mMessage;
    }

    /**
     * Returns the attribute corresponding to the specified key
     *
     * @return one of the attributes or null if no mapping for
     * the key is found
     */
    public Object getAttribute(String key) {
        return mAttributes.get(key);
    }
}

