/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.print;

import android.os.Parcel;
import android.os.Parcelable;
import android.print.PrintAttributes.Margins;
import android.print.PrintAttributes.MediaSize;
import android.text.TextUtils;

/**
 * This class encapsulates information about a printed document.
 */
public final class PrintDocumentInfo implements Parcelable {

    /**
     * Constant for an unknown media size.
     */
    public static final MediaSize MEDIA_SIZE_UNKNOWN = new MediaSize("Unknown", "Unknown", 1, 1);

    /**
     * Constant for unknown page count..
     */
    public static final int PAGE_COUNT_UNKNOWN = -1;

    /**
     * Content type: unknown.
     */
    public static final int CONTENT_TYPE_UNKNOWN = -1;

    /**
     * Content type: document.
     */
    public static final int CONTENT_TYPE_DOCUMENT = 0;

    /**
     * Content type: photo.
     */
    public static final int CONTENT_TYPE_PHOTO = 1;

    private String mName;
    private int mPageCount;
    private int mContentType;
    private int mOrientation;
    private int mFittingMode;
    private int mColorMode;
    private Margins mMargins;
    private MediaSize mMediaSize;
    private long mDataSize;

    /**
     * Creates a new instance.
     */
    private PrintDocumentInfo() {
        /* do nothing */
    }

    /**
     * Creates a new instance.
     *
     * @param Prototype from which to clone.
     */
    private PrintDocumentInfo(PrintDocumentInfo prototype) {
        mName = prototype.mName;
        mPageCount = prototype.mPageCount;
        mContentType = prototype.mContentType;
        mOrientation = prototype.mOrientation;
        mFittingMode = prototype.mFittingMode;
        mColorMode = prototype.mColorMode;
        mMargins = prototype.mMargins;
        mMediaSize = prototype.mMediaSize;
        mDataSize = prototype.mDataSize;
    }

    /**
     * Creates a new instance.
     *
     * @param parcel Data from which to initialize.
     */
    private PrintDocumentInfo(Parcel parcel) {
        mName = parcel.readString();
        mPageCount = parcel.readInt();
        mContentType = parcel.readInt();
        mOrientation = parcel.readInt();
        mFittingMode = parcel.readInt();
        mColorMode = parcel.readInt();
        mMargins = Margins.createFromParcel(parcel);
        mMediaSize = MediaSize.createFromParcel(parcel);
        mDataSize = parcel.readLong();
    }

    /**
     * Gets the document name.
     *
     * @return The document name.
     */
    public String getName() {
        return mName;
    }

    /**
     * Gets the total number of pages.
     *
     * @return The number of pages.
     *
     * @see #PAGE_COUNT_UNKNOWN
     */
    public int getPageCount() {
        return mPageCount;
    }

    /**
     * Gets the content type.
     *
     * @return The content type.
     *
     * @see #CONTENT_TYPE_UNKNOWN
     * @see #CONTENT_TYPE_DOCUMENT
     * @see #CONTENT_TYPE_PHOTO
     */
    public int getContentType() {
        return mContentType;
    }

    /**
     * Gets the document orientation.
     *
     * @return The orientation.
     *
     * @see PrintAttributes#ORIENTATION_PORTRAIT PrintAttributes.ORIENTATION_PORTRAIT
     * @see PrintAttributes#ORIENTATION_LANDSCAPE PrintAttributes.ORIENTATION_LANDSCAPE
     */
    public int getOrientation() {
        return mOrientation;
    }

    /**
     * Gets the document fitting mode.
     *
     * @return The fitting mode.
     *
     * @see PrintAttributes#FITTING_MODE_NONE PrintAttributes.FITTING_MODE_NONE
     * @see PrintAttributes#FITTING_MODE_SCALE_TO_FILL PrintAttributes.FITTING_MODE_SCALE_TO_FILL
     * @see PrintAttributes#FITTING_MODE_SCALE_TO_FIT PrintAttributes.FITTING_MODE_SCALE_TO_FIT
     */
    public int getFittingMode() {
        return mFittingMode;
    }

    /**
     * Gets document color mode.
     *
     * @return The color mode.
     *
     * @see PrintAttributes#COLOR_MODE_COLOR PrintAttributes.COLOR_MODE_COLOR
     * @see PrintAttributes#COLOR_MODE_MONOCHROME PrintAttributes.COLOR_MODE_MONOCHROME
     */
    public int getColorMode() {
        return mColorMode;
    }

    /**
     * Gets the document margins.
     *
     * @return The margins.
     */
    public Margins getMargins() {
        return mMargins;
    }

    /**
     * Gets the media size.
     *
     * @return The media size.
     */
    public MediaSize getMediaSize() {
        return mMediaSize;
    }

    /**
     * Gets the document data size in bytes.
     *
     * @return The data size.
     */
    public long getDataSize() {
        return mDataSize;
    }

