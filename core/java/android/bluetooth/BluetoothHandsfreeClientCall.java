/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *            notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *            notice, this list of conditions and the following disclaimer in the
 *            documentation and/or other materials provided with the distribution.
 *        * Neither the name of The Linux Foundation nor
 *            the names of its contributors may be used to endorse or promote
 *            products derived from this software without specific prior written
 *            permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.    IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class represents a single call, its state and properties.
 * It implements {@link Parcelable} for inter-process message passing.
 * @hide
 */
public final class BluetoothHandsfreeClientCall implements Parcelable {

    /* Call state */
    /**
     * Call is active.
     */
    public static final int CALL_STATE_ACTIVE = 0;
    /**
     * Call is in held state.
     */
    public static final int CALL_STATE_HELD = 1;
    /**
     * Outgoing call that is being dialed right now.
     */
    public static final int CALL_STATE_DIALING = 2;
    /**
     * Outgoing call that remote party has already been alerted about.
     */
    public static final int CALL_STATE_ALERTING = 3;
    /**
     * Incoming call that can be accepted or rejected.
     */
    public static final int CALL_STATE_INCOMING = 4;
    /**
     * Waiting call state when there is already an active call.
     */
    public static final int CALL_STATE_WAITING = 5;
    /**
     * Call that has been held by response and hold
     * (see Bluetooth specification for further references).
     */
    public static final int CALL_STATE_HELD_BY_RESPONSE_AND_HOLD = 6;
    /**
     * Call that has been already terminated and should not be referenced as a valid call.
     */
    public static final int CALL_STATE_TERMINATED = 7;

    private final int mId;
    private int mState;
    private String mNumber;
    private boolean mMultiParty;
    private final boolean mOutgoing;

    /**
     * Creates BluetoothHandsfreeClientCall instance.
     */
    public BluetoothHandsfreeClientCall(int id, int state, String number, boolean multiParty,
            boolean outgoing) {
        mId = id;
        mState = state;
        mNumber = number != null ? number : "";
        mMultiParty = multiParty;
        mOutgoing = outgoing;
    }

    /**
     * Sets call's state.
     *
     * <p>Note: This is an internal function and shouldn't be exposed</p>
     *
     * @param  state    new call state.
     */
    public void setState(int state) {
        mState = state;
    }

    /**
     * Sets call's number.
     *
     * <p>Note: This is an internal function and shouldn't be exposed</p>
     *
     * @param number    String representing phone number.
     */
    public void setNumber(String number) {
        mNumber = number;
    }

    /**
     * Sets this call as multi party call.
     *
     * <p>Note: This is an internal function and shouldn't be exposed</p>
     *
     * @param multiParty    if <code>true</code> sets this call as a part
     *                      of multi party conference.
     */
    public void setMultiParty(boolean multiParty) {
        mMultiParty = multiParty;
    }

    /**
     * Gets call's Id.
     *
     * @return call id.
     */
    public int getId() {
        return mId;
    }

    /**
     * Gets call's current state.
     *
     * @return state of this particular phone call.
     */
    public int getState() {
        return mState;
    }

    /**
     * Gets call's number.
     *
     * @return string representing phone number.
     */
    public String getNumber() {
        return mNumber;
    }

    /**
     * Checks if call is an active call in a conference mode (aka multi party).
     *
     * @return <code>true</code> if call is a multi party call,
     *         <code>false</code> otherwise.
     */
    public boolean isMultiParty() {
        return mMultiParty;
    }

    /**
     * Checks if this call is an outgoing call.
     *
     * @return <code>true</code> if its outgoing call,
     *         <code>false</code> otherwise.
     */
    public boolean isOutgoing() {
        return mOutgoing;
    }

    /**
     * {@link Parcelable.Creator} interface implementation.
     */
    public static final Parcelable.Creator<BluetoothHandsfreeClientCall> CREATOR =
            new Parcelable.Creator<BluetoothHandsfreeClientCall>() {
                @Override
                public BluetoothHandsfreeClientCall createFromParcel(Parcel in) {
                    return new BluetoothHandsfreeClientCall(in.readInt(), in.readInt(),
                            in.readString(), in.readInt() == 1, in.readInt() == 1);
                }

                @Override
                public BluetoothHandsfreeClientCall[] newArray(int size) {
                    return new BluetoothHandsfreeClientCall[size];
                }
            };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mId);
        out.writeInt(mState);
        out.writeString(mNumber);
        out.writeInt(mMultiParty ? 1 : 0);
        out.writeInt(mOutgoing ? 1 : 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
