/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.backup;

import android.app.backup.FullBackup.BackupScheme.PathWithRequiredFlags;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

/** @hide */
public class BackupUtils {

    private BackupUtils() {}

    /**
     * Returns {@code true} if {@code file} is either directly in {@code canonicalPathList} or is a
     * file contained in a directory in the list.
     */
    public static boolean isFileSpecifiedInPathList(
            File file, Collection<PathWithRequiredFlags> canonicalPathList) throws IOException {
        for (PathWithRequiredFlags canonical : canonicalPathList) {
            String canonicalPath = canonical.getPath();
            File fileFromList = new File(canonicalPath);
            if (fileFromList.isDirectory()) {
                if (file.isDirectory()) {
                    // If they are both directories check exact equals.
                    if (file.equals(fileFromList)) {
                        return true;
                    }
                } else {
                    // O/w we have to check if the file is within the directory from the list.
                    if (file.toPath().startsWith(canonicalPath)) {
                        return true;
                    }
                }
            } else if (file.equals(fileFromList)) {
                // Need to check the explicit "equals" so we don't end up with substrings.
                return true;
            }
        }
        return false;
    }
}
