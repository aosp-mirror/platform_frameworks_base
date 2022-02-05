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
 * @hide
 */
@SystemApi
public final class SmartspaceSubListUiTemplateData extends SmartspaceDefaultUiTemplateData {

    @Nullable
    private final SmartspaceIcon mSubListIcon;
    @NonNull
    private final List<SmartspaceText> mSubListTexts;

    /** Tap action for the sub-list secondary card. */
    @Nullable
    private final SmartspaceTapAction mSubListAction;

    SmartspaceSubListUiTemplateData(@NonNull Parcel in) {
        super(in);
        mSubListIcon = in.readTypedObject(SmartspaceIcon.CREATOR);
        mSubListTexts = in.createTypedArrayList(SmartspaceText.CREATOR);
        mSubListAction = in.readTypedObject(SmartspaceTapAction.CREATOR);
    }

    private SmartspaceSubListUiTemplateData(@SmartspaceTarget.UiTemplateType int templateType,
            @Nullable SmartspaceText titleText,
            @Nullable SmartspaceIcon titleIcon,
            @Nullable SmartspaceText subtitleText,
            @Nullable SmartspaceIcon subTitleIcon,
            @Nullable SmartspaceTapAction primaryTapAction,
            @Nullable SmartspaceText supplementalSubtitleText,
            @Nullable SmartspaceIcon supplementalSubtitleIcon,
            @Nullable SmartspaceTapAction supplementalSubtitleTapAction,
            @Nullable SmartspaceText supplementalAlarmText,
            @Nullable SmartspaceIcon subListIcon,
            @NonNull List<SmartspaceText> subListTexts,
            @Nullable SmartspaceTapAction subListAction) {
        super(templateType, titleText, titleIcon, subtitleText, subTitleIcon, primaryTapAction,
                supplementalSubtitleText, supplementalSubtitleIcon, supplementalSubtitleTapAction,
                supplementalAlarmText);
        mSubListIcon = subListIcon;
        mSubListTexts = subListTexts;
        mSubListAction = subListAction;
    }

    @Nullable
    public SmartspaceIcon getSubListIcon() {
        return mSubListIcon;
    }

    @NonNull
    public List<SmartspaceText> getSubListTexts() {
        return mSubListTexts;
    }

    @Nullable
    public SmartspaceTapAction getSubListAction() {
        return mSubListAction;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<SmartspaceSubListUiTemplateData> CREATOR =
            new Creator<SmartspaceSubListUiTemplateData>() {
                @Override
                public SmartspaceSubListUiTemplateData createFromParcel(Parcel in) {
                    return new SmartspaceSubListUiTemplateData(in);
                }

                @Override
                public SmartspaceSubListUiTemplateData[] newArray(int size) {
                    return new SmartspaceSubListUiTemplateData[size];
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
        if (!(o instanceof SmartspaceSubListUiTemplateData)) return false;
        if (!super.equals(o)) return false;
        SmartspaceSubListUiTemplateData that = (SmartspaceSubListUiTemplateData) o;
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
     * A builder for {@link SmartspaceSubListUiTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder extends SmartspaceDefaultUiTemplateData.Builder {

        private SmartspaceIcon mSubListIcon;
        private final List<SmartspaceText> mSubListTexts;
        private SmartspaceTapAction mSubListAction;

        /**
         * A builder for {@link SmartspaceSubListUiTemplateData}.
         */
        public Builder(@NonNull List<SmartspaceText> subListTexts) {
            super(SmartspaceTarget.UI_TEMPLATE_SUB_LIST);
            mSubListTexts = Objects.requireNonNull(subListTexts);
        }

        /**
         * Sets the sub-list card icon.
         */
        @NonNull
        public Builder setSubListIcon(@NonNull SmartspaceIcon subListIcon) {
            mSubListIcon = subListIcon;
            return this;
        }

        /**
         * Sets the card tap action.
         */
        @NonNull
        public Builder setSubListAction(@NonNull SmartspaceTapAction subListAction) {
            mSubListAction = subListAction;
            return this;
        }

        /**
         * Builds a new SmartspaceSubListUiTemplateData instance.
         */
        @NonNull
        public SmartspaceSubListUiTemplateData build() {
            return new SmartspaceSubListUiTemplateData(getTemplateType(), getTitleText(),
                    getTitleIcon(), getSubtitleText(), getSubtitleIcon(), getPrimaryTapAction(),
                    getSupplementalSubtitleText(), getSupplementalSubtitleIcon(),
                    getSupplementalSubtitleTapAction(), getSupplementalAlarmText(), mSubListIcon,
                    mSubListTexts,
                    mSubListAction);
        }
    }
}
