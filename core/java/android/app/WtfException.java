/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Exception meant to be thrown instead of calling Log.wtf() such that server side code can
 * throw this exception, and it will carry across the binder to do client side logging.
 * {@hide}
 */
public final class WtfException extends RuntimeException implements Parcelable {
    public static final @android.annotation.NonNull
            Creator<WtfException> CREATOR = new Creator<WtfException>() {
                @Override
                public WtfException createFromParcel(Parcel source) {
                    return new WtfException(source.readString8());
                }

                @Override
                public WtfException[] newArray(int size) {
                    return new WtfException[size];
                }
            };

    public WtfException(@android.annotation.NonNull String message) {
        super(message);
    }

    /** {@hide} */
    public static Throwable readFromParcel(Parcel in) {
        final String msg = in.readString8();
        return new WtfException(msg);
    }

    /** {@hide} */
    public static void writeToParcel(Parcel out, Throwable t) {
        out.writeString8(t.getMessage());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@android.annotation.NonNull Parcel dest, int flags) {
        dest.writeString8(getMessage());
    }
}

