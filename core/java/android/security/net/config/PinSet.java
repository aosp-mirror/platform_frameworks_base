/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.net.config;

import android.util.ArraySet;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/** @hide */
public final class PinSet {
    public static final PinSet EMPTY_PINSET =
            new PinSet(Collections.<Pin>emptySet(), Long.MAX_VALUE);
    public final long expirationTime;
    public final Set<Pin> pins;
    private final Set<String> algorithms;

    public PinSet(Set<Pin> pins, long expirationTime) {
        if (pins == null) {
            throw new NullPointerException("pins must not be null");
        }
        this.pins = pins;
        this.expirationTime = expirationTime;
        this.algorithms = pins.stream()
            .map(pin -> pin.digestAlgorithm)
            .collect(Collectors.toCollection(ArraySet::new));
    }

    Set<String> getPinAlgorithms() {
        return algorithms;
    }
}
