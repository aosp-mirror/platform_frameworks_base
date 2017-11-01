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

package com.android.internal.textservice;

import com.android.internal.textservice.ISpellCheckerSession;
import com.android.internal.textservice.ISpellCheckerSessionListener;

import android.os.Bundle;

/**
 * IPC channels from SpellCheckerService to TextServicesManagerService.
 * @hide
 */
oneway interface ISpellCheckerServiceCallback {
    // TODO: Currently SpellCheckerSession just ignores null newSession and continues waiting for
    // the next onSessionCreated with non-null newSession, which is supposed to never happen if
    // the system is working normally. We should at least free up resources in SpellCheckerSession.
    // Note: This method is called from non-system processes, in theory we cannot assume that
    // this method is always be called only once with non-null value.
    void onSessionCreated(ISpellCheckerSession newSession);
}
