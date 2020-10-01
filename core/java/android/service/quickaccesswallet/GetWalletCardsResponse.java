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
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * The response for an {@link GetWalletCardsRequest} contains a list of wallet cards and the index
 * of the card that should initially be displayed in the 'selected' position.
 */
public final class GetWalletCardsResponse implements Parcelable {

    private final List<WalletCard> mWalletCards;
    private final int mSelectedIndex;

    /**
     * Construct a new response.
     *
     * @param walletCards   The list of wallet cards. The list may be empty but must NOT be larger
     *                      than {@link GetWalletCardsRequest#getMaxCards()}. The list may not
     *                      contain null values.
     * @param selectedIndex The index of the card that should be presented as the initially
     *                      'selected' card. The index must be greater than or equal to zero and
     *                      less than the size of the list of walletCards (unless the list is empty
     *                      in which case the value may be 0).
     */
    public GetWalletCardsResponse(@NonNull List<WalletCard> walletCards, int selectedIndex) {
        this.mWalletCards = walletCards;
        this.mSelectedIndex = selectedIndex;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mWalletCards.size());
        dest.writeParcelableList(mWalletCards, flags);
        dest.writeInt(mSelectedIndex);
    }

    private static GetWalletCardsResponse readFromParcel(Parcel source) {
        int size = source.readInt();
        List<WalletCard> walletCards =
                source.readParcelableList(new ArrayList<>(size), WalletCard.class.getClassLoader());
        int selectedIndex = source.readInt();
        return new GetWalletCardsResponse(walletCards, selectedIndex);
    }

    @NonNull
    public static final Creator<GetWalletCardsResponse> CREATOR =
            new Creator<GetWalletCardsResponse>() {
                @Override
                public GetWalletCardsResponse createFromParcel(Parcel source) {
                    return readFromParcel(source);
                }

                @Override
                public GetWalletCardsResponse[] newArray(int size) {
                    return new GetWalletCardsResponse[size];
                }
            };

    /**
     * The list of {@link WalletCard}s. The size of this list should not exceed {@link
     * GetWalletCardsRequest#getMaxCards()}.
     */
    @NonNull
    public List<WalletCard> getWalletCards() {
        return mWalletCards;
    }

    /**
     * The {@code selectedIndex} represents the index of the card that should be presented in the
     * 'selected' position when the cards are initially displayed in the quick access wallet.  The
     * {@code selectedIndex} should be greater than or equal to zero and less than the size of the
     * list of {@link WalletCard walletCards}, unless the list is empty in which case the {@code
     * selectedIndex} can take any value. 0 is a nice round number for such cases.
     */
    public int getSelectedIndex() {
        return mSelectedIndex;
    }
}
