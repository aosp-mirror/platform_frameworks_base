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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import android.util.Log;

/**
 * A base class for compiled SQLite programs.
 */
public abstract class SQLiteProgram extends SQLiteClosable {
    private static final String TAG = "SQLiteProgram";

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

    /**
     * stores all bindargs for debugging purposes
     */
    private Map<Integer, String> mBindArgs = null;

    /* package */ SQLiteProgram(SQLiteDatabase db, String sql) {
        if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
            Log.d(TAG, "processing sql: " + sql);
        }

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
        if (SQLiteDebug.DEBUG_CAPTURE_SQL) {
            addToBindArgs(index, "null");
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
        if (SQLiteDebug.DEBUG_CAPTURE_SQL) {
            addToBindArgs(index, value + "");
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
        if (SQLiteDebug.DEBUG_CAPTURE_SQL) {
            addToBindArgs(index, value + "");
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
        if (SQLiteDebug.DEBUG_CAPTURE_SQL) {
            addToBindArgs(index, "'" + value + "'");
        }
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
        if (SQLiteDebug.DEBUG_CAPTURE_SQL) {
            addToBindArgs(index, "blob");
        }
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
        if (SQLiteDebug.DEBUG_CAPTURE_SQL) {
            mBindArgs = null;
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
        mDatabase.lock();
        try {
            releaseReference();
        } finally {
            mDatabase.unlock();
        }
    }

    /**
     * this method is called under the debug flag {@link SQLiteDebug.DEBUG_CAPTURE_SQL} only.
     * it collects the bindargs as they are called by the callers the bind... methods in this
     * class.
     */
    private void addToBindArgs(int index, String obj) {
        if (mBindArgs == null) {
            mBindArgs = new HashMap<Integer, String>();
        }
        mBindArgs.put(index, obj);
    }

    /**
     * constructs all the bindargs in sequence and returns a String Array of the values.
     * it uses the HashMap built up by the above method.
     *
     * @return the string array of bindArgs with the args arranged in sequence
     */
    /* package */ String[] getBindArgs() {
        if (mBindArgs == null) {
            return null;
        }
        Set<Integer> indexSet = mBindArgs.keySet();
        ArrayList<Integer> indexList = new ArrayList<Integer>(indexSet);
        Collections.sort(indexList);
        int len = indexList.size();
        String[] bindObjs = new String[len];
        for (int i = 0; i < len; i++) {
            bindObjs[i] = mBindArgs.get(indexList.get(i));
        }
        return bindObjs;
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

