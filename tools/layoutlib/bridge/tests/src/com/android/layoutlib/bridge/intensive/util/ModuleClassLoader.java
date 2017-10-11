/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.layoutlib.bridge.intensive.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import libcore.io.Streams;

/**
 * Module class loader that loads classes from the test project.
 */
public class ModuleClassLoader extends ClassLoader {
    private final Map<String, Class<?>> mClasses = new HashMap<>();
    private String myModuleRoot;

    /**
     * @param moduleRoot The path to the module root
     * @param parent The parent class loader
     */
    public ModuleClassLoader(String moduleRoot, ClassLoader parent) {
        super(parent);
        myModuleRoot = moduleRoot + (moduleRoot.endsWith("/") ? "" : "/");
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return super.findClass(name);
        } catch (ClassNotFoundException ignored) {
        }

        Class<?> clazz = mClasses.get(name);
        if (clazz == null) {
            String path = name.replace('.', '/').concat(".class");
            try {
                byte[] b = Streams.readFully(getResourceAsStream(myModuleRoot + path));
                clazz = defineClass(name, b, 0, b.length);
                mClasses.put(name, clazz);
            } catch (IOException ignore) {
                throw new ClassNotFoundException(name + " not found");
            }
        }

        return clazz;
    }
}
