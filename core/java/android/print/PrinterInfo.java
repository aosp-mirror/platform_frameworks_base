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

import android.annotation.IntDef;
import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class represents the description of a printer. Instances of
 * this class are created by print services to report to the system
 * the printers they manage. The information of this class has two
 * major components, printer properties such as name, id, status,
 * description and printer capabilities which describe the various
 * print modes a printer supports such as media sizes, margins, etc.
 */
public final class PrinterInfo implements Parcelable {

    /** @hide */
    @IntDef({
            STATUS_IDLE, STATUS_BUSY, STATUS_UNAVAILABLE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {
    }
    /** Printer status: the printer is idle and ready to print. */
    public static final int STATUS_IDLE = 1;

    /** Printer status: the printer is busy printing. */
    public static final int STATUS_BUSY = 2;

    /** Printer status: the printer is not available. */
    public static final int STATUS_UNAVAILABLE = 3;

    private PrinterId mId;

    /** Resource inside the printer's services's package to be used as an icon */
    private int mIconResourceId;

    /** If a custom icon can be loaded for the printer */
    private boolean mHasCustomPrinterIcon;

    /** The generation of the icon in the cache. */
    private int mCustomPrinterIconGen;

    /** Intent that launches the activity showing more information about the printer. */
    private PendingIntent mInfoIntent;

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
        mIconResourceId = other.mIconResourceId;
        mHasCustomPrinterIcon = other.mHasCustomPrinterIcon;
        mCustomPrinterIconGen = other.mCustomPrinterIconGen;
        mInfoIntent = other.mInfoIntent;
    }

    /**
     * Get the globally unique printer id.
     *
     * @return The printer id.
     */
    public @NonNull PrinterId getId() {
        return mId;
    }

    /**
     * Get the icon to be used for this printer. If no per printer icon is available, the printer's
     * service's icon is returned. If the printer has a custom icon this icon might get requested
     * asynchronously. Once the icon is loaded the discovery sessions will be notified that the
     * printer changed.
     *
     * @param context The context that will be using the icons
     * @return The icon to be used for the printer or null if no icon could be found.
     * @hide
     */
    @TestApi
    public @Nullable Drawable loadIcon(@NonNull Context context) {
        Drawable drawable = null;
        PackageManager packageManager = context.getPackageManager();

        if (mHasCustomPrinterIcon) {
            PrintManager printManager = (PrintManager) context
                    .getSystemService(Context.PRINT_SERVICE);

            Icon icon = printManager.getCustomPrinterIcon(mId);

            if (icon != null) {
                drawable = icon.loadDrawable(context);
            }
        }

        if (drawable == null) {
            try {
                String packageName = mId.getServiceName().getPackageName();
                PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 0);
                ApplicationInfo appInfo = packageInfo.applicationInfo;

                // If no custom icon is available, try the icon from the resources
                if (mIconResourceId != 0) {
                    drawable = packageManager.getDrawable(packageName, mIconResourceId, appInfo);
                }

                // Fall back to the printer's service's icon if no per printer icon could be found
                if (drawable == null) {
                    drawable = appInfo.loadIcon(packageManager);
                }
            } catch (NameNotFoundException e) {
            }
        }

        return drawable;
    }

