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

import android.util.Log;

/**
 * A base class for compiled SQLite programs.
 *<p>
 * SQLiteProgram is not internally synchronized so code using a SQLiteProgram from multiple
 * threads should perform its own synchronization when using the SQLiteProgram.
 */
public abstract class SQLiteProgram extends SQLiteClosable {

    private static final String TAG = "SQLiteProgram";

    /** the type of sql statement being processed by this object */
    private static final int SELECT_STMT = 1;
    private static final int UPDATE_STMT = 2;
    private static final int OTHER_STMT = 3;

    /** The database this program is compiled against.
     * @deprecated do not use this
     */
    @Deprecated
    protected SQLiteDatabase mDatabase;

    /** The SQL used to create this query */
    /* package */ final String mSql;

    /**
     * Native linkage, do not modify. This comes from the database and should not be modified
     * in here or in the native code.
     * @deprecated do not use this
     */
    @Deprecated
    protected int nHandle = 0;

    /**
     * the SQLiteCompiledSql object for the given sql statement.
     */
    private SQLiteCompiledSql mCompiledSql;

    /**
     * SQLiteCompiledSql statement id is populated with the corresponding object from the above
     * member. This member is used by the native_bind_* methods
     * @deprecated do not use this
     */
    @Deprecated
    protected int nStatement = 0;

    /* package */ SQLiteProgram(SQLiteDatabase db, String sql) {
        this(db, sql, true);
    }

    /* package */ SQLiteProgram(SQLiteDatabase db, String sql, boolean compileFlag) {
        mSql = sql.trim();
        attachObjectToDatabase(db);
        if (compileFlag) {
            compileSql();
        }
    }

    private void compileSql() {
        if (nStatement > 0) {
            // already compiled.
            return;
        }

        // only cache CRUD statements
        if (getSqlStatementType(mSql) == OTHER_STMT) {
            mCompiledSql = new SQLiteCompiledSql(mDatabase, mSql);
            nStatement = mCompiledSql.nStatement;
            // since it is not in the cache, no need to acquire() it.
            return;
        }

        mCompiledSql = mDatabase.getCompiledStatementForSql(mSql);
        if (mCompiledSql == null) {
            // create a new compiled-sql obj
            mCompiledSql = new SQLiteCompiledSql(mDatabase, mSql);

            // add it to the cache of compiled-sqls
            // but before adding it and thus making it available for anyone else to use it,
            // make sure it is acquired by me.
            mCompiledSql.acquire();
            mDatabase.addToCompiledQueries(mSql, mCompiledSql);
            if (SQLiteDebug.DEBUG_ACTIVE_CURSOR_FINALIZATION) {
                Log.v(TAG, "Created DbObj (id#" + mCompiledSql.nStatement +
                        ") for sql: " + mSql);
            }
        } else {
            // it is already in compiled-sql cache.
            // try to acquire the object.
            if (!mCompiledSql.acquire()) {
                int last = mCompiledSql.nStatement;
                // the SQLiteCompiledSql in cache is in use by some other SQLiteProgram object.
                // we can't have two different SQLiteProgam objects can't share the same
                // CompiledSql object. create a new one.
                // finalize it when I am done with it in "this" object.
                mCompiledSql = new SQLiteCompiledSql(mDatabase, mSql);
                if (SQLiteDebug.DEBUG_ACTIVE_CURSOR_FINALIZATION) {
                    Log.v(TAG, "** possible bug ** Created NEW DbObj (id#" +
                            mCompiledSql.nStatement +
                            ") because the previously created DbObj (id#" + last +
                            ") was not released for sql:" + mSql);
                }
                // since it is not in the cache, no need to acquire() it.
            }
        }
        nStatement = mCompiledSql.nStatement;
    }

    private int getSqlStatementType(String sql) {
        if (mSql.length() < 6) {
            return OTHER_STMT;
        }
        String prefixSql = mSql.substring(0, 6);
        if (prefixSql.equalsIgnoreCase("SELECT")) {
            return SELECT_STMT;
        } else if (prefixSql.equalsIgnoreCase("INSERT") ||
                prefixSql.equalsIgnoreCase("UPDATE") ||
                prefixSql.equalsIgnoreCase("REPLAC") ||
                prefixSql.equalsIgnoreCase("DELETE")) {
            return UPDATE_STMT;
        }
        return OTHER_STMT;
    }

    private synchronized void attachObjectToDatabase(SQLiteDatabase db) {
        db.acquireReference();
        db.addSQLiteClosable(this);
        mDatabase = db;
        nHandle = db.mNativeHandle;
    }

    private synchronized void detachObjectFromDatabase() {
        mDatabase.removeSQLiteClosable(this);
        mDatabase.releaseReference();
    }

