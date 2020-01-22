/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a request to a {@link QuickAccessWalletService} to select a particular {@link
 * WalletCard walletCard}. Card selection events are transmitted to the WalletService so that the
 * selected card may be used by the NFC payment service.
 */
public final class SelectWalletCardRequest implements Parcelable {

    private final String mCardId;

    /**
     * Creates a new GetWalletCardsRequest.
     *
     * @param cardId The {@link WalletCard#getCardId() cardId} of the wallet card that is currently
     *               selected.
     */
    public SelectWalletCardRequest(@NonNull String cardId) {
        this.mCardId = cardId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mCardId);
    }

    @NonNull
    public static final Creator<SelectWalletCardRequest> CREATOR =
            new Creator<SelectWalletCardRequest>() {
                @Override
                public SelectWalletCardRequest createFromParcel(Parcel source) {
                    String cardId = source.readString();
                    return new SelectWalletCardRequest(cardId);
                }

                @Override
                public SelectWalletCardRequest[] newArray(int size) {
                    return new SelectWalletCardRequest[size];
                }
            };

    /**
     * The {@link WalletCard#getCardId() cardId} of the wallet card that is currently selected.
     */
    @NonNull
    public String getCardId() {
        return mCardId;
    }
}
