// Copyright 2009 The Android Open Source Project
package com.android.internal.net;

import org.apache.harmony.xnet.provider.jsse.SSLClientSessionCache;
import org.apache.harmony.xnet.provider.jsse.SSLContextImpl;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * Utility class to configure SSL session caching. 
 * 
 * 
 * 
 * {@hide}
 */
public class SSLSessionCache {
    private static final String TAG = "SSLSessionCache";

    private static final String CACHE_TYPE_DB = "db";
    
    private static boolean sInitializationDone = false;
    
    // One per process
    private static DbSSLSessionCache sDbCache;
    
    /**
     * Check settings for ssl session caching. 
     * 
     * @return false if disabled.
     */
    public static boolean isEnabled(ContentResolver resolver) {
        String sslCache = Settings.Gservices.getString(resolver,
                Settings.Gservices.SSL_SESSION_CACHE);
        
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "enabled " + sslCache);
        }

        return CACHE_TYPE_DB.equals(sslCache);
    }

    /**
     * Return the configured session cache, or null if not enabled.
     */
    public static SSLClientSessionCache getSessionCache(Context context) {
        if (context == null) {
            return null;
        }
        if (!sInitializationDone) {
            if (isEnabled(context.getContentResolver())) {
                sDbCache = new DbSSLSessionCache(context);
                return sDbCache;
            }
            // Don't check again.
            sInitializationDone = true;
        }
        return sDbCache;
    }
    
    /**
     * Construct the factory, using default constructor if caching is disabled.
     * Refactored here to avoid duplication, used in tests.
     */
    public static SSLSocketFactory getSocketFactory(Context androidContext,
             TrustManager[] trustManager) {
        try {
            if (androidContext != null) {
                SSLClientSessionCache sessionCache = getSessionCache(androidContext);
                
                if (sessionCache != null) {
                    SSLContextImpl sslContext = new SSLContextImpl();
                    sslContext.engineInit(null /* kms */, 
                            trustManager, new java.security.SecureRandom(), 
                            sessionCache, null /* serverCache */);
                    return sslContext.engineGetSocketFactory(); 
                }
            }
            // default
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManager, new java.security.SecureRandom());
            return context.getSocketFactory();
            
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        } catch (KeyManagementException e) {
            throw new AssertionError(e);
        }
    }
    

}
