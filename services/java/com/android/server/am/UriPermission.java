/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.am;

import android.content.Intent;
import android.net.Uri;

import java.io.PrintWriter;
import java.util.HashSet;

/**
 * Description of a permission granted to an app to access a particular URI.
 *
 * CTS tests for this functionality can be run with "runtest cts-appsecurity".
 *
 * Test cases are at cts/tests/appsecurity-tests/test-apps/UsePermissionDiffCert/
 *      src/com/android/cts/usespermissiondiffcertapp/AccessPermissionWithDiffSigTest.java
 */
class UriPermission {
    final int uid;
    final Uri uri;
    int modeFlags = 0;
    int globalModeFlags = 0;
    final HashSet<UriPermissionOwner> readOwners = new HashSet<UriPermissionOwner>();
    final HashSet<UriPermissionOwner> writeOwners = new HashSet<UriPermissionOwner>();
    
    String stringName;
    
    UriPermission(int _uid, Uri _uri) {
        uid = _uid;
        uri = _uri;
    }
    
    void clearModes(int modeFlagsToClear) {
        if ((modeFlagsToClear&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            globalModeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
            modeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (readOwners.size() > 0) {
                for (UriPermissionOwner r : readOwners) {
                    r.removeReadPermission(this);
                }
                readOwners.clear();
            }
        }
        if ((modeFlagsToClear&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            globalModeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            modeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if (writeOwners.size() > 0) {
                for (UriPermissionOwner r : writeOwners) {
                    r.removeWritePermission(this);
                }
                writeOwners.clear();
            }
        }
    }
    
    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("UriPermission{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(uri);
        sb.append('}');
        return stringName = sb.toString();
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("modeFlags=0x");
                pw.print(Integer.toHexString(modeFlags));
                pw.print(" uid="); pw.print(uid); 
                pw.print(" globalModeFlags=0x");
                pw.println(Integer.toHexString(globalModeFlags));
        if (readOwners.size() != 0) {
            pw.print(prefix); pw.println("readOwners:");
            for (UriPermissionOwner owner : readOwners) {
                pw.print(prefix); pw.print("  * "); pw.println(owner);
            }
        }
        if (writeOwners.size() != 0) {
            pw.print(prefix); pw.println("writeOwners:");
            for (UriPermissionOwner owner : writeOwners) {
                pw.print(prefix); pw.print("  * "); pw.println(owner);
            }
        }
    }
}
