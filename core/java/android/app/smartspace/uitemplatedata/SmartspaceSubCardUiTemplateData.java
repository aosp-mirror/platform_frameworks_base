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
 * Holds all the relevant data needed to render a Smartspace card with the sub-card Ui Template.
 *
 * @hide
 */
@SystemApi
public final class SmartspaceSubCardUiTemplateData extends SmartspaceDefaultUiTemplateData {

    /** Icon for the sub-card. */
    @NonNull
    private final SmartspaceIcon mSubCardIcon;

    /** Text for the sub-card, which shows below the icon when being set. */
    @Nullable
    private final SmartspaceText mSubCardText;

    /** Tap action for the sub-card secondary card. */
    @Nullable
    private final SmartspaceTapAction mSubCardAction;

    SmartspaceSubCardUiTemplateData(@NonNull Parcel in) {
        super(in);
        mSubCardIcon = in.readTypedObject(SmartspaceIcon.CREATOR);
        mSubCardText = in.readTypedObject(SmartspaceText.CREATOR);
        mSubCardAction = in.readTypedObject(SmartspaceTapAction.CREATOR);
    }

    private SmartspaceSubCardUiTemplateData(int templateType,
            @Nullable SmartspaceText titleText,
            @Nullable SmartspaceIcon titleIcon,
            @Nullable SmartspaceText subtitleText,
            @Nullable SmartspaceIcon subTitleIcon,
            @Nullable SmartspaceTapAction primaryTapAction,
            @Nullable SmartspaceText supplementalSubtitleText,
            @Nullable SmartspaceIcon supplementalSubtitleIcon,
            @Nullable SmartspaceTapAction supplementalSubtitleTapAction,
            @Nullable SmartspaceText supplementalAlarmText,
            @NonNull SmartspaceIcon subCardIcon,
            @Nullable SmartspaceText subCardText,
            @Nullable SmartspaceTapAction subCardAction) {
        super(templateType, titleText, titleIcon, subtitleText, subTitleIcon, primaryTapAction,
                supplementalSubtitleText, supplementalSubtitleIcon, supplementalSubtitleTapAction,
                supplementalAlarmText);
        mSubCardIcon = subCardIcon;
        mSubCardText = subCardText;
        mSubCardAction = subCardAction;
    }

    @NonNull
    public SmartspaceIcon getSubCardIcon() {
        return mSubCardIcon;
    }

    @Nullable
    public SmartspaceText getSubCardText() {
        return mSubCardText;
    }

    @Nullable
    public SmartspaceTapAction getSubCardAction() {
        return mSubCardAction;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<SmartspaceSubCardUiTemplateData> CREATOR =
            new Creator<SmartspaceSubCardUiTemplateData>() {
                @Override
                public SmartspaceSubCardUiTemplateData createFromParcel(Parcel in) {
                    return new SmartspaceSubCardUiTemplateData(in);
                }

                @Override
                public SmartspaceSubCardUiTemplateData[] newArray(int size) {
                    return new SmartspaceSubCardUiTemplateData[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        super.writeToParcel(out, flags);
        out.writeTypedObject(mSubCardIcon, flags);
        out.writeTypedObject(mSubCardText, flags);
        out.writeTypedObject(mSubCardAction, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SmartspaceSubCardUiTemplateData)) return false;
        if (!super.equals(o)) return false;
        SmartspaceSubCardUiTemplateData that = (SmartspaceSubCardUiTemplateData) o;
        return mSubCardIcon.equals(that.mSubCardIcon) && SmartspaceUtils.isEqual(mSubCardText,
                that.mSubCardText) && Objects.equals(mSubCardAction,
                that.mSubCardAction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mSubCardIcon, mSubCardText, mSubCardAction);
    }

    @Override
    public String toString() {
        return super.toString() + " + SmartspaceSubCardUiTemplateData{"
                + "mSubCardIcon=" + mSubCardIcon
                + ", mSubCardText=" + mSubCardText
                + ", mSubCardAction=" + mSubCardAction
                + '}';
    }

    /**
     * A builder for {@link SmartspaceSubCardUiTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder extends SmartspaceDefaultUiTemplateData.Builder {

        private final SmartspaceIcon mSubCardIcon;
        private SmartspaceText mSubCardText;
        private SmartspaceTapAction mSubCardAction;

        /**
         * A builder for {@link SmartspaceSubCardUiTemplateData}.
         */
        public Builder(@NonNull SmartspaceIcon subCardIcon) {
            super(SmartspaceTarget.UI_TEMPLATE_SUB_CARD);
            mSubCardIcon = Objects.requireNonNull(subCardIcon);
        }

        /**
         * Sets the card text.
         */
        @NonNull
        public Builder setSubCardText(@NonNull SmartspaceText subCardText) {
            mSubCardText = subCardText;
            return this;
        }

        /**
         * Sets the card tap action.
         */
        @NonNull
        public Builder setSubCardAction(@NonNull SmartspaceTapAction subCardAction) {
            mSubCardAction = subCardAction;
            return this;
        }

        /**
         * Builds a new SmartspaceSubCardUiTemplateData instance.
         */
        @NonNull
        public SmartspaceSubCardUiTemplateData build() {
            return new SmartspaceSubCardUiTemplateData(getTemplateType(), getTitleText(),
                    getTitleIcon(), getSubtitleText(), getSubtitleIcon(), getPrimaryTapAction(),
                    getSupplementalSubtitleText(), getSupplementalSubtitleIcon(),
                    getSupplementalSubtitleTapAction(), getSupplementalAlarmText(), mSubCardIcon,
                    mSubCardText,
                    mSubCardAction);
        }
    }
}
