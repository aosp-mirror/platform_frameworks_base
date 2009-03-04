package com.google.android.net;

import org.apache.harmony.xnet.provider.jsse.SSLClientSessionCache;
import org.apache.harmony.xnet.provider.jsse.FileClientSessionCache;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.IOException;

import com.android.internal.net.DbSSLSessionCache;

/**
 * Factory that returns the appropriate implementation of a {@link SSLClientSessionCache} based
 * on gservices.
 *
 * @hide
 */
// TODO: return a proxied implementation that is updated as the gservices value changes.
public final class SSLClientSessionCacheFactory {

    private static final String TAG = "SSLClientSessionCacheFactory";

    public static final String DB = "db";
    public static final String FILE = "file";

    // utility class
    private SSLClientSessionCacheFactory() {}

    /**
     * Returns a new {@link SSLClientSessionCache} based on the persistent cache that's specified,
     * if any, in gservices.  If no cache is specified, returns null.
     * @param context The application context used for the per-process persistent cache.
     * @return A new {@link SSLClientSessionCache}, or null if no persistent cache is configured.
     */
    public static SSLClientSessionCache getCache(Context context) {
        String type = Settings.Gservices.getString(context.getContentResolver(),
                Settings.Gservices.SSL_SESSION_CACHE);

        if (type != null) {
            if (DB.equals(type)) {
                return DbSSLSessionCache.getInstanceForPackage(context);
            } else if (FILE.equals(type)) {
                File dir = context.getFilesDir();
                File cacheDir = new File(dir, "sslcache");
                if (!cacheDir.exists()) {
                    cacheDir.mkdir();
                }
                try {
                    return FileClientSessionCache.usingDirectory(cacheDir);
                } catch (IOException ioe) {
                    Log.w(TAG, "Unable to create FileClientSessionCache in " + cacheDir.getName(), ioe);
                    return null;
                }
            } else {
                Log.w(TAG, "Ignoring unrecognized type: '" + type + "'");       
            }
        }
        return null;
    }
}
