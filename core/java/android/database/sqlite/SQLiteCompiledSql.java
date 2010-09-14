/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.database.DatabaseUtils;
import android.util.Log;

/**
 * This class encapsulates compilation of sql statement and release of the compiled statement obj.
 * Once a sql statement is compiled, it is cached in {@link SQLiteDatabase}
 * and it is released in one of the 2 following ways
 * 1. when {@link SQLiteDatabase} object is closed.
 * 2. if this is not cached in {@link SQLiteDatabase}, {@link android.database.Cursor#close()}
 * releases this obj.
 */
/* package */ class SQLiteCompiledSql {

    private static final String TAG = "SQLiteCompiledSql";

    /** The database this program is compiled against. */
    /* package */ final SQLiteDatabase mDatabase;

    /**
     * Native linkage, do not modify. This comes from the database.
     */
    /* package */ final int nHandle;

    /**
     * Native linkage, do not modify. When non-0 this holds a reference to a valid
     * sqlite3_statement object. It is only updated by the native code, but may be
     * checked in this class when the database lock is held to determine if there
     * is a valid native-side program or not.
     */
    /* package */ int nStatement = 0;

    /** the following 3 members are for debugging purposes */
    private final String mSqlStmt;
    private final Throwable mStackTrace;
    private int nState = 0;
    /** values the above member can take */
    private static final int NSTATE_CACHEABLE = 64;
    private static final int NSTATE_IN_CACHE = 32;
    private static final int NSTATE_INUSE = 16;
    private static final int NSTATE_INUSE_RESETMASK = 0x0f;
    /* package */ static final int NSTATE_CLOSE_NOOP = 1;
    private static final int NSTATE_EVICTED_FROM_CACHE = 2;
    /* package */ static final int NSTATE_CACHE_DEALLOC = 4;
    private static final int NSTATE_IN_FINALIZER_Q = 8;

    private SQLiteCompiledSql(SQLiteDatabase db, String sql) {
        db.verifyDbIsOpen();
        db.verifyLockOwner();
        mDatabase = db;
        mSqlStmt = sql;
        mStackTrace = new DatabaseObjectNotClosedException().fillInStackTrace();
        nHandle = db.mNativeHandle;
        native_compile(sql);
    }

    /* package */ static SQLiteCompiledSql get(SQLiteDatabase db, String sql, int type) {
        // only CRUD statements are cached.
        if (type != DatabaseUtils.STATEMENT_SELECT && type != DatabaseUtils.STATEMENT_UPDATE) {
            return new SQLiteCompiledSql(db, sql);
        }
        // the given SQL statement is cacheable.
        SQLiteCompiledSql stmt = db.mCache.getCompiledStatementForSql(sql);
        if (stmt != null) {
            return stmt;
        }
        // either the entry doesn't exist in cache or the one in cache is currently in use.
        // try to add it to cache and let cache worry about what copy to keep
        stmt = new SQLiteCompiledSql(db, sql);
        stmt.nState |= NSTATE_CACHEABLE |
                ((db.mCache.addToCompiledQueries(sql, stmt)) ? NSTATE_IN_CACHE : 0);
        return stmt;
    }

    /* package */ synchronized void releaseFromDatabase() {
        // Note that native_finalize() checks to make sure that nStatement is
        // non-null before destroying it.
        if (nStatement != 0) {
            nState |= NSTATE_IN_FINALIZER_Q;
            mDatabase.finalizeStatementLater(nStatement);
            nStatement = 0;
        }
    }

    /**
     * returns true if acquire() succeeds. false otherwise.
     */
    /* package */ synchronized boolean acquire() {
        if ((nState & NSTATE_INUSE) > 0 ) {
            // this object is already in use
            return false;
        }
        nState |= NSTATE_INUSE;
        return true;
    }

    /* package */ synchronized void free() {
        nState &= NSTATE_INUSE_RESETMASK;
    }

    /* package */ void release(int type) {
        if (type != DatabaseUtils.STATEMENT_SELECT && type != DatabaseUtils.STATEMENT_UPDATE) {
            // it is not cached. release its memory from the database.
            releaseFromDatabase();
            return;
        }
        // if in cache, reset its in-use flag
        if (!mDatabase.mCache.releaseBackToCache(this)) {
            // not in cache. release its memory from the database.
            releaseFromDatabase();
        }
    }

    /* package */ synchronized void releaseIfNotInUse() {
        nState |= NSTATE_EVICTED_FROM_CACHE;
        // if it is not in use, release its memory from the database
        if ((nState & NSTATE_INUSE) == 0) {
            releaseFromDatabase();
        }
    }

    // only for testing purposes
    /* package */ synchronized boolean isInUse() {
        return (nState & NSTATE_INUSE) > 0;
    }

    /* package */ synchronized SQLiteCompiledSql setState(int val) {
        nState = nState & val;
        return this; // for chaining
    }

    /**
     * Make sure that the native resource is cleaned up.
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            if (nStatement == 0) return;
            // finalizer should NEVER get called
            // but if the database itself is not closed and is GC'ed, then
            // all sub-objects attached to the database could end up getting GC'ed too.
            // in that case, don't print any warning.
            if ((nState & NSTATE_INUSE) == 0) {
                // no need to print warning
            } else {
                int len = mSqlStmt.length();
                Log.w(TAG, "Releasing SQL statement in finalizer. " +
                        "Could be due to close() not being called on the cursor or on the database. " +
                        toString(), mStackTrace);
            }
            releaseFromDatabase();
        } finally {
            super.finalize();
        }
    }

    @Override public String toString() {
        synchronized(this) {
            StringBuilder buff = new StringBuilder();
            buff.append(" nStatement=");
            buff.append(nStatement);
            if ((nState & NSTATE_CACHEABLE) > 0) {
                buff.append(",cacheable");
            }
            if ((nState & NSTATE_IN_CACHE) > 0) {
                buff.append(",cached");
            }
            if ((nState & NSTATE_INUSE) > 0) {
                buff.append(",in_use");
            }
            if ((nState & NSTATE_CLOSE_NOOP) > 0) {
                buff.append(",no_op_close");
            }
            if ((nState & NSTATE_EVICTED_FROM_CACHE) > 0) {
                buff.append(",evicted_from_cache");
            }
            if ((nState & NSTATE_CACHE_DEALLOC) > 0) {
                buff.append(",dealloc_cache");
            }
            if ((nState & NSTATE_IN_FINALIZER_Q) > 0) {
                buff.append(",in dbFInalizerQ");
            }
            buff.append(", db=");
            buff.append(mDatabase.getPath());
            buff.append(", db_connectionNum=");
            buff.append(mDatabase.mConnectionNum);
            buff.append(", sql=");
            int len = mSqlStmt.length();
            buff.append(mSqlStmt.substring(0, (len > 100) ? 100 : len));
            return buff.toString();
        }
    }

    /**
     * Compiles SQL into a SQLite program.
     *
     * <P>The database lock must be held when calling this method.
     * @param sql The SQL to compile.
     */
    private final native void native_compile(String sql);
}
