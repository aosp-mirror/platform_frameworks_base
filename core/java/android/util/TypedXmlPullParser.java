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

package android.util;

import android.annotation.NonNull;
import android.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Specialization of {@link XmlPullParser} which adds explicit methods to
 * support consistent and efficient conversion of primitive data types.
 *
 * @hide
 */
public interface TypedXmlPullParser extends XmlPullParser {
    /**
     * @return index of requested attribute, otherwise {@code -1} if undefined
     */
    default int getAttributeIndex(@Nullable String namespace, @NonNull String name) {
        final boolean namespaceNull = (namespace == null);
        final int count = getAttributeCount();
        for (int i = 0; i < count; i++) {
            if ((namespaceNull || namespace.equals(getAttributeNamespace(i)))
                    && name.equals(getAttributeName(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return index of requested attribute
     * @throws XmlPullParserException if the value is undefined
     */
    default int getAttributeIndexOrThrow(@Nullable String namespace, @NonNull String name)
            throws XmlPullParserException {
        final int index = getAttributeIndex(namespace, name);
        if (index == -1) {
            throw new XmlPullParserException("Missing attribute " + name);
        } else {
            return index;
        }
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed
     */
    @NonNull byte[] getAttributeBytesHex(int index) throws XmlPullParserException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed
     */
    @NonNull byte[] getAttributeBytesBase64(int index) throws XmlPullParserException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed
     */
    int getAttributeInt(int index) throws XmlPullParserException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed
     */
    int getAttributeIntHex(int index) throws XmlPullParserException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed
     */
    long getAttributeLong(int index) throws XmlPullParserException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed
     */
    long getAttributeLongHex(int index) throws XmlPullParserException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed
     */
    float getAttributeFloat(int index) throws XmlPullParserException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed
     */
    double getAttributeDouble(int index) throws XmlPullParserException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed
     */
    boolean getAttributeBoolean(int index) throws XmlPullParserException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    default @NonNull byte[] getAttributeBytesHex(@Nullable String namespace,
            @NonNull String name) throws XmlPullParserException {
        return getAttributeBytesHex(getAttributeIndexOrThrow(namespace, name));
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    default @NonNull byte[] getAttributeBytesBase64(@Nullable String namespace,
            @NonNull String name) throws XmlPullParserException {
        return getAttributeBytesBase64(getAttributeIndexOrThrow(namespace, name));
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    default int getAttributeInt(@Nullable String namespace, @NonNull String name)
            throws XmlPullParserException {
        return getAttributeInt(getAttributeIndexOrThrow(namespace, name));
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    default int getAttributeIntHex(@Nullable String namespace, @NonNull String name)
            throws XmlPullParserException {
        return getAttributeIntHex(getAttributeIndexOrThrow(namespace, name));
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    default long getAttributeLong(@Nullable String namespace, @NonNull String name)
            throws XmlPullParserException {
        return getAttributeLong(getAttributeIndexOrThrow(namespace, name));
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    default long getAttributeLongHex(@Nullable String namespace, @NonNull String name)
            throws XmlPullParserException {
        return getAttributeLongHex(getAttributeIndexOrThrow(namespace, name));
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    default float getAttributeFloat(@Nullable String namespace, @NonNull String name)
            throws XmlPullParserException {
        return getAttributeFloat(getAttributeIndexOrThrow(namespace, name));
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    default double getAttributeDouble(@Nullable String namespace, @NonNull String name)
            throws XmlPullParserException {
        return getAttributeDouble(getAttributeIndexOrThrow(namespace, name));
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    default boolean getAttributeBoolean(@Nullable String namespace, @NonNull String name)
            throws XmlPullParserException {
        return getAttributeBoolean(getAttributeIndexOrThrow(namespace, name));
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, otherwise
     *         default value if the value is malformed or undefined
     */
    default @Nullable byte[] getAttributeBytesHex(@Nullable String namespace,
            @NonNull String name, @Nullable byte[] defaultValue) {
        final int index = getAttributeIndex(namespace, name);
        if (index == -1) return defaultValue;
        try {
            return getAttributeBytesHex(index);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, otherwise
     *         default value if the value is malformed or undefined
     */
    default @Nullable byte[] getAttributeBytesBase64(@Nullable String namespace,
            @NonNull String name, @Nullable byte[] defaultValue) {
        final int index = getAttributeIndex(namespace, name);
        if (index == -1) return defaultValue;
        try {
            return getAttributeBytesBase64(index);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, otherwise
     *         default value if the value is malformed or undefined
     */
    default int getAttributeInt(@Nullable String namespace, @NonNull String name,
            int defaultValue) {
        final int index = getAttributeIndex(namespace, name);
        if (index == -1) return defaultValue;
        try {
            return getAttributeInt(index);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, otherwise
     *         default value if the value is malformed or undefined
     */
    default int getAttributeIntHex(@Nullable String namespace, @NonNull String name,
            int defaultValue) {
        final int index = getAttributeIndex(namespace, name);
        if (index == -1) return defaultValue;
        try {
            return getAttributeIntHex(index);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, otherwise
     *         default value if the value is malformed or undefined
     */
    default long getAttributeLong(@Nullable String namespace, @NonNull String name,
            long defaultValue) {
        final int index = getAttributeIndex(namespace, name);
        if (index == -1) return defaultValue;
        try {
            return getAttributeLong(index);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, otherwise
     *         default value if the value is malformed or undefined
     */
    default long getAttributeLongHex(@Nullable String namespace, @NonNull String name,
            long defaultValue) {
        final int index = getAttributeIndex(namespace, name);
        if (index == -1) return defaultValue;
        try {
            return getAttributeLongHex(index);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, otherwise
     *         default value if the value is malformed or undefined
     */
    default float getAttributeFloat(@Nullable String namespace, @NonNull String name,
            float defaultValue) {
        final int index = getAttributeIndex(namespace, name);
        if (index == -1) return defaultValue;
        try {
            return getAttributeFloat(index);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, otherwise
     *         default value if the value is malformed or undefined
     */
    default double getAttributeDouble(@Nullable String namespace, @NonNull String name,
            double defaultValue) {
        final int index = getAttributeIndex(namespace, name);
        if (index == -1) return defaultValue;
        try {
            return getAttributeDouble(index);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, otherwise
     *         default value if the value is malformed or undefined
     */
    default boolean getAttributeBoolean(@Nullable String namespace, @NonNull String name,
            boolean defaultValue) {
        final int index = getAttributeIndex(namespace, name);
        if (index == -1) return defaultValue;
        try {
            return getAttributeBoolean(index);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