    /**
     * Get the printer name.
     *
     * @return The printer name.
     */
    public @Nullable String getName() {
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
    public @Status int getStatus() {
        return mStatus;
    }

    /**
     * Gets the  printer description.
     *
     * @return The description.
     */
    public @Nullable String getDescription() {
        return mDescription;
    }

    /**
     * Get the {@link PendingIntent} that launches the activity showing more information about the
     * printer.
     *
     * @return the {@link PendingIntent} that launches the activity showing more information about
     *         the printer or null if it is not configured
     * @hide
     */
    public @Nullable PendingIntent getInfoIntent() {
        return mInfoIntent;
    }

    /**
     * Gets the printer capabilities.
     *
     * @return The capabilities.
     */
    public @Nullable PrinterCapabilitiesInfo getCapabilities() {
        return mCapabilities;
    }

    private PrinterInfo(Parcel parcel) {
        mId = parcel.readParcelable(null);
        mName = parcel.readString();
        mStatus = parcel.readInt();
        mDescription = parcel.readString();
        mCapabilities = parcel.readParcelable(null);
        mIconResourceId = parcel.readInt();
        mHasCustomPrinterIcon = parcel.readByte() != 0;
        mCustomPrinterIconGen = parcel.readInt();
        mInfoIntent = parcel.readParcelable(null);
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
        parcel.writeInt(mIconResourceId);
        parcel.writeByte((byte) (mHasCustomPrinterIcon ? 1 : 0));
        parcel.writeInt(mCustomPrinterIconGen);
        parcel.writeParcelable(mInfoIntent, flags);
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
        result = prime * result + mIconResourceId;
        result = prime * result + (mHasCustomPrinterIcon ? 1 : 0);
        result = prime * result + mCustomPrinterIconGen;
        result = prime * result + ((mInfoIntent != null) ? mInfoIntent.hashCode() : 0);
        return result;
    }

    /**
     * Compare two {@link PrinterInfo printerInfos} in all aspects beside being null and the
     * {@link #mStatus}.
     *
     * @param other the other {@link PrinterInfo}
     * @return true iff the infos are equivalent
     * @hide
     */
    public boolean equalsIgnoringStatus(PrinterInfo other) {
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
        if (mIconResourceId != other.mIconResourceId) {
            return false;
        }
        if (mHasCustomPrinterIcon != other.mHasCustomPrinterIcon) {
            return false;
        }
        if (mCustomPrinterIconGen != other.mCustomPrinterIconGen) {
            return false;
        }
        if (mInfoIntent == null) {
            if (other.mInfoIntent != null) {
                return false;
            }
        } else if (!mInfoIntent.equals(other.mInfoIntent)) {
            return false;
        }
        return true;
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
        if (!equalsIgnoringStatus(other)) {
            return false;
        }
        if (mStatus != other.mStatus) {
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
        builder.append(", iconResId=").append(mIconResourceId);
        builder.append(", hasCustomPrinterIcon=").append(mHasCustomPrinterIcon);
        builder.append(", customPrinterIconGen=").append(mCustomPrinterIconGen);
        builder.append(", infoIntent=").append(mInfoIntent);
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
        public Builder(@NonNull PrinterId printerId, @NonNull String name, @Status int status) {
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
        public Builder(@NonNull PrinterInfo other) {
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
        public @Nullable Builder setStatus(@Status int status) {
            mPrototype.mStatus = status;
            return this;
        }

        /**
         * Set a drawable resource as icon for this printer. If no icon is set the printer's
         * service's icon is used for the printer.
         *
         * @return This builder.
         * @see PrinterInfo.Builder#setHasCustomPrinterIcon
         */
        public @NonNull Builder setIconResourceId(@DrawableRes int iconResourceId) {
            mPrototype.mIconResourceId = iconResourceId;
            return this;
        }

        /**
         * Declares that the print service can load a custom per printer's icon. If both
         * {@link PrinterInfo.Builder#setIconResourceId} and a custom icon are set the resource icon
         * is shown while the custom icon loads but then the custom icon is used. If
         * {@link PrinterInfo.Builder#setIconResourceId} is not set the printer's service's icon is
         * shown while loading.
         * <p>
         * The icon is requested asynchronously and only when needed via
         * {@link android.printservice.PrinterDiscoverySession#onRequestCustomPrinterIcon}.
         * </p>
         *
         * @return This builder.
         */
        public @NonNull Builder setHasCustomPrinterIcon() {
            mPrototype.mHasCustomPrinterIcon = true;
            return this;
        }

        /**
         * Sets the <strong>localized</strong> printer name which
         * is shown to the user
         *
         * @param name The name.
         * @return This builder.
         */
        public @NonNull Builder setName(@NonNull String name) {
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
        public @NonNull Builder setDescription(@NonNull String description) {
            mPrototype.mDescription = description;
            return this;
        }

        /**
         * Sets the {@link PendingIntent} that launches an activity showing more information about
         * the printer.
         *
         * @param infoIntent The {@link PendingIntent intent}.
         * @return This builder.
         */
        public @NonNull Builder setInfoIntent(@NonNull PendingIntent infoIntent) {
            mPrototype.mInfoIntent = infoIntent;
            return this;
        }

        /**
         * Sets the printer capabilities.
         *
         * @param capabilities The capabilities.
         * @return This builder.
         */
        public @NonNull Builder setCapabilities(@NonNull PrinterCapabilitiesInfo capabilities) {
            mPrototype.mCapabilities = capabilities;
            return this;
        }

        /**
         * Creates a new {@link PrinterInfo}.
         *
         * @return A new {@link PrinterInfo}.
         */
        public @NonNull PrinterInfo build() {
            return mPrototype;
        }

        private boolean isValidStatus(int status) {
            return (status == STATUS_IDLE
                    || status == STATUS_BUSY
                    || status == STATUS_UNAVAILABLE);
        }

        /**
         * Increments the generation number of the custom printer icon. As the {@link PrinterInfo}
         * does not match the previous one anymore, users of the {@link PrinterInfo} will reload the
         * icon if needed.
         *
         * @return This builder.
         * @hide
         */
        public @NonNull Builder incCustomPrinterIconGen() {
            mPrototype.mCustomPrinterIconGen++;
            return this;
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
