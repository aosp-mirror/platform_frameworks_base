/*
 * Copyright (C) 2007-2008 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.internal.view;

import android.os.Parcel;
import android.os.Parcelable;
import android.view.InputChannel;

/**
 * Bundle of information returned by input method manager about a successful
 * binding to an input method.
 */
public final class InputBindResult implements Parcelable {
    static final String TAG = "InputBindResult";
    
    /**
     * The input method service.
     */
    public final IInputMethodSession method;

    /**
     * The input channel used to send input events to this IME.
     */
    public final InputChannel channel;

    /**
     * The ID for this input method, as found in InputMethodInfo; null if
     * no input method will be bound.
     */
    public final String id;
    
    /**
     * Sequence number of this binding.
     */
    public final int sequence;
    
    public InputBindResult(IInputMethodSession _method, InputChannel _channel,
            String _id, int _sequence) {
        method = _method;
        channel = _channel;
        id = _id;
        sequence = _sequence;
    }
    
    InputBindResult(Parcel source) {
        method = IInputMethodSession.Stub.asInterface(source.readStrongBinder());
        if (source.readInt() != 0) {
            channel = InputChannel.CREATOR.createFromParcel(source);
        } else {
            channel = null;
        }
        id = source.readString();
        sequence = source.readInt();
    }

    @Override
    public String toString() {
        return "InputBindResult{" + method + " " + id
                + " #" + sequence + "}";
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongInterface(method);
        if (channel != null) {
            dest.writeInt(1);
            channel.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeString(id);
        dest.writeInt(sequence);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<InputBindResult> CREATOR =
            new Parcelable.Creator<InputBindResult>() {
        @Override
        public InputBindResult createFromParcel(Parcel source) {
            return new InputBindResult(source);
        }

        @Override
        public InputBindResult[] newArray(int size) {
            return new InputBindResult[size];
        }
    };

    @Override
    public int describeContents() {
        return channel != null ? channel.describeContents() : 0;
    }
}
