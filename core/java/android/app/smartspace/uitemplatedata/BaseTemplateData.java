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
import android.app.smartspace.SmartspaceTarget.FeatureType;
import android.app.smartspace.SmartspaceTarget.UiTemplateType;
import android.app.smartspace.SmartspaceUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

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
     *
     * Primary tap action for the entire card, including the blank spaces, except: 1. When title is
     * absent, the date view's default tap action is used; 2. Subtitle/Supplemental subtitle uses
     * its own tap action if being set; 3. Secondary card uses its own tap action if being set.
     */
    @Nullable
    private final SubItemInfo mPrimaryItem;


    /** Subtitle text and icon are shown at the second row. */
    @Nullable
    private final SubItemInfo mSubtitleItem;

    /**
     * Supplemental subtitle text and icon are shown at the second row following the subtitle text.
     * Mainly used for weather info on non-weather card.
     */
    @Nullable
    private final SubItemInfo mSubtitleSupplementalItem;

    /**
     * Supplemental line is shown at the third row.
     */
    @Nullable
    private final SubItemInfo mSupplementalLineItem;

    /**
     * Supplemental alarm item is specifically used for holiday alarm, which is appended to "next
     * alarm". This is also shown at the third row, but won't be shown the same time with
     * mSupplementalLineItem.
     */
    @Nullable
    private final SubItemInfo mSupplementalAlarmItem;

    /**
     * The layout weight info for the card, which indicates how much space it should occupy on the
     * screen. Default weight is 0.
     */
    private final int mLayoutWeight;

    BaseTemplateData(@NonNull Parcel in) {
        mTemplateType = in.readInt();
        mPrimaryItem = in.readTypedObject(SubItemInfo.CREATOR);
        mSubtitleItem = in.readTypedObject(SubItemInfo.CREATOR);
        mSubtitleSupplementalItem = in.readTypedObject(SubItemInfo.CREATOR);
        mSupplementalLineItem = in.readTypedObject(SubItemInfo.CREATOR);
        mSupplementalAlarmItem = in.readTypedObject(SubItemInfo.CREATOR);
        mLayoutWeight = in.readInt();
    }

    /**
     * Should ONLY used by subclasses. For the general instance creation, please use
     * SmartspaceDefaultUiTemplateData.Builder.
     */
    BaseTemplateData(@UiTemplateType int templateType,
            @Nullable SubItemInfo primaryItem,
            @Nullable SubItemInfo subtitleItem,
            @Nullable SubItemInfo subtitleSupplementalItem,
            @Nullable SubItemInfo supplementalLineItem,
            @Nullable SubItemInfo supplementalAlarmItem,
            int layoutWeight) {
        mTemplateType = templateType;
        mPrimaryItem = primaryItem;
        mSubtitleItem = subtitleItem;
        mSubtitleSupplementalItem = subtitleSupplementalItem;
        mSupplementalLineItem = supplementalLineItem;
        mSupplementalAlarmItem = supplementalAlarmItem;
        mLayoutWeight = layoutWeight;
    }

    /** Returns the template type. By default is UNDEFINED. */
    @UiTemplateType
    public int getTemplateType() {
        return mTemplateType;
    }

    /** Returns the primary item (the first line). */
    @Nullable
    public SubItemInfo getPrimaryItem() {
        return mPrimaryItem;
    }

    /** Returns the subtitle item (the second line). */
    @Nullable
    public SubItemInfo getSubtitleItem() {
        return mSubtitleItem;
    }

    /** Returns the subtitle's supplemental item (the second line following the subtitle). */
    @Nullable
    public SubItemInfo getSubtitleSupplementalItem() {
        return mSubtitleSupplementalItem;
    }

    /** Returns the supplemental line item (the 3rd line). */
    @Nullable
    public SubItemInfo getSupplementalLineItem() {
        return mSupplementalLineItem;
    }

    /** Returns the supplemental alarm item (the 3rd line). */
    @Nullable
    public SubItemInfo getSupplementalAlarmItem() {
        return mSupplementalAlarmItem;
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
        out.writeTypedObject(mPrimaryItem, flags);
        out.writeTypedObject(mSubtitleItem, flags);
        out.writeTypedObject(mSubtitleSupplementalItem, flags);
        out.writeTypedObject(mSupplementalLineItem, flags);
        out.writeTypedObject(mSupplementalAlarmItem, flags);
        out.writeInt(mLayoutWeight);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseTemplateData)) return false;
        BaseTemplateData that = (BaseTemplateData) o;
        return mTemplateType == that.mTemplateType && mLayoutWeight == that.mLayoutWeight
                && Objects.equals(mPrimaryItem, that.mPrimaryItem)
                && Objects.equals(mSubtitleItem, that.mSubtitleItem)
                && Objects.equals(mSubtitleSupplementalItem, that.mSubtitleSupplementalItem)
                && Objects.equals(mSupplementalLineItem, that.mSupplementalLineItem)
                && Objects.equals(mSupplementalAlarmItem, that.mSupplementalAlarmItem);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTemplateType, mPrimaryItem, mSubtitleItem, mSubtitleSupplementalItem,
                mSupplementalLineItem, mSupplementalAlarmItem, mLayoutWeight);
    }

    @Override
    public String toString() {
        return "BaseTemplateData{"
                + "mTemplateType=" + mTemplateType
                + ", mPrimaryItem=" + mPrimaryItem
                + ", mSubtitleItem=" + mSubtitleItem
                + ", mSubtitleSupplementalItem=" + mSubtitleSupplementalItem
                + ", mSupplementalLineItem=" + mSupplementalLineItem
                + ", mSupplementalAlarmItem=" + mSupplementalAlarmItem
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

        private SubItemInfo mPrimaryItem;
        private SubItemInfo mSubtitleItem;
        private SubItemInfo mSubtitleSupplementalItem;
        private SubItemInfo mSupplementalLineItem;
        private SubItemInfo mSupplementalAlarmItem;
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
        SubItemInfo getPrimaryItem() {
            return mPrimaryItem;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        SubItemInfo getSubtitleItem() {
            return mSubtitleItem;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        SubItemInfo getSubtitleSupplemtnalItem() {
            return mSubtitleSupplementalItem;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        SubItemInfo getSupplementalLineItem() {
            return mSupplementalLineItem;
        }

        /** Should ONLY be used by the subclasses */
        @Nullable
        @SuppressLint("GetterOnBuilder")
        SubItemInfo getSupplementalAlarmItem() {
            return mSupplementalAlarmItem;
        }

        /** Should ONLY be used by the subclasses */
        @SuppressLint("GetterOnBuilder")
        int getLayoutWeight() {
            return mLayoutWeight;
        }

        /**
         * Sets the card primary item.
         */
        @NonNull
        public Builder setPrimaryItem(@NonNull SubItemInfo primaryItem) {
            mPrimaryItem = primaryItem;
            return this;
        }

        /**
         * Sets the card subtitle item.
         */
        @NonNull
        public Builder setSubtitleItem(@NonNull SubItemInfo subtitleItem) {
            mSubtitleItem = subtitleItem;
            return this;
        }

        /**
         * Sets the card subtitle's supplemental item.
         */
        @NonNull
        public Builder setSubtitleSupplementalItem(@NonNull SubItemInfo subtitleSupplementalItem) {
            mSubtitleSupplementalItem = subtitleSupplementalItem;
            return this;
        }

        /**
         * Sets the card supplemental line item.
         */
        @NonNull
        public Builder setSupplementalLineItem(@NonNull SubItemInfo supplementalLineItem) {
            mSupplementalLineItem = supplementalLineItem;
            return this;
        }

        /**
         * Sets the card supplemental alarm item.
         */
        @NonNull
        public Builder setSupplementalAlarmItem(@NonNull SubItemInfo supplementalAlarmItem) {
            mSupplementalAlarmItem = supplementalAlarmItem;
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
            return new BaseTemplateData(
                    mTemplateType,
                    mPrimaryItem,
                    mSubtitleItem,
                    mSubtitleSupplementalItem,
                    mSupplementalLineItem,
                    mSupplementalAlarmItem,
                    mLayoutWeight);
        }
    }

    /**
     * Holds all the rendering and logging info needed for a sub item within the base card.
     */
    public static final class SubItemInfo implements Parcelable {

        /** The text information for the subitem, which will be rendered as it's text content. */
        @Nullable
        private final Text mText;

        /** The icon for the subitem, which will be rendered as a drawable in front of the text. */
        @Nullable
        private final Icon mIcon;

        /** The tap action for the subitem. */
        @Nullable
        private final TapAction mTapAction;

        /** The logging info for the subitem. */
        @Nullable
        private final SubItemLoggingInfo mLoggingInfo;

        SubItemInfo(@NonNull Parcel in) {
            mText = in.readTypedObject(Text.CREATOR);
            mIcon = in.readTypedObject(Icon.CREATOR);
            mTapAction = in.readTypedObject(TapAction.CREATOR);
            mLoggingInfo = in.readTypedObject(SubItemLoggingInfo.CREATOR);
        }

        private SubItemInfo(@Nullable Text text,
                @Nullable Icon icon,
                @Nullable TapAction tapAction,
                @Nullable SubItemLoggingInfo loggingInfo) {
            mText = text;
            mIcon = icon;
            mTapAction = tapAction;
            mLoggingInfo = loggingInfo;
        }

        /** Returns the subitem's text. */
        @Nullable
        public Text getText() {
            return mText;
        }

        /** Returns the subitem's icon. */
        @Nullable
        public Icon getIcon() {
            return mIcon;
        }

        /** Returns the subitem's tap action. */
        @Nullable
        public TapAction getTapAction() {
            return mTapAction;
        }

        /** Returns the subitem's logging info. */
        @Nullable
        public SubItemLoggingInfo getLoggingInfo() {
            return mLoggingInfo;
        }

        /**
         * @see Parcelable.Creator
         */
        @NonNull
        public static final Creator<SubItemInfo> CREATOR =
                new Creator<SubItemInfo>() {
                    @Override
                    public SubItemInfo createFromParcel(Parcel in) {
                        return new SubItemInfo(in);
                    }

                    @Override
                    public SubItemInfo[] newArray(int size) {
                        return new SubItemInfo[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeTypedObject(mText, flags);
            out.writeTypedObject(mIcon, flags);
            out.writeTypedObject(mTapAction, flags);
            out.writeTypedObject(mLoggingInfo, flags);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SubItemInfo)) return false;
            SubItemInfo that = (SubItemInfo) o;
            return SmartspaceUtils.isEqual(mText, that.mText) && Objects.equals(mIcon,
                    that.mIcon) && Objects.equals(mTapAction, that.mTapAction)
                    && Objects.equals(mLoggingInfo, that.mLoggingInfo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mText, mIcon, mTapAction, mLoggingInfo);
        }

        @Override
        public String toString() {
            return "SubItemInfo{"
                    + "mText=" + mText
                    + ", mIcon=" + mIcon
                    + ", mTapAction=" + mTapAction
                    + ", mLoggingInfo=" + mLoggingInfo
                    + '}';
        }

        /**
         * A builder for {@link SubItemInfo} object.
         *
         * @hide
         */
        @SystemApi
        public static final class Builder {

            private Text mText;
            private Icon mIcon;
            private TapAction mTapAction;
            private SubItemLoggingInfo mLoggingInfo;

            /**
             * Sets the sub item's text.
             */
            @NonNull
            public Builder setText(@NonNull Text text) {
                mText = text;
                return this;
            }

            /**
             * Sets the sub item's icon.
             */
            @NonNull
            public Builder setIcon(@NonNull Icon icon) {
                mIcon = icon;
                return this;
            }

            /**
             * Sets the sub item's tap action.
             */
            @NonNull
            public Builder setTapAction(@NonNull TapAction tapAction) {
                mTapAction = tapAction;
                return this;
            }

            /**
             * Sets the sub item's logging info.
             */
            @NonNull
            public Builder setLoggingInfo(@NonNull SubItemLoggingInfo loggingInfo) {
                mLoggingInfo = loggingInfo;
                return this;
            }

            /**
             * Builds a new {@link SubItemInfo} instance.
             *
             * @throws IllegalStateException if all the data field is empty.
             */
            @NonNull
            public SubItemInfo build() {
                if (SmartspaceUtils.isEmpty(mText) && mIcon == null && mTapAction == null
                        && mLoggingInfo == null) {
                    throw new IllegalStateException("SubItem data is empty");
                }

                return new SubItemInfo(mText, mIcon, mTapAction, mLoggingInfo);
            }
        }
    }

    /**
     * Holds all the logging info needed for a sub item within the base card. For example, the
     * supplemental-subtitle part should have its own logging info.
     */
    public static final class SubItemLoggingInfo implements Parcelable {

        /** A unique instance id for the sub item. */
        private final int mInstanceId;

        /**
         * {@link FeatureType} indicating the feature type of this subitem.
         *
         * @see FeatureType
         */
        @FeatureType
        private final int mFeatureType;

        /** The data source's package name for this sub item. */
        @Nullable
        private final CharSequence mPackageName;

        SubItemLoggingInfo(@NonNull Parcel in) {
            mInstanceId = in.readInt();
            mFeatureType = in.readInt();
            mPackageName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        }

        private SubItemLoggingInfo(int instanceId, @FeatureType int featureType,
                @Nullable CharSequence packageName) {
            mInstanceId = instanceId;
            mFeatureType = featureType;
            mPackageName = packageName;
        }

        public int getInstanceId() {
            return mInstanceId;
        }

        @FeatureType
        public int getFeatureType() {
            return mFeatureType;
        }

        @Nullable
        public CharSequence getPackageName() {
            return mPackageName;
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
            TextUtils.writeToParcel(mPackageName, out, flags);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SubItemLoggingInfo)) return false;
            SubItemLoggingInfo that = (SubItemLoggingInfo) o;
            return mInstanceId == that.mInstanceId && mFeatureType == that.mFeatureType
                    && SmartspaceUtils.isEqual(mPackageName, that.mPackageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mInstanceId, mFeatureType, mPackageName);
        }

        @Override
        public String toString() {
            return "SubItemLoggingInfo{"
                    + "mInstanceId=" + mInstanceId
                    + ", mFeatureType=" + mFeatureType
                    + ", mPackageName=" + mPackageName
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
            private CharSequence mPackageName;

            /**
             * A builder for {@link SubItemLoggingInfo}.
             *
             * @param instanceId  A unique instance id for the sub item
             * @param featureType The feature type id for this sub item
             */
            public Builder(int instanceId, @FeatureType int featureType) {
                mInstanceId = instanceId;
                mFeatureType = featureType;
            }

            /**
             * Sets the sub item's data source package name.
             */
            @NonNull
            public Builder setPackageName(@NonNull CharSequence packageName) {
                mPackageName = packageName;
                return this;
            }

            /** Builds a new {@link SubItemLoggingInfo} instance. */
            @NonNull
            public SubItemLoggingInfo build() {
                return new SubItemLoggingInfo(mInstanceId, mFeatureType, mPackageName);
            }
        }
    }
}
