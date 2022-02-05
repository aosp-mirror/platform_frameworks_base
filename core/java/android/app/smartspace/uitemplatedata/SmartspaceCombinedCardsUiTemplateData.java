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
import android.os.Parcel;

import java.util.List;
import java.util.Objects;

/**
 * Holds all the relevant data needed to render a Smartspace card with the combined-card Ui
 * Template.
 *
 * We only support 1 sub-list card combined with 1 carousel card. And we may expand our supported
 * combinations in the future.
 *
 * @hide
 */
@SystemApi
public final class SmartspaceCombinedCardsUiTemplateData extends SmartspaceDefaultUiTemplateData {

    /** A list of secondary cards. */
    @NonNull
    private final List<SmartspaceDefaultUiTemplateData> mCombinedCardDataList;

    SmartspaceCombinedCardsUiTemplateData(@NonNull Parcel in) {
        super(in);
        mCombinedCardDataList = in.createTypedArrayList(SmartspaceDefaultUiTemplateData.CREATOR);
    }

    private SmartspaceCombinedCardsUiTemplateData(@SmartspaceTarget.UiTemplateType int templateType,
            @Nullable SmartspaceText titleText,
            @Nullable SmartspaceIcon titleIcon,
            @Nullable SmartspaceText subtitleText,
            @Nullable SmartspaceIcon subTitleIcon,
            @Nullable SmartspaceTapAction primaryTapAction,
            @Nullable SmartspaceText supplementalSubtitleText,
            @Nullable SmartspaceIcon supplementalSubtitleIcon,
            @Nullable SmartspaceTapAction supplementalSubtitleTapAction,
            @Nullable SmartspaceText supplementalAlarmText,
            @NonNull List<SmartspaceDefaultUiTemplateData> combinedCardDataList) {
        super(templateType, titleText, titleIcon, subtitleText, subTitleIcon, primaryTapAction,
                supplementalSubtitleText, supplementalSubtitleIcon, supplementalSubtitleTapAction,
                supplementalAlarmText);
        mCombinedCardDataList = combinedCardDataList;
    }

    @NonNull
    public List<SmartspaceDefaultUiTemplateData> getCombinedCardDataList() {
        return mCombinedCardDataList;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<SmartspaceCombinedCardsUiTemplateData> CREATOR =
            new Creator<SmartspaceCombinedCardsUiTemplateData>() {
                @Override
                public SmartspaceCombinedCardsUiTemplateData createFromParcel(Parcel in) {
                    return new SmartspaceCombinedCardsUiTemplateData(in);
                }

                @Override
                public SmartspaceCombinedCardsUiTemplateData[] newArray(int size) {
                    return new SmartspaceCombinedCardsUiTemplateData[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        super.writeToParcel(out, flags);
        out.writeTypedList(mCombinedCardDataList);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SmartspaceCombinedCardsUiTemplateData)) return false;
        if (!super.equals(o)) return false;
        SmartspaceCombinedCardsUiTemplateData that = (SmartspaceCombinedCardsUiTemplateData) o;
        return mCombinedCardDataList.equals(that.mCombinedCardDataList);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mCombinedCardDataList);
    }

    @Override
    public String toString() {
        return super.toString() + " + SmartspaceCombinedCardsUiTemplateData{"
                + "mCombinedCardDataList=" + mCombinedCardDataList
                + '}';
    }

    /**
     * A builder for {@link SmartspaceCombinedCardsUiTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder extends SmartspaceDefaultUiTemplateData.Builder {

        private final List<SmartspaceDefaultUiTemplateData> mCombinedCardDataList;

        /**
         * A builder for {@link SmartspaceCombinedCardsUiTemplateData}.
         */
        public Builder(@NonNull List<SmartspaceDefaultUiTemplateData> combinedCardDataList) {
            super(SmartspaceTarget.UI_TEMPLATE_COMBINED_CARDS);
            mCombinedCardDataList = Objects.requireNonNull(combinedCardDataList);
        }

        /**
         * Builds a new SmartspaceCombinedCardsUiTemplateData instance.
         *
         * @throws IllegalStateException if any required non-null field is null
         */
        @NonNull
        public SmartspaceCombinedCardsUiTemplateData build() {
            if (mCombinedCardDataList == null) {
                throw new IllegalStateException("Please assign a value to all @NonNull args.");
            }
            return new SmartspaceCombinedCardsUiTemplateData(getTemplateType(), getTitleText(),
                    getTitleIcon(), getSubtitleText(), getSubtitleIcon(), getPrimaryTapAction(),
                    getSupplementalSubtitleText(), getSupplementalSubtitleIcon(),
                    getSupplementalSubtitleTapAction(), getSupplementalAlarmText(),
                    mCombinedCardDataList);
        }
    }
}
