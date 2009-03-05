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

    /** The database this program is compiled against. */
    protected SQLiteDatabase mDatabase;

    /**
     * Native linkage, do not modify. This comes from the database and should not be modified
     * in here or in the native code.
     */
    protected int nHandle = 0;

    /**
     * Native linkage, do not modify. When non-0 this holds a reference to a valid
     * sqlite3_statement object. It is only updated by the native code, but may be
     * checked in this class when the database lock is held to determine if there
     * is a valid native-side program or not.
     */
    protected int nStatement = 0;

    /**
     * Used to find out where a cursor was allocated in case it never got
     * released.
     */
    private StackTraceElement[] mStackTraceElements;    
 
    /* package */ SQLiteProgram(SQLiteDatabase db, String sql) {
        if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
            mStackTraceElements = new Exception().getStackTrace();
        }
        
        mDatabase = db;
        db.acquireReference();
        db.addSQLiteClosable(this);
        this.nHandle = db.mNativeHandle;
        compile(sql, false);
    }    
    
    @Override
    protected void onAllReferencesReleased() {
        // Note that native_finalize() checks to make sure that nStatement is
        // non-null before destroying it.
        native_finalize();
        mDatabase.releaseReference();
        mDatabase.removeSQLiteClosable(this);
    }
    
    @Override
    protected void onAllReferencesReleasedFromContainer(){
        // Note that native_finalize() checks to make sure that nStatement is
        // non-null before destroying it.
        native_finalize();
        mDatabase.releaseReference();        
    }

    /**
     * Returns a unique identifier for this program.
     * 
     * @return a unique identifier for this program
     */
    public final int getUniqueId() {
        return nStatement;
    }

    /**
     * Compiles the given SQL into a SQLite byte code program using sqlite3_prepare_v2(). If
     * this method has been called previously without a call to close and forCompilation is set
     * to false the previous compilation will be used. Setting forceCompilation to true will
     * always re-compile the program and should be done if you pass differing SQL strings to this
     * method.
     *
     * <P>Note: this method acquires the database lock.</P>
     *
     * @param sql the SQL string to compile
     * @param forceCompilation forces the SQL to be recompiled in the event that there is an
     *  existing compiled SQL program already around
     */
    protected void compile(String sql, boolean forceCompilation) {
        // Only compile if we don't have a valid statement already or the caller has
        // explicitly requested a recompile. 
        if (nStatement == 0 || forceCompilation) {
            mDatabase.lock();
            try {
                // Note that the native_compile() takes care of destroying any previously
                // existing programs before it compiles.
                acquireReference();                
                native_compile(sql);
            } finally {
                releaseReference();
                mDatabase.unlock();
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
     * Make sure that the native resource is cleaned up.
     */
    @Override
    protected void finalize() {
        if (nStatement != 0) {
            if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
                String message = "Finalizing " + this +  
                    " that has not been closed";

                Log.d(TAG, message + "\nThis cursor was created in:");
                for (StackTraceElement ste : mStackTraceElements) {
                    Log.d(TAG, "      " + ste);
                }
            }
            // when in finalize() it is already removed from weakhashmap
            // so it is safe to not removed itself from db
            onAllReferencesReleasedFromContainer();
        }
    }

    /**
     * Compiles SQL into a SQLite program.
     * 
     * <P>The database lock must be held when calling this method.
     * @param sql The SQL to compile.
     */
    protected final native void native_compile(String sql);
    protected final native void native_finalize();

    protected final native void native_bind_null(int index);
    protected final native void native_bind_long(int index, long value);
    protected final native void native_bind_double(int index, double value);
    protected final native void native_bind_string(int index, String value);
    protected final native void native_bind_blob(int index, byte[] value);
    private final native void native_clear_bindings();
}

