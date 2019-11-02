/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.dagger;

import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;

import com.android.systemui.SystemUI;
import com.android.systemui.recents.RecentsImplementation;

/**
 * Interface necessary to make Dagger happy. See {@link ContextComponentResolver}.
 */
public interface ContextComponentHelper {
    /** Turns a classname into an instance of the class or returns null. */
    Activity resolveActivity(String className);

    /** Turns a classname into an instance of the class or returns null. */
    RecentsImplementation resolveRecents(String className);

    /** Turns a classname into an instance of the class or returns null. */
    Service resolveService(String className);

    /** Turns a classname into an instance of the class or returns null. */
    SystemUI resolveSystemUI(String className);

    /** Turns a classname into an instance of the class or returns null. */
    BroadcastReceiver resolveBroadcastReceiver(String className);
}
