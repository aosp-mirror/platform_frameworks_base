/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.smartspace.uitemplatedata;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.smartspace.SmartspaceTarget.UiTemplateType;
import android.app.smartspace.SmartspaceUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Holds all the relevant data needed to render a Smartspace card with the default Ui Template.
 *
 * @hide
 */
@SystemApi
@SuppressLint("ParcelNotFinal")
public class SmartspaceDefaultUiTemplateData implements Parcelable {

    /**
     * {@link UiTemplateType} indicating the template type of this template data.
     *
     * @see UiTemplateType
     */
    @UiTemplateType
    private final int mTemplateType;

    /**
     * Title text and title icon are shown at the first row. When both are absent, the date view
     * will be used, which has its own tap action applied to the title area.
     */
    @Nullable
    private final CharSequence mTitleText;

    @Nullable
    private final SmartspaceIcon mTitleIcon;

    /** Subtitle text and icon are shown at the second row. */
    @Nullable
    private final CharSequence mSubtitleText;

    @Nullable
    private final SmartspaceIcon mSubTitleIcon;

    /**
     * Primary tap action for the entire card, including the blank spaces, except: 1. When title is
     * absent, the date view's default tap action is used; 2. Supplemental subtitle uses its own tap
     * action if being set; 3. Secondary card uses its own tap action if being set.
     */
    @Nullable
    private final SmartspaceTapAction mPrimaryTapAction;

    /**
     * Supplemental subtitle text and icon are shown at the second row following the subtitle text.
     * Mainly used for weather info on non-weather card.
     */
    @Nullable
    private final CharSequence mSupplementalSubtitleText;

    @Nullable
    private final SmartspaceIcon mSupplementalSubtitleIcon;

    /**
     * Tap action for the supplemental subtitle's text and icon. Will use the primary tap action if
     * not being set.
     */
    @Nullable
    private final SmartspaceTapAction mSupplementalSubtitleTapAction;

    /**
     * Supplemental alarm text is specifically used for holiday alarm, which is appended to "next
     * alarm".
     */
    @Nullable
    private final CharSequence mSupplementalAlarmText;

    SmartspaceDefaultUiTemplateData(@NonNull Parcel in) {
        mTemplateType = in.readInt();
        mTitleText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mTitleIcon = in.readTypedObject(SmartspaceIcon.CREATOR);
        mSubtitleText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mSubTitleIcon = in.readTypedObject(SmartspaceIcon.CREATOR);
        mPrimaryTapAction = in.readTypedObject(SmartspaceTapAction.CREATOR);
        mSupplementalSubtitleText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mSupplementalSubtitleIcon = in.readTypedObject(SmartspaceIcon.CREATOR);
        mSupplementalSubtitleTapAction = in.readTypedObject(SmartspaceTapAction.CREATOR);
        mSupplementalAlarmText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
    }

    /**
     * Should ONLY used by subclasses. For the general instance creation, please use
     * SmartspaceDefaultUiTemplateData.Builder.
     */
    SmartspaceDefaultUiTemplateData(@UiTemplateType int templateType,
            @Nullable CharSequence titleText,
            @Nullable SmartspaceIcon titleIcon,
            @Nullable CharSequence subtitleText,
            @Nullable SmartspaceIcon subTitleIcon,
            @Nullable SmartspaceTapAction primaryTapAction,
            @Nullable CharSequence supplementalSubtitleText,
            @Nullable SmartspaceIcon supplementalSubtitleIcon,
            @Nullable SmartspaceTapAction supplementalSubtitleTapAction,
            @Nullable CharSequence supplementalAlarmText) {
        mTemplateType = templateType;
        mTitleText = titleText;
        mTitleIcon = titleIcon;
        mSubtitleText = subtitleText;
        mSubTitleIcon = subTitleIcon;
        mPrimaryTapAction = primaryTapAction;
        mSupplementalSubtitleText = supplementalSubtitleText;
        mSupplementalSubtitleIcon = supplementalSubtitleIcon;
        mSupplementalSubtitleTapAction = supplementalSubtitleTapAction;
        mSupplementalAlarmText = supplementalAlarmText;
    }

    @UiTemplateType
    public int getTemplateType() {
        return mTemplateType;
    }

    @Nullable
    public CharSequence getTitleText() {
        return mTitleText;
    }

    @Nullable
    public SmartspaceIcon getTitleIcon() {
        return mTitleIcon;
    }

