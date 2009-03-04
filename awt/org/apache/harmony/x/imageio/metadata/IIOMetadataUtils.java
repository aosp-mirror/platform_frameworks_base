/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


package org.apache.harmony.x.imageio.metadata;

import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;

import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;

public class IIOMetadataUtils {
    private IIOMetadataUtils() {} 

    public static IIOMetadataFormat instantiateMetadataFormat(
            String formatName, boolean standardFormatSupported,
            String nativeMetadataFormatName, String nativeMetadataFormatClassName,
            String [] extraMetadataFormatNames, String [] extraMetadataFormatClassNames
    ) {
        if (formatName == null) {
            throw new IllegalArgumentException("formatName == null!");
        }
        if (formatName.equals(IIOMetadataFormatImpl.standardMetadataFormatName)) {
            if (standardFormatSupported) {
                return IIOMetadataFormatImpl.getStandardFormatInstance();
            }
        }

        String className = null;

        if (formatName.equals(nativeMetadataFormatName)) {
            className = nativeMetadataFormatClassName;
        } else if (extraMetadataFormatNames != null) {
            for (int i = 0; i < extraMetadataFormatNames.length; i++) {
                if (formatName.equals(extraMetadataFormatNames[i])) {
                    className = extraMetadataFormatClassNames[i];
                    break;
                }
            }
        }

        if (className == null) {
            throw new IllegalArgumentException("Unsupported format name");
        }

        // Get the context class loader and try to use it first
        ClassLoader contextClassloader = AccessController.doPrivileged(
                new PrivilegedAction<ClassLoader>() {
                    public ClassLoader run() {
                        return Thread.currentThread().getContextClassLoader();
                    }
        });

        Class cls;

        try {
            cls = Class.forName(className, true, contextClassloader);
        } catch (ClassNotFoundException e) {
            try {
                // Use current class loader
                cls = Class.forName(className);
            } catch (ClassNotFoundException e1) {
                throw new IllegalStateException ("Can't obtain format");
            }
        }

        try {
            //???AWT:
            //Method getInstance = cls.getMethod("getInstance");
            //return (IIOMetadataFormat) getInstance.invoke(null);
            return null;
        } catch (Exception e) {
            IllegalStateException e1 = new IllegalStateException("Can't obtain format");
            e1.initCause(e); // Add some details to the message
            throw e1;
        }
    }
}
