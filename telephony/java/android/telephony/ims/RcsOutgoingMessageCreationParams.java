/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephony.ims;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * {@link RcsOutgoingMessageCreationParams} is a collection of parameters that should be passed
 * into {@link RcsThread#addOutgoingMessage(RcsOutgoingMessageCreationParams)} to generate an
 * {@link RcsOutgoingMessage} on that {@link RcsThread}
 *
 * @hide
 */
public final class RcsOutgoingMessageCreationParams extends RcsMessageCreationParams
        implements Parcelable {
    /**
     * A builder to instantiate and persist an {@link RcsOutgoingMessage}
     */
    public static class Builder extends RcsMessageCreationParams.Builder {

        /**
         * Creates a new {@link Builder} to create an instance of
         * {@link RcsOutgoingMessageCreationParams}.
         *
         * @param originationTimestamp The timestamp of {@link RcsMessage} creation. The origination
         *                             timestamp value in milliseconds passed after midnight,
         *                             January 1, 1970 UTC
         * @param subscriptionId The subscription ID that was used to send or receive this
         *                       {@link RcsMessage}
         * @see android.telephony.SubscriptionInfo#getSubscriptionId()
         */
        public Builder(long originationTimestamp, int subscriptionId) {
            super(originationTimestamp, subscriptionId);
        }

        /**
         * Creates configuration parameters for a new message.
         */
        public RcsOutgoingMessageCreationParams build() {
            return new RcsOutgoingMessageCreationParams(this);
        }
    }

    private RcsOutgoingMessageCreationParams(Builder builder) {
        super(builder);
    }

    private RcsOutgoingMessageCreationParams(Parcel in) {
        super(in);
    }

    public static final @android.annotation.NonNull Creator<RcsOutgoingMessageCreationParams> CREATOR =
            new Creator<RcsOutgoingMessageCreationParams>() {
                @Override
                public RcsOutgoingMessageCreationParams createFromParcel(Parcel in) {
                    return new RcsOutgoingMessageCreationParams(in);
                }

                @Override
                public RcsOutgoingMessageCreationParams[] newArray(int size) {
                    return new RcsOutgoingMessageCreationParams[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest);
    }
}
