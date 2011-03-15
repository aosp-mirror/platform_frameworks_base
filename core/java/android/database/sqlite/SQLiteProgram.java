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
import android.database.Cursor;

import java.util.HashMap;

/**
 * A base class for compiled SQLite programs.
 *<p>
 * SQLiteProgram is NOT internally synchronized so code using a SQLiteProgram from multiple
 * threads should perform its own synchronization when using the SQLiteProgram.
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
    protected int nHandle;

    /**
     * the SQLiteCompiledSql object for the given sql statement.
     */
    /* package */ SQLiteCompiledSql mCompiledSql;

    /**
     * SQLiteCompiledSql statement id is populated with the corresponding object from the above
     * member. This member is used by the native_bind_* methods
     * @deprecated do not use this
     */
    @Deprecated
    protected int nStatement;

    /**
     * In the case of {@link SQLiteStatement}, this member stores the bindargs passed
     * to the following methods, instead of actually doing the binding.
     * <ul>
     *   <li>{@link #bindBlob(int, byte[])}</li>
     *   <li>{@link #bindDouble(int, double)}</li>
     *   <li>{@link #bindLong(int, long)}</li>
     *   <li>{@link #bindNull(int)}</li>
     *   <li>{@link #bindString(int, String)}</li>
     * </ul>
     * <p>
     * Each entry in the array is a Pair of
     * <ol>
     *   <li>bind arg position number</li>
     *   <li>the value to be bound to the bindarg</li>
     * </ol>
     * <p>
     * It is lazily initialized in the above bind methods
     * and it is cleared in {@link #clearBindings()} method.
     * <p>
     * It is protected (in multi-threaded environment) by {@link SQLiteProgram}.this
     */
    /* package */ HashMap<Integer, Object> mBindArgs = null;
    /* package */ final int mStatementType;
    /* package */ static final int STATEMENT_CACHEABLE = 16;
    /* package */ static final int STATEMENT_DONT_PREPARE = 32;
    /* package */ static final int STATEMENT_USE_POOLED_CONN = 64;
    /* package */ static final int STATEMENT_TYPE_MASK = 0x0f;

    /* package */ SQLiteProgram(SQLiteDatabase db, String sql) {
        this(db, sql, null, true);
    }

    /* package */ SQLiteProgram(SQLiteDatabase db, String sql, Object[] bindArgs,
            boolean compileFlag) {
        mSql = sql.trim();
        int n = DatabaseUtils.getSqlStatementType(mSql);
        switch (n) {
            case DatabaseUtils.STATEMENT_UPDATE:
                mStatementType = n | STATEMENT_CACHEABLE;
                break;
            case DatabaseUtils.STATEMENT_SELECT:
                mStatementType = n | STATEMENT_CACHEABLE | STATEMENT_USE_POOLED_CONN;
                break;
            case DatabaseUtils.STATEMENT_BEGIN:
            case DatabaseUtils.STATEMENT_COMMIT:
            case DatabaseUtils.STATEMENT_ABORT:
                mStatementType = n | STATEMENT_DONT_PREPARE;
                break;
            default:
                mStatementType = n;
        }
        db.acquireReference();
        db.addSQLiteClosable(this);
        mDatabase = db;
        nHandle = db.mNativeHandle;
        if (bindArgs != null) {
            int size = bindArgs.length;
            for (int i = 0; i < size; i++) {
                this.addToBindArgs(i + 1, bindArgs[i]);
            }
        }
        if (compileFlag) {
            compileAndbindAllArgs();
        }
    }

    private void compileSql() {
        // only cache CRUD statements
        if ((mStatementType & STATEMENT_CACHEABLE) == 0) {
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
                // since it is not in the cache, no need to acquire() it.
            }
        }
        nStatement = mCompiledSql.nStatement;
    }

    @Override
    protected void onAllReferencesReleased() {
        release();
        mDatabase.removeSQLiteClosable(this);
        mDatabase.releaseReference();
    }

    @Override
    protected void onAllReferencesReleasedFromContainer() {
        release();
        mDatabase.releaseReference();
    }

    /* package */ void release() {
        if (mCompiledSql == null) {
            return;
        }
        mDatabase.releaseCompiledSqlObj(mSql, mCompiledSql);
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

    private void bind(int type, int index, Object value) {
        mDatabase.verifyDbIsOpen();
        addToBindArgs(index, (type == Cursor.FIELD_TYPE_NULL) ? null : value);
        if (nStatement > 0) {
            // bind only if the SQL statement is compiled
            acquireReference();
            try {
                switch (type) {
                    case Cursor.FIELD_TYPE_NULL:
                        native_bind_null(index);
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        native_bind_blob(index, (byte[]) value);
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        native_bind_double(index, (Double) value);
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        native_bind_long(index, (Long) value);
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                    default:
                        native_bind_string(index, (String) value);
                        break;
                }
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Bind a NULL value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind null to
     */
    public void bindNull(int index) {
        bind(Cursor.FIELD_TYPE_NULL, index, null);
    }

    /**
     * Bind a long value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *addToBindArgs
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindLong(int index, long value) {
        bind(Cursor.FIELD_TYPE_INTEGER, index, value);
    }

    /**
     * Bind a double value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindDouble(int index, double value) {
        bind(Cursor.FIELD_TYPE_FLOAT, index, value);
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
        bind(Cursor.FIELD_TYPE_STRING, index, value);
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
        bind(Cursor.FIELD_TYPE_BLOB, index, value);
    }

    /**
     * Clears all existing bindings. Unset bindings are treated as NULL.
     */
    public void clearBindings() {
        mBindArgs = null;
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

    /**
     * Release this program's resources, making it invalid.
     */
    public void close() {
        mBindArgs = null;
        if (nHandle == 0 || !mDatabase.isOpen()) {
            return;
        }
        releaseReference();
    }

    private void addToBindArgs(int index, Object value) {
        if (mBindArgs == null) {
            mBindArgs = new HashMap<Integer, Object>();
        }
        mBindArgs.put(index, value);
    }

    /* package */ void compileAndbindAllArgs() {
        if ((mStatementType & STATEMENT_DONT_PREPARE) > 0) {
            if (mBindArgs != null) {
                throw new IllegalArgumentException("Can't pass bindargs for this sql :" + mSql);
            }
            // no need to prepare this SQL statement
            return;
        }
        if (nStatement == 0) {
            // SQL statement is not compiled yet. compile it now.
            compileSql();
        }
        if (mBindArgs == null) {
            return;
        }
        for (int index : mBindArgs.keySet()) {
            Object value = mBindArgs.get(index);
            if (value == null) {
                native_bind_null(index);
            } else if (value instanceof Double || value instanceof Float) {
                native_bind_double(index, ((Number) value).doubleValue());
            } else if (value instanceof Number) {
                native_bind_long(index, ((Number) value).longValue());
            } else if (value instanceof Boolean) {
                Boolean bool = (Boolean)value;
                native_bind_long(index, (bool) ? 1 : 0);
                if (bool) {
                    native_bind_long(index, 1);
                } else {
                    native_bind_long(index, 0);
                }
            } else if (value instanceof byte[]){
                native_bind_blob(index, (byte[]) value);
            } else {
                native_bind_string(index, value.toString());
            }
        }
    }

    /**
     * Given an array of String bindArgs, this method binds all of them in one single call.
     *
     * @param bindArgs the String array of bind args.
     */
    public void bindAllArgsAsStrings(String[] bindArgs) {
        if (bindArgs == null) {
            return;
        }
        int size = bindArgs.length;
        for (int i = 0; i < size; i++) {
            bindString(i + 1, bindArgs[i]);
        }
    }

    /* package */ synchronized final void setNativeHandle(int nHandle) {
        this.nHandle = nHandle;
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

