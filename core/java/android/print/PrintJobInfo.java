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

import java.util.Arrays;

/**
 * This class represents the description of a print job.
 */
public final class PrintJobInfo implements Parcelable {

    /** Undefined print job id. */
    public static final int PRINT_JOB_ID_UNDEFINED = -1;

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
     * Print job state: The print job is being created but not yet
     * ready to be printed.
     * <p>
     * Next valid states: {@link #STATE_QUEUED}
     * </p>
     *
     * @hide
     */
    public static final int STATE_CREATED = 1;

    /**
     * Print job status: The print jobs is created, it is ready
     * to be printed and should be processed.
     * <p>
     * Next valid states: {@link #STATE_STARTED}, {@link #STATE_FAILED},
     * {@link #STATE_CANCELED}
     * </p>
     */
    public static final int STATE_QUEUED = 2;

    /**
     * Print job status: The print job is being printed.
     * <p>
     * Next valid states: {@link #STATE_COMPLETED}, {@link #STATE_FAILED},
     * {@link #STATE_CANCELED}
     * </p>
     */
    public static final int STATE_STARTED = 3;

    /**
     * Print job status: The print job was successfully printed.
     * This is a terminal state.
     * <p>
     * Next valid states: None
     * </p>
     */
    public static final int STATE_COMPLETED = 4;

    /**
     * Print job status: The print job was printing but printing failed.
     * This is a terminal state.
     * <p>
     * Next valid states: None
     * </p>
     */
    public static final int STATE_FAILED = 5;

    /**
     * Print job status: The print job was canceled.
     * This is a terminal state.
     * <p>
     * Next valid states: None
     * </p>
     */
    public static final int STATE_CANCELED = 6;

    /** The unique print job id. */
    private int mId;

    /** The human readable print job label. */
    private CharSequence mLabel;

    /** The unique id of the printer. */
    private PrinterId mPrinterId;

    /** The status of the print job. */
    private int mState;

    /** The id of the app that created the job. */
    private int mAppId;

    /** The id of the user that created the job. */
    private int mUserId;

    /** Optional tag assigned by a print service.*/
    private String mTag;

    /** How many copies to print. */
    private int mCopies;

    /** Failure reason if this job failed. */
    private CharSequence mFailureReason;

    /** The pages to print */
    private PageRange[] mPageRanges;

    /** The print job attributes size. */
    private PrintAttributes mAttributes;

    /** Information about the printed document. */
    private PrintDocumentInfo mDocumentInfo;

    /** @hide*/
    public PrintJobInfo() {
        /* do nothing */
    }

    /** @hide */
    public PrintJobInfo(PrintJobInfo other) {
        mId = other.mId;
        mLabel = other.mLabel;
        mPrinterId = other.mPrinterId;
        mState = other.mState;
        mAppId = other.mAppId;
        mUserId = other.mUserId;
        mTag = other.mTag;
        mCopies = other.mCopies;
        mFailureReason = other.mFailureReason;
        mPageRanges = other.mPageRanges;
        mAttributes = other.mAttributes;
        mDocumentInfo = other.mDocumentInfo;
    }

    private PrintJobInfo(Parcel parcel) {
        mId = parcel.readInt();
        mLabel = parcel.readCharSequence();
        mPrinterId = parcel.readParcelable(null);
        mState = parcel.readInt();
        mAppId = parcel.readInt();
        mUserId = parcel.readInt();
        mTag = parcel.readString();
        mCopies = parcel.readInt();
        if (parcel.readInt() == 1) {
            mFailureReason = parcel.readCharSequence();
        }
        if (parcel.readInt() == 1) {
            Parcelable[] parcelables = parcel.readParcelableArray(null);
            mPageRanges = new PageRange[parcelables.length];
            for (int i = 0; i < parcelables.length; i++) {
                mPageRanges[i] = (PageRange) parcelables[i];
            }
        }
        if (parcel.readInt() == 1) {
            mAttributes = PrintAttributes.CREATOR.createFromParcel(parcel);
        }
        if (parcel.readInt() == 1) {
            mDocumentInfo = PrintDocumentInfo.CREATOR.createFromParcel(parcel);
        }
    }

    /**
     * Gets the unique print job id.
     *
     * @return The id.
     */
    public int getId() {
        return mId;
    }

    /**
     * Sets the unique print job id.
     *
     * @param The job id.
     *
     * @hide
     */
    public void setId(int id) {
        this.mId = id;
    }

    /**
     * Gets the human readable job label.
     *
     * @return The label.
     */
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * Sets the human readable job label.
     *
     * @param label The label.
     *
     * @hide
     */
    public void setLabel(CharSequence label) {
        mLabel = label;
    }

    /**
     * Gets the unique target printer id.
     *
     * @return The target printer id.
     */
    public PrinterId getPrinterId() {
        return mPrinterId;
    }

    /**
     * Sets the unique target pritner id.
     *
     * @param printerId The target printer id.
     *
     * @hide
     */
    public void setPrinterId(PrinterId printerId) {
        mPrinterId = printerId;
    }

    /**
     * Gets the current job state.
     *
     * @return The job state.
     */
    public int getState() {
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
     * Gets the owning user id.
     *
     * @return The user id.
     *
     * @hide
     */
    public int getUserId() {
        return mUserId;
    }

    /**
     * Sets the owning user id.
     *
     * @param userId The user id.
     *
     * @hide
     */
    public void setUserId(int userId) {
        mUserId = userId;
    }

    /**
     * Gets the optional tag assigned by a print service.
     *
     * @return The tag.
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
     * Gets the number of copies.
     *
     * @return The number of copies or zero if not set.
     */
    public int getCopies() {
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
     * The failure reason if this print job failed.
     *
     * @return The failure reason.
     *
     * @hide
     */
    public CharSequence getFailureReason() {
        return mFailureReason;
    }

    /**
     * The failure reason if this print job failed.
     *
     * @param failureReason The failure reason.
     *
     * @hide
     */
    public void setFailureReason(CharSequence failureReason) {
        mFailureReason = failureReason;
    }

    /**
     * Gets the included pages.
     *
     * @return The included pages or <code>null</code> if not set.
     */
    public PageRange[] getPages() {
        return mPageRanges;
    }

    /**
     * Sets the included pages.
     *
     * @return The included pages.
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
    public PrintAttributes getAttributes() {
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

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mId);
        parcel.writeCharSequence(mLabel);
        parcel.writeParcelable(mPrinterId, flags);
        parcel.writeInt(mState);
        parcel.writeInt(mAppId);
        parcel.writeInt(mUserId);
        parcel.writeString(mTag);
        parcel.writeInt(mCopies);
        if (mFailureReason != null) {
            parcel.writeInt(1);
            parcel.writeCharSequence(mFailureReason);
        } else {
            parcel.writeInt(0);
        }
        if (mPageRanges != null) {
            parcel.writeInt(1);
            parcel.writeParcelableArray(mPageRanges, flags);
        } else {
            parcel.writeInt(0);
        }
        if (mAttributes != null) {
            parcel.writeInt(1);
            mAttributes.writeToParcel(parcel, flags);
        } else {
            parcel.writeInt(0);
        }
        if (mDocumentInfo != null) {
            parcel.writeInt(1);
            mDocumentInfo.writeToParcel(parcel, flags);
        } else {
            parcel.writeInt(0);
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("PrintJobInfo{");
        builder.append("label: ").append(mLabel);
        builder.append(", id: ").append(mId);
        builder.append(", status: ").append(stateToString(mState));
        builder.append(", printer: " + mPrinterId);
        builder.append(", tag: ").append(mTag);
        builder.append(", copies: ").append(mCopies);
        builder.append(", attributes: " + (mAttributes != null
                ? mAttributes.toString() : null));
        builder.append(", documentInfo: " + (mDocumentInfo != null
                ? mDocumentInfo.toString() : null));
        builder.append(", pages: " + (mPageRanges != null
                ? Arrays.toString(mPageRanges) : null));
        builder.append("}");
        return builder.toString();
    }

    /** @hide */
    public static String stateToString(int state) {
        switch (state) {
            case STATE_CREATED: {
                return "STATUS_CREATED";
            }
            case STATE_QUEUED: {
                return "STATE_QUEUED";
            }
            case STATE_STARTED: {
                return "STATE_STARTED";
            }
            case STATE_FAILED: {
                return "STATUS_FAILED";
            }
            case STATE_COMPLETED: {
                return "STATUS_COMPLETED";
            }
            case STATE_CANCELED: {
                return "STATUS_CANCELED";
            }
            default: {
                return "STATUS_UNKNOWN";
            }
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
