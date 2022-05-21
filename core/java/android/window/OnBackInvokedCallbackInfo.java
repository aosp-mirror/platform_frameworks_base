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

package android.window;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data object to hold an {@link IOnBackInvokedCallback} and its priority.
 * @hide
 */
public final class OnBackInvokedCallbackInfo implements Parcelable {
    @NonNull
    private final IOnBackInvokedCallback mCallback;
    private @OnBackInvokedDispatcher.Priority int mPriority;

    public OnBackInvokedCallbackInfo(@NonNull IOnBackInvokedCallback callback, int priority) {
        mCallback = callback;
        mPriority = priority;
    }

    private OnBackInvokedCallbackInfo(@NonNull Parcel in) {
        mCallback = IOnBackInvokedCallback.Stub.asInterface(in.readStrongBinder());
        mPriority = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongInterface(mCallback);
        dest.writeInt(mPriority);
    }

    public static final Creator<OnBackInvokedCallbackInfo> CREATOR =
            new Creator<OnBackInvokedCallbackInfo>() {
                @Override
                public OnBackInvokedCallbackInfo createFromParcel(Parcel in) {
                    return new OnBackInvokedCallbackInfo(in);
                }

                @Override
                public OnBackInvokedCallbackInfo[] newArray(int size) {
                    return new OnBackInvokedCallbackInfo[size];
                }
            };

    public boolean isSystemCallback() {
        return mPriority == OnBackInvokedDispatcher.PRIORITY_SYSTEM;
    }

    @NonNull
    public IOnBackInvokedCallback getCallback() {
        return mCallback;
    }

    @OnBackInvokedDispatcher.Priority
    public int getPriority() {
        return mPriority;
    }

    @Override
    public String toString() {
        return "OnBackInvokedCallbackInfo{"
                + "mCallback=" + mCallback + ", mPriority=" + mPriority + '}';
    }
}
