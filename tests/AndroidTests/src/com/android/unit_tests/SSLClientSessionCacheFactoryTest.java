package com.android.unit_tests;

import com.google.android.net.SSLClientSessionCacheFactory;
import com.android.internal.net.DbSSLSessionCache;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import org.apache.harmony.xnet.provider.jsse.SSLClientSessionCache;

/**
 *  Unit test for {@link SSLClientSessionCacheFactory}.
 */
@MediumTest
public final class SSLClientSessionCacheFactoryTest extends AndroidTestCase {

    protected void tearDown() throws Exception {
        setSslSessionCacheValue(getContext(), "");
        super.tearDown();
    }

    private static void setSslSessionCacheValue(Context context, String value) {
        ContentResolver resolver = context.getContentResolver();
        Settings.Gservices.putString(resolver, Settings.Gservices.SSL_SESSION_CACHE, value);
    }

    private static SSLClientSessionCache getCache(Context context, String type) {
        setSslSessionCacheValue(context, type);
        return SSLClientSessionCacheFactory.getCache(context);
    }

    public void testGetDbCache() throws Exception {
        Context context = getContext();
        SSLClientSessionCache cache = getCache(context, "db");
        assertNotNull(cache);
        assertTrue(cache instanceof DbSSLSessionCache);
    }

    public void testGetFileCache() throws Exception {
        Context context = getContext();
        SSLClientSessionCache cache = getCache(context, "file");
        assertNotNull(cache);
        // yuck =)
        assertEquals("org.apache.harmony.xnet.provider.jsse.FileClientSessionCache$Impl",
                cache.getClass().getName());
    }

    public void testGetNoCache() throws Exception {
        Context context = getContext();
        SSLClientSessionCache cache = getCache(context, "none");
        assertNull(cache);
    }
}
