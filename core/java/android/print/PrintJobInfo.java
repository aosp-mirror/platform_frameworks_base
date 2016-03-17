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

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.TestApi;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * This class represents the description of a print job. The print job
 * state includes properties such as its id, print attributes used for
 * generating the content, and so on. Note that the print jobs state may
 * change over time and this class represents a snapshot of this state.
 */
public final class PrintJobInfo implements Parcelable {

    /** @hide */
    @IntDef({
            STATE_CREATED, STATE_QUEUED, STATE_STARTED, STATE_BLOCKED, STATE_COMPLETED,
            STATE_FAILED, STATE_CANCELED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
    }

    /**
     * Constant for matching any print job state.
     *
     * @hide
     */
    public static final int STATE_ANY = -1;

    /**
     * Constant for matching any print job state.
     *
     * @hide
     */
    public static final int STATE_ANY_VISIBLE_TO_CLIENTS = -2;

    /**
     * Constant for matching any active print job state.
     *
     * @hide
     */
    public static final int STATE_ANY_ACTIVE = -3;

    /**
     * Constant for matching any scheduled, i.e. delivered to a print
     * service, print job state.
     *
     * @hide
     */
    public static final int STATE_ANY_SCHEDULED = -4;

    /**
     * Print job state: The print job is being created but not yet
     * ready to be printed.
     * <p>
     * Next valid states: {@link #STATE_QUEUED}
     * </p>
     */
    public static final int STATE_CREATED = 1;

    /**
     * Print job state: The print jobs is created, it is ready
     * to be printed and should be processed.
     * <p>
     * Next valid states: {@link #STATE_STARTED}, {@link #STATE_FAILED},
     * {@link #STATE_CANCELED}
     * </p>
     */
    public static final int STATE_QUEUED = 2;

    /**
     * Print job state: The print job is being printed.
     * <p>
     * Next valid states: {@link #STATE_COMPLETED}, {@link #STATE_FAILED},
     * {@link #STATE_CANCELED}, {@link #STATE_BLOCKED}
     * </p>
     */
    public static final int STATE_STARTED = 3;

    /**
     * Print job state: The print job is blocked.
     * <p>
     * Next valid states: {@link #STATE_FAILED}, {@link #STATE_CANCELED},
     * {@link #STATE_STARTED}
     * </p>
     */
    public static final int STATE_BLOCKED = 4;

    /**
     * Print job state: The print job is successfully printed.
     * This is a terminal state.
     * <p>
     * Next valid states: None
     * </p>
     */
    public static final int STATE_COMPLETED = 5;

    /**
     * Print job state: The print job was printing but printing failed.
     * <p>
     * Next valid states: {@link #STATE_CANCELED}, {@link #STATE_STARTED}
     * </p>
     */
    public static final int STATE_FAILED = 6;

    /**
     * Print job state: The print job is canceled.
     * This is a terminal state.
     * <p>
     * Next valid states: None
     * </p>
     */
    public static final int STATE_CANCELED = 7;

    /** The unique print job id. */
    private PrintJobId mId;

    /** The human readable print job label. */
    private String mLabel;

    /** The unique id of the printer. */
    private PrinterId mPrinterId;

    /** The name of the printer - internally used */
    private String mPrinterName;

    /** The state of the print job. */
    private int mState;

    /** The id of the app that created the job. */
    private int mAppId;

    /** Optional tag assigned by a print service.*/
    private String mTag;

    /** The wall time when the print job was created. */
    private long mCreationTime;

    /** How many copies to print. */
    private int mCopies;

    /** The pages to print */
    private PageRange[] mPageRanges;

    /** The print job attributes size. */
    private PrintAttributes mAttributes;

    /** Information about the printed document. */
    private PrintDocumentInfo mDocumentInfo;

    /** The progress made on printing this job or -1 if not set. */
    private float mProgress;

    /** A short string describing the status of this job. */
    private @Nullable CharSequence mStatus;

    /** A string resource describing the status of this job. */
    private @StringRes int mStatusRes;
    private @Nullable CharSequence mStatusResAppPackageName;

    /** Advanced printer specific options. */
    private Bundle mAdvancedOptions;

    /** Whether we are trying to cancel this print job. */
    private boolean mCanceling;

    /** @hide*/
    public PrintJobInfo() {
        mProgress = -1;
    }

    /** @hide */
    public PrintJobInfo(PrintJobInfo other) {
        mId = other.mId;
        mLabel = other.mLabel;
        mPrinterId = other.mPrinterId;
        mPrinterName = other.mPrinterName;
        mState = other.mState;
        mAppId = other.mAppId;
        mTag = other.mTag;
        mCreationTime = other.mCreationTime;
        mCopies = other.mCopies;
        mPageRanges = other.mPageRanges;
        mAttributes = other.mAttributes;
        mDocumentInfo = other.mDocumentInfo;
        mProgress = other.mProgress;
        mStatus = other.mStatus;
        mStatusRes = other.mStatusRes;
        mStatusResAppPackageName = other.mStatusResAppPackageName;
        mCanceling = other.mCanceling;
        mAdvancedOptions = other.mAdvancedOptions;
    }

    private PrintJobInfo(@NonNull Parcel parcel) {
        mId = parcel.readParcelable(null);
        mLabel = parcel.readString();
        mPrinterId = parcel.readParcelable(null);
        mPrinterName = parcel.readString();
        mState = parcel.readInt();
        mAppId = parcel.readInt();
        mTag = parcel.readString();
        mCreationTime = parcel.readLong();
        mCopies = parcel.readInt();
        Parcelable[] parcelables = parcel.readParcelableArray(null);
        if (parcelables != null) {
            mPageRanges = new PageRange[parcelables.length];
            for (int i = 0; i < parcelables.length; i++) {
                mPageRanges[i] = (PageRange) parcelables[i];
            }
        }
        mAttributes = (PrintAttributes) parcel.readParcelable(null);
        mDocumentInfo = (PrintDocumentInfo) parcel.readParcelable(null);
        mProgress = parcel.readFloat();
        mStatus = parcel.readCharSequence();
        mStatusRes = parcel.readInt();
        mStatusResAppPackageName = parcel.readCharSequence();
        mCanceling = (parcel.readInt() == 1);
        mAdvancedOptions = parcel.readBundle();

        if (mAdvancedOptions != null) {
            Preconditions.checkArgument(!mAdvancedOptions.containsKey(null));
        }
    }

    /**
     * Gets the unique print job id.
     *
     * @return The id.
     */
    public @Nullable PrintJobId getId() {
        return mId;
    }

    /**
     * Sets the unique print job id.
     *
     * @param id The job id.
     *
     * @hide
     */
    public void setId(@NonNull PrintJobId id) {
        this.mId = id;
    }

    /**
     * Gets the human readable job label.
     *
     * @return The label.
     */
    public @NonNull String getLabel() {
        return mLabel;
    }

    /**
     * Sets the human readable job label.
     *
     * @param label The label.
     *
     * @hide
     */
    public void setLabel(@NonNull String label) {
        mLabel = label;
    }

    /**
     * Gets the unique target printer id.
     *
     * @return The target printer id.
     */
    public @Nullable PrinterId getPrinterId() {
        return mPrinterId;
    }

    /**
     * Sets the unique target printer id.
     *
     * @param printerId The target printer id.
     *
     * @hide
     */
    public void setPrinterId(@NonNull PrinterId printerId) {
        mPrinterId = printerId;
    }

    /**
     * Gets the name of the target printer.
     *
     * @return The printer name.
     *
     * @hide
     */
    public @Nullable String getPrinterName() {
        return mPrinterName;
    }

    /**
     * Sets the name of the target printer.
     *
     * @param printerName The printer name.
     *
     * @hide
     */
    public void setPrinterName(@NonNull String printerName) {
        mPrinterName = printerName;
    }

    /**
     * Gets the current job state.
     *
     * @return The job state.
     *
     * @see #STATE_CREATED
     * @see #STATE_QUEUED
     * @see #STATE_STARTED
     * @see #STATE_COMPLETED
     * @see #STATE_BLOCKED
     * @see #STATE_FAILED
     * @see #STATE_CANCELED
     */
    public @State int getState() {
        return mState;
    }

    /**
     * Sets the current job state.
     *
     * @param state The job state.
     *
     * @hide
     */
    public void setState(int state) {
        mState = state;
    }

    /**
     * Sets the progress of the print job.
     *
     * @param progress the progress of the job
     *
     * @hide
     */
    public void setProgress(@FloatRange(from=0.0, to=1.0) float progress) {
        Preconditions.checkArgumentInRange(progress, 0, 1, "progress");

        mProgress = progress;
    }

    /**
     * Sets the status of the print job.
     *
     * @param status the status of the job, can be null
     *
     * @hide
     */
    public void setStatus(@Nullable CharSequence status) {
        mStatusRes = 0;
        mStatusResAppPackageName = null;

        mStatus = status;
    }

    /**
     * Sets the status of the print job.
     *
     * @param status The new status as a string resource
     * @param appPackageName App package name the resource belongs to
     *
     * @hide
     */
    public void setStatus(@StringRes int status, @NonNull CharSequence appPackageName) {
        mStatus = null;

        mStatusRes = status;
        mStatusResAppPackageName = appPackageName;
    }

    /**
     * Sets the owning application id.
     *
     * @return The owning app id.
     *
     * @hide
     */
    public int getAppId() {
        return mAppId;
    }

    /**
     * Sets the owning application id.
     *
     * @param appId The owning app id.
     *
     * @hide
     */
    public void setAppId(int appId) {
        mAppId = appId;
    }

    /**
     * Gets the optional tag assigned by a print service.
     *
     * @return The tag.
     *
     * @hide
     */
    public String getTag() {
        return mTag;
    }

    /**
     * Sets the optional tag assigned by a print service.
     *
     * @param tag The tag.
     *
     * @hide
     */
    public void setTag(String tag) {
        mTag = tag;
    }

    /**
     * Gets the wall time in millisecond when this print job was created.
     *
     * @return The creation time in milliseconds.
     */
    public long getCreationTime() {
        return mCreationTime;
    }

    /**
     * Sets the wall time in milliseconds when this print job was created.
     *
     * @param creationTime The creation time in milliseconds.
     *
     * @hide
     */
    public void setCreationTime(long creationTime) {
        if (creationTime < 0) {
            throw new IllegalArgumentException("creationTime must be non-negative.");
        }
        mCreationTime = creationTime;
    }

    /**
     * Gets the number of copies.
     *
     * @return The number of copies or zero if not set.
     */
    public @IntRange(from = 0) int getCopies() {
        return mCopies;
    }

    /**
     * Sets the number of copies.
     *
     * @param copyCount The number of copies.
     *
     * @hide
     */
    public void setCopies(int copyCount) {
        if (copyCount < 1) {
            throw new IllegalArgumentException("Copies must be more than one.");
        }
        mCopies = copyCount;
    }

    /**
     * Gets the included pages.
     *
     * @return The included pages or <code>null</code> if not set.
     */
    public @Nullable PageRange[] getPages() {
        return mPageRanges;
    }

    /**
     * Sets the included pages.
     *
     * @param pageRanges The included pages.
     *
     * @hide
     */
    public void setPages(PageRange[] pageRanges) {
        mPageRanges = pageRanges;
    }

    /**
     * Gets the print job attributes.
     *
     * @return The attributes.
     */
    public @NonNull PrintAttributes getAttributes() {
        return mAttributes;
    }

    /**
     * Sets the print job attributes.
     *
     * @param attributes The attributes.
     *
     * @hide
     */
    public void setAttributes(PrintAttributes attributes) {
        mAttributes = attributes;
    }

    /**
     * Gets the info describing the printed document.
     *
     * @return The document info.
     *
     * @hide
     */
    public PrintDocumentInfo getDocumentInfo() {
        return mDocumentInfo;
    }

    /**
     * Sets the info describing the printed document.
     *
     * @param info The document info.
     *
     * @hide
     */
    public void setDocumentInfo(PrintDocumentInfo info) {
        mDocumentInfo = info;
    }

    /**
     * Gets whether this print is being cancelled.
     *
     * @return True if the print job is being cancelled.
     *
     * @hide
     */
    public boolean isCancelling() {
        return mCanceling;
    }

    /**
     * Sets whether this print is being cancelled.
     *
     * @param cancelling True if the print job is being cancelled.
     *
     * @hide
     */
    public void setCancelling(boolean cancelling) {
        mCanceling = cancelling;
    }

    /**
     * Gets whether this job has a given advanced (printer specific) print
     * option.
     *
     * @param key The option key.
     * @return Whether the option is present.
     *
     * @hide
     */
    public boolean hasAdvancedOption(String key) {
        return mAdvancedOptions != null && mAdvancedOptions.containsKey(key);
    }

    /**
     * Gets the value of an advanced (printer specific) print option.
     *
     * @param key The option key.
     * @return The option value.
     *
     * @hide
     */
    public String getAdvancedStringOption(String key) {
        if (mAdvancedOptions != null) {
            return mAdvancedOptions.getString(key);
        }
        return null;
    }

    /**
     * Gets the value of an advanced (printer specific) print option.
     *
     * @param key The option key.
     * @return The option value.
     *
     * @hide
     */
    public int getAdvancedIntOption(String key) {
        if (mAdvancedOptions != null) {
            return mAdvancedOptions.getInt(key);
        }
        return 0;
    }

    /**
     * Gets the advanced options.
     *
     * @return The advanced options.
     *
     * @hide
     */
    public Bundle getAdvancedOptions() {
        return mAdvancedOptions;
    }

    /**
     * Sets the advanced options.
     *
     * @param options The advanced options.
     *
     * @hide
     */
    public void setAdvancedOptions(Bundle options) {
        mAdvancedOptions = options;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mId, flags);
        parcel.writeString(mLabel);
        parcel.writeParcelable(mPrinterId, flags);
        parcel.writeString(mPrinterName);
        parcel.writeInt(mState);
        parcel.writeInt(mAppId);
        parcel.writeString(mTag);
        parcel.writeLong(mCreationTime);
        parcel.writeInt(mCopies);
        parcel.writeParcelableArray(mPageRanges, flags);
        parcel.writeParcelable(mAttributes, flags);
        parcel.writeParcelable(mDocumentInfo, 0);
        parcel.writeFloat(mProgress);
        parcel.writeCharSequence(mStatus);
        parcel.writeInt(mStatusRes);
        parcel.writeCharSequence(mStatusResAppPackageName);
        parcel.writeInt(mCanceling ? 1 : 0);
        parcel.writeBundle(mAdvancedOptions);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("PrintJobInfo{");
        builder.append("label: ").append(mLabel);
        builder.append(", id: ").append(mId);
        builder.append(", state: ").append(stateToString(mState));
        builder.append(", printer: " + mPrinterId);
        builder.append(", tag: ").append(mTag);
        builder.append(", creationTime: " + mCreationTime);
        builder.append(", copies: ").append(mCopies);
        builder.append(", attributes: " + (mAttributes != null
                ? mAttributes.toString() : null));
        builder.append(", documentInfo: " + (mDocumentInfo != null
                ? mDocumentInfo.toString() : null));
        builder.append(", cancelling: " + mCanceling);
        builder.append(", pages: " + (mPageRanges != null
                ? Arrays.toString(mPageRanges) : null));
        builder.append(", hasAdvancedOptions: " + (mAdvancedOptions != null));
        builder.append(", progress: " + mProgress);
        builder.append(", status: " + (mStatus != null
                ? mStatus.toString() : null));
        builder.append(", statusRes: " + mStatusRes);
        builder.append(", statusResAppPackageName: " + (mStatusResAppPackageName != null
                ? mStatusResAppPackageName.toString() : null));
        builder.append("}");
        return builder.toString();
    }

