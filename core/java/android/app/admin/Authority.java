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

package android.app.admin;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Parcelable;

/**
 * Abstract class used to identify the authority of the {@link EnforcingAdmin}.
 *
 * @hide
 */
// This is ok as the constructor is package-protected and all subclasses have implemented
// Parcelable.
@SuppressLint({"ParcelNotFinal", "ParcelCreator"})
@SystemApi
public abstract class Authority implements Parcelable {

    /**
     * @hide
     */
    protected Authority() {}

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        return o != null && getClass() == o.getClass();
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
