/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Filter configuration used to configure filters.
 *
 * @hide
 */
@SystemApi
public abstract class FilterConfiguration {

    /** @hide */
    @IntDef(prefix = "PACKET_TYPE_", value =
            {PACKET_TYPE_IPV4, PACKET_TYPE_COMPRESSED, PACKET_TYPE_SIGNALING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PacketType {}

    /**
     * IP v4 packet type.
     * @hide
     */
    public static final int PACKET_TYPE_IPV4 = 0;
    /**
     * Compressed packet type.
     * @hide
     */
    public static final int PACKET_TYPE_COMPRESSED = 2;
    /**
     * Signaling packet type.
     * @hide
     */
    public static final int PACKET_TYPE_SIGNALING = 4;


    @Nullable
    /* package */ final Settings mSettings;

    /* package */ FilterConfiguration(Settings settings) {
        mSettings = settings;
    }

    /**
     * Gets filter configuration type.
     * @hide
     */
    @Filter.Type
    public abstract int getType();

    /** @hide */
    @Nullable
    public Settings getSettings() {
        return mSettings;
    }

    /**
     * Builder for {@link FilterConfiguration}.
     *
     * @param <T> The subclass to be built.
     * @hide
     */
    public abstract static class Builder<T extends Builder<T>> {
        /* package */ Settings mSettings;

        /* package */ Builder() {
        }

        /**
         * Sets filter settings.
         */
        @Nullable
        public T setFrequency(Settings settings) {
            mSettings = settings;
            return self();
        }
        /* package */ abstract T self();
    }
}
