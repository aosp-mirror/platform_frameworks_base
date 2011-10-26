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

import android.os.StrictMode;
import android.util.Log;

/**
 * This class encapsulates compilation of sql statement and release of the compiled statement obj.
 * Once a sql statement is compiled, it is cached in {@link SQLiteDatabase}
 * and it is released in one of the 2 following ways
 * 1. when {@link SQLiteDatabase} object is closed.
 * 2. if this is not cached in {@link SQLiteDatabase}, {@link android.database.Cursor#close()}
 * releaases this obj.
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

    /** the following are for debugging purposes */
    private String mSqlStmt = null;
    private final Throwable mStackTrace;

    /** when in cache and is in use, this member is set */
    private boolean mInUse = false;

    /* package */ SQLiteCompiledSql(SQLiteDatabase db, String sql) {
        db.verifyDbIsOpen();
        db.verifyLockOwner();
        mDatabase = db;
        mSqlStmt = sql;
        if (StrictMode.vmSqliteObjectLeaksEnabled()) {
            mStackTrace = new DatabaseObjectNotClosedException().fillInStackTrace();
        } else {
            mStackTrace = null;
        }
        nHandle = db.mNativeHandle;
        native_compile(sql);
    }

    /* package */ void releaseSqlStatement() {
        // Note that native_finalize() checks to make sure that nStatement is
        // non-null before destroying it.
        if (nStatement != 0) {
            mDatabase.finalizeStatementLater(nStatement);
            nStatement = 0;
        }
    }

    /**
     * returns true if acquire() succeeds. false otherwise.
     */
    /* package */ synchronized boolean acquire() {
        if (mInUse) {
            // it is already in use.
            return false;
        }
        mInUse = true;
        return true;
    }

    /* package */ synchronized void release() {
        mInUse = false;
    }

    /* package */ synchronized void releaseIfNotInUse() {
        // if it is not in use, release its memory from the database
        if (!mInUse) {
            releaseSqlStatement();
        }
    }

    /**
     * Make sure that the native resource is cleaned up.
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            if (nStatement == 0) return;
            // don't worry about finalizing this object if it is ALREADY in the
            // queue of statements to be finalized later
            if (mDatabase.isInQueueOfStatementsToBeFinalized(nStatement)) {
                return;
            }
            // finalizer should NEVER get called
            // but if the database itself is not closed and is GC'ed, then
            // all sub-objects attached to the database could end up getting GC'ed too.
            // in that case, don't print any warning.
            if (mInUse && mStackTrace != null) {
                int len = mSqlStmt.length();
                StrictMode.onSqliteObjectLeaked(
                    "Releasing statement in a finalizer. Please ensure " +
                    "that you explicitly call close() on your cursor: " +
                    mSqlStmt.substring(0, (len > 1000) ? 1000 : len),
                    mStackTrace);
            }
            releaseSqlStatement();
        } finally {
            super.finalize();
        }
    }

    @Override public String toString() {
        synchronized(this) {
            StringBuilder buff = new StringBuilder();
            buff.append(" nStatement=");
            buff.append(nStatement);
            buff.append(", mInUse=");
            buff.append(mInUse);
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
