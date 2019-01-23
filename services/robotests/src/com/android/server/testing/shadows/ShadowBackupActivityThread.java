/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.testing.shadows;

import android.app.ActivityThread;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.RemoteException;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowActivityThread;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import javax.annotation.Nonnull;

/**
 * Extends the existing {@link ShadowActivityThread} to add support for
 * {@link PackageManager#getApplicationEnabledSetting(String)} in the shadow {@link PackageManager}
 * returned  by {@link ShadowBackupActivityThread#getPackageManager()}.
 */
@Implements(value = ActivityThread.class, isInAndroidSdk = false, looseSignatures = true)
public class ShadowBackupActivityThread extends ShadowActivityThread {
    @Implementation
    public static Object getPackageManager() {
        ClassLoader classLoader = ShadowActivityThread.class.getClassLoader();
        Class<?> iPackageManagerClass;
        try {
            iPackageManagerClass = classLoader.loadClass("android.content.pm.IPackageManager");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        return Proxy.newProxyInstance(
                classLoader,
                new Class[] {iPackageManagerClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, @Nonnull Method method, Object[] args)
                            throws Exception {
                        if (method.getName().equals("getApplicationInfo")) {
                            String packageName = (String) args[0];
                            int flags = (Integer) args[1];

                            try {
                                return RuntimeEnvironment.application
                                        .getPackageManager()
                                        .getApplicationInfo(packageName, flags);
                            } catch (PackageManager.NameNotFoundException e) {
                                throw new RemoteException(e.getMessage());
                            }
                        } else if (method.getName().equals("getApplicationEnabledSetting")) {
                            return 0;
                        } else {
                            return null;
                        }
                    }
                });
    }
}
