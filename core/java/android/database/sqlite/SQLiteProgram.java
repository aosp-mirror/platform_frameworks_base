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
 */
public abstract class SQLiteProgram extends SQLiteClosable {

    private static final String TAG = "SQLiteProgram";

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
        mDatabase = db;
        mSql = sql.trim();
        db.acquireReference();
        db.addSQLiteClosable(this);
        this.nHandle = db.mNativeHandle;

        // only cache CRUD statements
        String prefixSql = mSql.substring(0, 6);
        if (!prefixSql.equalsIgnoreCase("INSERT") && !prefixSql.equalsIgnoreCase("UPDATE") &&
                !prefixSql.equalsIgnoreCase("REPLAC") &&
                !prefixSql.equalsIgnoreCase("DELETE") && !prefixSql.equalsIgnoreCase("SELECT")) {
            mCompiledSql = new SQLiteCompiledSql(db, sql);
            nStatement = mCompiledSql.nStatement;
            // since it is not in the cache, no need to acquire() it.
            return;
        }

        // it is not pragma
        mCompiledSql = db.getCompiledStatementForSql(sql);
        if (mCompiledSql == null) {
            // create a new compiled-sql obj
            mCompiledSql = new SQLiteCompiledSql(db, sql);

            // add it to the cache of compiled-sqls
            // but before adding it and thus making it available for anyone else to use it,
            // make sure it is acquired by me.
            mCompiledSql.acquire();
            db.addToCompiledQueries(sql, mCompiledSql);
            if (SQLiteDebug.DEBUG_ACTIVE_CURSOR_FINALIZATION) {
                Log.v(TAG, "Created DbObj (id#" + mCompiledSql.nStatement +
                        ") for sql: " + sql);
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
                mCompiledSql = new SQLiteCompiledSql(db, sql);
                if (SQLiteDebug.DEBUG_ACTIVE_CURSOR_FINALIZATION) {
                    Log.v(TAG, "** possible bug ** Created NEW DbObj (id#" +
                            mCompiledSql.nStatement +
                            ") because the previously created DbObj (id#" + last +
                            ") was not released for sql:" + sql);
                }
                // since it is not in the cache, no need to acquire() it.
            }
        }
        nStatement = mCompiledSql.nStatement;
    }

    @Override
    protected void onAllReferencesReleased() {
        releaseCompiledSqlIfNotInCache();
        mDatabase.releaseReference();
        mDatabase.removeSQLiteClosable(this);
    }

    @Override
    protected void onAllReferencesReleasedFromContainer() {
        releaseCompiledSqlIfNotInCache();
        mDatabase.releaseReference();
    }

    private void releaseCompiledSqlIfNotInCache() {
        if (mCompiledSql == null) {
            return;
        }
        synchronized(mDatabase.mCompiledQueries) {
            if (!mDatabase.mCompiledQueries.containsValue(mCompiledSql)) {
                // it is NOT in compiled-sql cache. i.e., responsibility of
                // releasing this statement is on me.
                mCompiledSql.releaseSqlStatement();
                mCompiledSql = null;
                nStatement = 0;
            } else {
                // it is in compiled-sql cache. reset its CompiledSql#mInUse flag
                mCompiledSql.release();
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
        if (!mDatabase.isOpen()) {
            throw new IllegalStateException("database " + mDatabase.getPath() + " already closed");
        }
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
        if (!mDatabase.isOpen()) {
            throw new IllegalStateException("database " + mDatabase.getPath() + " already closed");
        }
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
        if (!mDatabase.isOpen()) {
            throw new IllegalStateException("database " + mDatabase.getPath() + " already closed");
        }
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
        if (!mDatabase.isOpen()) {
            throw new IllegalStateException("database " + mDatabase.getPath() + " already closed");
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
        if (!mDatabase.isOpen()) {
            throw new IllegalStateException("database " + mDatabase.getPath() + " already closed");
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
        if (!mDatabase.isOpen()) {
            throw new IllegalStateException("database " + mDatabase.getPath() + " already closed");
        }
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
        if (!mDatabase.isOpen()) {
            return;
        }
        mDatabase.lock();
        try {
            releaseReference();
        } finally {
            mDatabase.unlock();
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
    private final native void native_clear_bindings();
}

