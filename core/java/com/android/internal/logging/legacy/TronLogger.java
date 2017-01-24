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
package com.android.internal.logging.legacy;

import android.metrics.LogMaker;

/**
 * An entity that knows how to log events and counters.
 */
public interface TronLogger {
    /** Add one to the named counter. */
    void increment(String counterName);

    /** Add an arbitrary value to the named counter. */
    void incrementBy(String counterName, int value);

    /** Increment a specified bucket on the named histogram by one. */
    void incrementIntHistogram(String counterName, int bucket);

    /** Increment the specified bucket on the named histogram by one. */
    void incrementLongHistogram(String counterName, long bucket);

    /** Obtain a SystemUiEvent proto, must release this with dispose() or addEvent(). */
    LogMaker obtain();

    void dispose(LogMaker proto);

    /** Submit an event to be logged. Logger will dispose of proto. */
    void addEvent(LogMaker proto);

    /** Get a config flag. */
    boolean getConfig(String configName);

    /** Set a config flag. */
    void setConfig(String configName, boolean newValue);
}
