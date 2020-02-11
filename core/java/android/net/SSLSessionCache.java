/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.util.Log;

import com.android.org.conscrypt.ClientSessionContext;
import com.android.org.conscrypt.FileClientSessionCache;
import com.android.org.conscrypt.SSLClientSessionCache;

import java.io.File;
import java.io.IOException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSessionContext;

/**
 * File-based cache of established SSL sessions.  When re-establishing a
 * connection to the same server, using an SSL session cache can save some time,
 * power, and bandwidth by skipping directly to an encrypted stream.
 * This is a persistent cache which can span executions of the application.
 *
 * @see SSLCertificateSocketFactory
 */
public final class SSLSessionCache {
    private static final String TAG = "SSLSessionCache";
    @UnsupportedAppUsage
    /* package */ final SSLClientSessionCache mSessionCache;

    /**
     * Installs a {@link SSLSessionCache} on a {@link SSLContext}. The cache will
     * be used on all socket factories created by this context (including factories
     * created before this call).
     *
     * @param cache the cache instance to install, or {@code null} to uninstall any
     *         existing cache.
     * @param context the context to install it on.
     * @throws IllegalArgumentException if the context does not support a session
     *         cache.
     *
     * @hide candidate for public API
     */
    public static void install(SSLSessionCache cache, SSLContext context) {
        SSLSessionContext clientContext = context.getClientSessionContext();
        if (clientContext instanceof ClientSessionContext) {
            ((ClientSessionContext) clientContext).setPersistentCache(
                    cache == null ? null : cache.mSessionCache);
        } else {
            throw new IllegalArgumentException("Incompatible SSLContext: " + context);
        }
    }

    /**
     * NOTE: This needs to be Object (and not SSLClientSessionCache) because apps
     * that build directly against the framework (and not the SDK) might not declare
     * a dependency on conscrypt. Javac will then has fail while resolving constructors.
     *
     * @hide For unit test use only
     */
    public SSLSessionCache(Object cache) {
        mSessionCache = (SSLClientSessionCache) cache;
    }

    /**
     * Create a session cache using the specified directory.
     * Individual session entries will be files within the directory.
     * Multiple instances for the same directory share data internally.
     *
     * @param dir to store session files in (created if necessary)
     * @throws IOException if the cache can't be opened
     */
    public SSLSessionCache(File dir) throws IOException {
        mSessionCache = FileClientSessionCache.usingDirectory(dir);
    }

    /**
     * Create a session cache at the default location for this app.
     * Multiple instances share data internally.
     *
     * @param context for the application
     */
    public SSLSessionCache(Context context) {
        File dir = context.getDir("sslcache", Context.MODE_PRIVATE);
        SSLClientSessionCache cache = null;
        try {
            cache = FileClientSessionCache.usingDirectory(dir);
        } catch (IOException e) {
            Log.w(TAG, "Unable to create SSL session cache in " + dir, e);
        }
        mSessionCache = cache;
    }
}
