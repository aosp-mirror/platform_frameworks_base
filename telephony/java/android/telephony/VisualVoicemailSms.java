/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telecom.PhoneAccountHandle;
import android.telephony.VisualVoicemailService.VisualVoicemailTask;

/**
 * Represents the content of a visual voicemail SMS. If a incoming SMS matches the {@link
 * VisualVoicemailSmsFilterSettings} set by the default dialer, {@link
 * VisualVoicemailService#onSmsReceived(VisualVoicemailTask, VisualVoicemailSms)} will be called.
 */
public final class VisualVoicemailSms implements Parcelable {

    private final PhoneAccountHandle mPhoneAccountHandle;
    @Nullable
    private final String mPrefix;

    @Nullable
    private final Bundle mFields;

    private final String mMessageBody;

    VisualVoicemailSms(Builder builder) {
        mPhoneAccountHandle = builder.mPhoneAccountHandle;
        mPrefix = builder.mPrefix;
        mFields = builder.mFields;
        mMessageBody = builder.mMessageBody;
    }

    /**
     * The {@link PhoneAccountHandle} that received the SMS.
     */
    public PhoneAccountHandle getPhoneAccountHandle() {
        return mPhoneAccountHandle;
    }

    /**
     * The event type of the SMS or {@code null} if the framework cannot parse the SMS as voicemail
     * but the carrier pattern indicates it is. Common values are "SYNC" or "STATUS".
     */
    public String getPrefix() {
        return mPrefix;
    }

    /**
     * The key-value pairs sent by the SMS, or {@code null} if the framework cannot parse the SMS as
     * voicemail but the carrier pattern indicates it is. The interpretation of the fields is
     * carrier dependent.
     */
    public Bundle getFields() {
        return mFields;
    }

    /**
     * Raw message body of the received SMS.
     */
    public String getMessageBody() {
        return mMessageBody;
    }

    /**
     * Builder for the {@link VisualVoicemailSms}. Internal use only.
     *
     * @hide
     */
    public static class Builder {

        private PhoneAccountHandle mPhoneAccountHandle;
        private String mPrefix;
        private Bundle mFields;
        private String mMessageBody;

        public VisualVoicemailSms build() {
            return new VisualVoicemailSms(this);
        }

        public Builder setPhoneAccountHandle(PhoneAccountHandle phoneAccountHandle) {
            this.mPhoneAccountHandle = phoneAccountHandle;
            return this;
        }

        public Builder setPrefix(String prefix) {
            this.mPrefix = prefix;
            return this;
        }

        public Builder setFields(Bundle fields) {
            this.mFields = fields;
            return this;
        }

        public Builder setMessageBody(String messageBody) {
            this.mMessageBody = messageBody;
            return this;
        }

    }


    public static final Creator<VisualVoicemailSms> CREATOR =
            new Creator<VisualVoicemailSms>() {
                @Override
                public VisualVoicemailSms createFromParcel(Parcel in) {
                    return new Builder()
                            .setPhoneAccountHandle((PhoneAccountHandle) in.readParcelable(null))
                            .setPrefix(in.readString())
                            .setFields(in.readBundle())
                            .setMessageBody(in.readString())
                            .build();
                }

                @Override
                public VisualVoicemailSms[] newArray(int size) {
                    return new VisualVoicemailSms[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(getPhoneAccountHandle(), flags);
        dest.writeString(getPrefix());
        dest.writeBundle(getFields());
        dest.writeString(getMessageBody());
    }
}
