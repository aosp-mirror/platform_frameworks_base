/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.os.SystemClock;

import dalvik.system.BlockGuard;

/**
 * A pre-compiled statement against a {@link SQLiteDatabase} that can be reused.
 * The statement cannot return multiple rows, but 1x1 result sets are allowed.
 * Don't use SQLiteStatement constructor directly, please use
 * {@link SQLiteDatabase#compileStatement(String)}
 *<p>
 * SQLiteStatement is not internally synchronized so code using a SQLiteStatement from multiple
 * threads should perform its own synchronization when using the SQLiteStatement.
 */
@SuppressWarnings("deprecation")
public class SQLiteStatement extends SQLiteProgram
{
    private static final boolean READ = true;
    private static final boolean WRITE = false;

    private SQLiteDatabase mOrigDb;
    private int mState;
    /** possible value for {@link #mState}. indicates that a transaction is started.} */
    private static final int TRANS_STARTED = 1;
    /** possible value for {@link #mState}. indicates that a lock is acquired.} */
    private static final int LOCK_ACQUIRED = 2;

    /**
     * Don't use SQLiteStatement constructor directly, please use
     * {@link SQLiteDatabase#compileStatement(String)}
     * @param db
     * @param sql
     */
    /* package */ SQLiteStatement(SQLiteDatabase db, String sql, Object[] bindArgs) {
        super(db, sql, bindArgs, false /* don't compile sql statement */);
    }

    /**
     * Execute this SQL statement, if it is not a SELECT / INSERT / DELETE / UPDATE, for example
     * CREATE / DROP table, view, trigger, index etc.
     *
     * @throws android.database.SQLException If the SQL string is invalid for
     *         some reason
     */
    public void execute() {
        executeUpdateDelete();
    }

    /**
     * Execute this SQL statement, if the the number of rows affected by exection of this SQL
     * statement is of any importance to the caller - for example, UPDATE / DELETE SQL statements.
     *
     * @return the number of rows affected by this SQL statement execution.
     * @throws android.database.SQLException If the SQL string is invalid for
     *         some reason
     */
    public int executeUpdateDelete() {
        synchronized(this) {
            long timeStart = acquireAndLock(WRITE);
            try {
                int numChanges = native_execute();
                mDatabase.logTimeStat(mSql, timeStart);
                return numChanges;
            } finally {
                releaseAndUnlock();
            }
        }
    }

    /**
     * Execute this SQL statement and return the ID of the row inserted due to this call.
     * The SQL statement should be an INSERT for this to be a useful call.
     *
     * @return the row ID of the last row inserted, if this insert is successful. -1 otherwise.
     *
     * @throws android.database.SQLException If the SQL string is invalid for
     *         some reason
     */
    public long executeInsert() {
        synchronized(this) {
            long timeStart = acquireAndLock(WRITE);
            try {
                long lastInsertedRowId = native_executeInsert();
                mDatabase.logTimeStat(mSql, timeStart);
                return lastInsertedRowId;
            } finally {
                releaseAndUnlock();
            }
        }
    }

    /**
     * Execute a statement that returns a 1 by 1 table with a numeric value.
     * For example, SELECT COUNT(*) FROM table;
     *
     * @return The result of the query.
     *
     * @throws android.database.sqlite.SQLiteDoneException if the query returns zero rows
     */
    public long simpleQueryForLong() {
        synchronized(this) {
            long timeStart = acquireAndLock(READ);
            try {
                long retValue = native_1x1_long();
                mDatabase.logTimeStat(mSql, timeStart);
                return retValue;
            } finally {
                releaseAndUnlock();
            }
        }
    }

    /**
     * Execute a statement that returns a 1 by 1 table with a text value.
     * For example, SELECT COUNT(*) FROM table;
     *
     * @return The result of the query.
     *
     * @throws android.database.sqlite.SQLiteDoneException if the query returns zero rows
     */
    public String simpleQueryForString() {
        synchronized(this) {
            long timeStart = acquireAndLock(READ);
            try {
                String retValue = native_1x1_string();
                mDatabase.logTimeStat(mSql, timeStart);
                return retValue;
            } finally {
                releaseAndUnlock();
            }
        }
    }

