/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.telephony;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

import android.annotation.IntDef;

/**
 * Class for the information of a UICC slot.
 * @hide
 */
@SystemApi
public class UiccSlotInfo implements Parcelable {
    /**
     * Card state.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "CARD_STATE_INFO_" }, value = {
            CARD_STATE_INFO_ABSENT,
            CARD_STATE_INFO_PRESENT,
            CARD_STATE_INFO_ERROR,
            CARD_STATE_INFO_RESTRICTED
    })
    public @interface CardStateInfo {}

    /** Card state absent. */
    public static final int CARD_STATE_INFO_ABSENT = 1;

    /** Card state present. */
    public static final int CARD_STATE_INFO_PRESENT = 2;

    /** Card state error. */
    public static final int CARD_STATE_INFO_ERROR = 3;

    /** Card state restricted. */
    public static final int CARD_STATE_INFO_RESTRICTED = 4;

    public final boolean isActive;
    public final boolean isEuicc;
    public final String cardId;
    public final @CardStateInfo int cardStateInfo;

    public static final Creator<UiccSlotInfo> CREATOR = new Creator<UiccSlotInfo>() {
        @Override
        public UiccSlotInfo createFromParcel(Parcel in) {
            return new UiccSlotInfo(in);
        }

        @Override
        public UiccSlotInfo[] newArray(int size) {
            return new UiccSlotInfo[size];
        }
    };

    private UiccSlotInfo(Parcel in) {
        isActive = in.readByte() != 0;
        isEuicc = in.readByte() != 0;
        cardId = in.readString();
        cardStateInfo = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (isActive ? 1 : 0));
        dest.writeByte((byte) (isEuicc ? 1 : 0));
        dest.writeString(cardId);
        dest.writeInt(cardStateInfo);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public UiccSlotInfo(boolean isActive, boolean isEuicc, String cardId,
            @CardStateInfo int cardStateInfo) {
        this.isActive = isActive;
        this.isEuicc = isEuicc;
        this.cardId = cardId;
        this.cardStateInfo = cardStateInfo;
    }

    public boolean getIsActive() {
        return isActive;
    }

    public boolean getIsEuicc() {
        return isEuicc;
    }

    public String getCardId() {
        return cardId;
    }

    @CardStateInfo
    public int getCardStateInfo() {
        return cardStateInfo;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        UiccSlotInfo that = (UiccSlotInfo) obj;
        return (isActive == that.isActive)
                && (isEuicc == that.isEuicc)
                && (cardId == that.cardId)
                && (cardStateInfo == that.cardStateInfo);
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + (isActive ? 1 : 0);
        result = 31 * result + (isEuicc ? 1 : 0);
        result = 31 * result + Objects.hashCode(cardId);
        result = 31 * result + cardStateInfo;
        return result;
    }

    @Override
    public String toString() {
        return "UiccSlotInfo (isActive="
                + isActive
                + ", isEuicc="
                + isEuicc
                + ", cardId="
                + cardId
                + ", cardState="
                + cardStateInfo
                + ")";
    }
}
