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

import android.annotation.DrawableRes;
import android.annotation.IntDef;
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

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class represents the description of a printer. Instances of
 * this class are created by print services to report to the system
 * the printers they manage. The information of this class has two
 * major components, printer properties such as name, id, status,
 * description and printer capabilities which describe the various
 * print modes a printer supports such as media sizes, margins, etc.
 * <p>
 * Once {@link PrinterInfo.Builder#build() built} the objects are immutable.
 * </p>
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

    private final @NonNull PrinterId mId;

    /** Resource inside the printer's services's package to be used as an icon */
    private final int mIconResourceId;

    /** If a custom icon can be loaded for the printer */
    private final boolean mHasCustomPrinterIcon;

    /** The generation of the icon in the cache. */
    private final int mCustomPrinterIconGen;

    /** Intent that launches the activity showing more information about the printer. */
    private final @Nullable PendingIntent mInfoIntent;

    private final @NonNull String mName;

    private final @Status int mStatus;

    private final @Nullable String mDescription;

    private final @Nullable PrinterCapabilitiesInfo mCapabilities;

    private PrinterInfo(@NonNull PrinterId printerId, @NonNull String name, @Status int status,
            int iconResourceId, boolean hasCustomPrinterIcon, String description,
            PendingIntent infoIntent, PrinterCapabilitiesInfo capabilities,
            int customPrinterIconGen) {
        mId = printerId;
        mName = name;
        mStatus = status;
        mIconResourceId = iconResourceId;
        mHasCustomPrinterIcon = hasCustomPrinterIcon;
        mDescription = description;
        mInfoIntent = infoIntent;
        mCapabilities = capabilities;
        mCustomPrinterIconGen = customPrinterIconGen;
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
     * Check if the printer has a custom printer icon.
     *
     * @return {@code true} iff the printer has a custom printer icon.
     *
     * @hide
     */
    public boolean getHasCustomPrinterIcon() {
        return mHasCustomPrinterIcon;
    }

    /**
     * Get the printer name.
     *
     * @return The printer name.
     */
    public @NonNull String getName() {
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

    /**
     * Check if printerId is valid.
     *
     * @param printerId The printerId that might be valid
     * @return The valid printerId
     * @throws IllegalArgumentException if printerId is not valid.
     */
    private static @NonNull PrinterId checkPrinterId(PrinterId printerId) {
        return Preconditions.checkNotNull(printerId, "printerId cannot be null.");
    }

    /**
     * Check if status is valid.
     *
     * @param status The status that might be valid
     * @return The valid status
     * @throws IllegalArgumentException if status is not valid.
     */
    private static @Status int checkStatus(int status) {
        if (!(status == STATUS_IDLE
                || status == STATUS_BUSY
                || status == STATUS_UNAVAILABLE)) {
            throw new IllegalArgumentException("status is invalid.");
        }

        return status;
    }

    /**
     * Check if name is valid.
     *
     * @param name The name that might be valid
     * @return The valid name
     * @throws IllegalArgumentException if name is not valid.
     */
    private static @NonNull String checkName(String name) {
        return Preconditions.checkStringNotEmpty(name, "name cannot be empty.");
    }

    private PrinterInfo(Parcel parcel) {
        // mName can be null due to unchecked set in Builder.setName and status can be invalid
        // due to unchecked set in Builder.setStatus, hence we can only check mId for a valid state
        mId = checkPrinterId((PrinterId) parcel.readParcelable(null));
        mName = checkName(parcel.readString());
        mStatus = checkStatus(parcel.readInt());
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
        result = prime * result + mId.hashCode();
        result = prime * result + mName.hashCode();
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
        if (!mId.equals(other.mId)) {
            return false;
        }
        if (!mName.equals(other.mName)) {
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
        private @NonNull PrinterId mPrinterId;
        private @NonNull String mName;
        private @Status int mStatus;
        private int mIconResourceId;
        private boolean mHasCustomPrinterIcon;
        private String mDescription;
        private PendingIntent mInfoIntent;
        private PrinterCapabilitiesInfo mCapabilities;
        private int mCustomPrinterIconGen;

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
            mPrinterId = checkPrinterId(printerId);
            mName = checkName(name);
            mStatus = checkStatus(status);
        }

        /**
         * Constructor.
         *
         * @param other Other info from which to start building.
         */
        public Builder(@NonNull PrinterInfo other) {
            mPrinterId = other.mId;
            mName = other.mName;
            mStatus = other.mStatus;
            mIconResourceId = other.mIconResourceId;
            mHasCustomPrinterIcon = other.mHasCustomPrinterIcon;
            mDescription = other.mDescription;
            mInfoIntent = other.mInfoIntent;
            mCapabilities = other.mCapabilities;
            mCustomPrinterIconGen = other.mCustomPrinterIconGen;
        }

        /**
         * Sets the printer status.
         *
         * @param status The status.
         * @return This builder.
         * @see PrinterInfo#STATUS_IDLE
         * @see PrinterInfo#STATUS_BUSY
         * @see PrinterInfo#STATUS_UNAVAILABLE
         */
        public @NonNull Builder setStatus(@Status int status) {
            mStatus = checkStatus(status);
            return this;
        }

        /**
         * Set a drawable resource as icon for this printer. If no icon is set the printer's
         * service's icon is used for the printer.
         *
         * @param iconResourceId The resource ID of the icon.
         * @return This builder.
         * @see PrinterInfo.Builder#setHasCustomPrinterIcon
         */
        public @NonNull Builder setIconResourceId(@DrawableRes int iconResourceId) {
            mIconResourceId = Preconditions.checkArgumentNonnegative(iconResourceId,
                    "iconResourceId can't be negative");
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
         * @param hasCustomPrinterIcon If the printer has a custom icon or not.
         *
         * @return This builder.
         */
        public @NonNull Builder setHasCustomPrinterIcon(boolean hasCustomPrinterIcon) {
            mHasCustomPrinterIcon = hasCustomPrinterIcon;
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
            mName = checkName(name);
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
            mDescription = description;
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
            mInfoIntent = infoIntent;
            return this;
        }

        /**
         * Sets the printer capabilities.
         *
         * @param capabilities The capabilities.
         * @return This builder.
         */
        public @NonNull Builder setCapabilities(@NonNull PrinterCapabilitiesInfo capabilities) {
            mCapabilities = capabilities;
            return this;
        }

        /**
         * Creates a new {@link PrinterInfo}.
         *
         * @return A new {@link PrinterInfo}.
         */
        public @NonNull PrinterInfo build() {
            return new PrinterInfo(mPrinterId, mName, mStatus, mIconResourceId,
                    mHasCustomPrinterIcon, mDescription, mInfoIntent, mCapabilities,
                    mCustomPrinterIconGen);
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
            mCustomPrinterIconGen++;
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
