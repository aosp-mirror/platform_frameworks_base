// Copyright 2009 The Android Open Source Project

package com.android.internal.net;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.apache.commons.codec.binary.Base64;
import org.apache.harmony.xnet.provider.jsse.SSLClientSessionCache;

import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLSession;

/**
 * Hook into harmony SSL cache to persist the SSL sessions.
 * 
 * Current implementation is suitable for saving a small number of hosts -
 * like google services. It can be extended with expiration and more features
 * to support more hosts.  
 * 
 * {@hide}
 */
public class DbSSLSessionCache implements SSLClientSessionCache {
    private static final String TAG = "DbSSLSessionCache";

    /** 
     * Table where sessions are stored. 
     */
    public static final String SSL_CACHE_TABLE = "ssl_sessions";

    private static final String SSL_CACHE_ID = "_id";
    
    /**
     * Key is host:port - port is not optional.
     */
    private static final String SSL_CACHE_HOSTPORT = "hostport";
    
    /**
     * Base64-encoded DER value of the session.
     */
    private static final String SSL_CACHE_SESSION = "session";
    
    /**
     * Time when the record was added - should be close to the time 
     * of the initial session negotiation.
     */
    private static final String SSL_CACHE_TIME_SEC = "time_sec";

    public static final String DATABASE_NAME = "ssl_sessions.db";

    public static final int DATABASE_VERSION = 2;
    
    /** public for testing 
     */
    public static final int SSL_CACHE_ID_COL = 0;
    public static final int SSL_CACHE_HOSTPORT_COL = 1;
    public static final int SSL_CACHE_SESSION_COL = 2;
    public static final int SSL_CACHE_TIME_SEC_COL = 3;
    
    public static final int MAX_CACHE_SIZE = 256;
    
    private final Map<String, byte[]> mExternalCache =
        new HashMap<String, byte[]>();
        
        
    private DatabaseHelper mDatabaseHelper;

    private boolean mNeedsCacheLoad = true;
    
    public static final String[] PROJECTION = new String[] {
      SSL_CACHE_ID, 
      SSL_CACHE_HOSTPORT,
      SSL_CACHE_SESSION,
      SSL_CACHE_TIME_SEC
    };

    private static final Map<String,DbSSLSessionCache> sInstances =
            new HashMap<String,DbSSLSessionCache>();

    /**
     * Returns a singleton instance of the DbSSLSessionCache that should be used for this
     * context's package.
     *
     * @param context The context that should be used for getting/creating the singleton instance.
     * @return The singleton instance for the context's package.
     */
    public static synchronized DbSSLSessionCache getInstanceForPackage(Context context) {
        String packageName = context.getPackageName();
        if (sInstances.containsKey(packageName)) {
            return sInstances.get(packageName);
        }
        DbSSLSessionCache cache = new DbSSLSessionCache(context);
        sInstances.put(packageName, cache);
        return cache;
    }
    
    /**
     * Create a SslSessionCache instance, using the specified context to 
     * initialize the database.
     * 
     * This constructor will use the default database - created for the application
     * context.
     * 
     * @param activityContext
     */
    private DbSSLSessionCache(Context activityContext) {
        Context appContext = activityContext.getApplicationContext();
        mDatabaseHelper = new DatabaseHelper(appContext);
    }
    
    /**
     * Create a SslSessionCache that uses a specific database.
     * 
     * 
     * @param database
     */
    public DbSSLSessionCache(DatabaseHelper database) {
        this.mDatabaseHelper = database;
    }
    
