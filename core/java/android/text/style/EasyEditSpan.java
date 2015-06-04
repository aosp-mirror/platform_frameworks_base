/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.text.style;

import android.app.PendingIntent;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextUtils;
import android.widget.TextView;

/**
 * Provides an easy way to edit a portion of text.
 * <p>
 * The {@link TextView} uses this span to allow the user to delete a chuck of text in one click.
 * <p>
 * {@link TextView} removes the span when the user deletes the whole text or modifies it.
 * <p>
 * This span can be also used to receive notification when the user deletes or modifies the text;
 */
public class EasyEditSpan implements ParcelableSpan {

    /**
     * The extra key field in the pending intent that describes how the text changed.
     *
     * @see #TEXT_DELETED
     * @see #TEXT_MODIFIED
     * @see #getPendingIntent()
     */
    public static final String EXTRA_TEXT_CHANGED_TYPE =
            "android.text.style.EXTRA_TEXT_CHANGED_TYPE";

    /**
     * The value of {@link #EXTRA_TEXT_CHANGED_TYPE} when the text wrapped by this span is deleted.
     */
    public static final int TEXT_DELETED = 1;

    /**
     * The value of {@link #EXTRA_TEXT_CHANGED_TYPE} when the text wrapped by this span is modified.
     */
    public static final int TEXT_MODIFIED = 2;

    private final PendingIntent mPendingIntent;

    private boolean mDeleteEnabled;

    /**
     * Creates the span. No intent is sent when the wrapped text is modified or
     * deleted.
     */
    public EasyEditSpan() {
        mPendingIntent = null;
        mDeleteEnabled = true;
    }

    /**
     * @param pendingIntent The intent will be sent when the wrapped text is deleted or modified.
     *                      When the pending intent is sent, {@link #EXTRA_TEXT_CHANGED_TYPE} is
     *                      added in the intent to describe how the text changed.
     */
    public EasyEditSpan(PendingIntent pendingIntent) {
        mPendingIntent = pendingIntent;
        mDeleteEnabled = true;
    }

    /**
     * Constructor called from {@link TextUtils} to restore the span.
     */
    public EasyEditSpan(Parcel source) {
        mPendingIntent = source.readParcelable(null);
        mDeleteEnabled = (source.readByte() == 1);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    /** @hide */
    public void writeToParcelInternal(Parcel dest, int flags) {
        dest.writeParcelable(mPendingIntent, 0);
        dest.writeByte((byte) (mDeleteEnabled ? 1 : 0));
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    public int getSpanTypeIdInternal() {
        return TextUtils.EASY_EDIT_SPAN;
    }

    /**
     * @return True if the {@link TextView} should offer the ability to delete the text.
     *
     * @hide
     */
    public boolean isDeleteEnabled() {
        return mDeleteEnabled;
    }

    /**
     * Enables or disables the deletion of the text.
     *
     * @hide
     */
    public void setDeleteEnabled(boolean value) {
        mDeleteEnabled = value;
    }

    /**
     * @return the pending intent to send when the wrapped text is deleted or modified.
     *
     * @hide
     */
    public PendingIntent getPendingIntent() {
        return mPendingIntent;
    }
}
