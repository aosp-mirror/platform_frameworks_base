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

import java.util.Objects;

/**
 * Holds all the relevant data needed to render a Smartspace card with the default Ui Template.
 * <ul>
 *     <li> title_text (may contain a start drawable) </li>
 *     <li> subtitle_text (may contain a start drawable) . supplemental_subtitle_text (may
 *     contain a start drawable) </li>
 *     <li> next_alarm_text (contain a start drawable) + supplemental_alarm_text .
 *     do_not_disturb_view </li>
 * </ul>
 *
 * @hide
 */
@SystemApi
@SuppressLint("ParcelNotFinal")
public class BaseTemplateData implements Parcelable {

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
    private final Text mTitleText;

    @Nullable
    private final Icon mTitleIcon;

    /** Subtitle text and icon are shown at the second row. */
    @Nullable
    private final Text mSubtitleText;

    @Nullable
    private final Icon mSubtitleIcon;

    /**
     * Primary tap action for the entire card, including the blank spaces, except: 1. When title is
     * absent, the date view's default tap action is used; 2. Supplemental subtitle uses its own tap
     * action if being set; 3. Secondary card uses its own tap action if being set.
     */
    @Nullable
    private final TapAction mPrimaryTapAction;

    /**
     * Supplemental subtitle text and icon are shown at the second row following the subtitle text.
     * Mainly used for weather info on non-weather card.
     */
    @Nullable
    private final Text mSupplementalSubtitleText;

    @Nullable
    private final Icon mSupplementalSubtitleIcon;

    /**
     * Tap action for the supplemental subtitle's text and icon. Will use the primary tap action if
     * not being set.
     */
    @Nullable
    private final TapAction mSupplementalSubtitleTapAction;

    /**
     * Supplemental alarm text is specifically used for holiday alarm, which is appended to "next
     * alarm".
     */
    @Nullable
    private final Text mSupplementalAlarmText;

    BaseTemplateData(@NonNull Parcel in) {
        mTemplateType = in.readInt();
        mTitleText = in.readTypedObject(Text.CREATOR);
        mTitleIcon = in.readTypedObject(Icon.CREATOR);
        mSubtitleText = in.readTypedObject(Text.CREATOR);
        mSubtitleIcon = in.readTypedObject(Icon.CREATOR);
        mPrimaryTapAction = in.readTypedObject(TapAction.CREATOR);
        mSupplementalSubtitleText = in.readTypedObject(Text.CREATOR);
        mSupplementalSubtitleIcon = in.readTypedObject(Icon.CREATOR);
        mSupplementalSubtitleTapAction = in.readTypedObject(TapAction.CREATOR);
        mSupplementalAlarmText = in.readTypedObject(Text.CREATOR);
    }

    /**
     * Should ONLY used by subclasses. For the general instance creation, please use
     * SmartspaceDefaultUiTemplateData.Builder.
     */
    BaseTemplateData(@UiTemplateType int templateType,
            @Nullable Text titleText,
            @Nullable Icon titleIcon,
            @Nullable Text subtitleText,
            @Nullable Icon subtitleIcon,
            @Nullable TapAction primaryTapAction,
            @Nullable Text supplementalSubtitleText,
            @Nullable Icon supplementalSubtitleIcon,
            @Nullable TapAction supplementalSubtitleTapAction,
            @Nullable Text supplementalAlarmText) {
        mTemplateType = templateType;
        mTitleText = titleText;
        mTitleIcon = titleIcon;
        mSubtitleText = subtitleText;
        mSubtitleIcon = subtitleIcon;
        mPrimaryTapAction = primaryTapAction;
        mSupplementalSubtitleText = supplementalSubtitleText;
        mSupplementalSubtitleIcon = supplementalSubtitleIcon;
        mSupplementalSubtitleTapAction = supplementalSubtitleTapAction;
        mSupplementalAlarmText = supplementalAlarmText;
    }

    /** Returns the template type. By default is UNDEFINED. */
    @UiTemplateType
    public int getTemplateType() {
        return mTemplateType;
    }

    /** Returns the title's text. */
    @Nullable
    public Text getTitleText() {
        return mTitleText;
    }

    /** Returns the title's icon. */
    @Nullable
    public Icon getTitleIcon() {
        return mTitleIcon;
    }

    /** Returns the subtitle's text. */
    @Nullable
    public Text getSubtitleText() {
        return mSubtitleText;
    }

    /** Returns the subtitle's icon. */
    @Nullable
    public Icon getSubtitleIcon() {
        return mSubtitleIcon;
    }

    /** Returns the card's primary tap action. */
    @Nullable
    public TapAction getPrimaryTapAction() {
        return mPrimaryTapAction;
    }

    /** Returns the supplemental subtitle's text. */
    @Nullable
    public Text getSupplementalSubtitleText() {
        return mSupplementalSubtitleText;
    }

    /** Returns the supplemental subtitle's icon. */
    @Nullable
    public Icon getSupplementalSubtitleIcon() {
        return mSupplementalSubtitleIcon;
    }

    /** Returns the supplemental subtitle's tap action. Can be null if not being set. */
    @Nullable
    public TapAction getSupplementalSubtitleTapAction() {
        return mSupplementalSubtitleTapAction;
    }

