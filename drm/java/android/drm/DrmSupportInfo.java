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
 * This is an entity class which wraps the capability of each plug-in,
 * such as mimetype's and file suffixes it could handle.
 *
 * Plug-in developer could return the capability of the plugin by passing
 * {@link DrmSupportInfo} instance.
 *
 */
public class DrmSupportInfo {
    private final ArrayList<String> mFileSuffixList = new ArrayList<String>();
    private final ArrayList<String> mMimeTypeList = new ArrayList<String>();
    private String mDescription = "";

    /**
     * Add the mime-type to the support info such that respective plug-in is
     * capable of handling the given mime-type.
     *
     * @param mimeType MIME type
     */
    public void addMimeType(String mimeType) {
        mMimeTypeList.add(mimeType);
    }

    /**
     * Add the file suffix to the support info such that respective plug-in is
     * capable of handling the given file suffix.
     *
     * @param fileSuffix File suffix which can be handled
     */
    public void addFileSuffix(String fileSuffix) {
        mFileSuffixList.add(fileSuffix);
    }

    /**
     * Returns the iterator to walk to through mime types of this object
     *
     * @return Iterator object
     */
    public Iterator<String> getMimeTypeIterator() {
        return mMimeTypeList.iterator();
    }

    /**
     * Returns the iterator to walk to through file suffixes of this object
     *
     * @return Iterator object
     */
    public Iterator<String> getFileSuffixIterator() {
        return mFileSuffixList.iterator();
    }

    /**
     * Set the unique description about the plugin
     *
     * @param description Unique description
     */
    public void setDescription(String description) {
        if (null != description) {
            mDescription = description;
        }
    }

    /**
     * Returns the unique description associated with the plugin
     *
     * @return Unique description
     */
    public String getDescriprition() {
        return mDescription;
    }

    /**
     * Overridden hash code implementation
     *
     * @return Hash code value
     */
    public int hashCode() {
        return mFileSuffixList.hashCode() + mMimeTypeList.hashCode() + mDescription.hashCode();
    }

    /**
     * Overridden equals implementation
     *
     * @param object The object to be compared
     * @return
     *     true if equal
     *     false if not equal
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
     * Returns whether given mime-type is supported or not
     *
     * @param mimeType MIME type
     * @return
     *     true if mime type is supported
     *     false if mime type is not supported
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
     * Returns whether given file suffix is supported or not
     *
     * @param fileSuffix File suffix
     * @return
     *     true - if file suffix is supported
     *     false - if file suffix is not supported
     */
    /* package */ boolean isSupportedFileSuffix(String fileSuffix) {
        return mFileSuffixList.contains(fileSuffix);
    }
}

