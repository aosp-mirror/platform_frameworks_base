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

/**
 * A base class for compiled SQLite programs.
 */
public abstract class SQLiteProgram extends SQLiteClosable {

    /** The database this program is compiled against. */
    protected SQLiteDatabase mDatabase;

    /** The SQL used to create this query */
    /* package */ final String mSql;

    /**
     * Native linkage, do not modify. This comes from the database and should not be modified
     * in here or in the native code.
     */
    protected int nHandle = 0;

    /**
     * the SQLiteCompiledSql object for the given sql statement.
     */
    private SQLiteCompiledSql mCompiledSql;

    /**
     * SQLiteCompiledSql statement id is populated with the corresponding object from the above
     * member. This member is used by the native_bind_* methods
     */
    protected int nStatement = 0;

    /* package */ SQLiteProgram(SQLiteDatabase db, String sql) {
        mDatabase = db;
        mSql = sql;
        db.acquireReference();
        db.addSQLiteClosable(this);
        this.nHandle = db.mNativeHandle;

        mCompiledSql = db.getCompiledStatementForSql(sql);
        if (mCompiledSql == null) {
            // create a new compiled-sql obj
            mCompiledSql = new SQLiteCompiledSql(db, sql);

            // add it to the cache of compiled-sqls
            db.addToCompiledQueries(sql, mCompiledSql);
        }
        nStatement = mCompiledSql.nStatement;
    }

    @Override
    protected void onAllReferencesReleased() {
        releaseCompiledSqlIfInCache();
        mDatabase.releaseReference();
        mDatabase.removeSQLiteClosable(this);
    }

    @Override
    protected void onAllReferencesReleasedFromContainer() {
        releaseCompiledSqlIfInCache();
        mDatabase.releaseReference();
    }

    private void releaseCompiledSqlIfInCache() {
        if (mCompiledSql == null) {
            return;
        }
        synchronized(mDatabase.mCompiledQueries) {
            if (!mDatabase.mCompiledQueries.containsValue(mCompiledSql)) {
                mCompiledSql.releaseSqlStatement();
                mCompiledSql = null; // so that GC doesn't call finalize() on it
                nStatement = 0;
            }
        }
    }

    /**
     * Returns a unique identifier for this program.
     *
     * @return a unique identifier for this program
     */
    public final int getUniqueId() {
        return nStatement;
    }

    /* package */ String getSqlString() {
        return mSql;
    }

    /**
     * @deprecated use this.compiledStatement.compile instead
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
        acquireReference();
        try {
            native_bind_null(index);
        } finally {
            releaseReference();
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
        acquireReference();
        try {
            native_bind_long(index, value);
        } finally {
            releaseReference();
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
        acquireReference();
        try {
            native_bind_double(index, value);
        } finally {
            releaseReference();
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
        acquireReference();
        try {
            native_bind_string(index, value);
        } finally {
            releaseReference();
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
        acquireReference();
        try {
            native_bind_blob(index, value);
        } finally {
            releaseReference();
        }
    }

    /**
     * Clears all existing bindings. Unset bindings are treated as NULL.
     */
    public void clearBindings() {
        acquireReference();
        try {
            native_clear_bindings();
        } finally {
            releaseReference();
        }
    }

    /**
     * Release this program's resources, making it invalid.
     */
    public void close() {
        mDatabase.lock();
        try {
            releaseReference();
        } finally {
            mDatabase.unlock();
        }
    }

    /**
     * Compiles SQL into a SQLite program.
     *
     * <P>The database lock must be held when calling this method.
     * @param sql The SQL to compile.
     */
    @Deprecated
    protected final native void native_compile(String sql);
    @Deprecated
    protected final native void native_finalize();

    protected final native void native_bind_null(int index);
    protected final native void native_bind_long(int index, long value);
    protected final native void native_bind_double(int index, double value);
    protected final native void native_bind_string(int index, String value);
    protected final native void native_bind_blob(int index, byte[] value);
    private final native void native_clear_bindings();
}

