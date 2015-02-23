/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.keymaster;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * Class for handling the parceling of return values from keymaster crypto operations
 * (begin/update/finish).
 * @hide
 */
public class OperationResult implements Parcelable {
    public final int resultCode;
    public final IBinder token;
    public final int inputConsumed;
    public final byte[] output;

    public static final Parcelable.Creator<OperationResult> CREATOR = new
            Parcelable.Creator<OperationResult>() {
                public OperationResult createFromParcel(Parcel in) {
                    return new OperationResult(in);
                }

                public OperationResult[] newArray(int length) {
                    return new OperationResult[length];
                }
            };

    protected OperationResult(Parcel in) {
        resultCode = in.readInt();
        token = in.readStrongBinder();
        inputConsumed = in.readInt();
        output = in.createByteArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(resultCode);
        out.writeStrongBinder(token);
        out.writeInt(inputConsumed);
        out.writeByteArray(output);
    }
}