    /**
     * Called before every method in this class before executing a SQL statement,
     * this method does the following:
     * <ul>
     *   <li>make sure the database is open</li>
     *   <li>get a database connection from the connection pool,if possible</li>
     *   <li>notifies {@link BlockGuard} of read/write</li>
     *   <li>if the SQL statement is an update, start transaction if not already in one.
     *   otherwise, get lock on the database</li>
     *   <li>acquire reference on this object</li>
     *   <li>and then return the current time _before_ the database lock was acquired</li>
     * </ul>
     * <p>
     * This method removes the duplicate code from the other public
     * methods in this class.
     */
    private long acquireAndLock(boolean rwFlag) {
        mState = 0;
        // use pooled database connection handles for SELECT SQL statements
        mDatabase.verifyDbIsOpen();
        SQLiteDatabase db = (mStatementType != DatabaseUtils.STATEMENT_SELECT) ? mDatabase
                : mDatabase.getDbConnection(mSql);
        // use the database connection obtained above
        mOrigDb = mDatabase;
        mDatabase = db;
        nHandle = mDatabase.mNativeHandle;
        if (rwFlag == WRITE) {
            BlockGuard.getThreadPolicy().onWriteToDisk();
        } else {
            BlockGuard.getThreadPolicy().onReadFromDisk();
        }

        /*
         * Special case handling of SQLiteDatabase.execSQL("BEGIN transaction").
         * we know it is execSQL("BEGIN transaction") from the caller IF there is no lock held.
         * beginTransaction() methods in SQLiteDatabase call lockForced() before
         * calling execSQL("BEGIN transaction").
         */
        if (mStatementType == DatabaseUtils.STATEMENT_BEGIN) {
            if (!mDatabase.isDbLockedByCurrentThread()) {
                // transaction is  NOT started by calling beginTransaction() methods in
                // SQLiteDatabase
                mDatabase.setTransactionUsingExecSqlFlag();
            }
        } else if (mStatementType == DatabaseUtils.STATEMENT_UPDATE) {
            // got update SQL statement. if there is NO pending transaction, start one
            if (!mDatabase.inTransaction()) {
                mDatabase.beginTransactionNonExclusive();
                mState = TRANS_STARTED;
            }
        }
        // do I have database lock? if not, grab it.
        if (!mDatabase.isDbLockedByCurrentThread()) {
            mDatabase.lock();
            mState = LOCK_ACQUIRED;
        }

        acquireReference();
        long startTime = SystemClock.uptimeMillis();
        mDatabase.closePendingStatements();
        compileAndbindAllArgs();
        return startTime;
    }

    /**
     * this method releases locks and references acquired in {@link #acquireAndLock(boolean)}
     */
    private void releaseAndUnlock() {
        releaseReference();
        if (mState == TRANS_STARTED) {
            try {
                mDatabase.setTransactionSuccessful();
            } finally {
                mDatabase.endTransaction();
            }
        } else if (mState == LOCK_ACQUIRED) {
            mDatabase.unlock();
        }
        if (mStatementType == DatabaseUtils.STATEMENT_COMMIT ||
                mStatementType == DatabaseUtils.STATEMENT_ABORT) {
            mDatabase.resetTransactionUsingExecSqlFlag();
        }
        clearBindings();
        // release the compiled sql statement so that the caller's SQLiteStatement no longer
        // has a hard reference to a database object that may get deallocated at any point.
        releaseCompiledSqlIfNotInCache();
        // restore the database connection handle to the original value
        mDatabase = mOrigDb;
        nHandle = mDatabase.mNativeHandle;
    }

    private final native int native_execute();
    private final native long native_executeInsert();
    private final native long native_1x1_long();
    private final native String native_1x1_string();
}
