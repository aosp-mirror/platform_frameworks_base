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
public abstract class AbstractWindowedCursor extends AbstractCursor
{
    @Override
    public byte[] getBlob(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                return (byte[])getUpdatedField(columnIndex);
            }
        }

        return mWindow.getBlob(mPos, columnIndex);
    }

    @Override
    public String getString(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                return (String)getUpdatedField(columnIndex);
            }
        }

        return mWindow.getString(mPos, columnIndex);
    }
    
    @Override
    public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer)
    {
        checkPosition();
        
        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                super.copyStringToBuffer(columnIndex, buffer);
            }
        }
        
        mWindow.copyStringToBuffer(mPos, columnIndex, buffer);
    }

    @Override
    public short getShort(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                Number value = (Number)getUpdatedField(columnIndex);
                return value.shortValue();
            }
        }

        return mWindow.getShort(mPos, columnIndex);
    }

    @Override
    public int getInt(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                Number value = (Number)getUpdatedField(columnIndex);
                return value.intValue();
            }
        }

        return mWindow.getInt(mPos, columnIndex);
    }

    @Override
    public long getLong(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                Number value = (Number)getUpdatedField(columnIndex);
                return value.longValue();
            }
        }

        return mWindow.getLong(mPos, columnIndex);
    }

    @Override
    public float getFloat(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                Number value = (Number)getUpdatedField(columnIndex);
                return value.floatValue();
            }
        }

        return mWindow.getFloat(mPos, columnIndex);
    }

    @Override
    public double getDouble(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                Number value = (Number)getUpdatedField(columnIndex);
                return value.doubleValue();
            }
        }

        return mWindow.getDouble(mPos, columnIndex);
    }

    @Override
    public boolean isNull(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                return getUpdatedField(columnIndex) == null;
            }
        }

        return mWindow.isNull(mPos, columnIndex);
    }

    public boolean isBlob(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                Object object = getUpdatedField(columnIndex);
                return object == null || object instanceof byte[];
            }
        }

        return mWindow.isBlob(mPos, columnIndex);
    }

    public boolean isString(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                Object object = getUpdatedField(columnIndex);
                return object == null || object instanceof String;
            }
        }

        return mWindow.isString(mPos, columnIndex);
    }

    public boolean isLong(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                Object object = getUpdatedField(columnIndex);
                return object != null && (object instanceof Integer || object instanceof Long);
            }
        }

        return mWindow.isLong(mPos, columnIndex);
    }

    public boolean isFloat(int columnIndex)
    {
        checkPosition();

        synchronized(mUpdatedRows) {
            if (isFieldUpdated(columnIndex)) {
                Object object = getUpdatedField(columnIndex);
                return object != null && (object instanceof Float || object instanceof Double);
            }
        }

        return mWindow.isFloat(mPos, columnIndex);
    }

    @Override
    protected void checkPosition()
    {
        super.checkPosition();
        
        if (mWindow == null) {
            throw new StaleDataException("Access closed cursor");
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
