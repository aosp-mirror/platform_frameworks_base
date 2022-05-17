/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.annotation.SystemApi;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceUtils;
import android.os.Parcel;

import java.util.Objects;

/**
 * Holds all the relevant data needed to render a Smartspace card with the head-to-head Ui Template.
 *
 * This template will add a head-to-head card within the default-template card:
 * <ul>
 *                     <li> head-to-head title </li>
 *     <li> first-competitor icon       second-competitor icon </li>
 *     <li> first-competitor text       second-competitor text </li>
 * </ul>
 *
 * @hide
 */
@SystemApi
public final class HeadToHeadTemplateData extends BaseTemplateData {

    @Nullable
    private final Text mHeadToHeadTitle;
    @Nullable
    private final Icon mHeadToHeadFirstCompetitorIcon;
    @Nullable
    private final Icon mHeadToHeadSecondCompetitorIcon;
    @Nullable
    private final Text mHeadToHeadFirstCompetitorText;
    @Nullable
    private final Text mHeadToHeadSecondCompetitorText;

    /** Tap action for the head-to-head secondary card. */
    @Nullable
    private final TapAction mHeadToHeadAction;

    HeadToHeadTemplateData(@NonNull Parcel in) {
        super(in);
        mHeadToHeadTitle = in.readTypedObject(Text.CREATOR);
        mHeadToHeadFirstCompetitorIcon = in.readTypedObject(Icon.CREATOR);
        mHeadToHeadSecondCompetitorIcon = in.readTypedObject(Icon.CREATOR);
        mHeadToHeadFirstCompetitorText = in.readTypedObject(Text.CREATOR);
        mHeadToHeadSecondCompetitorText = in.readTypedObject(Text.CREATOR);
        mHeadToHeadAction = in.readTypedObject(TapAction.CREATOR);
    }

    private HeadToHeadTemplateData(@SmartspaceTarget.UiTemplateType int templateType,
            @Nullable SubItemInfo primaryItem,
            @Nullable SubItemInfo subtitleItem,
            @Nullable SubItemInfo subtitleSupplementalItem,
            @Nullable SubItemInfo supplementalLineItem,
            @Nullable SubItemInfo supplementalAlarmItem,
            int layoutWeight,
            @Nullable Text headToHeadTitle,
            @Nullable Icon headToHeadFirstCompetitorIcon,
            @Nullable Icon headToHeadSecondCompetitorIcon,
            @Nullable Text headToHeadFirstCompetitorText,
            @Nullable Text headToHeadSecondCompetitorText,
            @Nullable TapAction headToHeadAction) {
        super(templateType, primaryItem, subtitleItem, subtitleSupplementalItem,
                supplementalLineItem, supplementalAlarmItem, layoutWeight);

        mHeadToHeadTitle = headToHeadTitle;
        mHeadToHeadFirstCompetitorIcon = headToHeadFirstCompetitorIcon;
        mHeadToHeadSecondCompetitorIcon = headToHeadSecondCompetitorIcon;
        mHeadToHeadFirstCompetitorText = headToHeadFirstCompetitorText;
        mHeadToHeadSecondCompetitorText = headToHeadSecondCompetitorText;
        mHeadToHeadAction = headToHeadAction;
    }

    /** Returns the head-to-head card's title. */
    @Nullable
    public Text getHeadToHeadTitle() {
        return mHeadToHeadTitle;
    }

    /** Returns the first competitor's icon. */
    @Nullable
    public Icon getHeadToHeadFirstCompetitorIcon() {
        return mHeadToHeadFirstCompetitorIcon;
    }

    /** Returns the second competitor's icon. */
    @Nullable
    public Icon getHeadToHeadSecondCompetitorIcon() {
        return mHeadToHeadSecondCompetitorIcon;
    }

    /** Returns the first competitor's text. */
    @Nullable
    public Text getHeadToHeadFirstCompetitorText() {
        return mHeadToHeadFirstCompetitorText;
    }

    /** Returns the second competitor's text. */
    @Nullable
    public Text getHeadToHeadSecondCompetitorText() {
        return mHeadToHeadSecondCompetitorText;
    }

