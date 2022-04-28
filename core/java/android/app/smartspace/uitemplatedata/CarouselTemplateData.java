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
import android.os.Parcelable;

import java.util.List;
import java.util.Objects;

/**
 * Holds all the relevant data needed to render a Smartspace card with the carousel Ui Template.
 *
 * This template will add a sub-card displaying a list of carousel items within the default-template
 * card:
 * <ul>
 *     <li> carouselItem1, carouselItem2, carouselItem3... </li>
 * </ul>
 *
 * @hide
 */
@SystemApi
public final class CarouselTemplateData extends BaseTemplateData {

    /** Lists of {@link CarouselItem}. */
    @NonNull
    private final List<CarouselItem> mCarouselItems;

    /** Tap action for the entire carousel secondary card, including the blank space */
    @Nullable
    private final TapAction mCarouselAction;

    CarouselTemplateData(@NonNull Parcel in) {
        super(in);
        mCarouselItems = in.createTypedArrayList(CarouselItem.CREATOR);
        mCarouselAction = in.readTypedObject(TapAction.CREATOR);
    }

    private CarouselTemplateData(@SmartspaceTarget.UiTemplateType int templateType,
            @Nullable SubItemInfo primaryItem,
            @Nullable SubItemInfo subtitleItem,
            @Nullable SubItemInfo subtitleSupplementalItem,
            @Nullable SubItemInfo supplementalLineItem,
            @Nullable SubItemInfo supplementalAlarmItem,
            int layoutWeight,
            @NonNull List<CarouselItem> carouselItems,
            @Nullable TapAction carouselAction) {
        super(templateType, primaryItem, subtitleItem, subtitleSupplementalItem,
                supplementalLineItem, supplementalAlarmItem, layoutWeight);

        mCarouselItems = carouselItems;
        mCarouselAction = carouselAction;
    }

    /** Returns the list of {@link CarouselItem}. Can be empty if not being set. */
    @NonNull
    public List<CarouselItem> getCarouselItems() {
        return mCarouselItems;
    }

    /** Returns the card's tap action. */
    @Nullable
    public TapAction getCarouselAction() {
        return mCarouselAction;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<CarouselTemplateData> CREATOR =
            new Creator<CarouselTemplateData>() {
                @Override
                public CarouselTemplateData createFromParcel(Parcel in) {
                    return new CarouselTemplateData(in);
                }

                @Override
                public CarouselTemplateData[] newArray(int size) {
                    return new CarouselTemplateData[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        super.writeToParcel(out, flags);
        out.writeTypedList(mCarouselItems);
        out.writeTypedObject(mCarouselAction, flags);
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CarouselTemplateData)) return false;
        if (!super.equals(o)) return false;
        CarouselTemplateData that = (CarouselTemplateData) o;
        return mCarouselItems.equals(that.mCarouselItems) && Objects.equals(mCarouselAction,
                that.mCarouselAction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mCarouselItems, mCarouselAction);
    }

    @Override
    public String toString() {
        return super.toString() + " + SmartspaceCarouselUiTemplateData{"
                + "mCarouselItems=" + mCarouselItems
                + ", mCarouselActions=" + mCarouselAction
                + '}';
    }

    /**
     * A builder for {@link CarouselTemplateData} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder extends BaseTemplateData.Builder {

        private final List<CarouselItem> mCarouselItems;
        private TapAction mCarouselAction;

        /**
         * A builder for {@link CarouselTemplateData}.
         */
        public Builder(@NonNull List<CarouselItem> carouselItems) {
            super(SmartspaceTarget.UI_TEMPLATE_CAROUSEL);
            mCarouselItems = Objects.requireNonNull(carouselItems);
        }

        /**
         * Sets the card tap action.
         */
        @NonNull
        public Builder setCarouselAction(@NonNull TapAction carouselAction) {
            mCarouselAction = carouselAction;
            return this;
        }

        /**
         * Builds a new {@link CarouselTemplateData} instance.
         *
         * @throws IllegalStateException if the carousel data is invalid.
         */
        @NonNull
        public CarouselTemplateData build() {
            if (mCarouselItems.isEmpty()) {
                throw new IllegalStateException("Carousel data is empty");
            }

            return new CarouselTemplateData(getTemplateType(), getPrimaryItem(),
                    getSubtitleItem(), getSubtitleSupplemtnalItem(),
                    getSupplementalLineItem(), getSupplementalAlarmItem(), getLayoutWeight(),
                    mCarouselItems, mCarouselAction);
        }
    }

