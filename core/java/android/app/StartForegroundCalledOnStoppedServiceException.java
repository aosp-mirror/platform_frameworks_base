/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * Exception thrown when {@link Service#startForeground} is called on a service that's not
 * actually started.
 */
public final class StartForegroundCalledOnStoppedServiceException
        extends IllegalStateException implements Parcelable {
    /**
     * Constructor.
     */
    public StartForegroundCalledOnStoppedServiceException(@NonNull String message) {
        super(message);
    }

    StartForegroundCalledOnStoppedServiceException(@NonNull Parcel source) {
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

    public static final @NonNull Creator<StartForegroundCalledOnStoppedServiceException>
            CREATOR = new Creator<StartForegroundCalledOnStoppedServiceException>() {
                @NonNull
                public StartForegroundCalledOnStoppedServiceException createFromParcel(
                        Parcel source) {
                    return new StartForegroundCalledOnStoppedServiceException(source);
                }

                @NonNull
                public StartForegroundCalledOnStoppedServiceException[] newArray(int size) {
                    return new StartForegroundCalledOnStoppedServiceException[size];
                }
            };
}
