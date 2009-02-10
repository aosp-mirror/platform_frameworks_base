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
 * This is not instantiated - we just provide data for other classes to use
 */
public class Policy {
    
    /**
     * This location (in the build system) of the preloaded-classes file.
     */
    private static final String PRELOADED_CLASS_FILE = "frameworks/base/preloaded-classes";
    
    /**
     * The internal process name of the system process.  Note, this also shows up as
     * "system_process", e.g. in ddms.
     */
    private static final String SYSTEM_SERVER_PROCESS_NAME = "system_server";

    /** 
     * Names of non-application processes - these will not be checked for preloaded classes.
     * 
     * TODO: Replace this hardcoded list with a walk up the parent chain looking for zygote.
     */
    private static final Set<String> NOT_FROM_ZYGOTE = new HashSet<String>(Arrays.asList(
            "zygote",
            "dexopt",
            "unknown",
            SYSTEM_SERVER_PROCESS_NAME,
            "com.android.development",
            "app_process" // am & other shell commands
    ));

    /** 
     * Long running services.  These are restricted in their contribution to the preloader
     * because their launch time is less critical.
     */
    private static final Set<String> SERVICES = new HashSet<String>(Arrays.asList(
            SYSTEM_SERVER_PROCESS_NAME,
            "com.android.acore",
         // Commented out to make sure DefaultTimeZones gets preloaded.
         // "com.android.phone",
            "com.google.process.content",
            "android.process.media"
    ));

    /**
     * Classes which we shouldn't load from the Zygote.
     */
    private static final Set<String> EXCLUDED_CLASSES = new HashSet<String>(Arrays.asList(
        // Binders
        "android.app.AlarmManager",
        "android.app.SearchManager",
        "android.os.FileObserver",
        "com.android.server.PackageManagerService$AppDirObserver",

        // Threads
        "android.os.AsyncTask",
        "android.pim.ContactsAsyncHelper",
        "java.lang.ProcessManager"
        
    ));

    /**
     * No constructor - use static methods only
     */
    private Policy() {}
    
    /**
     * Returns the path/file name of the preloaded classes file that will be written 
     * by WritePreloadedClassFile.
     */
    public static String getPreloadedClassFileName() {
        return PRELOADED_CLASS_FILE;
    }
    
    /**
     * Reports if a given process name was created from zygote
     */
    public static boolean isFromZygote(String processName) {
        return !NOT_FROM_ZYGOTE.contains(processName);
    }
    
    /**
     * Reports if the given process name is a "long running" process or service
     */
    public static boolean isService(String processName) {
        return SERVICES.contains(processName);
    }
    
    /**
     * Reports if the given class should never be preloaded
     */
    public static boolean isPreloadableClass(String className) {
        return !EXCLUDED_CLASSES.contains(className);
    }
}
