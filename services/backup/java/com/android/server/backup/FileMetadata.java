/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup;

/**
 * Description of a file in the restore datastream.
 */
public class FileMetadata {
    public String packageName;             // name of the owning app
    public String installerPackageName;    // name of the market-type app that installed the owner
    public int type;                       // e.g. BackupAgent.TYPE_DIRECTORY
    public String domain;                  // e.g. FullBackup.DATABASE_TREE_TOKEN
    public String path;                    // subpath within the semantic domain
    public long mode;                      // e.g. 0666 (actually int)
    public long mtime;                     // last mod time, UTC time_t (actually int)
    public long size;                      // bytes of content

    @Override
    public String toString() {
        // TODO: Clean this up.
        StringBuilder sb = new StringBuilder(128);
        sb.append("FileMetadata{");
        sb.append(packageName);
        sb.append(',');
        sb.append(type);
        sb.append(',');
        sb.append(domain);
        sb.append(':');
        sb.append(path);
        sb.append(',');
        sb.append(size);
        sb.append('}');
        return sb.toString();
    }
}
