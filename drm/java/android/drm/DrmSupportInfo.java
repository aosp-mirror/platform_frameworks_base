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

import java.util.ArrayList;
import java.util.Iterator;

/**
 * An entity class that wraps the capability of each DRM plug-in (agent),
 * such as the MIME type and file suffix the DRM plug-in can handle.
 *<p>
 * Plug-in developers can expose the capability of their plug-in by passing an instance of this
 * class to an application.
 *
 */
public class DrmSupportInfo {
    private final ArrayList<String> mFileSuffixList = new ArrayList<String>();
    private final ArrayList<String> mMimeTypeList = new ArrayList<String>();
    private String mDescription = "";

    /**
     * Adds the specified MIME type to the list of MIME types this DRM plug-in supports.
     *
     * @param mimeType MIME type that can be handles by this DRM plug-in.
     */
    public void addMimeType(String mimeType) {
        mMimeTypeList.add(mimeType);
    }

    /**
     * Adds the specified file suffix to the list of file suffixes this DRM plug-in supports.
     *
     * @param fileSuffix File suffix that can be handled by this DRM plug-in.
     */
    public void addFileSuffix(String fileSuffix) {
        mFileSuffixList.add(fileSuffix);
    }

    /**
     * Retrieves an iterator object that you can use to iterate over the MIME types that 
     * this DRM plug-in supports.
     *
     * @return The iterator object
     */
    public Iterator<String> getMimeTypeIterator() {
        return mMimeTypeList.iterator();
    }

    /**
     * Retrieves an iterator object that you can use to iterate over the file suffixes that
     * this DRM plug-in supports.
     *
     * @return The iterator object.
     */
    public Iterator<String> getFileSuffixIterator() {
        return mFileSuffixList.iterator();
    }

    /**
     * Sets a description for the DRM plug-in (agent).
     *
     * @param description Unique description of plug-in.
     */
    public void setDescription(String description) {
        if (null != description) {
            mDescription = description;
        }
    }

    /**
     * Retrieves the DRM plug-in (agent) description.
     *
     * @return The plug-in description.
     */
    public String getDescriprition() {
        return mDescription;
    }

    /**
     * Overridden hash code implementation.
     *
     * @return The hash code value.
     */
    public int hashCode() {
        return mFileSuffixList.hashCode() + mMimeTypeList.hashCode() + mDescription.hashCode();
    }

    /**
     * Overridden <code>equals</code> implementation.
     *
     * @param object The object to be compared.
     * @return True if equal; false if not equal.
     */
    public boolean equals(Object object) {
        boolean result = false;

        if (object instanceof DrmSupportInfo) {
            result = mFileSuffixList.equals(((DrmSupportInfo) object).mFileSuffixList) &&
                    mMimeTypeList.equals(((DrmSupportInfo) object).mMimeTypeList) &&
                    mDescription.equals(((DrmSupportInfo) object).mDescription);
        }
        return result;
    }

    /**
     * Determines whether a given MIME type is supported.
     *
     * @param mimeType MIME type.
     * @return True if Mime type is supported; false if MIME type is not supported.
     */
    /* package */ boolean isSupportedMimeType(String mimeType) {
        if (null != mimeType && !mimeType.equals("")) {
            for (int i = 0; i < mMimeTypeList.size(); i++) {
                String completeMimeType = mMimeTypeList.get(i);
                if (completeMimeType.startsWith(mimeType)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Determines whether a given file suffix is supported.
     *
     * @param fileSuffix File suffix.
     * @return True if file suffix is supported; false if file suffix is not supported.
     */
    /* package */ boolean isSupportedFileSuffix(String fileSuffix) {
        return mFileSuffixList.contains(fileSuffix);
    }
}

