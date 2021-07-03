/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.os;

import android.app.LoadedApk;
import android.app.ZygotePreload;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.net.LocalSocket;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Startup class for an Application zygote process.
 *
 * See {@link ZygoteInit} for generic zygote startup documentation.
 *
 * @hide
 */
class AppZygoteInit {
    public static final String TAG = "AppZygoteInit";

    private static ZygoteServer sServer;

    private static class AppZygoteServer extends ZygoteServer {
        @Override
        protected ZygoteConnection createNewConnection(LocalSocket socket, String abiList)
                throws IOException {
            return new AppZygoteConnection(socket, abiList);
        }
    }

    private static class AppZygoteConnection extends ZygoteConnection {
        AppZygoteConnection(LocalSocket socket, String abiList) throws IOException {
            super(socket, abiList);
        }

        @Override
        protected void preload() {
            // Nothing to preload by default.
        }

        @Override
        protected boolean isPreloadComplete() {
            // App zygotes don't preload any classes or resources or defaults, all of their
            // preloading is package specific.
            return true;
        }

        @Override
        protected boolean canPreloadApp() {
            return true;
        }

        @Override
        protected void handlePreloadApp(ApplicationInfo appInfo) {
            Log.i(TAG, "Beginning application preload for " + appInfo.packageName);
            LoadedApk loadedApk = new LoadedApk(null, appInfo, null, null, false, true, false);
            ClassLoader loader = loadedApk.getClassLoader();

            Zygote.allowAppFilesAcrossFork(appInfo);

            if (appInfo.zygotePreloadName != null) {
                Class<?> cl;
                Method m;
                try {
                    ComponentName preloadName = ComponentName.createRelative(appInfo.packageName,
                            appInfo.zygotePreloadName);
                    cl = Class.forName(preloadName.getClassName(), true, loader);
                    if (!ZygotePreload.class.isAssignableFrom(cl)) {
                        Log.e(TAG, preloadName.getClassName() + " does not implement "
                                + ZygotePreload.class.getName());
                    } else {
                        Constructor<?> ctor = cl.getConstructor();
                        ZygotePreload preloadObject = (ZygotePreload) ctor.newInstance();
                        Zygote.markOpenedFilesBeforePreload();
                        preloadObject.doPreload(appInfo);
                        Zygote.allowFilesOpenedByPreload();
                    }
                } catch (ReflectiveOperationException e) {
                    Log.e(TAG, "AppZygote application preload failed for "
                            + appInfo.zygotePreloadName, e);
                }
            } else {
                Log.i(TAG, "No zygotePreloadName attribute specified.");
            }

            try {
                DataOutputStream socketOut = getSocketOutputStream();
                socketOut.writeInt(loader != null ? 1 : 0);
            } catch (IOException e) {
                throw new IllegalStateException("Error writing to command socket", e);
            }

            Log.i(TAG, "Application preload done");
        }
    }

    public static void main(String[] argv) {
        AppZygoteServer server = new AppZygoteServer();
        ChildZygoteInit.runZygoteServer(server, argv);
    }
}
