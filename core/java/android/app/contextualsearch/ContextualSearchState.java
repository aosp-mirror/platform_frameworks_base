/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.contextualsearch;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.app.contextualsearch.flags.Flags;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/**
 * {@link ContextualSearchState} contains additional data a contextual search handler can request
 * via {@link ContextualSearchManager#getContextualSearchState} method.
 *
 * It provides the caller of {@link ContextualSearchManager#getContextualSearchState} with an
 * {@link AssistStructure}, {@link AssistContent} and a {@link Bundle} extras containing any
 * relevant data added by th system server. When invoked via the Launcher, this bundle is empty.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_SERVICE)
@SystemApi
public final class ContextualSearchState implements Parcelable {
    private final @NonNull Bundle mExtras;
    private final @Nullable AssistStructure mStructure;
    private final @Nullable AssistContent mContent;

    /**
     * {@link ContextualSearchState} contains non-essential data which can be requested by the
     * Contextual Search activity.
     * The activity can request an instance of {@link ContextualSearchState} by calling
     * {@link CallbackToken#getContextualSearchState} and passing a valid token as an argument.
     */
    public ContextualSearchState(@Nullable AssistStructure structure,
            @Nullable AssistContent content, @NonNull Bundle extras) {
        mStructure = structure;
        mContent = content;
        mExtras = extras;
    }

    private ContextualSearchState(Parcel source) {
        this.mStructure = source.readTypedObject(AssistStructure.CREATOR);
        this.mContent = source.readTypedObject(AssistContent.CREATOR);
        Bundle extras = source.readBundle(getClass().getClassLoader());
        this.mExtras = extras != null ? extras : Bundle.EMPTY;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(this.mStructure, flags);
        dest.writeTypedObject(this.mContent, flags);
        dest.writeBundle(this.mExtras);
    }

    /** Gets an instance of {@link AssistContent}. */
    @Nullable
    public AssistContent getContent() {
        return mContent;
    }

    /** Gets an instance of {@link AssistStructure}. */
    @Nullable
    public AssistStructure getStructure() {
        return mStructure;
    }

    /**
     * Gets an instance of {@link Bundle} containing the extras added by the system server.
     * The contents of this bundle vary by usecase. When Contextual is invoked via Launcher, this
     * bundle is empty.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    @NonNull
    public static final Creator<ContextualSearchState> CREATOR = new Creator<>() {
        @Override
        public ContextualSearchState createFromParcel(Parcel source) {
            return new ContextualSearchState(source);
        }

        @Override
        public ContextualSearchState[] newArray(int size) {
            return new ContextualSearchState[size];
        }
    };
}
