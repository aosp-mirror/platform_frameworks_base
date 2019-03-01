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

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.VisualVoicemailService.VisualVoicemailTask;

import java.util.Collections;
import java.util.List;

/**
 * Class to represent various settings for the visual voicemail SMS filter. When the filter is
 * enabled, incoming SMS matching the generalized OMTP format:
 *
 * <p>[clientPrefix]:[prefix]:([key]=[value];)*
 *
 * <p>will be regarded as a visual voicemail SMS, and removed before reaching the SMS provider. The
 * {@link VisualVoicemailService} in the current default dialer will be bound and
 * {@link VisualVoicemailService#onSmsReceived(VisualVoicemailTask, VisualVoicemailSms)}
 * will called with the information extracted from the SMS.
 *
 * <p>Use {@link android.telephony.VisualVoicemailSmsFilterSettings.Builder} to construct this
 * class.
 *
 * @see TelephonyManager#setVisualVoicemailSmsFilterSettings(VisualVoicemailSmsFilterSettings)
 */
public final class VisualVoicemailSmsFilterSettings implements Parcelable {


    /**
     * The visual voicemail SMS message does not have to be a data SMS, and can be directed to any
     * port.
     */
    public static final int DESTINATION_PORT_ANY = -1;

    /**
     * The visual voicemail SMS message can be directed to any port, but must be a data SMS.
     */
    public static final int DESTINATION_PORT_DATA_SMS = -2;

    /**
     * @hide
     */
    public static final String DEFAULT_CLIENT_PREFIX = "//VVM";
    /**
     * @hide
     */
    public static final List<String> DEFAULT_ORIGINATING_NUMBERS = Collections.emptyList();
    /**
     * @hide
     */
    public static final int DEFAULT_DESTINATION_PORT = DESTINATION_PORT_ANY;

    /**
     * Builder class for {@link VisualVoicemailSmsFilterSettings} objects.
     */
    public static class Builder {

        private String mClientPrefix = DEFAULT_CLIENT_PREFIX;
        private List<String> mOriginatingNumbers = DEFAULT_ORIGINATING_NUMBERS;
        private int mDestinationPort = DEFAULT_DESTINATION_PORT;
        private String mPackageName;

        public VisualVoicemailSmsFilterSettings build() {
            return new VisualVoicemailSmsFilterSettings(this);
        }

        /**
         * Sets the client prefix for the visual voicemail SMS filter. The client prefix will appear
         * at the start of a visual voicemail SMS message, followed by a colon(:).
         */
        public Builder setClientPrefix(String clientPrefix) {
            if (clientPrefix == null) {
                throw new IllegalArgumentException("Client prefix cannot be null");
            }
            mClientPrefix = clientPrefix;
            return this;
        }

        /**
         * Sets the originating number whitelist for the visual voicemail SMS filter. If the list is
         * not null only the SMS messages from a number in the list can be considered as a visual
         * voicemail SMS. Otherwise, messages from any address will be considered.
         */
        public Builder setOriginatingNumbers(List<String> originatingNumbers) {
            if (originatingNumbers == null) {
                throw new IllegalArgumentException("Originating numbers cannot be null");
            }
            mOriginatingNumbers = originatingNumbers;
            return this;
        }

        /**
         * Sets the destination port for the visual voicemail SMS filter.
         *
         * @param destinationPort The destination port, or {@link #DESTINATION_PORT_ANY}, or {@link
         * #DESTINATION_PORT_DATA_SMS}
         */
        public Builder setDestinationPort(int destinationPort) {
            mDestinationPort = destinationPort;
            return this;
        }

        /**
         * The package that registered this filter.
         *
         * @hide
         */
        public Builder setPackageName(String packageName) {
            mPackageName = packageName;
            return this;
        }
    }

    /**
     * The client prefix for the visual voicemail SMS filter. The client prefix will appear at the
     * start of a visual voicemail SMS message, followed by a colon(:).
     */
    public final String clientPrefix;

    /**
     * The originating number whitelist for the visual voicemail SMS filter of a phone account. If
     * the list is not null only the SMS messages from a number in the list can be considered as a
     * visual voicemail SMS. Otherwise, messages from any address will be considered.
     */
    public final List<String> originatingNumbers;

    /**
     * The destination port for the visual voicemail SMS filter, or {@link #DESTINATION_PORT_ANY},
     * or {@link #DESTINATION_PORT_DATA_SMS}
     */
    public final int destinationPort;

    /**
     * The package that registered this filter.
     *
     * @hide
     */
    public final String packageName;

    /**
     * Use {@link Builder} to construct
     */
    private VisualVoicemailSmsFilterSettings(Builder builder) {
        clientPrefix = builder.mClientPrefix;
        originatingNumbers = builder.mOriginatingNumbers;
        destinationPort = builder.mDestinationPort;
        packageName = builder.mPackageName;
    }

    public static final @android.annotation.NonNull Creator<VisualVoicemailSmsFilterSettings> CREATOR =
            new Creator<VisualVoicemailSmsFilterSettings>() {
                @Override
                public VisualVoicemailSmsFilterSettings createFromParcel(Parcel in) {
                    Builder builder = new Builder();
                    builder.setClientPrefix(in.readString());
                    builder.setOriginatingNumbers(in.createStringArrayList());
                    builder.setDestinationPort(in.readInt());
                    builder.setPackageName(in.readString());
                    return builder.build();
                }

                @Override
                public VisualVoicemailSmsFilterSettings[] newArray(int size) {
                    return new VisualVoicemailSmsFilterSettings[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(clientPrefix);
        dest.writeStringList(originatingNumbers);
        dest.writeInt(destinationPort);
        dest.writeString(packageName);
    }

    @Override
    public String toString() {
        return "[VisualVoicemailSmsFilterSettings "
                + "clientPrefix=" + clientPrefix
                + ", originatingNumbers=" + originatingNumbers
                + ", destinationPort=" + destinationPort
                + "]";
    }

}
