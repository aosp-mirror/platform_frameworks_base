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
 * limitations under the License.
 */
package com.android.settingslib.core.instrumentation;

import android.content.Context;
import android.util.Pair;

/**
 * Generic log writer interface.
 */
public interface LogWriter {

    /**
     * Logs a visibility event when view becomes visible.
     */
    void visible(Context context, int source, int category);

    /**
     * Logs a visibility event when view becomes hidden.
     */
    void hidden(Context context, int category);

    /**
     * Logs a user action.
     */
    void action(int category, int value, Pair<Integer, Object>... taggedData);

    /**
     * Logs a user action.
     */
    void action(int category, boolean value, Pair<Integer, Object>... taggedData);

    /**
     * Logs an user action.
     */
    void action(Context context, int category, Pair<Integer, Object>... taggedData);

    /**
     * Logs an user action.
     */
    void actionWithSource(Context context, int source, int category);

    /**
     * Logs an user action.
     * @deprecated use {@link #action(int, int, Pair[])}
     */
    @Deprecated
    void action(Context context, int category, int value);

    /**
     * Logs an user action.
     * @deprecated use {@link #action(int, boolean, Pair[])}
     */
    @Deprecated
    void action(Context context, int category, boolean value);

    /**
     * Logs an user action.
     */
    void action(Context context, int category, String pkg, Pair<Integer, Object>... taggedData);

    /**
     * Logs a count.
     */
    void count(Context context, String name, int value);

    /**
     * Logs a histogram event.
     */
    void histogram(Context context, String name, int bucket);
}
