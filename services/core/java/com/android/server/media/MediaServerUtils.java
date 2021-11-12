/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.media;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;

import java.io.PrintWriter;

/**
 * Util class for media server.
 */
class MediaServerUtils {
    /**
     * Verify that caller holds {@link android.Manifest.permission#DUMP}.
     */
    public static boolean checkDumpPermission(Context context, String tag, PrintWriter pw) {
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump " + tag + " from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " due to missing android.permission.DUMP permission");
            return false;
        } else {
            return true;
        }
    }
}
