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
 * This is the base class which would be used to notify the caller
 * about any event occurred in DRM framework.
 *
 */
public class DrmEvent {
    private final int mUniqueId;
    private final int mType;
    private String mMessage = "";

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
}

