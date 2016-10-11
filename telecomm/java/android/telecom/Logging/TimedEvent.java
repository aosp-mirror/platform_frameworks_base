/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.telecom.Logging;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * @hide
 */
public abstract class TimedEvent<T> {
    public abstract long getTime();
    public abstract T getKey();

    public static <T> Map<T, Double> averageTimings(Collection<? extends TimedEvent<T>> events) {
        HashMap<T, Integer> counts = new HashMap<>();
        HashMap<T, Double> result = new HashMap<>();

        for (TimedEvent<T> entry : events) {
            if (counts.containsKey(entry.getKey())) {
                counts.put(entry.getKey(), counts.get(entry.getKey()) + 1);
                result.put(entry.getKey(), result.get(entry.getKey()) + entry.getTime());
            } else {
                counts.put(entry.getKey(), 1);
                result.put(entry.getKey(), (double) entry.getTime());
            }
        }

        for (Map.Entry<T, Double> entry : result.entrySet()) {
            result.put(entry.getKey(), entry.getValue() / counts.get(entry.getKey()));
        }

        return result;
    }
}