    /**
     * Sets the document data size in bytes.
     *
     * @param dataSize The data size.
     *
     * @hide
     */
    public void setDataSize(long dataSize) {
        mDataSize = dataSize;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mName);
        parcel.writeInt(mPageCount);
        parcel.writeInt(mContentType);
        parcel.writeInt(mOrientation);
        parcel.writeInt(mFittingMode);
        parcel.writeInt(mColorMode);
        mMargins.writeToParcel(parcel);
        mMediaSize.writeToParcel(parcel);
        parcel.writeLong(mDataSize);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mName != null) ? mName.hashCode() : 0);
        result = prime * result + mContentType;
        result = prime * result + mPageCount;
        result = prime * result + mOrientation;
        result = prime * result + mFittingMode;
        result = prime * result + mColorMode;
        result = prime * result + (mMargins != null ? mMargins.hashCode() : 0);
        result = prime * result + (mMediaSize != null ? mMediaSize.hashCode() : 0);
        result = prime * result + (int) mDataSize;
        result = prime * result + (int) mDataSize >> 32;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        PrintDocumentInfo other = (PrintDocumentInfo) obj;
        if (!TextUtils.equals(mName, other.mName)) {
            return false;
        }
        if (mContentType != other.mContentType) {
            return false;
        }
        if (mPageCount != other.mPageCount) {
            return false;
        }
        if (mOrientation != other.mOrientation) {
            return false;
        }
        if (mFittingMode != other.mFittingMode) {
            return false;
        }
        if (mColorMode != other.mColorMode) {
            return false;
        }
        if (mMargins == null) {
            if (other.mMargins != null) {
                return false;
            }
        } else if (!mMargins.equals(other.mMargins)) {
            return false;
        }
        if (mMediaSize == null) {
            if (other.mMediaSize != null) {
                return false;
            }
        } else if (!mMediaSize.equals(other.mMediaSize)) {
            return false;
        }
        if (mDataSize != other.mDataSize) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("PrintDocumentInfo{");
        builder.append("name=").append(mName);
        builder.append(", pageCount=").append(mPageCount);
        builder.append(", contentType=").append(contentTyepToString(mContentType));
        builder.append(", orientation=").append(PrintAttributes.orientationToString(mOrientation));
        builder.append(", fittingMode=").append(PrintAttributes.fittingModeToString(mFittingMode));
        builder.append(", colorMode=").append(PrintAttributes.colorModeToString(mColorMode));
        builder.append(", margins=").append(mMargins);
        builder.append(", mediaSize=").append(mMediaSize);
        builder.append(", size=").append(mDataSize);
        builder.append("}");
        return builder.toString();
    }

    private String contentTyepToString(int contentType) {
        switch (contentType) {
            case CONTENT_TYPE_DOCUMENT: {
                return "CONTENT_TYPE_DOCUMENT";
            }
            case CONTENT_TYPE_PHOTO: {
                return "CONTENT_TYPE_PHOTO";
            }
            default: {
                return "CONTENT_TYPE_UNKNOWN";
            }
        }
    }

    /**
     * Builder for creating an {@link PrintDocumentInfo}.
     */
    public static final class Builder {
        private final PrintDocumentInfo mPrototype;

        /**
         * Constructor.
         * <p>
         * The values of the relevant properties are initialized from the
         * provided print attributes. For example, the orientation is set
         * to be the same as the orientation returned by calling {@link
         * PrintAttributes#getOrientation() PrintAttributes.getOrientation()}.
         * </p>
         *
         * @param name The document name. Cannot be empty.
         * @param attributes Print attributes. Cannot be null.
         *
         * @throws IllegalArgumentException If the name is empty.
         */
        public Builder(String name, PrintAttributes attributes) {
            if (TextUtils.isEmpty(name)) {
                throw new IllegalArgumentException("name cannot be empty");
            }
            if (attributes == null) {
                throw new IllegalArgumentException("attributes cannot be null");
            }
            mPrototype = new PrintDocumentInfo();
            mPrototype.mName = name;
            mPrototype.mOrientation = attributes.getOrientation();
            mPrototype.mFittingMode = attributes.getFittingMode();
            mPrototype.mColorMode = attributes.getColorMode();
            mPrototype.mMargins = attributes.getMargins();
            mPrototype.mMediaSize = attributes.getMediaSize();
        }

        /**
         * Constructor.
         * <p>
         * The values of the relevant properties are initialized with default
         * values. Please refer to the documentation of the individual setters
         * for information about the default values.
         * </p>
         *
         * @param name The document name. Cannot be empty. 
         */
        public Builder(String name) {
            if (TextUtils.isEmpty(name)) {
                throw new IllegalArgumentException("name cannot be empty");
            }
            mPrototype = new PrintDocumentInfo();
            mPrototype.mName = name;
            mPrototype.mOrientation = PrintAttributes.ORIENTATION_PORTRAIT;
            mPrototype.mFittingMode = PrintAttributes.FITTING_MODE_NONE;
            mPrototype.mColorMode = PrintAttributes.COLOR_MODE_COLOR;
            mPrototype.mMargins = Margins.NO_MARGINS;
            mPrototype.mMediaSize = MEDIA_SIZE_UNKNOWN;
        }

        /**
         * Sets the total number of pages.
         * <p>
         * <strong>Default: </strong> {@link #PAGE_COUNT_UNKNOWN}
         * </p>
         *
         * @param pageCount The number of pages. Must be greater than
         * or equal to zero or {@link PrintDocumentInfo#PAGE_COUNT_UNKNOWN}.
         */
        public Builder setPageCount(int pageCount) {
            if (pageCount < 0 && pageCount != PAGE_COUNT_UNKNOWN) {
                throw new IllegalArgumentException("pageCount"
                        + " must be greater than or euqal to zero or"
                        + " DocumentInfo#PAGE_COUNT_UNKNOWN");
            }
            mPrototype.mPageCount = pageCount;
            return this;
        }

        /**
         * Sets the content type.
         * <p>
         * <strong>Default: </strong> {@link #CONTENT_TYPE_UNKNOWN}
         * </p>
         *
         * @param type The content type.
         *
         * @see #CONTENT_TYPE_UNKNOWN
         * @see #CONTENT_TYPE_DOCUMENT
         * @see #CONTENT_TYPE_PHOTO
         */
        public Builder setContentType(int type) {
            mPrototype.mContentType = type;
            return this;
        }

        /**
         * Sets the orientation.
         * <p>
         * <strong>Default: </strong> {@link PrintAttributes#ORIENTATION_PORTRAIT
         * PrintAttributes.ORIENTATION_PORTRAIT}
         * </p>
         *
         * @param orientation The orientation.
         *
         * @see PrintAttributes#ORIENTATION_PORTRAIT PrintAttributes.ORIENTATION_PORTRAIT
         * @see PrintAttributes#ORIENTATION_LANDSCAPE PrintAttributes.ORIENTATION_LANDSCAPE
         */
        public Builder setOrientation(int orientation) {
            PrintAttributes.enforceValidOrientation(orientation);
            mPrototype.mOrientation = orientation;
            return this;
        }

        /**
         * Sets the content fitting mode.
         * <p>
         * <strong>Default: </strong> {@link PrintAttributes#FITTING_MODE_NONE
         * PrintAttributes.FITTING_MODE_NONE}
         * </p>
         *
         * @param fittingMode The fitting mode.
         *
         * @see PrintAttributes#FITTING_MODE_NONE PrintAttributes.FITTING_MODE_NONE
         * @see PrintAttributes#FITTING_MODE_SCALE_TO_FILL PrintAttributes.FITTING_MODE_SCALE_TO_FILL
         * @see PrintAttributes#FITTING_MODE_SCALE_TO_FIT PrintAttributes.FITTING_MODE_SCALE_TO_FIT
         */
        public Builder setFittingMode(int fittingMode) {
            PrintAttributes.enforceValidFittingMode(fittingMode);
            mPrototype.mFittingMode = fittingMode;
            return this;
        }

        /**
         * Sets the content color mode.
         * <p>
         * <strong>Default: </strong> {@link PrintAttributes#COLOR_MODE_COLOR
         * PrintAttributes.COLOR_MODE_COLOR}
         * </p>
         *
         * @param colorMode The color mode.
         *
         * @see PrintAttributes#COLOR_MODE_COLOR PrintAttributes.COLOR_MODE_COLOR
         * @see PrintAttributes#COLOR_MODE_MONOCHROME PrintAttributes.COLOR_MODE_MONOCHROME
         */
        public Builder setColorMode(int colorMode) {
            PrintAttributes.enforceValidColorMode(colorMode);
            mPrototype.mColorMode = colorMode;
            return this;
        }

        /**
         * Sets the document margins.
         * <p>
         * <strong>Default: </strong> {@link PrintAttributes.Margins#NO_MARGINS Margins.NO_MARGINS}
         * </p>
         *
         * @param margins The margins. Cannot be null.
         */
        public Builder setMargins(Margins margins) {
            if (margins == null) {
                throw new IllegalArgumentException("margins cannot be null");
            }
            mPrototype.mMargins = margins;
            return this;
        }

        /**
         * Sets the document media size.
         * <p>
         * <strong>Default: </strong>#MEDIA_SIZE_UNKNOWN
         * </p>
         *
         * @param mediaSize The media size. Cannot be null.
         *
         * @see #MEDIA_SIZE_UNKNOWN
         */
        public Builder setMediaSize(MediaSize mediaSize) {
            if (mediaSize == null) {
                throw new IllegalArgumentException("media size cannot be null");
            }
            mPrototype.mMediaSize = mediaSize;
            return this;
        }

        /**
         * Creates a new {@link PrintDocumentInfo} instance.
         *
         * @return The new instance.
         */
        public PrintDocumentInfo create() {
            return new PrintDocumentInfo(mPrototype);
        }
    }

    public static final Parcelable.Creator<PrintDocumentInfo> CREATOR =
            new Creator<PrintDocumentInfo>() {
        @Override
        public PrintDocumentInfo createFromParcel(Parcel parcel) {
            return new PrintDocumentInfo(parcel);
        }

        @Override
        public PrintDocumentInfo[] newArray(int size) {
            return new PrintDocumentInfo[size];
        }
    };
}
