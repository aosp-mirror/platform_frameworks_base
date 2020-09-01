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
import android.annotation.Nullable;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * Error response for an {@link GetWalletCardsRequest}.
 */
public final class GetWalletCardsError implements Parcelable {

    private final Icon mIcon;
    private final CharSequence mMessage;

    /**
     * Construct a new error response. If provided, the icon and message will be displayed to the
     * user.
     *
     * @param icon    an icon to be shown to the user next to the message. Optional.
     * @param message message to be shown to the user. Optional.
     */
    public GetWalletCardsError(@Nullable Icon icon, @Nullable CharSequence message) {
        mIcon = icon;
        mMessage = message;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        if (mIcon == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            mIcon.writeToParcel(dest, flags);
        }
        TextUtils.writeToParcel(mMessage, dest, flags);
    }

    private static GetWalletCardsError readFromParcel(Parcel source) {
        Icon icon = source.readByte() == 0 ? null : Icon.CREATOR.createFromParcel(source);
        CharSequence message = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        return new GetWalletCardsError(icon, message);
    }

    @NonNull
    public static final Creator<GetWalletCardsError> CREATOR =
            new Creator<GetWalletCardsError>() {
                @Override
                public GetWalletCardsError createFromParcel(Parcel source) {
                    return readFromParcel(source);
                }

                @Override
                public GetWalletCardsError[] newArray(int size) {
                    return new GetWalletCardsError[size];
                }
            };

    /**
     * An icon that may be displayed with the message to provide a visual indication of why cards
     * could not be provided in the Quick Access Wallet.
     */
    @Nullable
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * A localized message that may be shown to the user in the event that the wallet cards cannot
     * be retrieved. <b>Note: </b> this message should <b>not</b> contain PII (Personally
     * Identifiable Information, such as username or email address).
     */
    @Nullable
    public CharSequence getMessage() {
        return mMessage;
    }
}
