/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.database.CursorWindow;

/**
 * An object created from a SQLiteDatabase that can be closed.
 */
public abstract class SQLiteClosable {
    private int mReferenceCount = 1;

    protected abstract void onAllReferencesReleased();
    protected void onAllReferencesReleasedFromContainer() {}

    public void acquireReference() {
        synchronized(this) {
            checkRefCount();
            if (mReferenceCount <= 0) {
                throw new IllegalStateException(
                        "attempt to re-open an already-closed object: " + getObjInfo());
            }
            mReferenceCount++;
        }
    }

    public void releaseReference() {
        synchronized(this) {
            checkRefCount();
            mReferenceCount--;
            if (mReferenceCount == 0) {
                onAllReferencesReleased();
            }
        }
    }

    public void releaseReferenceFromContainer() {
        synchronized(this) {
            checkRefCount();
            mReferenceCount--;
            if (mReferenceCount == 0) {
                onAllReferencesReleasedFromContainer();
            }
        }
    }

    private String getObjInfo() {
        StringBuilder buff = new StringBuilder();
        buff.append(this.getClass().getName());
        buff.append(" (");
        if (this instanceof SQLiteDatabase) {
            buff.append("database = ");
            buff.append(((SQLiteDatabase)this).getPath());
        } else if (this instanceof SQLiteProgram) {
            buff.append("mSql = ");
            buff.append(((SQLiteProgram)this).mSql);
        } else if (this instanceof CursorWindow) {
            buff.append("mStartPos = ");
            buff.append(((CursorWindow)this).getStartPosition());
        }
        buff.append(") ");
        return buff.toString();
    }

    // STOPSHIP remove this method before shipping
    private void checkRefCount() {
        if (mReferenceCount > 1000) {
            throw new IllegalStateException("bad refcount: " + mReferenceCount +
                    ". file bug against frameworks->database" + getObjInfo());
        }
    }
}
