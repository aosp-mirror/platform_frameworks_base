/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.webkit;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

class PluginUtil {

    private static final String LOGTAG = "PluginUtil";

    /**
     * 
     * @param packageName the name of the apk where the class can be found
     * @param className the fully qualified name of a subclass of PluginStub
     */
    /* package */
    static PluginStub getPluginStub(Context context, String packageName, 
            String className) {
        try {
            Context pluginContext = context.createPackageContext(packageName,
                    Context.CONTEXT_INCLUDE_CODE |
                    Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader pluginCL = pluginContext.getClassLoader();

            Class<?> stubClass = pluginCL.loadClass(className);
            Object stubObject = stubClass.newInstance();

            if (stubObject instanceof PluginStub) {
                return (PluginStub) stubObject;
            } else {
                Log.e(LOGTAG, "The plugin class is not of type PluginStub");
            }
        } catch (Exception e) {
            // Any number of things could have happened. Log the exception and
            // return null. Careful not to use Log.e(LOGTAG, "String", e)
            // because that reports the exception to the checkin service.
            Log.e(LOGTAG, Log.getStackTraceString(e));
        }
        return null;
    }
}
