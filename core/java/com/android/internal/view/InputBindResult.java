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
     * The ID for this input method, as found in InputMethodInfo; null if
     * no input method will be bound.
     */
    public final String id;
    
    /**
     * Sequence number of this binding.
     */
    public final int sequence;
    
    public InputBindResult(IInputMethodSession _method, String _id, int _sequence) {
        method = _method;
        id = _id;
        sequence = _sequence;
    }
    
    InputBindResult(Parcel source) {
        method = IInputMethodSession.Stub.asInterface(source.readStrongBinder());
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
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongInterface(method);
        dest.writeString(id);
        dest.writeInt(sequence);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<InputBindResult> CREATOR = new Parcelable.Creator<InputBindResult>() {
        public InputBindResult createFromParcel(Parcel source) {
            return new InputBindResult(source);
        }

        public InputBindResult[] newArray(int size) {
            return new InputBindResult[size];
        }
    };

    public int describeContents() {
        return 0;
    }
}
