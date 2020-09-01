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
 * A base class that is used to send asynchronous event information from the DRM framework.
 *
 * @deprecated Please use {@link android.media.MediaDrm}
 */
@Deprecated
public class DrmEvent {

    // Please do not add type constants in this class. More event type constants
    // should go to DrmInfoEvent or DrmErrorEvent classes.

    /**
     * All of the rights information associated with all DRM schemes have been successfully removed.
     */
    public static final int TYPE_ALL_RIGHTS_REMOVED = 1001;
    /**
     * The given DRM information has been successfully processed.
     */
    public static final int TYPE_DRM_INFO_PROCESSED = 1002;
    /**
     * The key that is used in the <code>attributes</code> HashMap to pass the return status.
     */
    public static final String DRM_INFO_STATUS_OBJECT = "drm_info_status_object";
    /**
     * The key that is used in the <code>attributes</code> HashMap to pass the
     * {@link DrmInfo} object.
     */
    public static final String DRM_INFO_OBJECT = "drm_info_object";

    private final int mUniqueId;
    private final int mType;
    private String mMessage = "";

    private HashMap<String, Object> mAttributes = new HashMap<String, Object>();

    /**
     * Creates a <code>DrmEvent</code> object with the specified parameters.
     *
     * @param uniqueId Unique session identifier.
     * @param type Type of information.
     * @param message Message description.
     * @param attributes Attributes for extensible information.
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
     * Creates a <code>DrmEvent</code> object with the specified parameters.
     *
     * @param uniqueId Unique session identifier.
     * @param type Type of information.
     * @param message Message description.
     */
    protected DrmEvent(int uniqueId, int type, String message) {
        mUniqueId = uniqueId;
        mType = type;

        if (null != message) {
            mMessage = message;
        }
    }

    /**
     * Retrieves the unique session identifier associated with this object.
     *
     * @return The unique session identifier.
     */
    public int getUniqueId() {
        return mUniqueId;
    }

    /**
     * Retrieves the type of information that is associated with this object.
     *
     * @return The type of information.
     */
    public int getType() {
        return mType;
    }

    /**
     * Retrieves the message description associated with this object.
     *
     * @return The message description.
     */
    public String getMessage() {
        return mMessage;
    }

    /**
     * Retrieves the attribute associated with the specified key.
     *
     * @return One of the attributes or null if no mapping for
     * the key is found.
     */
    public Object getAttribute(String key) {
        return mAttributes.get(key);
    }
}
