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
 * This template will add a sub-card card within the default-template card:
 * <ul>
 *     <li> sub-card icon </li>
 *     <li> sub-card text </li>
 * </ul>
 *
 * @hide
 */
@SystemApi
public final class SubCardTemplateData extends BaseTemplateData {

    /** Icon for the sub-card. */
    @NonNull
    private final Icon mSubCardIcon;

    /** Text for the sub-card, which shows below the icon when being set. */
    @Nullable
    private final Text mSubCardText;

    /** Tap action for the sub-card secondary card. */
    @Nullable
    private final TapAction mSubCardAction;

    SubCardTemplateData(@NonNull Parcel in) {
        super(in);
        mSubCardIcon = in.readTypedObject(Icon.CREATOR);
        mSubCardText = in.readTypedObject(Text.CREATOR);
        mSubCardAction = in.readTypedObject(TapAction.CREATOR);
    }

    private SubCardTemplateData(int templateType,
            @Nullable SubItemInfo primaryItem,
            @Nullable SubItemInfo subtitleItem,
            @Nullable SubItemInfo subtitleSupplementalItem,
            @Nullable SubItemInfo supplementalLineItem,
            @Nullable SubItemInfo supplementalAlarmItem,
            int layoutWeight,
            @NonNull Icon subCardIcon,
            @Nullable Text subCardText,
            @Nullable TapAction subCardAction) {
        super(templateType, primaryItem, subtitleItem, subtitleSupplementalItem,
                supplementalLineItem, supplementalAlarmItem, layoutWeight);

        mSubCardIcon = subCardIcon;
        mSubCardText = subCardText;
        mSubCardAction = subCardAction;
    }

    /** Returns the sub-card card's icon. */
    @NonNull
    public Icon getSubCardIcon() {
        return mSubCardIcon;
    }

    /** Returns the sub-card card's text. */
    @Nullable
    public Text getSubCardText() {
        return mSubCardText;
    }

    /** Returns the sub-card card's tap action. */
    @Nullable
    public TapAction getSubCardAction() {
        return mSubCardAction;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<SubCardTemplateData> CREATOR =
            new Creator<SubCardTemplateData>() {
                @Override
                public SubCardTemplateData createFromParcel(Parcel in) {
                    return new SubCardTemplateData(in);
                }

                @Override
                public SubCardTemplateData[] newArray(int size) {
                    return new SubCardTemplateData[size];
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
        if (!(o instanceof SubCardTemplateData)) return false;
        if (!super.equals(o)) return false;
        SubCardTemplateData that = (SubCardTemplateData) o;
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
     * A builder for {@link SubCardTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder extends BaseTemplateData.Builder {

        private final Icon mSubCardIcon;
        private Text mSubCardText;
        private TapAction mSubCardAction;

        /**
         * A builder for {@link SubCardTemplateData}.
         */
        public Builder(@NonNull Icon subCardIcon) {
            super(SmartspaceTarget.UI_TEMPLATE_SUB_CARD);
            mSubCardIcon = Objects.requireNonNull(subCardIcon);
        }

        /**
         * Sets the card text.
         */
        @NonNull
        public Builder setSubCardText(@NonNull Text subCardText) {
            mSubCardText = subCardText;
            return this;
        }

        /**
         * Sets the card tap action.
         */
        @NonNull
        public Builder setSubCardAction(@NonNull TapAction subCardAction) {
            mSubCardAction = subCardAction;
            return this;
        }

        /**
         * Builds a new SmartspaceSubCardUiTemplateData instance.
         */
        @NonNull
        public SubCardTemplateData build() {
            return new SubCardTemplateData(getTemplateType(), getPrimaryItem(),
                    getSubtitleItem(), getSubtitleSupplemtnalItem(),
                    getSupplementalLineItem(), getSupplementalAlarmItem(), getLayoutWeight(),
                    mSubCardIcon,
                    mSubCardText,
                    mSubCardAction);
        }
    }
}
