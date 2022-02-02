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
 * Holds all the relevant data needed to render a Smartspace card with the sub-image Ui Template.
 *
 * @hide
 */
@SystemApi
public final class SmartspaceSubImageUiTemplateData extends SmartspaceDefaultUiTemplateData {

    /** Texts are shown next to the image as a vertical list */
    @NonNull
    private final List<SmartspaceText> mSubImageTexts;

    /** If multiple images are passed in, they will be rendered as GIF. */
    @NonNull
    private final List<SmartspaceIcon> mSubImages;

    /** Tap action for the sub-image secondary card. */
    @Nullable
    private final SmartspaceTapAction mSubImageAction;

    SmartspaceSubImageUiTemplateData(@NonNull Parcel in) {
        super(in);
        mSubImageTexts = in.createTypedArrayList(SmartspaceText.CREATOR);
        mSubImages = in.createTypedArrayList(SmartspaceIcon.CREATOR);
        mSubImageAction = in.readTypedObject(SmartspaceTapAction.CREATOR);
    }

    private SmartspaceSubImageUiTemplateData(@SmartspaceTarget.UiTemplateType int templateType,
            @Nullable SmartspaceText titleText,
            @Nullable SmartspaceIcon titleIcon,
            @Nullable SmartspaceText subtitleText,
            @Nullable SmartspaceIcon subTitleIcon,
            @Nullable SmartspaceTapAction primaryTapAction,
            @Nullable SmartspaceText supplementalSubtitleText,
            @Nullable SmartspaceIcon supplementalSubtitleIcon,
            @Nullable SmartspaceTapAction supplementalSubtitleTapAction,
            @Nullable SmartspaceText supplementalAlarmText,
            @NonNull List<SmartspaceText> subImageTexts,
            @NonNull List<SmartspaceIcon> subImages,
            @Nullable SmartspaceTapAction subImageAction) {
        super(templateType, titleText, titleIcon, subtitleText, subTitleIcon, primaryTapAction,
                supplementalSubtitleText, supplementalSubtitleIcon, supplementalSubtitleTapAction,
                supplementalAlarmText);
        mSubImageTexts = subImageTexts;
        mSubImages = subImages;
        mSubImageAction = subImageAction;
    }

    @NonNull
    public List<SmartspaceText> getSubImageTexts() {
        return mSubImageTexts;
    }

    @NonNull
    public List<SmartspaceIcon> getSubImages() {
        return mSubImages;
    }

    @Nullable
    public SmartspaceTapAction getSubImageAction() {
        return mSubImageAction;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<SmartspaceSubImageUiTemplateData> CREATOR =
            new Creator<SmartspaceSubImageUiTemplateData>() {
                @Override
                public SmartspaceSubImageUiTemplateData createFromParcel(Parcel in) {
                    return new SmartspaceSubImageUiTemplateData(in);
                }

                @Override
                public SmartspaceSubImageUiTemplateData[] newArray(int size) {
                    return new SmartspaceSubImageUiTemplateData[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        super.writeToParcel(out, flags);
        out.writeTypedList(mSubImageTexts);
        out.writeTypedList(mSubImages);
        out.writeTypedObject(mSubImageAction, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SmartspaceSubImageUiTemplateData)) return false;
        if (!super.equals(o)) return false;
        SmartspaceSubImageUiTemplateData that = (SmartspaceSubImageUiTemplateData) o;
        return Objects.equals(mSubImageTexts, that.mSubImageTexts)
                && Objects.equals(mSubImages, that.mSubImages) && Objects.equals(
                mSubImageAction, that.mSubImageAction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mSubImageTexts, mSubImages, mSubImageAction);
    }

    @Override
    public String toString() {
        return super.toString() + " + SmartspaceSubImageUiTemplateData{"
                + "mSubImageTexts=" + mSubImageTexts
                + ", mSubImages=" + mSubImages
                + ", mSubImageAction=" + mSubImageAction
                + '}';
    }

    /**
     * A builder for {@link SmartspaceSubImageUiTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder extends SmartspaceDefaultUiTemplateData.Builder {

        private final List<SmartspaceText> mSubImageTexts;
        private final List<SmartspaceIcon> mSubImages;
        private SmartspaceTapAction mSubImageAction;

        /**
         * A builder for {@link SmartspaceSubImageUiTemplateData}.
         */
        public Builder(@NonNull List<SmartspaceText> subImageTexts,
                @NonNull List<SmartspaceIcon> subImages) {
            super(SmartspaceTarget.UI_TEMPLATE_SUB_IMAGE);
            mSubImageTexts = Objects.requireNonNull(subImageTexts);
            mSubImages = Objects.requireNonNull(subImages);
        }

        /**
         * Sets the card tap action.
         */
        @NonNull
        public Builder setSubImageAction(@NonNull SmartspaceTapAction subImageAction) {
            mSubImageAction = subImageAction;
            return this;
        }

        /**
         * Builds a new SmartspaceSubImageUiTemplateData instance.
         */
        @NonNull
        public SmartspaceSubImageUiTemplateData build() {
            return new SmartspaceSubImageUiTemplateData(getTemplateType(), getTitleText(),
                    getTitleIcon(), getSubtitleText(), getSubtitleIcon(), getPrimaryTapAction(),
                    getSupplementalSubtitleText(), getSupplementalSubtitleIcon(),
                    getSupplementalSubtitleTapAction(), getSupplementalAlarmText(), mSubImageTexts,
                    mSubImages,
                    mSubImageAction);
        }
    }
}
