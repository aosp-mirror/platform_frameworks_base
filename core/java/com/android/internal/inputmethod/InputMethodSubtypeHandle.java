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

package com.android.internal.inputmethod;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils.SimpleStringSplitter;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.security.InvalidParameterException;
import java.util.Objects;

/**
 * A stable and serializable identifier for the pair of {@link InputMethodInfo#getId()} and
 * {@link android.view.inputmethod.InputMethodSubtype}.
 *
 * <p>To save {@link InputMethodSubtypeHandle} to storage, call {@link #toStringHandle()} to get a
 * {@link String} handle and just save it.  Once you load a {@link String} handle, you can obtain a
 * {@link InputMethodSubtypeHandle} instance from {@link #of(String)}.</p>
 *
 * <p>For better readability, consider specifying {@link RawHandle} annotation to {@link String}
 * object when it is a raw {@link String} handle.</p>
 */
public final class InputMethodSubtypeHandle implements Parcelable {
    private static final String SUBTYPE_TAG = "subtype";
    private static final char DATA_SEPARATOR = ':';

    /**
     * Can be used to annotate {@link String} object if it is raw handle format.
     */
    @Retention(SOURCE)
    @Target({ElementType.METHOD, ElementType.FIELD, ElementType.LOCAL_VARIABLE,
            ElementType.PARAMETER})
    public @interface RawHandle {
    }

    /**
     * The main content of this {@link InputMethodSubtypeHandle}.  Is designed to be safe to be
     * saved into storage.
     */
    @RawHandle
    private final String mHandle;

    /**
     * Encode {@link InputMethodInfo} and {@link InputMethodSubtype#hashCode()} into
     * {@link RawHandle}.
     *
     * @param imeId {@link InputMethodInfo#getId()} to be used.
     * @param subtypeHashCode {@link InputMethodSubtype#hashCode()} to be used.
     * @return The encoded {@link RawHandle} string.
     */
    @AnyThread
    @RawHandle
    @NonNull
    private static String encodeHandle(@NonNull String imeId, int subtypeHashCode) {
        return imeId + DATA_SEPARATOR + SUBTYPE_TAG + DATA_SEPARATOR + subtypeHashCode;
    }

    private InputMethodSubtypeHandle(@NonNull String handle) {
        mHandle = handle;
    }

    /**
     * Creates {@link InputMethodSubtypeHandle} from {@link InputMethodInfo} and
     * {@link InputMethodSubtype}.
     *
     * @param imi {@link InputMethodInfo} to be used.
     * @param subtype {@link InputMethodSubtype} to be used.
     * @return A {@link InputMethodSubtypeHandle} object.
     */
    @AnyThread
    @NonNull
    public static InputMethodSubtypeHandle of(
            @NonNull InputMethodInfo imi, @Nullable InputMethodSubtype subtype) {
        final int subtypeHashCode =
                subtype != null ? subtype.hashCode() : InputMethodSubtype.SUBTYPE_ID_NONE;
        return new InputMethodSubtypeHandle(encodeHandle(imi.getId(), subtypeHashCode));
    }

    /**
     * Creates {@link InputMethodSubtypeHandle} from a {@link RawHandle} {@link String}, which can
     * be obtained by {@link #toStringHandle()}.
     *
     * @param stringHandle {@link RawHandle} {@link String} to be parsed.
     * @return A {@link InputMethodSubtypeHandle} object.
     * @throws NullPointerException when {@code stringHandle} is {@code null}
     * @throws InvalidParameterException when {@code stringHandle} is not a valid {@link RawHandle}.
     */
    @AnyThread
    @NonNull
    public static InputMethodSubtypeHandle of(@RawHandle @NonNull String stringHandle) {
        final SimpleStringSplitter splitter = new SimpleStringSplitter(DATA_SEPARATOR);
        splitter.setString(Objects.requireNonNull(stringHandle));
        if (!splitter.hasNext()) {
            throw new InvalidParameterException("Invalid handle=" + stringHandle);
        }
        final String imeId = splitter.next();
        final ComponentName componentName = ComponentName.unflattenFromString(imeId);
        if (componentName == null) {
            throw new InvalidParameterException("Invalid handle=" + stringHandle);
        }
        // TODO: Consolidate IME ID validation logic into one place.
        if (!Objects.equals(componentName.flattenToShortString(), imeId)) {
            throw new InvalidParameterException("Invalid handle=" + stringHandle);
        }
        if (!splitter.hasNext()) {
            throw new InvalidParameterException("Invalid handle=" + stringHandle);
        }
        final String source = splitter.next();
        if (!Objects.equals(source, SUBTYPE_TAG)) {
            throw new InvalidParameterException("Invalid handle=" + stringHandle);
        }
        if (!splitter.hasNext()) {
            throw new InvalidParameterException("Invalid handle=" + stringHandle);
        }
        final String hashCodeStr = splitter.next();
        if (splitter.hasNext()) {
            throw new InvalidParameterException("Invalid handle=" + stringHandle);
        }
        final int subtypeHashCode;
        try {
            subtypeHashCode = Integer.parseInt(hashCodeStr);
        } catch (NumberFormatException ignore) {
            throw new InvalidParameterException("Invalid handle=" + stringHandle);
        }

        // Redundant expressions (e.g. "0001" instead of "1") are not allowed.
        if (!Objects.equals(encodeHandle(imeId, subtypeHashCode), stringHandle)) {
            throw new InvalidParameterException("Invalid handle=" + stringHandle);
        }

        return new InputMethodSubtypeHandle(stringHandle);
    }

    /**
     * @return {@link ComponentName} of the input method.
     * @see InputMethodInfo#getComponent()
     */
    @AnyThread
    @NonNull
    public ComponentName getComponentName() {
        return ComponentName.unflattenFromString(getImeId());
    }

    /**
     * @return IME ID.
     * @see InputMethodInfo#getId()
     */
    @AnyThread
    @NonNull
    public String getImeId() {
        return mHandle.substring(0, mHandle.indexOf(DATA_SEPARATOR));
    }

    /**
     * @return {@link RawHandle} {@link String} data that should be stable and persistable.
     * @see #of(String)
     */
    @RawHandle
    @AnyThread
    @NonNull
    public String toStringHandle() {
        return mHandle;
    }

    /**
     * {@inheritDoc}
     */
    @AnyThread
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof InputMethodSubtypeHandle)) {
            return false;
        }
        final InputMethodSubtypeHandle that = (InputMethodSubtypeHandle) obj;
        return Objects.equals(mHandle, that.mHandle);
    }

    /**
     * {@inheritDoc}
     */
    @AnyThread
    @Override
    public int hashCode() {
        return Objects.hashCode(mHandle);
    }

    /**
     * {@inheritDoc}
     */
    @AnyThread
    @NonNull
    @Override
    public String toString() {
        return "InputMethodSubtypeHandle{mHandle=" + mHandle + "}";
    }

    /**
     * {@link Creator} for parcelable.
     */
    public static final Creator<InputMethodSubtypeHandle> CREATOR = new Creator<>() {
        @Override
        public InputMethodSubtypeHandle createFromParcel(Parcel in) {
            return of(in.readString8());
        }

        @Override
        public InputMethodSubtypeHandle[] newArray(int size) {
            return new InputMethodSubtypeHandle[size];
        }
    };

    /**
     * {@inheritDoc}
     */
    @AnyThread
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @AnyThread
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(toStringHandle());
    }
}