    /** @hide */
    public static String stateToString(int state) {
        switch (state) {
            case STATE_CREATED: {
                return "STATE_CREATED";
            }
            case STATE_QUEUED: {
                return "STATE_QUEUED";
            }
            case STATE_STARTED: {
                return "STATE_STARTED";
            }
            case STATE_BLOCKED: {
                return "STATE_BLOCKED";
            }
            case STATE_FAILED: {
                return "STATE_FAILED";
            }
            case STATE_COMPLETED: {
                return "STATE_COMPLETED";
            }
            case STATE_CANCELED: {
                return "STATE_CANCELED";
            }
            default: {
                return "STATE_UNKNOWN";
            }
        }
    }

    /**
     * Get the progress that has been made printing this job.
     *
     * @return the print progress or -1 if not set
     * @hide
     */
    @TestApi
    public float getProgress() {
        return mProgress;
    }

    /**
     * Get the status of this job.
     *
     * @param pm Package manager used to resolve the string
     *
     * @return the status of this job or null if not set
     * @hide
     */
    @TestApi
    public @Nullable CharSequence getStatus(@NonNull PackageManager pm) {
        if (mStatusRes == 0) {
            return mStatus;
        } else {
            try {
                return pm.getResourcesForApplication(mStatusResAppPackageName.toString())
                        .getString(mStatusRes);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
                return null;
            }
        }
    }

