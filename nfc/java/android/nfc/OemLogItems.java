/*
 * Copyright 2024 The Android Open Source Project
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

package android.nfc;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;

/**
 * A log class for OEMs to get log information of NFC events.
 * @hide
 */
@FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
@SystemApi
public final class OemLogItems implements Parcelable {
    /**
     * Used when RF field state is changed.
     */
    public static final int LOG_ACTION_RF_FIELD_STATE_CHANGED = 0X01;
    /**
     * Used when NFC is toggled. Event should be set to {@link LogEvent#EVENT_ENABLE} or
     * {@link LogEvent#EVENT_DISABLE} if this action is used.
     */
    public static final int LOG_ACTION_NFC_TOGGLE = 0x0201;
    /**
     * Used when sending host routing status.
     */
    public static final int LOG_ACTION_HCE_DATA = 0x0204;
    /**
     * Used when screen state is changed.
     */
    public static final int LOG_ACTION_SCREEN_STATE_CHANGED = 0x0206;
    /**
     * Used when tag is detected.
     */
    public static final int LOG_ACTION_TAG_DETECTED = 0x03;

    /**
     * @hide
     */
    @IntDef(prefix = { "LOG_ACTION_" }, value = {
            LOG_ACTION_RF_FIELD_STATE_CHANGED,
            LOG_ACTION_NFC_TOGGLE,
            LOG_ACTION_HCE_DATA,
            LOG_ACTION_SCREEN_STATE_CHANGED,
            LOG_ACTION_TAG_DETECTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LogAction {}

    /**
     * Represents the event is not set.
     */
    public static final int EVENT_UNSET = 0;
    /**
     * Represents nfc enable is called.
     */
    public static final int EVENT_ENABLE = 1;
    /**
     * Represents nfc disable is called.
     */
    public static final int EVENT_DISABLE = 2;
    /** @hide */
    @IntDef(prefix = { "EVENT_" }, value = {
            EVENT_UNSET,
            EVENT_ENABLE,
            EVENT_DISABLE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LogEvent {}
    private int mAction;
    private int mEvent;
    private int mCallingPid;
    private byte[] mCommandApdus;
    private byte[] mResponseApdus;
    private Instant mRfFieldOnTime;
    private Tag mTag;

    /** @hide */
    public OemLogItems(@LogAction int action, @LogEvent int event, int callingPid,
            byte[] commandApdus, byte[] responseApdus, Instant rfFieldOnTime,
            Tag tag) {
        mAction = action;
        mEvent = event;
        mTag = tag;
        mCallingPid = callingPid;
        mCommandApdus = commandApdus;
        mResponseApdus = responseApdus;
        mRfFieldOnTime = rfFieldOnTime;
    }

    /**
     * Describe the kinds of special objects contained in this Parcelable
     * instance's marshaled representation. For example, if the object will
     * include a file descriptor in the output of {@link #writeToParcel(Parcel, int)},
     * the return value of this method must include the
     * {@link #CONTENTS_FILE_DESCRIPTOR} bit.
     *
     * @return a bitmask indicating the set of special object types marshaled
     * by this Parcelable object instance.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Flatten this object in to a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAction);
        dest.writeInt(mEvent);
        dest.writeInt(mCallingPid);
        dest.writeInt(mCommandApdus.length);
        dest.writeByteArray(mCommandApdus);
        dest.writeInt(mResponseApdus.length);
        dest.writeByteArray(mResponseApdus);
        dest.writeBoolean(mRfFieldOnTime != null);
        if (mRfFieldOnTime != null) {
            dest.writeLong(mRfFieldOnTime.getEpochSecond());
            dest.writeInt(mRfFieldOnTime.getNano());
        }
        dest.writeParcelable(mTag, 0);
    }

    /** @hide */
    public static class Builder {
        private final OemLogItems mItem;

        public Builder(@LogAction int type) {
            mItem = new OemLogItems(type, EVENT_UNSET, 0, new byte[0], new byte[0], null, null);
        }

        /** Setter of the log action. */
        public OemLogItems.Builder setAction(@LogAction int action) {
            mItem.mAction = action;
            return this;
        }

        /** Setter of the log calling event. */
        public OemLogItems.Builder setCallingEvent(@LogEvent int event) {
            mItem.mEvent = event;
            return this;
        }

        /** Setter of the log calling Pid. */
        public OemLogItems.Builder setCallingPid(int pid) {
            mItem.mCallingPid = pid;
            return this;
        }

        /** Setter of APDU command. */
        public OemLogItems.Builder setApduCommand(byte[] apdus) {
            mItem.mCommandApdus = apdus;
            return this;
        }

        /** Setter of RF field on time. */
        public OemLogItems.Builder setRfFieldOnTime(Instant time) {
            mItem.mRfFieldOnTime = time;
            return this;
        }

        /** Setter of APDU response. */
        public OemLogItems.Builder setApduResponse(byte[] apdus) {
            mItem.mResponseApdus = apdus;
            return this;
        }

        /** Setter of dispatched tag. */
        public OemLogItems.Builder setTag(Tag tag) {
            mItem.mTag = tag;
            return this;
        }

        /** Builds an {@link OemLogItems} instance. */
        public OemLogItems build() {
            return mItem;
        }
    }

    /**
     * Gets the action of this log.
     * @return one of {@link LogAction}
     */
    @LogAction
    public int getAction() {
        return mAction;
    }

    /**
     * Gets the event of this log. This will be set to {@link LogEvent#EVENT_ENABLE} or
     * {@link LogEvent#EVENT_DISABLE} only when action is set to
     * {@link LogAction#LOG_ACTION_NFC_TOGGLE}
     * @return one of {@link LogEvent}
     */
    @LogEvent
    public int getEvent() {
        return mEvent;
    }

    /**
     * Gets the calling Pid of this log. This field will be set only when action is set to
     * {@link LogAction#LOG_ACTION_NFC_TOGGLE}
     * @return calling Pid
     */
    public int getCallingPid() {
        return mCallingPid;
    }

    /**
     * Gets the command APDUs of this log. This field will be set only when action is set to
     * {@link LogAction#LOG_ACTION_HCE_DATA}
     * @return a byte array of command APDUs with the same format as
     * {@link android.nfc.cardemulation.HostApduService#sendResponseApdu(byte[])}
     */
    @Nullable
    public byte[] getCommandApdu() {
        return mCommandApdus;
    }

    /**
     * Gets the response APDUs of this log. This field will be set only when action is set to
     * {@link LogAction#LOG_ACTION_HCE_DATA}
     * @return a byte array of response APDUs with the same format as
     * {@link android.nfc.cardemulation.HostApduService#sendResponseApdu(byte[])}
     */
    @Nullable
    public byte[] getResponseApdu() {
        return mResponseApdus;
    }

    /**
     * Gets the RF field event time in this log in millisecond. This field will be set only when
     * action is set to {@link LogAction#LOG_ACTION_RF_FIELD_STATE_CHANGED}
     * @return an {@link Instant} of RF field event time.
     */
    @Nullable
    public Instant getRfFieldEventTimeMillis() {
        return mRfFieldOnTime;
    }

    /**
     * Gets the tag of this log. This field will be set only when action is set to
     * {@link LogAction#LOG_ACTION_TAG_DETECTED}
     * @return a detected {@link Tag} in {@link #LOG_ACTION_TAG_DETECTED} case. Return
     * null otherwise.
     */
    @Nullable
    public Tag getTag() {
        return mTag;
    }

    private String byteToHex(byte[] bytes) {
        char[] HexArray = "0123456789ABCDEF".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HexArray[v >>> 4];
            hexChars[j * 2 + 1] = HexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    @Override
    public String toString() {
        return "[mCommandApdus: "
                + ((mCommandApdus != null) ? byteToHex(mCommandApdus) : "null")
                + "[mResponseApdus: "
                + ((mResponseApdus != null) ? byteToHex(mResponseApdus) : "null")
                + ", mCallingApi= " + mEvent
                + ", mAction= " + mAction
                + ", mCallingPId = " + mCallingPid
                + ", mRfFieldOnTime= " + mRfFieldOnTime;
    }
    private OemLogItems(Parcel in) {
        this.mAction = in.readInt();
        this.mEvent = in.readInt();
        this.mCallingPid = in.readInt();
        this.mCommandApdus = new byte[in.readInt()];
        in.readByteArray(this.mCommandApdus);
        this.mResponseApdus = new byte[in.readInt()];
        in.readByteArray(this.mResponseApdus);
        boolean isRfFieldOnTimeSet = in.readBoolean();
        if (isRfFieldOnTimeSet) {
            this.mRfFieldOnTime = Instant.ofEpochSecond(in.readLong(), in.readInt());
        } else {
            this.mRfFieldOnTime = null;
        }
        this.mTag = in.readParcelable(Tag.class.getClassLoader(), Tag.class);
    }

    public static final @NonNull Parcelable.Creator<OemLogItems> CREATOR =
            new Parcelable.Creator<OemLogItems>() {
                @Override
                public OemLogItems createFromParcel(Parcel in) {
                    return new OemLogItems(in);
                }

                @Override
                public OemLogItems[] newArray(int size) {
                    return new OemLogItems[size];
                }
            };

}
