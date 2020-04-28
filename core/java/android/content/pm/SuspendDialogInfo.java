/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.content.pm;

import static android.content.res.Resources.ID_NULL;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.SystemApi;
import android.content.res.ResourceId;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.util.Slog;

import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/**
 * A container to describe the dialog to be shown when the user tries to launch a suspended
 * application.
 * The suspending app can customize the dialog's following attributes:
 * <ul>
 * <li>The dialog icon, by providing a resource id.
 * <li>The title text, by providing a resource id.
 * <li>The text of the dialog's body, by providing a resource id or a string.
 * <li>The text on the neutral button which starts the
 * {@link android.content.Intent#ACTION_SHOW_SUSPENDED_APP_DETAILS SHOW_SUSPENDED_APP_DETAILS}
 * activity, by providing a resource id.
 * </ul>
 * System defaults are used whenever any of these are not provided, or any of the provided resource
 * ids cannot be resolved at the time of displaying the dialog.
 *
 * @hide
 * @see PackageManager#setPackagesSuspended(String[], boolean, PersistableBundle, PersistableBundle,
 * SuspendDialogInfo)
 * @see Builder
 */
@SystemApi
public final class SuspendDialogInfo implements Parcelable {
    private static final String TAG = SuspendDialogInfo.class.getSimpleName();
    private static final String XML_ATTR_ICON_RES_ID = "iconResId";
    private static final String XML_ATTR_TITLE_RES_ID = "titleResId";
    private static final String XML_ATTR_DIALOG_MESSAGE_RES_ID = "dialogMessageResId";
    private static final String XML_ATTR_DIALOG_MESSAGE = "dialogMessage";
    private static final String XML_ATTR_BUTTON_TEXT_RES_ID = "buttonTextResId";

    private final int mIconResId;
    private final int mTitleResId;
    private final int mDialogMessageResId;
    private final String mDialogMessage;
    private final int mNeutralButtonTextResId;

    /**
     * @return the resource id of the icon to be used with the dialog
     * @hide
     */
    @DrawableRes
    public int getIconResId() {
        return mIconResId;
    }

    /**
     * @return the resource id of the title to be used with the dialog
     * @hide
     */
    @StringRes
    public int getTitleResId() {
        return mTitleResId;
    }

    /**
     * @return the resource id of the text to be shown in the dialog's body
     * @hide
     */
    @StringRes
    public int getDialogMessageResId() {
        return mDialogMessageResId;
    }

    /**
     * @return the text to be shown in the dialog's body. Returns {@code null} if
     * {@link #getDialogMessageResId()} returns a valid resource id.
     * @hide
     */
    @Nullable
    public String getDialogMessage() {
        return mDialogMessage;
    }

    /**
     * @return the text to be shown
     * @hide
     */
    @StringRes
    public int getNeutralButtonTextResId() {
        return mNeutralButtonTextResId;
    }

    /**
     * @hide
     */
    public void saveToXml(XmlSerializer out) throws IOException {
        if (mIconResId != ID_NULL) {
            XmlUtils.writeIntAttribute(out, XML_ATTR_ICON_RES_ID, mIconResId);
        }
        if (mTitleResId != ID_NULL) {
            XmlUtils.writeIntAttribute(out, XML_ATTR_TITLE_RES_ID, mTitleResId);
        }
        if (mDialogMessageResId != ID_NULL) {
            XmlUtils.writeIntAttribute(out, XML_ATTR_DIALOG_MESSAGE_RES_ID, mDialogMessageResId);
        } else {
            XmlUtils.writeStringAttribute(out, XML_ATTR_DIALOG_MESSAGE, mDialogMessage);
        }
        if (mNeutralButtonTextResId != ID_NULL) {
            XmlUtils.writeIntAttribute(out, XML_ATTR_BUTTON_TEXT_RES_ID, mNeutralButtonTextResId);
        }
    }

