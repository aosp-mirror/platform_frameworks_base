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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a request from the {@link QuickAccessWalletService wallet app} to the Quick Access
 * Wallet in System UI. Background events may necessitate that the Quick Access Wallet update its
 * view. For example, if the wallet application handles an NFC payment while the Quick Access Wallet
 * is being shown, it needs to tell the Quick Access Wallet so that the wallet can be dismissed and
 * Activity showing the payment can be displayed to the user.
 */
public final class WalletServiceEvent implements Parcelable {

    /**
     * An NFC payment has started. If the Quick Access Wallet is in a system window, it will need to
     * be dismissed so that an Activity showing the payment can be displayed.
     */
    public static final int TYPE_NFC_PAYMENT_STARTED = 1;

    /**
     * Indicates that the wallet cards have changed and should be refreshed.
     * @hide
     */
    public static final int TYPE_WALLET_CARDS_UPDATED = 2;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_NFC_PAYMENT_STARTED, TYPE_WALLET_CARDS_UPDATED})
    public @interface EventType {
    }

    @EventType
    private final int mEventType;

    /**
     * Creates a new DismissWalletRequest.
     */
    public WalletServiceEvent(@EventType int eventType) {
        this.mEventType = eventType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mEventType);
    }

    @NonNull
    public static final Creator<WalletServiceEvent> CREATOR =
            new Creator<WalletServiceEvent>() {
                @Override
                public WalletServiceEvent createFromParcel(Parcel source) {
                    int eventType = source.readInt();
                    return new WalletServiceEvent(eventType);
                }

                @Override
                public WalletServiceEvent[] newArray(int size) {
                    return new WalletServiceEvent[size];
                }
            };

    /**
     * @return the event type
     */
    @EventType
    public int getEventType() {
        return mEventType;
    }
}
