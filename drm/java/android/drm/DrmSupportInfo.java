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
     * Must not be null or an empty string.
     */
    public void addMimeType(String mimeType) {
        if (mimeType == null) {
            throw new IllegalArgumentException("mimeType is null");
        }
        if (mimeType == "") {
            throw new IllegalArgumentException("mimeType is an empty string");
        }

        mMimeTypeList.add(mimeType);
    }

    /**
     * Adds the specified file suffix to the list of file suffixes this DRM plug-in supports.
     *
     * @param fileSuffix File suffix that can be handled by this DRM plug-in.
     * it could be null but not an empty string. When it is null, it indicates
     * that some DRM content comes with no file suffix.
     */
    public void addFileSuffix(String fileSuffix) {
        if (fileSuffix == "") {
            throw new IllegalArgumentException("fileSuffix is an empty string");
        }

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
     * @param description Unique description of plug-in. Must not be null
     * or an empty string.
     */
    public void setDescription(String description) {
        if (description == null) {
            throw new IllegalArgumentException("description is null");
        }
        if (description == "") {
            throw new IllegalArgumentException("description is an empty string");
        }

        mDescription = description;
    }

    /**
     * Retrieves the DRM plug-in (agent) description.
     *
     * @return The plug-in description.
     * @deprecated The method name is mis-spelled, and it is replaced by
     * {@link #getDescription()}.
     */
    public String getDescriprition() {
        return mDescription;
    }

    /**
     * Retrieves the DRM plug-in (agent) description. Even if null or an empty
     * string is not allowed in {@link #setDescription(String)}, if
     * {@link #setDescription(String)} is not called, description returned
     * from this method is an empty string.
     *
     * @return The plug-in description.
     */
    public String getDescription() {
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
     * Overridden <code>equals</code> implementation. Two DrmSupportInfo objects
     * are considered being equal if they support exactly the same set of mime
     * types, file suffixes, and has exactly the same description.
     *
     * @param object The object to be compared.
     * @return True if equal; false if not equal.
     */
    public boolean equals(Object object) {
        if (object instanceof DrmSupportInfo) {
            DrmSupportInfo info = (DrmSupportInfo) object;
            return mFileSuffixList.equals(info.mFileSuffixList) &&
                   mMimeTypeList.equals(info.mMimeTypeList) &&
                   mDescription.equals(info.mDescription);
        }
        return false;
    }

    /**
     * Determines whether a given MIME type is supported.
     *
     * @param mimeType MIME type.
     * @return True if Mime type is supported; false if MIME type is not supported.
     * Null or empty string is not a supported mimeType.
     */
    /* package */ boolean isSupportedMimeType(String mimeType) {
        if (null != mimeType && !mimeType.equals("")) {
            for (int i = 0; i < mMimeTypeList.size(); i++) {
                String completeMimeType = mMimeTypeList.get(i);

                // The reason that equals() is not used is that sometimes,
                // content distributor might just append something to
                // the basic MIME type. startsWith() is used to avoid
                // frequent update of DRM agent.
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

