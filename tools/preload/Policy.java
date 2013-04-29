/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Policy that governs which classes are preloaded.
 */
public class Policy {

    /**
     * No constructor - use static methods only
     */
    private Policy() {}

    /**
     * This location (in the build system) of the preloaded-classes file.
     */
    static final String PRELOADED_CLASS_FILE
            = "frameworks/base/preloaded-classes";

    /**
     * Long running services. These are restricted in their contribution to the 
     * preloader because their launch time is less critical.
     */
    // TODO: Generate this automatically from package manager.
    private static final Set<String> SERVICES = new HashSet<String>(Arrays.asList(
        "system_server",
        "com.google.process.content",
        "android.process.media",
        "com.android.bluetooth",
        "com.android.calendar",
        "com.android.inputmethod.latin",
        "com.android.phone",
        "com.google.android.apps.maps.FriendService", // pre froyo
        "com.google.android.apps.maps:FriendService", // froyo
        "com.google.android.apps.maps.LocationFriendService",
        "com.google.android.deskclock",
        "com.google.process.gapps",
        "android.tts"
    ));

    /**
     * Classes which we shouldn't load from the Zygote.
     */
    private static final Set<String> EXCLUDED_CLASSES
            = new HashSet<String>(Arrays.asList(
        // Binders
        "android.app.AlarmManager",
        "android.app.SearchManager",
        "android.os.FileObserver",
        "com.android.server.PackageManagerService$AppDirObserver",

        // Threads
        "android.os.AsyncTask",
        "android.pim.ContactsAsyncHelper",
        "android.webkit.WebViewClassic$1",
        "java.lang.ProcessManager"
    ));

    /**
     * Returns true if the given process name is a "long running" process or
     * service.
     */
    public static boolean isService(String processName) {
        return SERVICES.contains(processName);
    }

    /**Reports if the given class should be preloaded. */
    public static boolean isPreloadable(LoadedClass clazz) {
        return clazz.systemClass && !EXCLUDED_CLASSES.contains(clazz.name);
    }
}
