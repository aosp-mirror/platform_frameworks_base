/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.service.controls.templates;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Wrapper for parceling/unparceling {@link ControlTemplate}.
 * @hide
 */
public final class ControlTemplateWrapper implements Parcelable {

    private final @NonNull ControlTemplate mControlTemplate;

    public ControlTemplateWrapper(@NonNull ControlTemplate template) {
        Preconditions.checkNotNull(template);
        mControlTemplate = template;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public ControlTemplate getWrappedTemplate() {
        return mControlTemplate;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBundle(mControlTemplate.getDataBundle());
    }

    public static final @NonNull Creator<ControlTemplateWrapper> CREATOR =
            new Creator<ControlTemplateWrapper>() {
        @Override
        public ControlTemplateWrapper createFromParcel(@NonNull Parcel source) {
            return new ControlTemplateWrapper(
                    ControlTemplate.createTemplateFromBundle(source.readBundle()));
        }

        @Override
        public ControlTemplateWrapper[] newArray(int size) {
            return new ControlTemplateWrapper[size];
        }
    };
}
