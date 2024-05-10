/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Exception thrown when an app tries to start a foreground {@link Service} without a type.
 */
public final class MissingForegroundServiceTypeException
        extends ForegroundServiceTypeException implements Parcelable {
    /**
     * Constructor.
     */
    public MissingForegroundServiceTypeException(@NonNull String message) {
        super(message);
    }

    MissingForegroundServiceTypeException(@NonNull Parcel source) {
        super(source.readString());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(getMessage());
    }

    public static final @NonNull Creator<android.app.MissingForegroundServiceTypeException>
            CREATOR = new Creator<android.app.MissingForegroundServiceTypeException>() {
                @NonNull
                public android.app.MissingForegroundServiceTypeException createFromParcel(
                        Parcel source) {
                    return new android.app.MissingForegroundServiceTypeException(source);
                }

                @NonNull
                public android.app.MissingForegroundServiceTypeException[] newArray(int size) {
                    return new android.app.MissingForegroundServiceTypeException[size];
                }
            };
}
