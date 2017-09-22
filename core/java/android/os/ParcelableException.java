/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.os;

import java.io.IOException;

/**
 * Wrapper class that offers to transport typical {@link Throwable} across a
 * {@link Binder} call. This class is typically used to transport exceptions
 * that cannot be modified to add {@link Parcelable} behavior, such as
 * {@link IOException}.
 * <ul>
 * <li>The wrapped throwable must be defined as system class (that is, it must
 * be in the same {@link ClassLoader} as {@link Parcelable}).
 * <li>The wrapped throwable must support the
 * {@link Throwable#Throwable(String)} constructor.
 * <li>The receiver side must catch any thrown {@link ParcelableException} and
 * call {@link #maybeRethrow(Class)} for all expected exception types.
 * </ul>
 *
 * @hide
 */
public final class ParcelableException extends RuntimeException implements Parcelable {
    public ParcelableException(Throwable t) {
        super(t);
    }

    @SuppressWarnings("unchecked")
    public <T extends Throwable> void maybeRethrow(Class<T> clazz) throws T {
        if (clazz.isAssignableFrom(getCause().getClass())) {
            throw (T) getCause();
        }
    }

    /** {@hide} */
    public static Throwable readFromParcel(Parcel in) {
        final String name = in.readString();
        final String msg = in.readString();
        try {
            final Class<?> clazz = Class.forName(name, true, Parcelable.class.getClassLoader());
            if (Throwable.class.isAssignableFrom(clazz)) {
                return (Throwable) clazz.getConstructor(String.class).newInstance(msg);
            }
        } catch (ReflectiveOperationException e) {
        }
        return new RuntimeException(name + ": " + msg);
    }

    /** {@hide} */
    public static void writeToParcel(Parcel out, Throwable t) {
        out.writeString(t.getClass().getName());
        out.writeString(t.getMessage());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcel(dest, getCause());
    }

    public static final Creator<ParcelableException> CREATOR = new Creator<ParcelableException>() {
        @Override
        public ParcelableException createFromParcel(Parcel source) {
            return new ParcelableException(readFromParcel(source));
        }

        @Override
        public ParcelableException[] newArray(int size) {
            return new ParcelableException[size];
        }
    };
}