    /**
     * Builder for creating a {@link PrintJobInfo}.
     */
    public static final class Builder {
        private final PrintJobInfo mPrototype;

        /**
         * Constructor.
         *
         * @param prototype Prototype to use as a starting point.
         * Can be <code>null</code>.
         */
        public Builder(@Nullable PrintJobInfo prototype) {
            mPrototype = (prototype != null)
                    ? new PrintJobInfo(prototype)
                    : new PrintJobInfo();
        }

        /**
         * Sets the number of copies.
         *
         * @param copies The number of copies.
         */
        public void setCopies(@IntRange(from = 1) int copies) {
            mPrototype.mCopies = copies;
        }

        /**
         * Sets the print job attributes.
         *
         * @param attributes The attributes.
         */
        public void setAttributes(@NonNull PrintAttributes attributes) {
            mPrototype.mAttributes = attributes;
        }

        /**
         * Sets the included pages.
         *
         * @param pages The included pages.
         */
        public void setPages(@NonNull PageRange[] pages) {
            mPrototype.mPageRanges = pages;
        }

        /**
         * Sets the progress of the print job.
         *
         * @param progress the progress of the job
         * @hide
         */
        public void setProgress(@FloatRange(from=0.0, to=1.0) float progress) {
            Preconditions.checkArgumentInRange(progress, 0, 1, "progress");

            mPrototype.mProgress = progress;
        }

