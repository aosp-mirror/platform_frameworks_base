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
 * We only support adding a 1 sub-list card combined with 1 sub-card card (may expand our supported
 * combinations in the future) within the default-template card:
 *
 * <ul>
 *     <li> sub-list card, sub-card card </li>
 * </ul>
 *
 * @hide
 */
@SystemApi
public final class CombinedCardsTemplateData extends BaseTemplateData {

    /** A list of secondary cards. */
    @NonNull
    private final List<BaseTemplateData> mCombinedCardDataList;

    CombinedCardsTemplateData(@NonNull Parcel in) {
        super(in);
        mCombinedCardDataList = in.createTypedArrayList(BaseTemplateData.CREATOR);
    }

    private CombinedCardsTemplateData(@SmartspaceTarget.UiTemplateType int templateType,
            @Nullable SubItemInfo primaryItem,
            @Nullable SubItemInfo subtitleItem,
            @Nullable SubItemInfo subtitleSupplementalItem,
            @Nullable SubItemInfo supplementalLineItem,
            @Nullable SubItemInfo supplementalAlarmItem,
            int layoutWeight,
            @NonNull List<BaseTemplateData> combinedCardDataList) {
        super(templateType, primaryItem, subtitleItem, subtitleSupplementalItem,
                supplementalLineItem, supplementalAlarmItem, layoutWeight);

        mCombinedCardDataList = combinedCardDataList;
    }

    /** Returns the list of secondary cards. Can be null if not being set. */
    @NonNull
    public List<BaseTemplateData> getCombinedCardDataList() {
        return mCombinedCardDataList;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<CombinedCardsTemplateData> CREATOR =
            new Creator<CombinedCardsTemplateData>() {
                @Override
                public CombinedCardsTemplateData createFromParcel(Parcel in) {
                    return new CombinedCardsTemplateData(in);
                }

                @Override
                public CombinedCardsTemplateData[] newArray(int size) {
                    return new CombinedCardsTemplateData[size];
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
        if (!(o instanceof CombinedCardsTemplateData)) return false;
        if (!super.equals(o)) return false;
        CombinedCardsTemplateData that = (CombinedCardsTemplateData) o;
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
     * A builder for {@link CombinedCardsTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder extends BaseTemplateData.Builder {

        private final List<BaseTemplateData> mCombinedCardDataList;

        /**
         * A builder for {@link CombinedCardsTemplateData}.
         */
        public Builder(@NonNull List<BaseTemplateData> combinedCardDataList) {
            super(SmartspaceTarget.UI_TEMPLATE_COMBINED_CARDS);
            mCombinedCardDataList = Objects.requireNonNull(combinedCardDataList);
        }

        /**
         * Builds a new SmartspaceCombinedCardsUiTemplateData instance.
         *
         * @throws IllegalStateException if any required non-null field is null
         */
        @NonNull
        public CombinedCardsTemplateData build() {
            if (mCombinedCardDataList == null) {
                throw new IllegalStateException("Please assign a value to all @NonNull args.");
            }
            return new CombinedCardsTemplateData(getTemplateType(), getPrimaryItem(),
                    getSubtitleItem(), getSubtitleSupplemtnalItem(),
                    getSupplementalLineItem(), getSupplementalAlarmItem(), getLayoutWeight(),
                    mCombinedCardDataList);
        }
    }
}
