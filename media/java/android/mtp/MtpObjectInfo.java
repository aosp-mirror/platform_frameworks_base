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

import com.android.internal.util.Preconditions;

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

    // only instantiated via JNI or via a builder
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
        Preconditions.checkState(mCompressedSize >= 0);
        return mCompressedSize;
    }

    /**
     * Returns the size of the MTP object
     *
     * @return the object size
     */
    public final long getCompressedSizeLong() {
        return uint32ToLong(mCompressedSize);
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
        Preconditions.checkState(mThumbCompressedSize >= 0);
        return mThumbCompressedSize;
    }

    /**
     * Returns the size of the MTP object's thumbnail
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail size
     */
    public final long getThumbCompressedSizeLong() {
        return uint32ToLong(mThumbCompressedSize);
    }

    /**
     * Returns the width of the MTP object's thumbnail in pixels
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail width
     */
    public final int getThumbPixWidth() {
        Preconditions.checkState(mThumbPixWidth >= 0);
        return mThumbPixWidth;
    }

    /**
     * Returns the width of the MTP object's thumbnail in pixels
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail width
     */
    public final long getThumbPixWidthLong() {
        return uint32ToLong(mThumbPixWidth);
    }

    /**
     * Returns the height of the MTP object's thumbnail in pixels
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail height
     */
    public final int getThumbPixHeight() {
        Preconditions.checkState(mThumbPixHeight >= 0);
        return mThumbPixHeight;
    }

    /**
     * Returns the height of the MTP object's thumbnail in pixels
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail height
     */
    public final long getThumbPixHeightLong() {
        return uint32ToLong(mThumbPixHeight);
    }

    /**
     * Returns the width of the MTP object in pixels
     * Will be zero for non-image objects
     *
     * @return the image width
     */
    public final int getImagePixWidth() {
        Preconditions.checkState(mImagePixWidth >= 0);
        return mImagePixWidth;
    }

    /**
     * Returns the width of the MTP object in pixels
     * Will be zero for non-image objects
     *
     * @return the image width
     */
    public final long getImagePixWidthLong() {
        return uint32ToLong(mImagePixWidth);
    }

    /**
     * Returns the height of the MTP object in pixels
     * Will be zero for non-image objects
     *
     * @return the image height
     */
    public final int getImagePixHeight() {
        Preconditions.checkState(mImagePixHeight >= 0);
        return mImagePixHeight;
    }

    /**
     * Returns the height of the MTP object in pixels
     * Will be zero for non-image objects
     *
     * @return the image height
     */
    public final long getImagePixHeightLong() {
        return uint32ToLong(mImagePixHeight);
    }

    /**
     * Returns the depth of the MTP object in bits per pixel
     * Will be zero for non-image objects
     *
     * @return the image depth
     */
    public final int getImagePixDepth() {
        Preconditions.checkState(mImagePixDepth >= 0);
        return mImagePixDepth;
    }

    /**
     * Returns the depth of the MTP object in bits per pixel
     * Will be zero for non-image objects
     *
     * @return the image depth
     */
    public final long getImagePixDepthLong() {
        return uint32ToLong(mImagePixDepth);
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
        Preconditions.checkState(mSequenceNumber >= 0);
        return mSequenceNumber;
    }

    /**
     * Returns the sequence number for the MTP object
     * This field is typically not used for MTP devices,
     * but is sometimes used to define a sequence of photos
     * on PTP cameras.
     *
     * @return the object's sequence number
     */
    public final long getSequenceNumberLong() {
        return uint32ToLong(mSequenceNumber);
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

    /**
     * Builds a new object info instance.
     */
    public static class Builder {
        private MtpObjectInfo mObjectInfo;

        public Builder() {
            mObjectInfo = new MtpObjectInfo();
            mObjectInfo.mHandle = -1;
        }

        /**
         * Creates a builder on a copy of an existing object info.
         * All fields, except the object handle will be copied.
         *
         * @param objectInfo object info of an existing entry
         */
        public Builder(MtpObjectInfo objectInfo) {
            mObjectInfo = new MtpObjectInfo();
            mObjectInfo.mHandle = -1;
            mObjectInfo.mAssociationDesc = objectInfo.mAssociationDesc;
            mObjectInfo.mAssociationType = objectInfo.mAssociationType;
            mObjectInfo.mCompressedSize = objectInfo.mCompressedSize;
            mObjectInfo.mDateCreated = objectInfo.mDateCreated;
            mObjectInfo.mDateModified = objectInfo.mDateModified;
            mObjectInfo.mFormat = objectInfo.mFormat;
            mObjectInfo.mImagePixDepth = objectInfo.mImagePixDepth;
            mObjectInfo.mImagePixHeight = objectInfo.mImagePixHeight;
            mObjectInfo.mImagePixWidth = objectInfo.mImagePixWidth;
            mObjectInfo.mKeywords = objectInfo.mKeywords;
            mObjectInfo.mName = objectInfo.mName;
            mObjectInfo.mParent = objectInfo.mParent;
            mObjectInfo.mProtectionStatus = objectInfo.mProtectionStatus;
            mObjectInfo.mSequenceNumber = objectInfo.mSequenceNumber;
            mObjectInfo.mStorageId = objectInfo.mStorageId;
            mObjectInfo.mThumbCompressedSize = objectInfo.mThumbCompressedSize;
            mObjectInfo.mThumbFormat = objectInfo.mThumbFormat;
            mObjectInfo.mThumbPixHeight = objectInfo.mThumbPixHeight;
            mObjectInfo.mThumbPixWidth = objectInfo.mThumbPixWidth;
        }

        public Builder setObjectHandle(int value) {
            mObjectInfo.mHandle = value;
            return this;
        }

        public Builder setAssociationDesc(int value) {
            mObjectInfo.mAssociationDesc = value;
            return this;
        }

        public Builder setAssociationType(int value) {
            mObjectInfo.mAssociationType = value;
            return this;
        }

        public Builder setCompressedSize(long value) {
            mObjectInfo.mCompressedSize = longToUint32(value, "value");
            return this;
        }

        public Builder setDateCreated(long value) {
            mObjectInfo.mDateCreated = value;
            return this;
        }

        public Builder setDateModified(long value) {
            mObjectInfo.mDateModified = value;
            return this;
        }

        public Builder setFormat(int value) {
            mObjectInfo.mFormat = value;
            return this;
        }

        public Builder setImagePixDepth(long value) {
            mObjectInfo.mImagePixDepth = longToUint32(value, "value");
            return this;
        }

        public Builder setImagePixHeight(long value) {
            mObjectInfo.mImagePixHeight = longToUint32(value, "value");
            return this;
        }

        public Builder setImagePixWidth(long value) {
            mObjectInfo.mImagePixWidth = longToUint32(value, "value");
            return this;
        }

        public Builder setKeywords(String value) {
            mObjectInfo.mKeywords = value;
            return this;
        }

        public Builder setName(String value) {
            mObjectInfo.mName = value;
            return this;
        }

        public Builder setParent(int value) {
            mObjectInfo.mParent = value;
            return this;
        }

        public Builder setProtectionStatus(int value) {
            mObjectInfo.mProtectionStatus = value;
            return this;
        }

        public Builder setSequenceNumber(long value) {
            mObjectInfo.mSequenceNumber = longToUint32(value, "value");
            return this;
        }

        public Builder setStorageId(int value) {
            mObjectInfo.mStorageId = value;
            return this;
        }

        public Builder setThumbCompressedSize(long value) {
            mObjectInfo.mThumbCompressedSize = longToUint32(value, "value");
            return this;
        }

        public Builder setThumbFormat(int value) {
            mObjectInfo.mThumbFormat = value;
            return this;
        }

        public Builder setThumbPixHeight(long value) {
            mObjectInfo.mThumbPixHeight = longToUint32(value, "value");
            return this;
        }

        public Builder setThumbPixWidth(long value) {
            mObjectInfo.mThumbPixWidth = longToUint32(value, "value");
            return this;
        }

        /**
         * Builds the object info instance. Once called, methods of the builder
         * must not be called anymore.
         *
         * @return the object info of the newly created file, or NULL in case
         *         of an error.
         */
        public MtpObjectInfo build() {
            MtpObjectInfo result = mObjectInfo;
            mObjectInfo = null;
            return result;
        }
    }

    private static long uint32ToLong(int value) {
        return value < 0 ? 0x100000000L + value : value;
    }

    private static int longToUint32(long value, String valueName) {
        Preconditions.checkArgumentInRange(value, 0, 0xffffffffL, valueName);
        return (int) value;
    }
}