        /**
         * Sets the status of the print job.
         *
         * @param status the status of the job, can be null
         * @hide
         */
        public void setStatus(@Nullable CharSequence status) {
            mPrototype.mStatus = status;
        }

        /**
         * Puts an advanced (printer specific) option.
         *
         * @param key The option key.
         * @param value The option value.
         */
        public void putAdvancedOption(@NonNull String key, @Nullable String value) {
            Preconditions.checkNotNull(key, "key cannot be null");

            if (mPrototype.mAdvancedOptions == null) {
                mPrototype.mAdvancedOptions = new Bundle();
            }
            mPrototype.mAdvancedOptions.putString(key, value);
        }

        /**
         * Puts an advanced (printer specific) option.
         *
         * @param key The option key.
         * @param value The option value.
         */
        public void putAdvancedOption(@NonNull String key, int value) {
            if (mPrototype.mAdvancedOptions == null) {
                mPrototype.mAdvancedOptions = new Bundle();
            }
            mPrototype.mAdvancedOptions.putInt(key, value);
        }

        /**
         * Creates a new {@link PrintJobInfo} instance.
         *
         * @return The new instance.
         */
        public @NonNull PrintJobInfo build() {
            return mPrototype;
        }
    }

    public static final Parcelable.Creator<PrintJobInfo> CREATOR =
            new Creator<PrintJobInfo>() {
        @Override
        public PrintJobInfo createFromParcel(Parcel parcel) {
            return new PrintJobInfo(parcel);
        }

        @Override
        public PrintJobInfo[] newArray(int size) {
            return new PrintJobInfo[size];
        }
    };
}
