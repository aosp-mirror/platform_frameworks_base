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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;

class PluginUtil {
    
    private static final String LOGTAG = "PluginUtil";
    
    protected static PluginStub getPluginStub(Context context, String packageName, String className, int NPP) {
        
        try {
            Context pluginContext = context.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader pluginCL = pluginContext.getClassLoader();

            Class<?> stubClass = pluginCL.loadClass(className);
            Constructor<?> stubConstructor = stubClass.getConstructor(int.class);
            Object stubObject = stubConstructor.newInstance(NPP);
            
            if (stubObject instanceof PluginStub) {
                return (PluginStub) stubObject;
            } else {
                Log.e(LOGTAG, "The plugin class is not of type PluginStub");                
            }
        } catch (NameNotFoundException e) {
            Log.e(LOGTAG, e.toString());
        } catch (ClassNotFoundException e) {
            Log.e(LOGTAG, e.toString());
        } catch (IllegalAccessException e) {
            Log.e(LOGTAG, e.toString());
        } catch (InstantiationException e) {
            Log.e(LOGTAG, e.toString());
        } catch (SecurityException e) {
            Log.e(LOGTAG, e.toString());
        } catch (NoSuchMethodException e) {
            Log.e(LOGTAG, e.toString());
        } catch (IllegalArgumentException e) {
            Log.e(LOGTAG, e.toString());
        } catch (InvocationTargetException e) {
            Log.e(LOGTAG, e.toString());
        }
        
        return null;
    }
    

}