    /* package */ synchronized void verifyDbAndCompileSql() {
        mDatabase.verifyDbIsOpen();
        // use pooled database connection handles for SELECT SQL statements
        SQLiteDatabase db = (getSqlStatementType(mSql) != SELECT_STMT) ? mDatabase
                : mDatabase.getDbConnection(mSql);
        if (!db.equals(mDatabase)) {
            // the database connection handle to be used is not the same as the one supplied
            // in the constructor. do some housekeeping.
            detachObjectFromDatabase();
            attachObjectToDatabase(db);
        }
        // compile the sql statement
        mDatabase.lock();
        try {
            compileSql();
        } finally {
            mDatabase.unlock();
        }
    }

    @Override
    protected void onAllReferencesReleased() {
        releaseCompiledSqlIfNotInCache();
        detachObjectFromDatabase();
    }

    @Override
    protected void onAllReferencesReleasedFromContainer() {
        releaseCompiledSqlIfNotInCache();
        mDatabase.releaseReference();
    }

    /* package */ synchronized void releaseCompiledSqlIfNotInCache() {
        if (mCompiledSql == null) {
            return;
        }
        synchronized(mDatabase.mCompiledQueries) {
            if (!mDatabase.mCompiledQueries.containsValue(mCompiledSql)) {
                // it is NOT in compiled-sql cache. i.e., responsibility of
                // releasing this statement is on me.
                mCompiledSql.releaseSqlStatement();
            } else {
                // it is in compiled-sql cache. reset its CompiledSql#mInUse flag
                mCompiledSql.release();
            }
        }
        mCompiledSql = null;
        nStatement = 0;
    }

    /**
     * Returns a unique identifier for this program.
     *
     * @return a unique identifier for this program
     * @deprecated do not use this method. it is not guaranteed to be the same across executions of
     * the SQL statement contained in this object.
     */
    @Deprecated
    public final int getUniqueId() {
      return -1;
    }

    /**
     * used only for testing purposes
     */
    /* package */ int getSqlStatementId() {
      synchronized(this) {
        return (mCompiledSql == null) ? 0 : nStatement;
      }
    }

    /* package */ String getSqlString() {
        return mSql;
    }

    /**
     * @deprecated This method is deprecated and must not be used.
     *
     * @param sql the SQL string to compile
     * @param forceCompilation forces the SQL to be recompiled in the event that there is an
     *  existing compiled SQL program already around
     */
    @Deprecated
    protected void compile(String sql, boolean forceCompilation) {
        // TODO is there a need for this?
    }

    /**
     * Bind a NULL value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind null to
     */
    public void bindNull(int index) {
        synchronized (this) {
            verifyDbAndCompileSql();
            acquireReference();
            try {
                native_bind_null(index);
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Bind a long value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindLong(int index, long value) {
        synchronized (this) {
            verifyDbAndCompileSql();
            acquireReference();
            try {
                native_bind_long(index, value);
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Bind a double value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindDouble(int index, double value) {
        synchronized (this) {
            verifyDbAndCompileSql();
            acquireReference();
            try {
                native_bind_double(index, value);
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Bind a String value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindString(int index, String value) {
        if (value == null) {
            throw new IllegalArgumentException("the bind value at index " + index + " is null");
        }
        synchronized (this) {
            verifyDbAndCompileSql();
            acquireReference();
            try {
                native_bind_string(index, value);
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Bind a byte array value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindBlob(int index, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("the bind value at index " + index + " is null");
        }
        synchronized (this) {
            verifyDbAndCompileSql();
            acquireReference();
            try {
                native_bind_blob(index, value);
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Clears all existing bindings. Unset bindings are treated as NULL.
     */
    public void clearBindings() {
        synchronized (this) {
            if (this.nStatement == 0) {
                return;
            }
            mDatabase.verifyDbIsOpen();
            acquireReference();
            try {
                native_clear_bindings();
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Release this program's resources, making it invalid.
     */
    public void close() {
        synchronized (this) {
            if (nHandle == 0 || !mDatabase.isOpen()) {
                return;
            }
            releaseReference();
        }
    }

    /**
     * @deprecated This method is deprecated and must not be used.
     * Compiles SQL into a SQLite program.
     *
     * <P>The database lock must be held when calling this method.
     * @param sql The SQL to compile.
     */
    @Deprecated
    protected final native void native_compile(String sql);

    /**
     * @deprecated This method is deprecated and must not be used.
     */
    @Deprecated
    protected final native void native_finalize();

    protected final native void native_bind_null(int index);
    protected final native void native_bind_long(int index, long value);
    protected final native void native_bind_double(int index, double value);
    protected final native void native_bind_string(int index, String value);
    protected final native void native_bind_blob(int index, byte[] value);
    /* package */ final native void native_clear_bindings();
}

