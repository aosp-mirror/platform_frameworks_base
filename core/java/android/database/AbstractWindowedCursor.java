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

package android.database;

/**
 * A base class for Cursors that store their data in {@link CursorWindow}s.
 */
public abstract class AbstractWindowedCursor extends AbstractCursor {
    @Override
    public byte[] getBlob(int columnIndex) {
        checkPosition();
        return mWindow.getBlob(mPos, columnIndex);
    }

    @Override
    public String getString(int columnIndex) {
        checkPosition();
        return mWindow.getString(mPos, columnIndex);
    }

    @Override
    public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
        checkPosition();
        mWindow.copyStringToBuffer(mPos, columnIndex, buffer);
    }

    @Override
    public short getShort(int columnIndex) {
        checkPosition();
        return mWindow.getShort(mPos, columnIndex);
    }

    @Override
    public int getInt(int columnIndex) {
        checkPosition();
        return mWindow.getInt(mPos, columnIndex);
    }

    @Override
    public long getLong(int columnIndex) {
        checkPosition();
        return mWindow.getLong(mPos, columnIndex);
    }

    @Override
    public float getFloat(int columnIndex) {
        checkPosition();
        return mWindow.getFloat(mPos, columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) {
        checkPosition();
        return mWindow.getDouble(mPos, columnIndex);
    }

    @Override
    public boolean isNull(int columnIndex) {
        checkPosition();
        return mWindow.getType(mPos, columnIndex) == Cursor.FIELD_TYPE_NULL;
    }

    /**
     * @deprecated Use {@link #getType}
     */
    @Deprecated
    public boolean isBlob(int columnIndex) {
        return getType(columnIndex) == Cursor.FIELD_TYPE_BLOB;
    }

    /**
     * @deprecated Use {@link #getType}
     */
    @Deprecated
    public boolean isString(int columnIndex) {
        return getType(columnIndex) == Cursor.FIELD_TYPE_STRING;
    }

    /**
     * @deprecated Use {@link #getType}
     */
    @Deprecated
    public boolean isLong(int columnIndex) {
        return getType(columnIndex) == Cursor.FIELD_TYPE_INTEGER;
    }

    /**
     * @deprecated Use {@link #getType}
     */
    @Deprecated
    public boolean isFloat(int columnIndex) {
        return getType(columnIndex) == Cursor.FIELD_TYPE_FLOAT;
    }

    @Override
    public int getType(int columnIndex) {
        checkPosition();
        return mWindow.getType(mPos, columnIndex);
    }

    @Override
    protected void checkPosition() {
        super.checkPosition();
        
        if (mWindow == null) {
            throw new StaleDataException("Attempting to access a closed cursor");
        }
    }

    @Override
    public CursorWindow getWindow() {
        return mWindow;
    }
    
    /**
     * Set a new cursor window to cursor, usually set a remote cursor window
     * @param window cursor window
     */
    public void setWindow(CursorWindow window) {
        if (mWindow != null) {
            mWindow.close();
        }
        mWindow = window;
    }
    
    public boolean hasWindow() {
        return mWindow != null;
    }

    /**
     * This needs be updated in {@link #onMove} by subclasses, and
     * needs to be set to NULL when the contents of the cursor change.
     */
    protected CursorWindow mWindow;
}
