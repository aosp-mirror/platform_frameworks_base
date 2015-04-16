/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.layoutlib.bridge.intensive.setup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import com.google.android.collect.Maps;

/**
 * The ClassLoader to load the project's classes.
 */
public class ModuleClassLoader extends ClassLoader {

    private final Map<String, Class<?>> mClasses = Maps.newHashMap();
    private final String mClassLocation;

    public ModuleClassLoader(String classLocation) {
        mClassLocation = classLocation;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> aClass = mClasses.get(name);
        if (aClass != null) {
            return aClass;
        }
        String pathName = mClassLocation.concat(name.replace('.', '/')).concat(".class");
        InputStream classInputStream = getClass().getResourceAsStream(pathName);
        if (classInputStream == null) {
            throw new ClassNotFoundException("Unable to find class " + name + " at " + pathName);
        }
        byte[] data;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            data = new byte[16384];  // 16k
            while ((nRead = classInputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            data = buffer.toByteArray();
        } catch (IOException e) {
            // Wrap the exception with ClassNotFoundException so that caller can deal with it.
            throw new ClassNotFoundException("Unable to load class " + name, e);
        }
        aClass = defineClass(name, data, 0, data.length);
        mClasses.put(name, aClass);
        return aClass;
    }
}
