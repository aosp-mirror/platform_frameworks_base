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
 * limitations under the License.
 */

package android.media.update;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;

/**
 * @hide
 */
public final class ApiLoader {
    private static Object sMediaLibrary;

    private static final String UPDATE_PACKAGE = "com.android.media.update";
    private static final String UPDATE_CLASS = "com.android.media.update.ApiFactory";
    private static final String UPDATE_METHOD = "initialize";

    private ApiLoader() { }

    public static StaticProvider getProvider(Context context) {
        try {
            return (StaticProvider) getMediaLibraryImpl(context);
        } catch (NameNotFoundException | ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // TODO This method may do I/O; Ensure it does not violate (emit warnings in) strict mode.
    private static synchronized Object getMediaLibraryImpl(Context appContext)
            throws NameNotFoundException, ReflectiveOperationException {
        if (sMediaLibrary != null) return sMediaLibrary;

        // TODO Dynamically find the package name
        Context libContext = appContext.createPackageContext(UPDATE_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        sMediaLibrary = libContext.getClassLoader()
                .loadClass(UPDATE_CLASS)
                .getMethod(UPDATE_METHOD, Context.class, Context.class)
                .invoke(null, appContext, libContext);
        return sMediaLibrary;
    }
}
