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
 * Register a dismiss request listener with the QuickAccessWalletService. This allows the service to
 * dismiss the wallet if it needs to show a payment activity in response to an NFC event.
 *
 * @hide
 */
public final class WalletServiceEventListenerRequest implements Parcelable {

    private final String mListenerId;

    /**
     * Construct a new {@code DismissWalletListenerRequest}.
     *
     * @param listenerKey A unique key that identifies the listener.
     */
    public WalletServiceEventListenerRequest(@NonNull String listenerKey) {
        mListenerId = listenerKey;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mListenerId);
    }

    private static WalletServiceEventListenerRequest readFromParcel(Parcel source) {
        String listenerId = source.readString();
        return new WalletServiceEventListenerRequest(listenerId);
    }

    @NonNull
    public static final Creator<WalletServiceEventListenerRequest> CREATOR =
            new Creator<WalletServiceEventListenerRequest>() {
                @Override
                public WalletServiceEventListenerRequest createFromParcel(Parcel source) {
                    return readFromParcel(source);
                }

                @Override
                public WalletServiceEventListenerRequest[] newArray(int size) {
                    return new WalletServiceEventListenerRequest[size];
                }
            };

    /**
     * Returns the unique key that identifies the wallet dismiss request listener.
     */
    @NonNull
    public String getListenerId() {
        return mListenerId;
    }
}