    /** Returns the head-to-head card's tap action. */
    @Nullable
    public TapAction getHeadToHeadAction() {
        return mHeadToHeadAction;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<HeadToHeadTemplateData> CREATOR =
            new Creator<HeadToHeadTemplateData>() {
                @Override
                public HeadToHeadTemplateData createFromParcel(Parcel in) {
                    return new HeadToHeadTemplateData(in);
                }

                @Override
                public HeadToHeadTemplateData[] newArray(int size) {
                    return new HeadToHeadTemplateData[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        super.writeToParcel(out, flags);
        out.writeTypedObject(mHeadToHeadTitle, flags);
        out.writeTypedObject(mHeadToHeadFirstCompetitorIcon, flags);
        out.writeTypedObject(mHeadToHeadSecondCompetitorIcon, flags);
        out.writeTypedObject(mHeadToHeadFirstCompetitorText, flags);
        out.writeTypedObject(mHeadToHeadSecondCompetitorText, flags);
        out.writeTypedObject(mHeadToHeadAction, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HeadToHeadTemplateData)) return false;
        if (!super.equals(o)) return false;
        HeadToHeadTemplateData that = (HeadToHeadTemplateData) o;
        return SmartspaceUtils.isEqual(mHeadToHeadTitle, that.mHeadToHeadTitle) && Objects.equals(
                mHeadToHeadFirstCompetitorIcon, that.mHeadToHeadFirstCompetitorIcon)
                && Objects.equals(
                mHeadToHeadSecondCompetitorIcon, that.mHeadToHeadSecondCompetitorIcon)
                && SmartspaceUtils.isEqual(mHeadToHeadFirstCompetitorText,
                that.mHeadToHeadFirstCompetitorText)
                && SmartspaceUtils.isEqual(mHeadToHeadSecondCompetitorText,
                that.mHeadToHeadSecondCompetitorText)
                && Objects.equals(
                mHeadToHeadAction, that.mHeadToHeadAction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mHeadToHeadTitle, mHeadToHeadFirstCompetitorIcon,
                mHeadToHeadSecondCompetitorIcon, mHeadToHeadFirstCompetitorText,
                mHeadToHeadSecondCompetitorText,
                mHeadToHeadAction);
    }

    @Override
    public String toString() {
        return super.toString() + " + SmartspaceHeadToHeadUiTemplateData{"
                + "mH2HTitle=" + mHeadToHeadTitle
                + ", mH2HFirstCompetitorIcon=" + mHeadToHeadFirstCompetitorIcon
                + ", mH2HSecondCompetitorIcon=" + mHeadToHeadSecondCompetitorIcon
                + ", mH2HFirstCompetitorText=" + mHeadToHeadFirstCompetitorText
                + ", mH2HSecondCompetitorText=" + mHeadToHeadSecondCompetitorText
                + ", mH2HAction=" + mHeadToHeadAction
                + '}';
    }

    /**
     * A builder for {@link HeadToHeadTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder extends BaseTemplateData.Builder {

        private Text mHeadToHeadTitle;
        private Icon mHeadToHeadFirstCompetitorIcon;
        private Icon mHeadToHeadSecondCompetitorIcon;
        private Text mHeadToHeadFirstCompetitorText;
        private Text mHeadToHeadSecondCompetitorText;
        private TapAction mHeadToHeadAction;

        /**
         * A builder for {@link HeadToHeadTemplateData}.
         */
        public Builder() {
            super(SmartspaceTarget.UI_TEMPLATE_HEAD_TO_HEAD);
        }

        /**
         * Sets the head-to-head card's title
         */
        @NonNull
        public Builder setHeadToHeadTitle(@Nullable Text headToHeadTitle) {
            mHeadToHeadTitle = headToHeadTitle;
            return this;
        }

        /**
         * Sets the head-to-head card's first competitor icon
         */
        @NonNull
        public Builder setHeadToHeadFirstCompetitorIcon(
                @Nullable Icon headToHeadFirstCompetitorIcon) {
            mHeadToHeadFirstCompetitorIcon = headToHeadFirstCompetitorIcon;
            return this;
        }

        /**
         * Sets the head-to-head card's second competitor icon
         */
        @NonNull
        public Builder setHeadToHeadSecondCompetitorIcon(
                @Nullable Icon headToHeadSecondCompetitorIcon) {
            mHeadToHeadSecondCompetitorIcon = headToHeadSecondCompetitorIcon;
            return this;
        }

        /**
         * Sets the head-to-head card's first competitor text
         */
        @NonNull
        public Builder setHeadToHeadFirstCompetitorText(
                @Nullable Text headToHeadFirstCompetitorText) {
            mHeadToHeadFirstCompetitorText = headToHeadFirstCompetitorText;
            return this;
        }

        /**
         * Sets the head-to-head card's second competitor text
         */
        @NonNull
        public Builder setHeadToHeadSecondCompetitorText(
                @Nullable Text headToHeadSecondCompetitorText) {
            mHeadToHeadSecondCompetitorText = headToHeadSecondCompetitorText;
            return this;
        }

        /**
         * Sets the head-to-head card's tap action
         */
        @NonNull
        public Builder setHeadToHeadAction(@Nullable TapAction headToHeadAction) {
            mHeadToHeadAction = headToHeadAction;
            return this;
        }

        /**
         * Builds a new SmartspaceHeadToHeadUiTemplateData instance.
         */
        @NonNull
        public HeadToHeadTemplateData build() {
            return new HeadToHeadTemplateData(getTemplateType(), getPrimaryItem(),
                    getSubtitleItem(), getSubtitleSupplemtnalItem(),
                    getSupplementalLineItem(), getSupplementalAlarmItem(), getLayoutWeight(),
                    mHeadToHeadTitle,
                    mHeadToHeadFirstCompetitorIcon,
                    mHeadToHeadSecondCompetitorIcon, mHeadToHeadFirstCompetitorText,
                    mHeadToHeadSecondCompetitorText,
                    mHeadToHeadAction);
        }
    }
}
