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
import android.text.TextUtils;

/**
 * This class represents the description of a printer. Instances of
 * this class are created by print services to report to the system
 * the printers they manage. The information of this class has two
 * major components, printer properties such as name, id, status,
 * description and printer capabilities which describe the various
 * print modes a printer supports such as media sizes, margins, etc.
 */
public final class PrinterInfo implements Parcelable {

    /** Printer status: the printer is idle and ready to print. */
    public static final int STATUS_IDLE = 1;

    /** Printer status: the printer is busy printing. */
    public static final int STATUS_BUSY = 2;

    /** Printer status: the printer is not available. */
    public static final int STATUS_UNAVAILABLE = 3;

    private PrinterId mId;

    private String mName;

    private int mStatus;

    private String mDescription;

    private PrinterCapabilitiesInfo mCapabilities;

    private PrinterInfo() {
        /* do nothing */
    }

    private PrinterInfo(PrinterInfo prototype) {
        copyFrom(prototype);
    }

    /**
     * @hide
     */
    public void copyFrom(PrinterInfo other) {
        if (this == other) {
            return;
        }
        mId = other.mId;
        mName = other.mName;
        mStatus = other.mStatus;
        mDescription = other.mDescription;
        if (other.mCapabilities != null) {
            if (mCapabilities != null) {
                mCapabilities.copyFrom(other.mCapabilities);
            } else {
                mCapabilities = new PrinterCapabilitiesInfo(other.mCapabilities);
            }
        } else {
            mCapabilities = null;
        }
    }

    /**
     * Get the globally unique printer id.
     *
     * @return The printer id.
     */
    public PrinterId getId() {
        return mId;
    }

    /**
     * Get the printer name.
     *
     * @return The printer name.
     */
    public String getName() {
        return mName;
    }

    /**
     * Gets the printer status.
     *
     * @return The status.
     *
     * @see #STATUS_BUSY
     * @see #STATUS_IDLE
     * @see #STATUS_UNAVAILABLE
     */
    public int getStatus() {
        return mStatus;
    }

    /**
     * Gets the  printer description.
     *
     * @return The description.
     */
    public String getDescription() {
        return mDescription;
    }

    /**
     * Gets the printer capabilities.
     *
     * @return The capabilities.
     */
    public PrinterCapabilitiesInfo getCapabilities() {
        return mCapabilities;
    }

    private PrinterInfo(Parcel parcel) {
        mId = parcel.readParcelable(null);
        mName = parcel.readString();
        mStatus = parcel.readInt();
        mDescription = parcel.readString();
        mCapabilities = parcel.readParcelable(null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mId, flags);
        parcel.writeString(mName);
        parcel.writeInt(mStatus);
        parcel.writeString(mDescription);
        parcel.writeParcelable(mCapabilities, flags);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mId != null) ? mId.hashCode() : 0);
        result = prime * result + ((mName != null) ? mName.hashCode() : 0);
        result = prime * result + mStatus;
        result = prime * result + ((mDescription != null) ? mDescription.hashCode() : 0);
        result = prime * result + ((mCapabilities != null) ? mCapabilities.hashCode() : 0);
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
        PrinterInfo other = (PrinterInfo) obj;
        if (mId == null) {
            if (other.mId != null) {
                return false;
            }
        } else if (!mId.equals(other.mId)) {
            return false;
        }
        if (!TextUtils.equals(mName, other.mName)) {
            return false;
        }
        if (mStatus != other.mStatus) {
            return false;
        }
        if (!TextUtils.equals(mDescription, other.mDescription)) {
            return false;
        }
        if (mCapabilities == null) {
            if (other.mCapabilities != null) {
                return false;
            }
        } else if (!mCapabilities.equals(other.mCapabilities)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("PrinterInfo{");
        builder.append("id=").append(mId);
        builder.append(", name=").append(mName);
        builder.append(", status=").append(mStatus);
        builder.append(", description=").append(mDescription);
        builder.append(", capabilities=").append(mCapabilities);
        builder.append("\"}");
        return builder.toString();
    }

    /**
     * Builder for creating of a {@link PrinterInfo}.
     */
    public static final class Builder {
        private final PrinterInfo mPrototype;

        /**
         * Constructor.
         *
         * @param printerId The printer id. Cannot be null.
         * @param name The printer name. Cannot be empty.
         * @param status The printer status. Must be a valid status.
         * @throws IllegalArgumentException If the printer id is null, or the
         * printer name is empty or the status is not a valid one.
         */
        public Builder(PrinterId printerId, String name, int status) {
            if (printerId == null) {
                throw new IllegalArgumentException("printerId cannot be null.");
            }
            if (TextUtils.isEmpty(name)) {
                throw new IllegalArgumentException("name cannot be empty.");
            }
            if (!isValidStatus(status)) {
                throw new IllegalArgumentException("status is invalid.");
            }
            mPrototype = new PrinterInfo();
            mPrototype.mId = printerId;
            mPrototype.mName = name;
            mPrototype.mStatus = status;
        }

        /**
         * Constructor.
         *
         * @param other Other info from which to start building.
         */
        public Builder(PrinterInfo other) {
            mPrototype = new PrinterInfo();
            mPrototype.copyFrom(other);
        }

        /**
         * Sets the printer status.
         *
         * @param status The status.
         * @return This builder.
         *
         * @see PrinterInfo#STATUS_IDLE
         * @see PrinterInfo#STATUS_BUSY
         * @see PrinterInfo#STATUS_UNAVAILABLE
         */
        public Builder setStatus(int status) {
            mPrototype.mStatus = status;
            return this;
        }

        /**
         * Sets the <strong>localized</strong> printer name which
         * is shown to the user
         *
         * @param name The name.
         * @return This builder.
         */
        public Builder setName(String name) {
            mPrototype.mName = name;
            return this;
        }

        /**
         * Sets the <strong>localized</strong> printer description
         * which is shown to the user
         *
         * @param description The description.
         * @return This builder.
         */
        public Builder setDescription(String description) {
            mPrototype.mDescription = description;
            return this;
        }

        /**
         * Sets the printer capabilities.
         *
         * @param capabilities The capabilities.
         * @return This builder.
         */
        public Builder setCapabilities(PrinterCapabilitiesInfo capabilities) {
            mPrototype.mCapabilities = capabilities;
            return this;
        }

        /**
         * Creates a new {@link PrinterInfo}.
         *
         * @return A new {@link PrinterInfo}.
         */
        public PrinterInfo build() {
            return mPrototype;
        }

        private boolean isValidStatus(int status) {
            return (status == STATUS_IDLE
                    || status == STATUS_BUSY
                    || status == STATUS_UNAVAILABLE);
        }
    }

    public static final Parcelable.Creator<PrinterInfo> CREATOR =
            new Parcelable.Creator<PrinterInfo>() {
        @Override
        public PrinterInfo createFromParcel(Parcel parcel) {
            return new PrinterInfo(parcel);
        }

        @Override
        public PrinterInfo[] newArray(int size) {
            return new PrinterInfo[size];
        }
    };
}