    @Nullable
    public CharSequence getSubtitleText() {
        return mSubtitleText;
    }

    @Nullable
    public SmartspaceIcon getSubTitleIcon() {
        return mSubTitleIcon;
    }

    @Nullable
    public CharSequence getSupplementalSubtitleText() {
        return mSupplementalSubtitleText;
    }

    @Nullable
    public SmartspaceIcon getSupplementalSubtitleIcon() {
        return mSupplementalSubtitleIcon;
    }

    @Nullable
    public SmartspaceTapAction getPrimaryTapAction() {
        return mPrimaryTapAction;
    }

    @Nullable
    public SmartspaceTapAction getSupplementalSubtitleTapAction() {
        return mSupplementalSubtitleTapAction;
    }

    @Nullable
    public CharSequence getSupplementalAlarmText() {
        return mSupplementalAlarmText;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<SmartspaceDefaultUiTemplateData> CREATOR =
            new Creator<SmartspaceDefaultUiTemplateData>() {
                @Override
                public SmartspaceDefaultUiTemplateData createFromParcel(Parcel in) {
                    return new SmartspaceDefaultUiTemplateData(in);
                }

                @Override
                public SmartspaceDefaultUiTemplateData[] newArray(int size) {
                    return new SmartspaceDefaultUiTemplateData[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mTemplateType);
        TextUtils.writeToParcel(mTitleText, out, flags);
        out.writeTypedObject(mTitleIcon, flags);
        TextUtils.writeToParcel(mSubtitleText, out, flags);
        out.writeTypedObject(mSubTitleIcon, flags);
        out.writeTypedObject(mPrimaryTapAction, flags);
        TextUtils.writeToParcel(mSupplementalSubtitleText, out, flags);
        out.writeTypedObject(mSupplementalSubtitleIcon, flags);
        out.writeTypedObject(mSupplementalSubtitleTapAction, flags);
        TextUtils.writeToParcel(mSupplementalAlarmText, out, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SmartspaceDefaultUiTemplateData)) return false;
        SmartspaceDefaultUiTemplateData that = (SmartspaceDefaultUiTemplateData) o;
        return mTemplateType == that.mTemplateType && SmartspaceUtils.isEqual(mTitleText,
                that.mTitleText)
                && Objects.equals(mTitleIcon, that.mTitleIcon)
                && SmartspaceUtils.isEqual(mSubtitleText, that.mSubtitleText)
                && Objects.equals(mSubTitleIcon, that.mSubTitleIcon)
                && Objects.equals(mPrimaryTapAction, that.mPrimaryTapAction)
                && SmartspaceUtils.isEqual(mSupplementalSubtitleText,
                that.mSupplementalSubtitleText)
                && Objects.equals(mSupplementalSubtitleIcon, that.mSupplementalSubtitleIcon)
                && Objects.equals(mSupplementalSubtitleTapAction,
                that.mSupplementalSubtitleTapAction)
                && SmartspaceUtils.isEqual(mSupplementalAlarmText, that.mSupplementalAlarmText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTemplateType, mTitleText, mTitleIcon, mSubtitleText, mSubTitleIcon,
                mPrimaryTapAction, mSupplementalSubtitleText, mSupplementalSubtitleIcon,
                mSupplementalSubtitleTapAction, mSupplementalAlarmText);
    }

    @Override
    public String toString() {
        return "SmartspaceDefaultUiTemplateData{"
                + "mTemplateType=" + mTemplateType
                + ", mTitleText=" + mTitleText
                + ", mTitleIcon=" + mTitleIcon
                + ", mSubtitleText=" + mSubtitleText
                + ", mSubTitleIcon=" + mSubTitleIcon
                + ", mPrimaryTapAction=" + mPrimaryTapAction
                + ", mSupplementalSubtitleText=" + mSupplementalSubtitleText
                + ", mSupplementalSubtitleIcon=" + mSupplementalSubtitleIcon
                + ", mSupplementalSubtitleTapAction=" + mSupplementalSubtitleTapAction
                + ", mSupplementalAlarmText=" + mSupplementalAlarmText
                + '}';
    }

    /**
     * A builder for {@link SmartspaceDefaultUiTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("StaticFinalBuilder")
    public static class Builder {
        @UiTemplateType
        private final int mTemplateType;
        private CharSequence mTitleText;
        private SmartspaceIcon mTitleIcon;
        private CharSequence mSubtitleText;
        private SmartspaceIcon mSubTitleIcon;
        private SmartspaceTapAction mPrimaryTapAction;
        private CharSequence mSupplementalSubtitleText;
        private SmartspaceIcon mSupplementalSubtitleIcon;
        private SmartspaceTapAction mSupplementalSubtitleTapAction;
        private CharSequence mSupplementalAlarmText;

        /**
         * A builder for {@link SmartspaceDefaultUiTemplateData}.
         *
         * @param templateType the {@link UiTemplateType} of this template data.
         */
        public Builder(@UiTemplateType int templateType) {
            mTemplateType = templateType;
        }

        /** Should ONLY be used by the subclasses */
        @UiTemplateType
        @SuppressLint("GetterOnBuilder")
        int getTemplateType() {
            return mTemplateType;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        CharSequence getTitleText() {
            return mTitleText;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        SmartspaceIcon getTitleIcon() {
            return mTitleIcon;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        CharSequence getSubtitleText() {
            return mSubtitleText;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        SmartspaceIcon getSubTitleIcon() {
            return mSubTitleIcon;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        SmartspaceTapAction getPrimaryTapAction() {
            return mPrimaryTapAction;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        CharSequence getSupplementalSubtitleText() {
            return mSupplementalSubtitleText;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        SmartspaceIcon getSupplementalSubtitleIcon() {
            return mSupplementalSubtitleIcon;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        SmartspaceTapAction getSupplementalSubtitleTapAction() {
            return mSupplementalSubtitleTapAction;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        CharSequence getSupplementalAlarmText() {
            return mSupplementalAlarmText;
        }

        /**
         * Sets the card title.
         */
        @NonNull
        public Builder setTitleText(@NonNull CharSequence titleText) {
            mTitleText = titleText;
            return this;
        }

        /**
         * Sets the card title icon.
         */
        @NonNull
        public Builder setTitleIcon(@NonNull SmartspaceIcon titleIcon) {
            mTitleIcon = titleIcon;
            return this;
        }

        /**
         * Sets the card subtitle.
         */
        @NonNull
        public Builder setSubtitleText(@NonNull CharSequence subtitleText) {
            mSubtitleText = subtitleText;
            return this;
        }

        /**
         * Sets the card subtitle icon.
         */
        @NonNull
        public Builder setSubTitleIcon(@NonNull SmartspaceIcon subTitleIcon) {
            mSubTitleIcon = subTitleIcon;
            return this;
        }

        /**
         * Sets the card primary tap action.
         */
        @NonNull
        public Builder setPrimaryTapAction(@NonNull SmartspaceTapAction primaryTapAction) {
            mPrimaryTapAction = primaryTapAction;
            return this;
        }

        /**
         * Sets the supplemental subtitle text.
         */
        @NonNull
        public Builder setSupplementalSubtitleText(@NonNull CharSequence supplementalSubtitleText) {
            mSupplementalSubtitleText = supplementalSubtitleText;
            return this;
        }

        /**
         * Sets the supplemental subtitle icon.
         */
        @NonNull
        public Builder setSupplementalSubtitleIcon(
                @NonNull SmartspaceIcon supplementalSubtitleIcon) {
            mSupplementalSubtitleIcon = supplementalSubtitleIcon;
            return this;
        }

        /**
         * Sets the supplemental subtitle tap action. {@code mPrimaryTapAction} will be used if not
         * being
         * set.
         */
        @NonNull
        public Builder setSupplementalSubtitleTapAction(
                @NonNull SmartspaceTapAction supplementalSubtitleTapAction) {
            mSupplementalSubtitleTapAction = supplementalSubtitleTapAction;
            return this;
        }

        /**
         * Sets the supplemental alarm text.
         */
        @NonNull
        public Builder setSupplementalAlarmText(@NonNull CharSequence supplementalAlarmText) {
            mSupplementalAlarmText = supplementalAlarmText;
            return this;
        }

        /**
         * Builds a new SmartspaceDefaultUiTemplateData instance.
         */
        @NonNull
        public SmartspaceDefaultUiTemplateData build() {
            return new SmartspaceDefaultUiTemplateData(mTemplateType, mTitleText, mTitleIcon,
                    mSubtitleText, mSubTitleIcon, mPrimaryTapAction, mSupplementalSubtitleText,
                    mSupplementalSubtitleIcon, mSupplementalSubtitleTapAction,
                    mSupplementalAlarmText);
        }
    }
}
