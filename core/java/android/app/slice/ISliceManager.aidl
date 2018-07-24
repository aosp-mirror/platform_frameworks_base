/**
 * Copyright (c) 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.slice;

import android.app.slice.SliceSpec;
import android.net.Uri;

/** @hide */
interface ISliceManager {
    void pinSlice(String pkg, in Uri uri, in SliceSpec[] specs, in IBinder token);
    void unpinSlice(String pkg, in Uri uri, in IBinder token);
    boolean hasSliceAccess(String pkg);
    SliceSpec[] getPinnedSpecs(in Uri uri, String pkg);
    Uri[] getPinnedSlices(String pkg);

    byte[] getBackupPayload(int user);
    void applyRestore(in byte[] payload, int user);

    // Perms.
    void grantSlicePermission(String callingPkg, String toPkg, in Uri uri);
    void revokeSlicePermission(String callingPkg, String toPkg, in Uri uri);
    int checkSlicePermission(in Uri uri, String callingPkg, String pkg, int pid, int uid,
            in String[] autoGrantPermissions);
    void grantPermissionFromUser(in Uri uri, String pkg, String callingPkg, boolean allSlices);
}
