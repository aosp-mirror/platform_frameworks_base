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

package com.android.internal.textservice;

import com.android.internal.textservice.ISpellCheckerServiceCallback;
import com.android.internal.textservice.ISpellCheckerSession;
import com.android.internal.textservice.ISpellCheckerSessionListener;

import android.os.Bundle;

/**
 * IPC channels from TextServicesManagerService to SpellCheckerService.
 * @hide
 */
oneway interface ISpellCheckerService {
    /**
     * Called from the system when an application is requesting a new spell checker session.
     *
     * <p>Note: This is an internal protocol used by the system to establish spell checker sessions,
     * which is not guaranteed to be stable and is subject to change.</p>
     *
     * @param locale locale to be returned from
     *               {@link android.service.textservice.SpellCheckerService.Session#getLocale()}
     * @param listener IPC channel object to be used to implement
     *                 {@link android.service.textservice.SpellCheckerService.Session#onGetSuggestionsMultiple(TextInfo[], int, boolean)} and
     *                 {@link android.service.textservice.SpellCheckerService.Session#onGetSuggestions(TextInfo, int)}
     * @param bundle bundle to be returned from {@link android.service.textservice.SpellCheckerService.Session#getBundle()}
     * @param callback IPC channel to return the result to the caller in an asynchronous manner
     */
    void getISpellCheckerSession(
            String locale, ISpellCheckerSessionListener listener, in Bundle bundle,
            ISpellCheckerServiceCallback callback);
}
