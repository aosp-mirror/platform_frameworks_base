/*
 * Copyright (C) 2008-2015 The Android Open Source Project
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

package android.renderscript;

import android.compat.annotation.UnsupportedAppUsage;

import java.io.File;

/**
 * Used only for tracking the RenderScript cache directory.
 * @hide
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 */
@Deprecated
public class RenderScriptCacheDir {
     /**
     * Sets the directory to use as a persistent storage for the
     * renderscript object file cache.
     *
     * @hide
     * @param cacheDir A directory the current process can write to
     */
    @UnsupportedAppUsage
    public static void setupDiskCache(File cacheDir) {
        // Defer creation of cache path to nScriptCCreate().
        mCacheDir = cacheDir;
    }

    @UnsupportedAppUsage
    static File mCacheDir;

}
