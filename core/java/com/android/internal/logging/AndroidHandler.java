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

import android.util.Log;

import java.util.logging.*;
import java.util.Date;
import java.text.MessageFormat;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Implements a {@link java.util.logging.Logger} handler that writes to the Android log. The
 * implementation is rather straightforward. The name of the logger serves as
 * the log tag. Only the log levels need to be converted appropriately. For
 * this purpose, the following mapping is being used:
 * 
 * <table>
 *   <tr>
 *     <th>logger level</th>
 *     <th>Android level</th>
 *   </tr>
 *   <tr>
 *     <td>
 *       SEVERE
 *     </td>
 *     <td>
 *       ERROR
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>
 *       WARNING
 *     </td>
 *     <td>
 *       WARN
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>
 *       INFO
 *     </td>
 *     <td>
 *       INFO
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>
 *       CONFIG
 *     </td>
 *     <td>
 *       DEBUG
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>
 *       FINE, FINER, FINEST
 *     </td>
 *     <td>
 *       VERBOSE
 *     </td>
 *   </tr>
 * </table>
 */
public class AndroidHandler extends Handler {
    /**
     * Holds the formatter for all Android log handlers.
     */
    private static final Formatter THE_FORMATTER = new Formatter() {
        @Override
        public String format(LogRecord r) {
            Throwable thrown = r.getThrown();
            if (thrown != null) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                sw.write(r.getMessage());
                sw.write("\n");
                thrown.printStackTrace(pw);
                pw.flush();
                return sw.toString();
            } else {
                return r.getMessage();
            }
        }
    };

    /**
     * Constructs a new instance of the Android log handler.
     */
    public AndroidHandler() {
        setFormatter(THE_FORMATTER);
    }

    @Override
    public void close() {
        // No need to close, but must implement abstract method.
    }

    @Override
    public void flush() {
        // No need to flush, but must implement abstract method.
    }

    @Override
    public void publish(LogRecord record) {
        try {
            int level = getAndroidLevel(record.getLevel());
            String tag = record.getLoggerName();

            if (tag == null) {
                // Anonymous logger.
                tag = "null";    
            } else {
                // Tags must be <= 23 characters.
                int length = tag.length();
                if (length > 23) {
                    // Most loggers use the full class name. Try dropping the
                    // package.
                    int lastPeriod = tag.lastIndexOf(".");
                    if (length - lastPeriod - 1 <= 23) {
                        tag = tag.substring(lastPeriod + 1);
                    } else {
                        // Use last 23 chars.
                        tag = tag.substring(tag.length() - 23);
                    }
                }
            }

            if (!Log.isLoggable(tag, level)) {
                return;
            }

            String message = getFormatter().format(record);
            Log.println(level, tag, message);
        } catch (RuntimeException e) {
            Log.e("AndroidHandler", "Error logging message.", e);
        }
    }
    
    /**
     * Converts a {@link java.util.logging.Logger} logging level into an Android one.
     * 
     * @param level The {@link java.util.logging.Logger} logging level.
     * 
     * @return The resulting Android logging level. 
     */
    static int getAndroidLevel(Level level)
    {
        int value = level.intValue();
        
        if (value >= Level.SEVERE.intValue()) {
            return Log.ERROR;
        } else if (value >= Level.WARNING.intValue()) {
            return Log.WARN;
        } else if (value >= Level.INFO.intValue()) {
            return Log.INFO;
        } else if (value >= Level.CONFIG.intValue()) {
            return Log.DEBUG;
        }  else {
            return Log.VERBOSE;
        }
    }
    
}
