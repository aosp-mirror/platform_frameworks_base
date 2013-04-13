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

package android.app;

import android.os.Trace;
import dalvik.system.PathClassLoader;

import java.util.HashMap;
import java.util.Map;

class ApplicationLoaders
{
    public static ApplicationLoaders getDefault()
    {
        return gApplicationLoaders;
    }

    public ClassLoader getClassLoader(String zip, String libPath, ClassLoader parent)
    {
        /*
         * This is the parent we use if they pass "null" in.  In theory
         * this should be the "system" class loader; in practice we
         * don't use that and can happily (and more efficiently) use the
         * bootstrap class loader.
         */
        ClassLoader baseParent = ClassLoader.getSystemClassLoader().getParent();

        synchronized (mLoaders) {
            if (parent == null) {
                parent = baseParent;
            }

            /*
             * If we're one step up from the base class loader, find
             * something in our cache.  Otherwise, we create a whole
             * new ClassLoader for the zip archive.
             */
            if (parent == baseParent) {
                ClassLoader loader = mLoaders.get(zip);
                if (loader != null) {
                    return loader;
                }
    
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, zip);
                PathClassLoader pathClassloader =
                    new PathClassLoader(zip, libPath, parent);
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

                mLoaders.put(zip, pathClassloader);
                return pathClassloader;
            }

            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, zip);
            PathClassLoader pathClassloader = new PathClassLoader(zip, parent);
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            return pathClassloader;
        }
    }

    private final Map<String, ClassLoader> mLoaders = new HashMap<String, ClassLoader>();

    private static final ApplicationLoaders gApplicationLoaders
        = new ApplicationLoaders();
}
