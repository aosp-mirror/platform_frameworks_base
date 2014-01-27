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

package android.util;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

class Log_Delegate {
    // to replicate prefix visible when using 'adb logcat'
    private static char priorityChar(int priority) {
        switch (priority) {
            case Log.VERBOSE:
                return 'V';
            case Log.DEBUG:
                return 'D';
            case Log.INFO:
                return 'I';
            case Log.WARN:
                return 'W';
            case Log.ERROR:
                return 'E';
            case Log.ASSERT:
                return 'A';
            default:
                return '?';
        }
    }

    @LayoutlibDelegate
    static int println_native(int bufID, int priority, String tag, String msgs) {
        String prefix = priorityChar(priority) + "/" + tag + ": ";
        for (String msg: msgs.split("\n")) {
            System.out.println(prefix + msg);
        }
        return 0;
    }

}
