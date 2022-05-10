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
 * Holds all the relevant data needed to render a Smartspace card with the sub-list Ui Template.
 *
 * This template will add a sub-list card within the default-template card:
 * <ul>
 *     <li> sub-list text1       sub-list icon </li>
 *     <li> sub-list text2 </li>
 *     <li> sub-list text3 </li>
 *     ...
 * </ul>
 *
 * @hide
 */
@SystemApi
public final class SubListTemplateData extends BaseTemplateData {

    @Nullable
    private final Icon mSubListIcon;
    @NonNull
    private final List<Text> mSubListTexts;

    /** Tap action for the sub-list secondary card. */
    @Nullable
    private final TapAction mSubListAction;

    SubListTemplateData(@NonNull Parcel in) {
        super(in);
        mSubListIcon = in.readTypedObject(Icon.CREATOR);
        mSubListTexts = in.createTypedArrayList(Text.CREATOR);
        mSubListAction = in.readTypedObject(TapAction.CREATOR);
    }

    private SubListTemplateData(@SmartspaceTarget.UiTemplateType int templateType,
            @Nullable SubItemInfo primaryItem,
            @Nullable SubItemInfo subtitleItem,
            @Nullable SubItemInfo subtitleSupplementalItem,
            @Nullable SubItemInfo supplementalLineItem,
            @Nullable SubItemInfo supplementalAlarmItem,
            int layoutWeight,
            @Nullable Icon subListIcon,
            @NonNull List<Text> subListTexts,
            @Nullable TapAction subListAction) {
        super(templateType, primaryItem, subtitleItem, subtitleSupplementalItem,
                supplementalLineItem, supplementalAlarmItem, layoutWeight);

        mSubListIcon = subListIcon;
        mSubListTexts = subListTexts;
        mSubListAction = subListAction;
    }

    /** Returns the sub-list card's icon. */
    @Nullable
    public Icon getSubListIcon() {
        return mSubListIcon;
    }

    /** Returns the sub-list card's texts list. */
    @NonNull
    public List<Text> getSubListTexts() {
        return mSubListTexts;
    }

    /** Returns the sub-list card's tap action. */
    @Nullable
    public TapAction getSubListAction() {
        return mSubListAction;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<SubListTemplateData> CREATOR =
            new Creator<SubListTemplateData>() {
                @Override
                public SubListTemplateData createFromParcel(Parcel in) {
                    return new SubListTemplateData(in);
                }

                @Override
                public SubListTemplateData[] newArray(int size) {
                    return new SubListTemplateData[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        super.writeToParcel(out, flags);
        out.writeTypedObject(mSubListIcon, flags);
        out.writeTypedList(mSubListTexts);
        out.writeTypedObject(mSubListAction, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubListTemplateData)) return false;
        if (!super.equals(o)) return false;
        SubListTemplateData that = (SubListTemplateData) o;
        return Objects.equals(mSubListIcon, that.mSubListIcon) && Objects.equals(
                mSubListTexts, that.mSubListTexts) && Objects.equals(mSubListAction,
                that.mSubListAction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mSubListIcon, mSubListTexts, mSubListAction);
    }

    @Override
    public String toString() {
        return super.toString() + " + SmartspaceSubListUiTemplateData{"
                + "mSubListIcon=" + mSubListIcon
                + ", mSubListTexts=" + mSubListTexts
                + ", mSubListAction=" + mSubListAction
                + '}';
    }

    /**
     * A builder for {@link SubListTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder extends BaseTemplateData.Builder {

        private Icon mSubListIcon;
        private final List<Text> mSubListTexts;
        private TapAction mSubListAction;

        /**
         * A builder for {@link SubListTemplateData}.
         */
        public Builder(@NonNull List<Text> subListTexts) {
            super(SmartspaceTarget.UI_TEMPLATE_SUB_LIST);
            mSubListTexts = Objects.requireNonNull(subListTexts);
        }

        /**
         * Sets the sub-list card icon.
         */
        @NonNull
        public Builder setSubListIcon(@NonNull Icon subListIcon) {
            mSubListIcon = subListIcon;
            return this;
        }

        /**
         * Sets the card tap action.
         */
        @NonNull
        public Builder setSubListAction(@NonNull TapAction subListAction) {
            mSubListAction = subListAction;
            return this;
        }

        /**
         * Builds a new SmartspaceSubListUiTemplateData instance.
         */
        @NonNull
        public SubListTemplateData build() {
            return new SubListTemplateData(getTemplateType(), getPrimaryItem(),
                    getSubtitleItem(), getSubtitleSupplemtnalItem(),
                    getSupplementalLineItem(), getSupplementalAlarmItem(), getLayoutWeight(),
                    mSubListIcon,
                    mSubListTexts,
                    mSubListAction);
        }
    }
}
