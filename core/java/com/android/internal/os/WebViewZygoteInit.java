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
import android.net.LocalSocket;
import android.net.LocalServerSocket;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
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

    private static ZygoteServer sServer;

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
        protected void handlePreloadPackage(String packagePath, String libsPath, String libFileName,
                String cacheKey) {
            Log.i(TAG, "Beginning package preload");
            // Ask ApplicationLoaders to create and cache a classloader for the WebView APK so that
            // our children will reuse the same classloader instead of creating their own.
            // This enables us to preload Java and native code in the webview zygote process and
            // have the preloaded versions actually be used post-fork.
            ClassLoader loader = ApplicationLoaders.getDefault().createAndCacheWebViewClassLoader(
                    packagePath, libsPath, cacheKey);

            // Load the native library using WebViewLibraryLoader to share the RELRO data with other
            // processes.
            WebViewLibraryLoader.loadNativeLibrary(loader, libFileName);

            // Add the APK to the Zygote's list of allowed files for children.
            String[] packageList = TextUtils.split(packagePath, File.pathSeparator);
            for (String packageEntry : packageList) {
                Zygote.nativeAllowFileAcrossFork(packageEntry);
            }

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

            Log.i(TAG, "Package preload done");
        }
    }

    public static void main(String argv[]) {
        Log.i(TAG, "Starting WebViewZygoteInit");

        String socketName = null;
        for (String arg : argv) {
            Log.i(TAG, arg);
            if (arg.startsWith(Zygote.CHILD_ZYGOTE_SOCKET_NAME_ARG)) {
                socketName = arg.substring(Zygote.CHILD_ZYGOTE_SOCKET_NAME_ARG.length());
            }
        }
        if (socketName == null) {
            throw new RuntimeException("No " + Zygote.CHILD_ZYGOTE_SOCKET_NAME_ARG + " specified");
        }

        try {
            Os.prctl(OsConstants.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
        } catch (ErrnoException ex) {
            throw new RuntimeException("Failed to set PR_SET_NO_NEW_PRIVS", ex);
        }

        sServer = new WebViewZygoteServer();

        final Runnable caller;
        try {
            sServer.registerServerSocketAtAbstractName(socketName);

            // Add the abstract socket to the FD whitelist so that the native zygote code
            // can properly detach it after forking.
            Zygote.nativeAllowFileAcrossFork("ABSTRACT/" + socketName);

            // The select loop returns early in the child process after a fork and
            // loops forever in the zygote.
            caller = sServer.runSelectLoop(TextUtils.join(",", Build.SUPPORTED_ABIS));
        } catch (RuntimeException e) {
            Log.e(TAG, "Fatal exception:", e);
            throw e;
        } finally {
            sServer.closeServerSocket();
        }

        // We're in the child process and have exited the select loop. Proceed to execute the
        // command.
        if (caller != null) {
            caller.run();
        }
    }
}