    /** Returns the supplemental alarm text. */
    @Nullable
    public Text getSupplementalAlarmText() {
        return mSupplementalAlarmText;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<BaseTemplateData> CREATOR =
            new Creator<BaseTemplateData>() {
                @Override
                public BaseTemplateData createFromParcel(Parcel in) {
                    return new BaseTemplateData(in);
                }

                @Override
                public BaseTemplateData[] newArray(int size) {
                    return new BaseTemplateData[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mTemplateType);
        out.writeTypedObject(mTitleText, flags);
        out.writeTypedObject(mTitleIcon, flags);
        out.writeTypedObject(mSubtitleText, flags);
        out.writeTypedObject(mSubtitleIcon, flags);
        out.writeTypedObject(mPrimaryTapAction, flags);
        out.writeTypedObject(mSupplementalSubtitleText, flags);
        out.writeTypedObject(mSupplementalSubtitleIcon, flags);
        out.writeTypedObject(mSupplementalSubtitleTapAction, flags);
        out.writeTypedObject(mSupplementalAlarmText, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseTemplateData)) return false;
        BaseTemplateData that = (BaseTemplateData) o;
        return mTemplateType == that.mTemplateType && SmartspaceUtils.isEqual(mTitleText,
                that.mTitleText)
                && Objects.equals(mTitleIcon, that.mTitleIcon)
                && SmartspaceUtils.isEqual(mSubtitleText, that.mSubtitleText)
                && Objects.equals(mSubtitleIcon, that.mSubtitleIcon)
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
        return Objects.hash(mTemplateType, mTitleText, mTitleIcon, mSubtitleText, mSubtitleIcon,
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
                + ", mSubTitleIcon=" + mSubtitleIcon
                + ", mPrimaryTapAction=" + mPrimaryTapAction
                + ", mSupplementalSubtitleText=" + mSupplementalSubtitleText
                + ", mSupplementalSubtitleIcon=" + mSupplementalSubtitleIcon
                + ", mSupplementalSubtitleTapAction=" + mSupplementalSubtitleTapAction
                + ", mSupplementalAlarmText=" + mSupplementalAlarmText
                + '}';
    }

    /**
     * A builder for {@link BaseTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("StaticFinalBuilder")
    public static class Builder {
        @UiTemplateType
        private final int mTemplateType;
        private Text mTitleText;
        private Icon mTitleIcon;
        private Text mSubtitleText;
        private Icon mSubtitleIcon;
        private TapAction mPrimaryTapAction;
        private Text mSupplementalSubtitleText;
        private Icon mSupplementalSubtitleIcon;
        private TapAction mSupplementalSubtitleTapAction;
        private Text mSupplementalAlarmText;

        /**
         * A builder for {@link BaseTemplateData}.
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
        Text getTitleText() {
            return mTitleText;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        Icon getTitleIcon() {
            return mTitleIcon;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        Text getSubtitleText() {
            return mSubtitleText;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        Icon getSubtitleIcon() {
            return mSubtitleIcon;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        TapAction getPrimaryTapAction() {
            return mPrimaryTapAction;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        Text getSupplementalSubtitleText() {
            return mSupplementalSubtitleText;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        Icon getSupplementalSubtitleIcon() {
            return mSupplementalSubtitleIcon;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        TapAction getSupplementalSubtitleTapAction() {
            return mSupplementalSubtitleTapAction;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        Text getSupplementalAlarmText() {
            return mSupplementalAlarmText;
        }

        /**
         * Sets the card title.
         */
        @NonNull
        public Builder setTitleText(@NonNull Text titleText) {
            mTitleText = titleText;
            return this;
        }

        /**
         * Sets the card title icon.
         */
        @NonNull
        public Builder setTitleIcon(@NonNull Icon titleIcon) {
            mTitleIcon = titleIcon;
            return this;
        }

        /**
         * Sets the card subtitle.
         */
        @NonNull
        public Builder setSubtitleText(@NonNull Text subtitleText) {
            mSubtitleText = subtitleText;
            return this;
        }

        /**
         * Sets the card subtitle icon.
         */
        @NonNull
        public Builder setSubtitleIcon(@NonNull Icon subtitleIcon) {
            mSubtitleIcon = subtitleIcon;
            return this;
        }

        /**
         * Sets the card primary tap action.
         */
        @NonNull
        public Builder setPrimaryTapAction(@NonNull TapAction primaryTapAction) {
            mPrimaryTapAction = primaryTapAction;
            return this;
        }

        /**
         * Sets the supplemental subtitle text.
         */
        @NonNull
        public Builder setSupplementalSubtitleText(
                @NonNull Text supplementalSubtitleText) {
            mSupplementalSubtitleText = supplementalSubtitleText;
            return this;
        }

        /**
         * Sets the supplemental subtitle icon.
         */
        @NonNull
        public Builder setSupplementalSubtitleIcon(
                @NonNull Icon supplementalSubtitleIcon) {
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
                @NonNull TapAction supplementalSubtitleTapAction) {
            mSupplementalSubtitleTapAction = supplementalSubtitleTapAction;
            return this;
        }

        /**
         * Sets the supplemental alarm text.
         */
        @NonNull
        public Builder setSupplementalAlarmText(@NonNull Text supplementalAlarmText) {
            mSupplementalAlarmText = supplementalAlarmText;
            return this;
        }

        /**
         * Builds a new SmartspaceDefaultUiTemplateData instance.
         */
        @NonNull
        public BaseTemplateData build() {
            return new BaseTemplateData(mTemplateType, mTitleText, mTitleIcon,
                    mSubtitleText, mSubtitleIcon, mPrimaryTapAction, mSupplementalSubtitleText,
                    mSupplementalSubtitleIcon, mSupplementalSubtitleTapAction,
                    mSupplementalAlarmText);
        }
    }
}