    public void putSessionData(SSLSession session, byte[] der) {
        if (mDatabaseHelper == null) {
            return;
        }
        synchronized (this.getClass()) {
            SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();
            if (mExternalCache.size() == MAX_CACHE_SIZE) {
                // remove oldest.
                // TODO: check if the new one is in cached already ( i.e. update ).
                Cursor byTime = mDatabaseHelper.getReadableDatabase().query(SSL_CACHE_TABLE, 
                        PROJECTION, null, null, null, null, SSL_CACHE_TIME_SEC);
                if (byTime.moveToFirst()) {
                    // TODO: can I do byTime.deleteRow() ? 
                    String hostPort = byTime.getString(SSL_CACHE_HOSTPORT_COL);
                    db.delete(SSL_CACHE_TABLE, 
                            SSL_CACHE_HOSTPORT + "= ?" , new String[] { hostPort });
                    mExternalCache.remove(hostPort);
                } else {
                    Log.w(TAG, "No rows found");
                    // something is wrong, clear it
                    clear();
                }
            }
            // Serialize native session to standard DER encoding    
            long t0 = System.currentTimeMillis();

            String b64 = new String(Base64.encodeBase64(der));
            String key = session.getPeerHost() + ":" + session.getPeerPort();

            ContentValues values = new ContentValues();
            values.put(SSL_CACHE_HOSTPORT, key);
            values.put(SSL_CACHE_SESSION, b64);
            values.put(SSL_CACHE_TIME_SEC, System.currentTimeMillis() / 1000);

            mExternalCache.put(key, der);

            try {
                db.insert(SSL_CACHE_TABLE, null /*nullColumnHack */ , values);
            } catch(SQLException ex) {
                // Ignore - nothing we can do to recover, and caller shouldn't 
                // be affected.
                Log.w(TAG, "Ignoring SQL exception when caching session", ex);
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                long t1 = System.currentTimeMillis();
                Log.d(TAG, "New SSL session " + session.getPeerHost() +
                        " DER len: " + der.length + " " + (t1 - t0));
            }
        }

    }

    public byte[] getSessionData(String host, int port) {
        // Current (simple) implementation does a single lookup to DB, then saves 
        // all entries to the cache. 

        // This works for google services - i.e. small number of certs.
        // If we extend this to all processes - we should hold a separate cache 
        // or do lookups to DB each time. 
        if (mDatabaseHelper == null) {
            return null;
        }
        synchronized(this.getClass()) {
            if (mNeedsCacheLoad) {
                // Don't try to load again, if something is wrong on the first 
                // request it'll likely be wrong each time.
                mNeedsCacheLoad = false;
                long t0 = System.currentTimeMillis();

                Cursor cur = null;
                try {
                    cur = mDatabaseHelper.getReadableDatabase().query(SSL_CACHE_TABLE, 
                            PROJECTION, null, null, null, null, null);
                    if (cur.moveToFirst()) {
                        do {
                            String hostPort = cur.getString(SSL_CACHE_HOSTPORT_COL);
                            String value = cur.getString(SSL_CACHE_SESSION_COL);

                            if (hostPort == null || value == null) {
                                continue;
                            }
                            // TODO: blob support ?
                            byte[] der = Base64.decodeBase64(value.getBytes());
                            mExternalCache.put(hostPort, der);
                        } while (cur.moveToNext());

                    }
                } catch (SQLException ex) {
                    Log.d(TAG, "Error loading SSL cached entries ", ex);
                } finally {
                    if (cur != null) {
                        cur.close();
                    }
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        long t1 = System.currentTimeMillis();
                        Log.d(TAG, "LOADED CACHED SSL " + (t1 - t0) + " ms");
                    }
                }
            }

            String key = host + ":" + port;

            return mExternalCache.get(key);
        }
    }

    /** 
     * Reset the database and internal state. 
     * Used for testing or to free space.
     */
    public void clear() {
        synchronized(this) {
            try {
                mExternalCache.clear();
                mNeedsCacheLoad = true;
                mDatabaseHelper.getWritableDatabase().delete(SSL_CACHE_TABLE, 
                        null, null);
            } catch (SQLException ex) {
                Log.d(TAG, "Error removing SSL cached entries ", ex);
                // ignore - nothing we can do about it
            }
        } 
    }

    public byte[] getSessionData(byte[] id) {
        // We support client side only - the cache will do nothing for 
        // server-side sessions. 
        return null;
    }

    /** Visible for testing. 
     */
    public static class DatabaseHelper extends SQLiteOpenHelper {

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null /* factory */, DATABASE_VERSION);
        }   

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + SSL_CACHE_TABLE + " (" +
                    SSL_CACHE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    SSL_CACHE_HOSTPORT + " TEXT UNIQUE ON CONFLICT REPLACE," +
                    SSL_CACHE_SESSION + " TEXT," +
                    SSL_CACHE_TIME_SEC + " INTEGER" +
            ");");
            
            // No index - we load on startup, index would slow down inserts.
            // If we want to scale this to lots of rows - we could use 
            // index, but then we'll hit DB a bit too often ( including 
            // negative hits )
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + SSL_CACHE_TABLE );
            onCreate(db);
        }

    }
    
}
