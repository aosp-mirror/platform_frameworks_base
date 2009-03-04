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

package com.android.internal.logging;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implements the java.util.logging configuration for Android. Activates a log
 * handler that writes to the Android log.
 */
public class AndroidConfig {

    /**
     * This looks a bit weird, but it's the way the logging config works: A
     * named class is instantiated, the constructor is assumed to tweak the
     * configuration, the instance itself is of no interest.
     */
    public AndroidConfig() {
        super();
        
        try {
            Logger rootLogger = Logger.getLogger("");
            rootLogger.addHandler(new AndroidHandler());
            rootLogger.setLevel(Level.INFO);

            // Turn down logging in Apache libraries.
            Logger.getLogger("org.apache").setLevel(Level.WARNING);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }    
}
