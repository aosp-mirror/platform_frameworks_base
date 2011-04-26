/*
 * Copyright (C) 2011 The Android Open Source Project
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

/**
 * Interface that provides trusted time information, possibly coming from an NTP
 * server. Implementations may cache answers until {@link #forceRefresh()}.
 *
 * @hide
 */
public interface TrustedTime {
    /**
     * Force update with an external trusted time source, returning {@code true}
     * when successful.
     */
    public boolean forceRefresh();

    /**
     * Check if this instance has cached a response from a trusted time source.
     */
    public boolean hasCache();

    /**
     * Return time since last trusted time source contact, or
     * {@link Long#MAX_VALUE} if never contacted.
     */
    public long getCacheAge();

    /**
     * Return certainty of cached trusted time in milliseconds, or
     * {@link Long#MAX_VALUE} if never contacted. Smaller values are more
     * precise.
     */
    public long getCacheCertainty();

    /**
     * Return current time similar to {@link System#currentTimeMillis()},
     * possibly using a cached authoritative time source.
     */
    public long currentTimeMillis();
}
