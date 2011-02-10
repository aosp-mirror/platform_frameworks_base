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

package android.mtp;

/**
 * This class encapsulates information about an object on an MTP device.
 * This corresponds to the ObjectInfo Dataset described in
 * section 5.3.1 of the MTP specification.
 */
public final class MtpObjectInfo {
    private int mHandle;
    private int mStorageId;
    private int mFormat;
    private int mProtectionStatus;
    private int mCompressedSize;
    private int mThumbFormat;
    private int mThumbCompressedSize;
    private int mThumbPixWidth;
    private int mThumbPixHeight;
    private int mImagePixWidth;
    private int mImagePixHeight;
    private int mImagePixDepth;
    private int mParent;
    private int mAssociationType;
    private int mAssociationDesc;
    private int mSequenceNumber;
    private String mName;
    private long mDateCreated;
    private long mDateModified;
    private String mKeywords;

    // only instantiated via JNI
    private MtpObjectInfo() {
    }

    /**
     * Returns the object handle for the MTP object
     *
     * @return the object handle
     */
    public final int getObjectHandle() {
        return mHandle;
    }

    /**
     * Returns the storage ID for the MTP object's storage unit
     *
     * @return the storage ID
     */
    public final int getStorageId() {
        return mStorageId;
    }

    /**
     * Returns the format code for the MTP object
     *
     * @return the format code
     */
    public final int getFormat() {
        return mFormat;
    }

    /**
     * Returns the protection status for the MTP object
     * Possible values are:
     *
     * <ul>
     * <li> {@link android.mtp.MtpConstants#PROTECTION_STATUS_NONE}
     * <li> {@link android.mtp.MtpConstants#PROTECTION_STATUS_READ_ONLY}
     * <li> {@link android.mtp.MtpConstants#PROTECTION_STATUS_NON_TRANSFERABLE_DATA}
     * </ul>
     *
     * @return the protection status
     */
    public final int getProtectionStatus() {
        return mProtectionStatus;
    }

    /**
     * Returns the size of the MTP object
     *
     * @return the object size
     */
    public final int getCompressedSize() {
        return mCompressedSize;
    }

    /**
     * Returns the format code for the MTP object's thumbnail
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail format code
     */
    public final int getThumbFormat() {
        return mThumbFormat;
    }

    /**
     * Returns the size of the MTP object's thumbnail
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail size
     */
    public final int getThumbCompressedSize() {
        return mThumbCompressedSize;
    }

    /**
     * Returns the width of the MTP object's thumbnail in pixels
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail width
     */
    public final int getThumbPixWidth() {
        return mThumbPixWidth;
    }

    /**
     * Returns the height of the MTP object's thumbnail in pixels
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail height
     */
    public final int getThumbPixHeight() {
        return mThumbPixHeight;
    }

    /**
     * Returns the width of the MTP object in pixels
     * Will be zero for non-image objects
     *
     * @return the image width
     */
    public final int getImagePixWidth() {
        return mImagePixWidth;
    }

    /**
     * Returns the height of the MTP object in pixels
     * Will be zero for non-image objects
     *
     * @return the image height
     */
    public final int getImagePixHeight() {
        return mImagePixHeight;
    }

    /**
     * Returns the depth of the MTP object in bits per pixel
     * Will be zero for non-image objects
     *
     * @return the image depth
     */
    public final int getImagePixDepth() {
        return mImagePixDepth;
    }

    /**
     * Returns the object handle for the object's parent
     * Will be zero for the root directory of a storage unit
     *
     * @return the object's parent
     */
    public final int getParent() {
        return mParent;
    }

    /**
     * Returns the association type for the MTP object
     * Will be zero objects that are not of format
     * {@link android.mtp.MtpConstants#FORMAT_ASSOCIATION}
     * For directories the association type is typically
     * {@link android.mtp.MtpConstants#ASSOCIATION_TYPE_GENERIC_FOLDER}
     *
     * @return the object's association type
     */
    public final int getAssociationType() {
        return mAssociationType;
    }

    /**
     * Returns the association description for the MTP object
     * Will be zero objects that are not of format
     * {@link android.mtp.MtpConstants#FORMAT_ASSOCIATION}
     *
     * @return the object's association description
     */
    public final int getAssociationDesc() {
        return mAssociationDesc;
    }

   /**
     * Returns the sequence number for the MTP object
     * This field is typically not used for MTP devices,
     * but is sometimes used to define a sequence of photos
     * on PTP cameras.
     *
     * @return the object's sequence number
     */
    public final int getSequenceNumber() {
        return mSequenceNumber;
    }

   /**
     * Returns the name of the MTP object
     *
     * @return the object's name
     */
    public final String getName() {
        return mName;
    }

   /**
     * Returns the creation date of the MTP object
     * The value is represented as milliseconds since January 1, 1970
     *
     * @return the object's creation date
     */
    public final long getDateCreated() {
        return mDateCreated;
    }

   /**
     * Returns the modification date of the MTP object
     * The value is represented as milliseconds since January 1, 1970
     *
     * @return the object's modification date
     */
    public final long getDateModified() {
        return mDateModified;
    }

   /**
     * Returns a comma separated list of keywords for the MTP object
     *
     * @return the object's keyword list
     */
    public final String getKeywords() {
        return mKeywords;
    }
}
