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

import java.io.IOException;

/**
 * Specialization of {@link XmlPullParser} which adds explicit methods to
 * support consistent and efficient conversion of primitive data types.
 *
 * @hide
 */
public interface TypedXmlPullParser extends XmlPullParser {
    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, or
     *         {@code null} if malformed or undefined
     */
    @Nullable byte[] getAttributeBytesHex(@Nullable String namespace, @NonNull String name)
            throws IOException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, or
     *         {@code null} if malformed or undefined
     */
    @Nullable byte[] getAttributeBytesBase64(@Nullable String namespace, @NonNull String name)
            throws IOException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws IOException if the value is malformed or undefined
     */
    int getAttributeInt(@Nullable String namespace, @NonNull String name)
            throws IOException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws IOException if the value is malformed or undefined
     */
    int getAttributeIntHex(@Nullable String namespace, @NonNull String name)
            throws IOException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws IOException if the value is malformed or undefined
     */
    long getAttributeLong(@Nullable String namespace, @NonNull String name)
            throws IOException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws IOException if the value is malformed or undefined
     */
    long getAttributeLongHex(@Nullable String namespace, @NonNull String name)
            throws IOException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws IOException if the value is malformed or undefined
     */
    float getAttributeFloat(@Nullable String namespace, @NonNull String name)
            throws IOException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws IOException if the value is malformed or undefined
     */
    double getAttributeDouble(@Nullable String namespace, @NonNull String name)
            throws IOException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}
     * @throws IOException if the value is malformed or undefined
     */
    boolean getAttributeBoolean(@Nullable String namespace, @NonNull String name)
            throws IOException;

    /**
     * @return decoded strongly-typed {@link #getAttributeValue}, otherwise
     *         default value if the value is malformed or undefined
     */
    default int getAttributeInt(@Nullable String namespace, @NonNull String name,
            int defaultValue) {
        try {
            return getAttributeInt(namespace, name);
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
        try {
            return getAttributeIntHex(namespace, name);
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
        try {
            return getAttributeLong(namespace, name);
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
        try {
            return getAttributeLongHex(namespace, name);
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
        try {
            return getAttributeFloat(namespace, name);
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
        try {
            return getAttributeDouble(namespace, name);
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
        try {
            return getAttributeBoolean(namespace, name);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