    /**
     * Holds all the relevant data needed to render a carousel item.
     *
     * <ul>
     *     <li> upper text </li>
     *     <li> image </li>
     *     <li> lower text </li>
     * </ul>
     */
    public static final class CarouselItem implements Parcelable {

        /** Text which is above the image item. */
        @Nullable
        private final Text mUpperText;

        /** Image item. Can be empty. */
        @Nullable
        private final Icon mImage;

        /** Text which is under the image item. */
        @Nullable
        private final Text mLowerText;

        /**
         * Tap action for this {@link CarouselItem} instance. {@code mCarouselAction} is used if not
         * being set.
         */
        @Nullable
        private final TapAction mTapAction;

        CarouselItem(@NonNull Parcel in) {
            mUpperText = in.readTypedObject(Text.CREATOR);
            mImage = in.readTypedObject(Icon.CREATOR);
            mLowerText = in.readTypedObject(Text.CREATOR);
            mTapAction = in.readTypedObject(TapAction.CREATOR);
        }

        private CarouselItem(@Nullable Text upperText, @Nullable Icon image,
                @Nullable Text lowerText, @Nullable TapAction tapAction) {
            mUpperText = upperText;
            mImage = image;
            mLowerText = lowerText;
            mTapAction = tapAction;
        }

        @Nullable
        public Text getUpperText() {
            return mUpperText;
        }

        @Nullable
        public Icon getImage() {
            return mImage;
        }

        @Nullable
        public Text getLowerText() {
            return mLowerText;
        }

        @Nullable
        public TapAction getTapAction() {
            return mTapAction;
        }

        /**
         * @see Parcelable.Creator
         */
        @NonNull
        public static final Creator<CarouselItem> CREATOR =
                new Creator<CarouselItem>() {
                    @Override
                    public CarouselItem createFromParcel(Parcel in) {
                        return new CarouselItem(in);
                    }

                    @Override
                    public CarouselItem[] newArray(int size) {
                        return new CarouselItem[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeTypedObject(mUpperText, flags);
            out.writeTypedObject(mImage, flags);
            out.writeTypedObject(mLowerText, flags);
            out.writeTypedObject(mTapAction, flags);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CarouselItem)) return false;
            CarouselItem that = (CarouselItem) o;
            return SmartspaceUtils.isEqual(mUpperText, that.mUpperText) && Objects.equals(
                    mImage,
                    that.mImage) && SmartspaceUtils.isEqual(mLowerText, that.mLowerText)
                    && Objects.equals(mTapAction, that.mTapAction);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mUpperText, mImage, mLowerText, mTapAction);
        }

        @Override
        public String toString() {
            return "CarouselItem{"
                    + "mUpperText=" + mUpperText
                    + ", mImage=" + mImage
                    + ", mLowerText=" + mLowerText
                    + ", mTapAction=" + mTapAction
                    + '}';
        }

        /**
         * A builder for {@link CarouselItem} object.
         *
         * @hide
         */
        @SystemApi
        public static final class Builder {

            private Text mUpperText;
            private Icon mImage;
            private Text mLowerText;
            private TapAction mTapAction;

            /**
             * Sets the upper text.
             */
            @NonNull
            public Builder setUpperText(@Nullable Text upperText) {
                mUpperText = upperText;
                return this;
            }

            /**
             * Sets the image.
             */
            @NonNull
            public Builder setImage(@Nullable Icon image) {
                mImage = image;
                return this;
            }


            /**
             * Sets the lower text.
             */
            @NonNull
            public Builder setLowerText(@Nullable Text lowerText) {
                mLowerText = lowerText;
                return this;
            }

            /**
             * Sets the tap action.
             */
            @NonNull
            public Builder setTapAction(@Nullable TapAction tapAction) {
                mTapAction = tapAction;
                return this;
            }

            /**
             * Builds a new CarouselItem instance.
             *
             * @throws IllegalStateException if all the rendering data is empty.
             */
            @NonNull
            public CarouselItem build() {
                if (SmartspaceUtils.isEmpty(mUpperText) && mImage == null
                        && SmartspaceUtils.isEmpty(
                        mLowerText)) {
                    throw new IllegalStateException("Carousel data is empty");
                }
                return new CarouselItem(mUpperText, mImage, mLowerText, mTapAction);
            }
        }
    }
}