    /**
     * @hide
     */
    public static SuspendDialogInfo restoreFromXml(XmlPullParser in) {
        final SuspendDialogInfo.Builder dialogInfoBuilder = new SuspendDialogInfo.Builder();
        try {
            final int iconId = XmlUtils.readIntAttribute(in, XML_ATTR_ICON_RES_ID, ID_NULL);
            final int titleId = XmlUtils.readIntAttribute(in, XML_ATTR_TITLE_RES_ID, ID_NULL);
            final int buttonTextId = XmlUtils.readIntAttribute(in, XML_ATTR_BUTTON_TEXT_RES_ID,
                    ID_NULL);
            final int dialogMessageResId = XmlUtils.readIntAttribute(
                    in, XML_ATTR_DIALOG_MESSAGE_RES_ID, ID_NULL);
            final String dialogMessage = XmlUtils.readStringAttribute(in, XML_ATTR_DIALOG_MESSAGE);

            if (iconId != ID_NULL) {
                dialogInfoBuilder.setIcon(iconId);
            }
            if (titleId != ID_NULL) {
                dialogInfoBuilder.setTitle(titleId);
            }
            if (buttonTextId != ID_NULL) {
                dialogInfoBuilder.setNeutralButtonText(buttonTextId);
            }
            if (dialogMessageResId != ID_NULL) {
                dialogInfoBuilder.setMessage(dialogMessageResId);
            } else if (dialogMessage != null) {
                dialogInfoBuilder.setMessage(dialogMessage);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Exception while parsing from xml. Some fields may default", e);
        }
        return dialogInfoBuilder.build();
    }

    @Override
    public int hashCode() {
        int hashCode = mIconResId;
        hashCode = 31 * hashCode + mTitleResId;
        hashCode = 31 * hashCode + mNeutralButtonTextResId;
        hashCode = 31 * hashCode + mDialogMessageResId;
        hashCode = 31 * hashCode + Objects.hashCode(mDialogMessage);
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SuspendDialogInfo)) {
            return false;
        }
        final SuspendDialogInfo otherDialogInfo = (SuspendDialogInfo) obj;
        return mIconResId == otherDialogInfo.mIconResId
                && mTitleResId == otherDialogInfo.mTitleResId
                && mDialogMessageResId == otherDialogInfo.mDialogMessageResId
                && mNeutralButtonTextResId == otherDialogInfo.mNeutralButtonTextResId
                && Objects.equals(mDialogMessage, otherDialogInfo.mDialogMessage);
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("SuspendDialogInfo: {");
        if (mIconResId != ID_NULL) {
            builder.append("mIconId = 0x");
            builder.append(Integer.toHexString(mIconResId));
            builder.append(" ");
        }
        if (mTitleResId != ID_NULL) {
            builder.append("mTitleResId = 0x");
            builder.append(Integer.toHexString(mTitleResId));
            builder.append(" ");
        }
        if (mNeutralButtonTextResId != ID_NULL) {
            builder.append("mNeutralButtonTextResId = 0x");
            builder.append(Integer.toHexString(mNeutralButtonTextResId));
            builder.append(" ");
        }
        if (mDialogMessageResId != ID_NULL) {
            builder.append("mDialogMessageResId = 0x");
            builder.append(Integer.toHexString(mDialogMessageResId));
            builder.append(" ");
        } else if (mDialogMessage != null) {
            builder.append("mDialogMessage = \"");
            builder.append(mDialogMessage);
            builder.append("\" ");
        }
        builder.append("}");
        return builder.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(mIconResId);
        dest.writeInt(mTitleResId);
        dest.writeInt(mDialogMessageResId);
        dest.writeString(mDialogMessage);
        dest.writeInt(mNeutralButtonTextResId);
    }

    private SuspendDialogInfo(Parcel source) {
        mIconResId = source.readInt();
        mTitleResId = source.readInt();
        mDialogMessageResId = source.readInt();
        mDialogMessage = source.readString();
        mNeutralButtonTextResId = source.readInt();
    }

    SuspendDialogInfo(Builder b) {
        mIconResId = b.mIconResId;
        mTitleResId = b.mTitleResId;
        mDialogMessageResId = b.mDialogMessageResId;
        mDialogMessage = (mDialogMessageResId == ID_NULL) ? b.mDialogMessage : null;
        mNeutralButtonTextResId = b.mNeutralButtonTextResId;
    }

    public static final @android.annotation.NonNull Creator<SuspendDialogInfo> CREATOR = new Creator<SuspendDialogInfo>() {
        @Override
        public SuspendDialogInfo createFromParcel(Parcel source) {
            return new SuspendDialogInfo(source);
        }

        @Override
        public SuspendDialogInfo[] newArray(int size) {
            return new SuspendDialogInfo[size];
        }
    };

    /**
     * Builder to build a {@link SuspendDialogInfo} object.
     */
    public static final class Builder {
        private int mDialogMessageResId = ID_NULL;
        private String mDialogMessage;
        private int mTitleResId = ID_NULL;
        private int mIconResId = ID_NULL;
        private int mNeutralButtonTextResId = ID_NULL;

        /**
         * Set the resource id of the icon to be used. If not provided, no icon will be shown.
         *
         * @param resId The resource id of the icon.
         * @return this builder object.
         */
        @NonNull
        public Builder setIcon(@DrawableRes int resId) {
            Preconditions.checkArgument(ResourceId.isValid(resId), "Invalid resource id provided");
            mIconResId = resId;
            return this;
        }

        /**
         * Set the resource id of the title text to be displayed. If this is not provided, the
         * system will use a default title.
         *
         * @param resId The resource id of the title.
         * @return this builder object.
         */
        @NonNull
        public Builder setTitle(@StringRes int resId) {
            Preconditions.checkArgument(ResourceId.isValid(resId), "Invalid resource id provided");
            mTitleResId = resId;
            return this;
        }

        /**
         * Set the text to show in the body of the dialog. Ignored if a resource id is set via
         * {@link #setMessage(int)}.
         * <p>
         * The system will use {@link String#format(Locale, String, Object...) String.format} to
         * insert the suspended app name into the message, so an example format string could be
         * {@code "The app %1$s is currently suspended"}. This is optional - if the string passed in
         * {@code message} does not accept an argument, it will be used as is.
         *
         * @param message The dialog message.
         * @return this builder object.
         * @see #setMessage(int)
         */
        @NonNull
        public Builder setMessage(@NonNull String message) {
            Preconditions.checkStringNotEmpty(message, "Message cannot be null or empty");
            mDialogMessage = message;
            return this;
        }

        /**
         * Set the resource id of the dialog message to be shown. If no dialog message is provided
         * via either this method or {@link #setMessage(String)}, the system will use a
         * default message.
         * <p>
         * The system will use {@link android.content.res.Resources#getString(int, Object...)
         * getString} to insert the suspended app name into the message, so an example format string
         * could be {@code "The app %1$s is currently suspended"}. This is optional - if the string
         * referred to by {@code resId} does not accept an argument, it will be used as is.
         *
         * @param resId The resource id of the dialog message.
         * @return this builder object.
         * @see #setMessage(String)
         */
        @NonNull
        public Builder setMessage(@StringRes int resId) {
            Preconditions.checkArgument(ResourceId.isValid(resId), "Invalid resource id provided");
            mDialogMessageResId = resId;
            return this;
        }

        /**
         * Set the resource id of text to be shown on the neutral button. Tapping this button starts
         * the {@link android.content.Intent#ACTION_SHOW_SUSPENDED_APP_DETAILS} activity. If this is
         * not provided, the system will use a default text.
         *
         * @param resId The resource id of the button text
         * @return this builder object.
         */
        @NonNull
        public Builder setNeutralButtonText(@StringRes int resId) {
            Preconditions.checkArgument(ResourceId.isValid(resId), "Invalid resource id provided");
            mNeutralButtonTextResId = resId;
            return this;
        }

        /**
         * Build the final object based on given inputs.
         *
         * @return The {@link SuspendDialogInfo} object built using this builder.
         */
        @NonNull
        public SuspendDialogInfo build() {
            return new SuspendDialogInfo(this);
        }
    }
}
