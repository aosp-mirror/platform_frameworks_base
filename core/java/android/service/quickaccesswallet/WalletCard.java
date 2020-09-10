/*
 * Copyright 2020 The Android Open Source Project
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

package android.service.quickaccesswallet;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * A {@link WalletCard} can represent anything that a user might carry in their wallet -- a credit
 * card, library card, transit pass, etc. Cards are identified by a String identifier and contain a
 * card image, card image content description, and a {@link PendingIntent} to be used if the user
 * clicks on the card. Cards may be displayed with an icon and label, though these are optional.
 */
public final class WalletCard implements Parcelable {

    private final String mCardId;
    private final Icon mCardImage;
    private final CharSequence mContentDescription;
    private final PendingIntent mPendingIntent;
    private final Icon mCardIcon;
    private final CharSequence mCardLabel;

    private WalletCard(Builder builder) {
        this.mCardId = builder.mCardId;
        this.mCardImage = builder.mCardImage;
        this.mContentDescription = builder.mContentDescription;
        this.mPendingIntent = builder.mPendingIntent;
        this.mCardIcon = builder.mCardIcon;
        this.mCardLabel = builder.mCardLabel;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mCardId);
        mCardImage.writeToParcel(dest, flags);
        TextUtils.writeToParcel(mContentDescription, dest, flags);
        PendingIntent.writePendingIntentOrNullToParcel(mPendingIntent, dest);
        if (mCardIcon == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            mCardIcon.writeToParcel(dest, flags);
        }
        TextUtils.writeToParcel(mCardLabel, dest, flags);
    }

    private static WalletCard readFromParcel(Parcel source) {
        String cardId = source.readString();
        Icon cardImage = Icon.CREATOR.createFromParcel(source);
        CharSequence contentDesc = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        PendingIntent pendingIntent = PendingIntent.readPendingIntentOrNullFromParcel(source);
        Icon cardIcon = source.readByte() == 0 ? null : Icon.CREATOR.createFromParcel(source);
        CharSequence cardLabel = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        return new Builder(cardId, cardImage, contentDesc, pendingIntent)
                .setCardIcon(cardIcon)
                .setCardLabel(cardLabel)
                .build();
    }

    @NonNull
    public static final Creator<WalletCard> CREATOR =
            new Creator<WalletCard>() {
                @Override
                public WalletCard createFromParcel(Parcel source) {
                    return readFromParcel(source);
                }

                @Override
                public WalletCard[] newArray(int size) {
                    return new WalletCard[size];
                }
            };

    /**
     * The card id must be unique within the list of cards returned.
     */
    @NonNull
    public String getCardId() {
        return mCardId;
    }

    /**
     * The visual representation of the card. If the card image Icon is a bitmap, it should have a
     * width of {@link GetWalletCardsRequest#getCardWidthPx()} and a height of {@link
     * GetWalletCardsRequest#getCardHeightPx()}.
     */
    @NonNull
    public Icon getCardImage() {
        return mCardImage;
    }

    /**
     * The content description of the card image.
     */
    @NonNull
    public CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * If the user performs a click on the card, this PendingIntent will be sent. If the device is
     * locked, the wallet will first request device unlock before sending the pending intent.
     */
    @NonNull
    public PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    /**
     * An icon may be shown alongside the card image to convey information about how the card can be
     * used, or if some other action must be taken before using the card. For example, an NFC logo
     * could indicate that the card is NFC-enabled and will be provided to an NFC terminal if the
     * phone is held in close proximity to the NFC reader.
     *
     * <p>If the supplied Icon is backed by a bitmap, it should have width and height
     * {@link GetWalletCardsRequest#getIconSizePx()}.
     */
    @Nullable
    public Icon getCardIcon() {
        return mCardIcon;
    }

    /**
     * A card label may be shown alongside the card image to convey information about how the card
     * can be used, or if some other action must be taken before using the card. For example, an
     * NFC-enabled card could be labeled "Hold near reader" to inform the user of how to use NFC
     * cards when interacting with an NFC reader.
     *
     * <p>If the provided label is too long to fit on one line, it may be truncated and ellipsized.
     */
    @Nullable
    public CharSequence getCardLabel() {
        return mCardLabel;
    }

    /**
     * Builder for {@link WalletCard} objects. You must to provide cardId, cardImage,
     * contentDescription, and pendingIntent. If the card is opaque and should be shown with
     * elevation, set hasShadow to true. cardIcon and cardLabel are optional.
     */
    public static final class Builder {
        private String mCardId;
        private Icon mCardImage;
        private CharSequence mContentDescription;
        private PendingIntent mPendingIntent;
        private Icon mCardIcon;
        private CharSequence mCardLabel;

        /**
         * @param cardId             The card id must be non-null and unique within the list of
         *                           cards returned. <b>Note:
         *                           </b> this card ID should <b>not</b> contain PII (Personally
         *                           Identifiable Information, such as username or email address).
         * @param cardImage          The visual representation of the card. If the card image Icon
         *                           is a bitmap, it should have a width of {@link
         *                           GetWalletCardsRequest#getCardWidthPx()} and a height of {@link
         *                           GetWalletCardsRequest#getCardHeightPx()}. If the card image
         *                           does not have these dimensions, it may appear distorted when it
         *                           is scaled to fit these dimensions on screen. Bitmaps must be
         *                           of type {@link android.graphics.Bitmap.Config#HARDWARE} for
         *                           performance reasons.
         * @param contentDescription The content description of the card image. This field is
         *                           required and may not be null or empty.
         *                           <b>Note: </b> this message should <b>not</b> contain PII
         *                           (Personally Identifiable Information, such as username or email
         *                           address).
         * @param pendingIntent      If the user performs a click on the card, this PendingIntent
         *                           will be sent. If the device is locked, the wallet will first
         *                           request device unlock before sending the pending intent. It is
         *                           recommended that the pending intent be immutable (use {@link
         *                           PendingIntent#FLAG_IMMUTABLE}).
         */
        public Builder(@NonNull String cardId,
                @NonNull Icon cardImage,
                @NonNull CharSequence contentDescription,
                @NonNull PendingIntent pendingIntent) {
            mCardId = cardId;
            mCardImage = cardImage;
            mContentDescription = contentDescription;
            mPendingIntent = pendingIntent;
        }

        /**
         * An icon may be shown alongside the card image to convey information about how the card
         * can be used, or if some other action must be taken before using the card. For example, an
         * NFC logo could indicate that the card is NFC-enabled and will be provided to an NFC
         * terminal if the phone is held in close proximity to the NFC reader. This field is
         * optional.
         *
         * <p>If the supplied Icon is backed by a bitmap, it should have width and height
         * {@link GetWalletCardsRequest#getIconSizePx()}.
         */
        @NonNull
        public Builder setCardIcon(@Nullable Icon cardIcon) {
            mCardIcon = cardIcon;
            return this;
        }

        /**
         * A card label may be shown alongside the card image to convey information about how the
         * card can be used, or if some other action must be taken before using the card. For
         * example, an NFC-enabled card could be labeled "Hold near reader" to inform the user of
         * how to use NFC cards when interacting with an NFC reader. This field is optional.
         * <b>Note: </b> this card label should <b>not</b> contain PII (Personally Identifiable
         * Information, such as username or email address). If the provided label is too long to fit
         * on one line, it may be truncated and ellipsized.
         */
        @NonNull
        public Builder setCardLabel(@Nullable CharSequence cardLabel) {
            mCardLabel = cardLabel;
            return this;
        }

        /**
         * Builds a new {@link WalletCard} instance.
         *
         * @return A built response.
         */
        @NonNull
        public WalletCard build() {
            return new WalletCard(this);
        }
    }
}
