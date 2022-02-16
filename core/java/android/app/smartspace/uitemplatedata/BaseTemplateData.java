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
 *
 *     <li> supplemental_text (contain a start drawable) . do_not_disturb_view </li>
 *     Or
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
     * Primary logging info for the entire card. This will only be used when rendering a sub card
     * within the base card. For the base card itself, BcSmartspaceCardLoggingInfo should be used,
     * which has the display-specific info (e.g. display surface).
     */
    @Nullable
    private final SubItemLoggingInfo mPrimaryLoggingInfo;

    /**
     * Supplemental subtitle text and icon are shown at the second row following the subtitle text.
     * Mainly used for weather info on non-weather card.
     */
    @Nullable
    private final Text mSupplementalSubtitleText;

    @Nullable
    private final Icon mSupplementalSubtitleIcon;

    /**
     * Tap action for the supplemental subtitle's text and icon. Uses the primary tap action if
     * not being set.
     */
    @Nullable
    private final TapAction mSupplementalSubtitleTapAction;

    /**
     * Logging info for the supplemental subtitle's are. Uses the primary logging info if not being
     * set.
     */
    @Nullable
    private final SubItemLoggingInfo mSupplementalSubtitleLoggingInfo;

    @Nullable
    private final Text mSupplementalText;

    @Nullable
    private final Icon mSupplementalIcon;

    @Nullable
    private final TapAction mSupplementalTapAction;

    /**
     * Logging info for the supplemental line. Uses the primary logging info if not being set.
     */
    @Nullable
    private final SubItemLoggingInfo mSupplementalLoggingInfo;

    /**
     * Supplemental alarm text is specifically used for holiday alarm, which is appended to "next
     * alarm".
     */
    @Nullable
    private final Text mSupplementalAlarmText;

    /**
     * The layout weight info for the card, which indicates how much space it should occupy on the
     * screen. Default weight is 0.
     */
    private final int mLayoutWeight;

    BaseTemplateData(@NonNull Parcel in) {
        mTemplateType = in.readInt();
        mTitleText = in.readTypedObject(Text.CREATOR);
        mTitleIcon = in.readTypedObject(Icon.CREATOR);
        mSubtitleText = in.readTypedObject(Text.CREATOR);
        mSubtitleIcon = in.readTypedObject(Icon.CREATOR);
        mPrimaryTapAction = in.readTypedObject(TapAction.CREATOR);
        mPrimaryLoggingInfo = in.readTypedObject(SubItemLoggingInfo.CREATOR);
        mSupplementalSubtitleText = in.readTypedObject(Text.CREATOR);
        mSupplementalSubtitleIcon = in.readTypedObject(Icon.CREATOR);
        mSupplementalSubtitleTapAction = in.readTypedObject(TapAction.CREATOR);
        mSupplementalSubtitleLoggingInfo = in.readTypedObject(SubItemLoggingInfo.CREATOR);
        mSupplementalText = in.readTypedObject(Text.CREATOR);
        mSupplementalIcon = in.readTypedObject(Icon.CREATOR);
        mSupplementalTapAction = in.readTypedObject(TapAction.CREATOR);
        mSupplementalLoggingInfo = in.readTypedObject(SubItemLoggingInfo.CREATOR);
        mSupplementalAlarmText = in.readTypedObject(Text.CREATOR);
        mLayoutWeight = in.readInt();
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
            @Nullable SubItemLoggingInfo primaryLoggingInfo,
            @Nullable Text supplementalSubtitleText,
            @Nullable Icon supplementalSubtitleIcon,
            @Nullable TapAction supplementalSubtitleTapAction,
            @Nullable SubItemLoggingInfo supplementalSubtitleLoggingInfo,
            @Nullable Text supplementalText,
            @Nullable Icon supplementalIcon,
            @Nullable TapAction supplementalTapAction,
            @Nullable SubItemLoggingInfo supplementalLoggingInfo,
            @Nullable Text supplementalAlarmText,
            int layoutWeight) {
        mTemplateType = templateType;
        mTitleText = titleText;
        mTitleIcon = titleIcon;
        mSubtitleText = subtitleText;
        mSubtitleIcon = subtitleIcon;
        mPrimaryTapAction = primaryTapAction;
        mPrimaryLoggingInfo = primaryLoggingInfo;
        mSupplementalSubtitleText = supplementalSubtitleText;
        mSupplementalSubtitleIcon = supplementalSubtitleIcon;
        mSupplementalSubtitleTapAction = supplementalSubtitleTapAction;
        mSupplementalSubtitleLoggingInfo = supplementalSubtitleLoggingInfo;
        mSupplementalText = supplementalText;
        mSupplementalIcon = supplementalIcon;
        mSupplementalTapAction = supplementalTapAction;
        mSupplementalLoggingInfo = supplementalLoggingInfo;
        mSupplementalAlarmText = supplementalAlarmText;
        mLayoutWeight = layoutWeight;
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

    /** Returns the card's primary logging info. */
    @Nullable
    public SubItemLoggingInfo getPrimaryLoggingInfo() {
        return mPrimaryLoggingInfo;
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

    /** Returns the card's supplemental title's logging info. */
    @Nullable
    public SubItemLoggingInfo getSupplementalSubtitleLoggingInfo() {
        return mSupplementalSubtitleLoggingInfo;
    }

    /** Returns the supplemental text. */
    @Nullable
    public Text getSupplementalText() {
        return mSupplementalText;
    }

    /** Returns the supplemental icon. */
    @Nullable
    public Icon getSupplementalIcon() {
        return mSupplementalIcon;
    }

    /** Returns the supplemental line's tap action. Can be null if not being set. */
    @Nullable
    public TapAction getSupplementalTapAction() {
        return mSupplementalTapAction;
    }

    /** Returns the card's supplemental line logging info. */
    @Nullable
    public SubItemLoggingInfo getSupplementalLoggingInfo() {
        return mSupplementalLoggingInfo;
    }

    /** Returns the supplemental alarm text. */
    @Nullable
    public Text getSupplementalAlarmText() {
        return mSupplementalAlarmText;
    }

    /** Returns the card layout weight info. Default weight is 0. */
    public int getLayoutWeight() {
        return mLayoutWeight;
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
        out.writeTypedObject(mPrimaryLoggingInfo, flags);
        out.writeTypedObject(mSupplementalSubtitleText, flags);
        out.writeTypedObject(mSupplementalSubtitleIcon, flags);
        out.writeTypedObject(mSupplementalSubtitleTapAction, flags);
        out.writeTypedObject(mSupplementalSubtitleLoggingInfo, flags);
        out.writeTypedObject(mSupplementalText, flags);
        out.writeTypedObject(mSupplementalIcon, flags);
        out.writeTypedObject(mSupplementalTapAction, flags);
        out.writeTypedObject(mSupplementalLoggingInfo, flags);
        out.writeTypedObject(mSupplementalAlarmText, flags);
        out.writeInt(mLayoutWeight);
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
                && Objects.equals(mPrimaryLoggingInfo, that.mPrimaryLoggingInfo)
                && SmartspaceUtils.isEqual(mSupplementalSubtitleText,
                that.mSupplementalSubtitleText)
                && Objects.equals(mSupplementalSubtitleIcon, that.mSupplementalSubtitleIcon)
                && Objects.equals(mSupplementalSubtitleTapAction,
                that.mSupplementalSubtitleTapAction)
                && Objects.equals(mSupplementalSubtitleLoggingInfo,
                that.mSupplementalSubtitleLoggingInfo)
                && SmartspaceUtils.isEqual(mSupplementalText,
                that.mSupplementalText)
                && Objects.equals(mSupplementalIcon, that.mSupplementalIcon)
                && Objects.equals(mSupplementalTapAction, that.mSupplementalTapAction)
                && Objects.equals(mSupplementalLoggingInfo, that.mSupplementalLoggingInfo)
                && SmartspaceUtils.isEqual(mSupplementalAlarmText, that.mSupplementalAlarmText)
                && mLayoutWeight == that.mLayoutWeight;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTemplateType, mTitleText, mTitleIcon, mSubtitleText, mSubtitleIcon,
                mPrimaryTapAction, mPrimaryLoggingInfo, mSupplementalSubtitleText,
                mSupplementalSubtitleIcon, mSupplementalSubtitleTapAction,
                mSupplementalSubtitleLoggingInfo,
                mSupplementalText, mSupplementalIcon, mSupplementalTapAction,
                mSupplementalLoggingInfo, mSupplementalAlarmText, mLayoutWeight);
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
                + ", mPrimaryLoggingInfo=" + mPrimaryLoggingInfo
                + ", mSupplementalSubtitleText=" + mSupplementalSubtitleText
                + ", mSupplementalSubtitleIcon=" + mSupplementalSubtitleIcon
                + ", mSupplementalSubtitleTapAction=" + mSupplementalSubtitleTapAction
                + ", mSupplementalSubtitleLoggingInfo=" + mSupplementalSubtitleLoggingInfo
                + ", mSupplementalText=" + mSupplementalText
                + ", mSupplementalIcon=" + mSupplementalIcon
                + ", mSupplementalTapAction=" + mSupplementalTapAction
                + ", mSupplementalLoggingInfo=" + mSupplementalLoggingInfo
                + ", mSupplementalAlarmText=" + mSupplementalAlarmText
                + ", mLayoutWeight=" + mLayoutWeight
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
        private SubItemLoggingInfo mPrimaryLoggingInfo;
        private Text mSupplementalSubtitleText;
        private Icon mSupplementalSubtitleIcon;
        private TapAction mSupplementalSubtitleTapAction;
        private SubItemLoggingInfo mSupplementalSubtitleLoggingInfo;
        private Text mSupplementalText;
        private Icon mSupplementalIcon;
        private TapAction mSupplementalTapAction;
        private SubItemLoggingInfo mSupplementalLoggingInfo;
        private Text mSupplementalAlarmText;
        private int mLayoutWeight;

        /**
         * A builder for {@link BaseTemplateData}. By default sets the layout weight to be 0.
         *
         * @param templateType the {@link UiTemplateType} of this template data.
         */
        public Builder(@UiTemplateType int templateType) {
            mTemplateType = templateType;
            mLayoutWeight = 0;
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
        SubItemLoggingInfo getPrimaryLoggingInfo() {
            return mPrimaryLoggingInfo;
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
        SubItemLoggingInfo getSupplementalSubtitleLoggingInfo() {
            return mSupplementalSubtitleLoggingInfo;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        Text getSupplementalText() {
            return mSupplementalText;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        Icon getSupplementalIcon() {
            return mSupplementalIcon;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        TapAction getSupplementalTapAction() {
            return mSupplementalTapAction;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        SubItemLoggingInfo getSupplementalLoggingInfo() {
            return mSupplementalLoggingInfo;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        Text getSupplementalAlarmText() {
            return mSupplementalAlarmText;
        }

        /** Should ONLY be used by the subclasses */
        @SuppressLint("GetterOnBuilder")
        int getLayoutWeight() {
            return mLayoutWeight;
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
         * Sets the card primary logging info.
         */
        @NonNull
        public Builder setPrimaryLoggingInfo(@NonNull SubItemLoggingInfo primaryLoggingInfo) {
            mPrimaryLoggingInfo = primaryLoggingInfo;
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
         * being set.
         */
        @NonNull
        public Builder setSupplementalSubtitleTapAction(
                @NonNull TapAction supplementalSubtitleTapAction) {
            mSupplementalSubtitleTapAction = supplementalSubtitleTapAction;
            return this;
        }

        /**
         * Sets the card supplemental title's logging info.
         */
        @NonNull
        public Builder setSupplementalSubtitleLoggingInfo(
                @NonNull SubItemLoggingInfo supplementalSubtitleLoggingInfo) {
            mSupplementalSubtitleLoggingInfo = supplementalSubtitleLoggingInfo;
            return this;
        }

        /**
         * Sets the supplemental text.
         */
        @NonNull
        public Builder setSupplementalText(@NonNull Text supplementalText) {
            mSupplementalText = supplementalText;
            return this;
        }

        /**
         * Sets the supplemental icon.
         */
        @NonNull
        public Builder setSupplementalIcon(@NonNull Icon supplementalIcon) {
            mSupplementalIcon = supplementalIcon;
            return this;
        }

        /**
         * Sets the supplemental line tap action. {@code mPrimaryTapAction} will be used if not
         * being set.
         */
        @NonNull
        public Builder setSupplementalTapAction(@NonNull TapAction supplementalTapAction) {
            mSupplementalTapAction = supplementalTapAction;
            return this;
        }

        /**
         * Sets the card supplemental line's logging info.
         */
        @NonNull
        public Builder setSupplementalLoggingInfo(
                @NonNull SubItemLoggingInfo supplementalLoggingInfo) {
            mSupplementalLoggingInfo = supplementalLoggingInfo;
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
         * Sets the layout weight.
         */
        @NonNull
        public Builder setLayoutWeight(int layoutWeight) {
            mLayoutWeight = layoutWeight;
            return this;
        }

        /**
         * Builds a new SmartspaceDefaultUiTemplateData instance.
         */
        @NonNull
        public BaseTemplateData build() {
            return new BaseTemplateData(mTemplateType, mTitleText, mTitleIcon,
                    mSubtitleText, mSubtitleIcon, mPrimaryTapAction,
                    mPrimaryLoggingInfo,
                    mSupplementalSubtitleText, mSupplementalSubtitleIcon,
                    mSupplementalSubtitleTapAction, mSupplementalSubtitleLoggingInfo,
                    mSupplementalText, mSupplementalIcon,
                    mSupplementalTapAction, mSupplementalLoggingInfo,
                    mSupplementalAlarmText, mLayoutWeight);
        }
    }

    /**
     * Holds all the logging info needed for a sub item within the base card. For example, the
     * supplemental-subtitle part should have its own logging info.
     */
    public static final class SubItemLoggingInfo implements Parcelable {

        /** A unique instance id for the sub item. */
        private final int mInstanceId;

        /** The feature type for this sub item. */
        private final int mFeatureType;

        SubItemLoggingInfo(@NonNull Parcel in) {
            mInstanceId = in.readInt();
            mFeatureType = in.readInt();
        }

        private SubItemLoggingInfo(int instanceId, int featureType) {
            mInstanceId = instanceId;
            mFeatureType = featureType;
        }

        public int getInstanceId() {
            return mInstanceId;
        }

        public int getFeatureType() {
            return mFeatureType;
        }

        /**
         * @see Parcelable.Creator
         */
        @NonNull
        public static final Creator<SubItemLoggingInfo> CREATOR =
                new Creator<SubItemLoggingInfo>() {
                    @Override
                    public SubItemLoggingInfo createFromParcel(Parcel in) {
                        return new SubItemLoggingInfo(in);
                    }

                    @Override
                    public SubItemLoggingInfo[] newArray(int size) {
                        return new SubItemLoggingInfo[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(mInstanceId);
            out.writeInt(mFeatureType);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SubItemLoggingInfo)) return false;
            SubItemLoggingInfo that = (SubItemLoggingInfo) o;
            return mInstanceId == that.mInstanceId && mFeatureType == that.mFeatureType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mInstanceId, mFeatureType);
        }

        @Override
        public String toString() {
            return "SubItemLoggingInfo{"
                    + "mInstanceId=" + mInstanceId
                    + ", mFeatureType=" + mFeatureType
                    + '}';
        }

        /**
         * A builder for {@link SubItemLoggingInfo} object.
         *
         * @hide
         */
        @SystemApi
        public static final class Builder {

            private final int mInstanceId;
            private final int mFeatureType;

            /**
             * A builder for {@link SubItemLoggingInfo}.
             *
             * @param instanceId  A unique instance id for the sub item
             * @param featureType The feature type for this sub item
             */
            public Builder(int instanceId, int featureType) {
                mInstanceId = instanceId;
                mFeatureType = featureType;
            }

            /** Builds a new {@link SubItemLoggingInfo} instance. */
            @NonNull
            public SubItemLoggingInfo build() {
                return new SubItemLoggingInfo(mInstanceId, mFeatureType);
            }
        }
    }
}
