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

package android.database.sqlite;

import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * For each instance of {@link SQLiteDatabase}, this class maintains a LRU cache to store
 * the compiled query statement ids returned by sqlite database.
 *<p>
 *<ul>
 *     <li>key = SQL statement with "?" for bind args</li>
 *     <li>value = {@link SQLiteCompiledSql}</li>
 *</ul>
 * If an application opens the database and keeps it open during its entire life, then
 * there will not be an overhead of compilation of SQL statements by sqlite.
 *<p>
 * Why is this cache NOT static? because sqlite attaches compiled-sql statements to the
 * database connections.
 *<p>
 * This cache has an upper limit of mMaxSqlCacheSize (settable by calling the method
 * (@link #setMaxSqlCacheSize(int)}).
 */
/* package */ class SQLiteCache {
    private static final String TAG = "SQLiteCache";

    /** The {@link SQLiteDatabase} instance this cache is attached to */
    private final SQLiteDatabase mDatabase;

    /** Default statement-cache size per database connection ( = instance of this class) */
    private int mMaxSqlCacheSize = 25;

    /** The LRU cache */
    private final Map<String, SQLiteCompiledSql> mCompiledQueries =
        new LinkedHashMap<String, SQLiteCompiledSql>(mMaxSqlCacheSize + 1, 0.75f, true) {
            @Override
            public boolean removeEldestEntry(Map.Entry<String, SQLiteCompiledSql> eldest) {
                // eldest = least-recently used entry
                // if it needs to be removed to accommodate a new entry,
                //     close {@link SQLiteCompiledSql} represented by this entry, if not in use
                //     and then let it be removed from the Map.
                mDatabase.verifyLockOwner();
                if (this.size() <= mMaxSqlCacheSize) {
                    // cache is not full. nothing needs to be removed
                    return false;
                }
                // cache is full. eldest will be removed.
                eldest.getValue().releaseIfNotInUse();
                // return true, so that this entry is removed automatically by the caller.
                return true;
            }
        };

    /** Maintains whether or not cacheFullWarning has been logged */
    private boolean mCacheFullWarning;

    /** The following 2 members maintain stats about cache hits and misses */
    private int mNumCacheHits;
    private int mNumCacheMisses;

    /**
     * Constructor used by {@link SQLiteDatabase}.
     * @param db
     */
    /* package */ SQLiteCache(SQLiteDatabase db) {
        mDatabase = db;
    }

    /**
     * Adds the given SQL and its compiled-statement to the cache, if the given SQL statement
     * doesn't already exist in cache.
     *
     * @return true if added to cache. false otherwise.
     */
    /* package */ synchronized boolean addToCompiledQueries(String sql,
            SQLiteCompiledSql compiledStatement) {
        if (mCompiledQueries.containsKey(sql)) {
            // already exists.
            return false;
        }

        /* add the given SQLiteCompiledSql compiledStatement to cache.
         * no need to worry about the cache size - because {@link #mCompiledQueries}
         * self-limits its size to {@link #mMaxSqlCacheSize}.
         */
        mCompiledQueries.put(sql, compiledStatement);

        // need to log a warning to say that the cache is full?
        if (!isCacheFullWarningLogged() && mCompiledQueries.size() == mMaxSqlCacheSize) {
            /*
             * cache size of {@link #mMaxSqlCacheSize} is not enough for this app.
             * log a warning.
             * chances are it is NOT using ? for bindargs - or cachesize is too small.
             */
            Log.w(TAG, "Reached MAX size for compiled-sql statement cache for database " +
                    mDatabase.getPath() +
                    ". Use setMaxSqlCacheSize() in SQLiteDatabase to increase cachesize. ");
            setCacheFullWarningLogged();
        }
        return true;
    }

    /**
     * Returns the compiled-statement for the given SQL statement, if the entry exists in cache
     * and is free to use. Returns null otherwise.
     * <p>
     * If a compiled-sql statement is returned for the caller, it is reserved for the caller.
     * So, don't use this method unless the caller needs to acquire the object.
     */
    /* package */ synchronized SQLiteCompiledSql getCompiledStatementForSql(String sql) {
        SQLiteCompiledSql compiledStatement = mCompiledQueries.get(sql);
        if (compiledStatement == null) {
            mNumCacheMisses++;
            return null;
        }
        mNumCacheHits++;
        // reserve it for the caller, if it is not already in use
        if (!compiledStatement.acquire()) {
            // couldn't acquire it since it is already in use. bug in app?
            if (SQLiteDebug.DEBUG_ACTIVE_CURSOR_FINALIZATION) {
                Log.w(TAG, "Possible bug: Either using the same SQL in 2 threads at " +
                        " the same time, or previous instance of this SQL statement was " +
                        "never close()d. " + compiledStatement.toString());
            }
            return null;
        }
        return compiledStatement;
    }

    /**
     * If the given statement is in cache, it is released back to cache and it is made available for
     * others to use.
     * <p>
     * return true if the statement is put back in cache, false otherwise (false = the statement
     * is NOT in cache)
     */
    /* package */ synchronized boolean releaseBackToCache(SQLiteCompiledSql stmt) {
        if (!mCompiledQueries.containsValue(stmt)) {
            return false;
        }
        // it is in cache. release it from the caller, make it available for others to use
        stmt.free();
        return true;
    }

    /**
     * releases all compiled-sql statements in the cache.
     */
    /* package */ synchronized void dealloc() {
        for (SQLiteCompiledSql stmt : mCompiledQueries.values()) {
            stmt.setState(SQLiteCompiledSql.NSTATE_CACHE_DEALLOC);
            stmt.releaseFromDatabase();
        }
        mCompiledQueries.clear();
    }

    /**
     * see documentation on {@link SQLiteDatabase#setMaxSqlCacheSize(int)}.
     */
    /* package */ synchronized void setMaxSqlCacheSize(int cacheSize) {
        if (cacheSize > SQLiteDatabase.MAX_SQL_CACHE_SIZE || cacheSize < 0) {
            throw new IllegalStateException("expected value between 0 and " +
                    SQLiteDatabase.MAX_SQL_CACHE_SIZE);
        } else if (cacheSize < mMaxSqlCacheSize) {
            throw new IllegalStateException("cannot set cacheSize to a value less than the value " +
                    "set with previous setMaxSqlCacheSize() call.");
        }
        mMaxSqlCacheSize = cacheSize;
    }

    /* package */ synchronized boolean isSqlInStatementCache(String sql) {
        return mCompiledQueries.containsKey(sql);
    }

    private synchronized boolean isCacheFullWarningLogged() {
        return mCacheFullWarning;
    }

    private synchronized void setCacheFullWarningLogged() {
        mCacheFullWarning = true;
    }
    /* package */ synchronized int getCacheHitNum() {
        return mNumCacheHits;
    }
    /* package */ synchronized int getCacheMissNum() {
        return mNumCacheMisses;
    }
    /* package */ synchronized int getCachesize() {
        return mCompiledQueries.size();
    }

    // only for testing
    /* package */ synchronized Set<String> getKeys() {
        return mCompiledQueries.keySet();
    }
}
