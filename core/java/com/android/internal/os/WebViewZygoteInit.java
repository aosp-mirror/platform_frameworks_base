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

package com.android.internal.os;

import android.app.ApplicationLoaders;
import android.app.LoadedApk;
import android.content.pm.ApplicationInfo;
import android.net.LocalSocket;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebViewFactory;
import android.webkit.WebViewFactoryProvider;
import android.webkit.WebViewLibraryLoader;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Startup class for the WebView zygote process.
 *
 * See {@link ZygoteInit} for generic zygote startup documentation.
 *
 * @hide
 */
class WebViewZygoteInit {
    public static final String TAG = "WebViewZygoteInit";

    private static class WebViewZygoteServer extends ZygoteServer {
        @Override
        protected ZygoteConnection createNewConnection(LocalSocket socket, String abiList)
                throws IOException {
            return new WebViewZygoteConnection(socket, abiList);
        }
    }

    private static class WebViewZygoteConnection extends ZygoteConnection {
        WebViewZygoteConnection(LocalSocket socket, String abiList) throws IOException {
            super(socket, abiList);
        }

        @Override
        protected void preload() {
            // Nothing to preload by default.
        }

        @Override
        protected boolean isPreloadComplete() {
            // Webview zygotes don't preload any classes or resources or defaults, all of their
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
            doPreload(loader, WebViewFactory.getWebViewLibrary(appInfo));

            Zygote.allowAppFilesAcrossFork(appInfo);

            Log.i(TAG, "Application preload done");
        }

        @Override
        protected void handlePreloadPackage(String packagePath, String libsPath, String libFileName,
                String cacheKey) {
            Log.i(TAG, "Beginning package preload");
            // Ask ApplicationLoaders to create and cache a classloader for the WebView APK so that
            // our children will reuse the same classloader instead of creating their own.
            // This enables us to preload Java and native code in the webview zygote process and
            // have the preloaded versions actually be used post-fork.
            ClassLoader loader = ApplicationLoaders.getDefault().createAndCacheWebViewClassLoader(
                    packagePath, libsPath, cacheKey);

            // Add the APK to the Zygote's list of allowed files for children.
            String[] packageList = TextUtils.split(packagePath, File.pathSeparator);
            for (String packageEntry : packageList) {
                Zygote.nativeAllowFileAcrossFork(packageEntry);
            }

            doPreload(loader, libFileName);

            Log.i(TAG, "Package preload done");
        }

        private void doPreload(ClassLoader loader, String libFileName) {
            // Load the native library using WebViewLibraryLoader to share the RELRO data with other
            // processes.
            WebViewLibraryLoader.loadNativeLibrary(loader, libFileName);

            // Once we have the classloader, look up the WebViewFactoryProvider implementation and
            // call preloadInZygote() on it to give it the opportunity to preload the native library
            // and perform any other initialisation work that should be shared among the children.
            boolean preloadSucceeded = false;
            try {
                Class<WebViewFactoryProvider> providerClass =
                        WebViewFactory.getWebViewProviderClass(loader);
                Method preloadInZygote = providerClass.getMethod("preloadInZygote");
                preloadInZygote.setAccessible(true);
                if (preloadInZygote.getReturnType() != Boolean.TYPE) {
                    Log.e(TAG, "Unexpected return type: preloadInZygote must return boolean");
                } else {
                    preloadSucceeded = (boolean) providerClass.getMethod("preloadInZygote")
                            .invoke(null);
                    if (!preloadSucceeded) {
                        Log.e(TAG, "preloadInZygote returned false");
                    }
                }
            } catch (ReflectiveOperationException e) {
                Log.e(TAG, "Exception while preloading package", e);
            }

            try {
                DataOutputStream socketOut = getSocketOutputStream();
                socketOut.writeInt(preloadSucceeded ? 1 : 0);
            } catch (IOException ioe) {
                throw new IllegalStateException("Error writing to command socket", ioe);
            }
        }
    }

    public static void main(String argv[]) {
        Log.i(TAG, "Starting WebViewZygoteInit");
        WebViewZygoteServer server = new WebViewZygoteServer();
        ChildZygoteInit.runZygoteServer(server, argv);
    }
}
