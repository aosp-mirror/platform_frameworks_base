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
 * This template will add a sub-image card within the default-template card:
 * <ul>
 *     <li> sub-image text1 </li>   <ul>
 *     <li> sub-image text2 </li>        image (can be a GIF)
 *     ...                          </ul>
 * </ul>
 *
 * @hide
 */
@SystemApi
public final class SubImageTemplateData extends BaseTemplateData {

    /** Texts are shown next to the image as a vertical list */
    @NonNull
    private final List<Text> mSubImageTexts;

    /** If multiple images are passed in, they will be rendered as GIF. */
    @NonNull
    private final List<Icon> mSubImages;

    /** Tap action for the sub-image secondary card. */
    @Nullable
    private final TapAction mSubImageAction;

    SubImageTemplateData(@NonNull Parcel in) {
        super(in);
        mSubImageTexts = in.createTypedArrayList(Text.CREATOR);
        mSubImages = in.createTypedArrayList(Icon.CREATOR);
        mSubImageAction = in.readTypedObject(TapAction.CREATOR);
    }

    private SubImageTemplateData(@SmartspaceTarget.UiTemplateType int templateType,
            @Nullable SubItemInfo primaryItem,
            @Nullable SubItemInfo subtitleItem,
            @Nullable SubItemInfo subtitleSupplementalItem,
            @Nullable SubItemInfo supplementalLineItem,
            @Nullable SubItemInfo supplementalAlarmItem,
            int layoutWeight,
            @NonNull List<Text> subImageTexts,
            @NonNull List<Icon> subImages,
            @Nullable TapAction subImageAction) {
        super(templateType, primaryItem, subtitleItem, subtitleSupplementalItem,
                supplementalLineItem, supplementalAlarmItem, layoutWeight);

        mSubImageTexts = subImageTexts;
        mSubImages = subImages;
        mSubImageAction = subImageAction;
    }

    /** Returns the list of sub-image card's texts. Can be empty if not being set. */
    @NonNull
    public List<Text> getSubImageTexts() {
        return mSubImageTexts;
    }

    /**
     * Returns the list of sub-image card's image. It's a single-element list if it's a static
     * image, or a multi-elements list if it's a GIF.
     */
    @NonNull
    public List<Icon> getSubImages() {
        return mSubImages;
    }

    /** Returns the sub-image card's tap action. */
    @Nullable
    public TapAction getSubImageAction() {
        return mSubImageAction;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<SubImageTemplateData> CREATOR =
            new Creator<SubImageTemplateData>() {
                @Override
                public SubImageTemplateData createFromParcel(Parcel in) {
                    return new SubImageTemplateData(in);
                }

                @Override
                public SubImageTemplateData[] newArray(int size) {
                    return new SubImageTemplateData[size];
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
        if (!(o instanceof SubImageTemplateData)) return false;
        if (!super.equals(o)) return false;
        SubImageTemplateData that = (SubImageTemplateData) o;
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
     * A builder for {@link SubImageTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder extends BaseTemplateData.Builder {

        private final List<Text> mSubImageTexts;
        private final List<Icon> mSubImages;
        private TapAction mSubImageAction;

        /**
         * A builder for {@link SubImageTemplateData}.
         */
        public Builder(@NonNull List<Text> subImageTexts,
                @NonNull List<Icon> subImages) {
            super(SmartspaceTarget.UI_TEMPLATE_SUB_IMAGE);
            mSubImageTexts = Objects.requireNonNull(subImageTexts);
            mSubImages = Objects.requireNonNull(subImages);
        }

        /**
         * Sets the card tap action.
         */
        @NonNull
        public Builder setSubImageAction(@NonNull TapAction subImageAction) {
            mSubImageAction = subImageAction;
            return this;
        }

        /**
         * Builds a new SmartspaceSubImageUiTemplateData instance.
         */
        @NonNull
        public SubImageTemplateData build() {
            return new SubImageTemplateData(getTemplateType(), getPrimaryItem(),
                    getSubtitleItem(), getSubtitleSupplemtnalItem(),
                    getSupplementalLineItem(), getSupplementalAlarmItem(), getLayoutWeight(),
                    mSubImageTexts,
                    mSubImages,
                    mSubImageAction);
        }
    }
}
