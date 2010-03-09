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

import android.os.RemoteException;
import android.os.Bundle;
import android.util.Log;

import java.util.Map;

/**
 * Adapts an {@link IBulkCursor} to a {@link Cursor} for use in the local
 * process.
 *
 * {@hide}
 */
public final class BulkCursorToCursorAdaptor extends AbstractWindowedCursor {
    private static final String TAG = "BulkCursor";

    private SelfContentObserver mObserverBridge;
    private IBulkCursor mBulkCursor;
    private int mCount;
    private String[] mColumns;
    private boolean mWantsAllOnMoveCalls;

    public void set(IBulkCursor bulkCursor) {
        mBulkCursor = bulkCursor;

        try {
            mCount = mBulkCursor.count();
            mWantsAllOnMoveCalls = mBulkCursor.getWantsAllOnMoveCalls();

            // Search for the rowID column index and set it for our parent
            mColumns = mBulkCursor.getColumnNames();
            mRowIdColumnIndex = findRowIdColumnIndex(mColumns);
        } catch (RemoteException ex) {
            Log.e(TAG, "Setup failed because the remote process is dead");
        }
    }

    /**
     * Version of set() that does fewer Binder calls if the caller
     * already knows BulkCursorToCursorAdaptor's properties.
     */
    public void set(IBulkCursor bulkCursor, int count, int idIndex) {
        mBulkCursor = bulkCursor;
        mColumns = null;  // lazily retrieved
        mCount = count;
        mRowIdColumnIndex = idIndex;
    }

    /**
     * Returns column index of "_id" column, or -1 if not found.
     */
    public static int findRowIdColumnIndex(String[] columnNames) {
        int length = columnNames.length;
        for (int i = 0; i < length; i++) {
            if (columnNames[i].equals("_id")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Gets a SelfDataChangeOberserver that can be sent to a remote
     * process to receive change notifications over IPC.
     *
     * @return A SelfContentObserver hooked up to this Cursor
     */
    public synchronized IContentObserver getObserver() {
        if (mObserverBridge == null) {
            mObserverBridge = new SelfContentObserver(this);
        }
        return mObserverBridge.getContentObserver();
    }

    @Override
    public int getCount() {
        return mCount;
    }

    @Override
    public boolean onMove(int oldPosition, int newPosition) {
        try {
            // Make sure we have the proper window
            if (mWindow != null) {
                if (newPosition < mWindow.getStartPosition() ||
                        newPosition >= (mWindow.getStartPosition() + mWindow.getNumRows())) {
                    mWindow = mBulkCursor.getWindow(newPosition);
                } else if (mWantsAllOnMoveCalls) {
                    mBulkCursor.onMove(newPosition);
                }
            } else {
                mWindow = mBulkCursor.getWindow(newPosition);
            }
        } catch (RemoteException ex) {
            // We tried to get a window and failed
            Log.e(TAG, "Unable to get window because the remote process is dead");
            return false;
        }

        // Couldn't obtain a window, something is wrong
        if (mWindow == null) {
            return false;
        }

        return true;
    }

    @Override
    public void deactivate() {
        // This will call onInvalidated(), so make sure to do it before calling release,
        // which is what actually makes the data set invalid.
        super.deactivate();

        try {
            mBulkCursor.deactivate();
        } catch (RemoteException ex) {
            Log.w(TAG, "Remote process exception when deactivating");
        }
        mWindow = null;
    }
    
    @Override
    public void close() {
        super.close();
        try {
            mBulkCursor.close();
        } catch (RemoteException ex) {
            Log.w(TAG, "Remote process exception when closing");
        }
        mWindow = null;        
    }

    @Override
    public boolean requery() {
        try {
            int oldCount = mCount;
            //TODO get the window from a pool somewhere to avoid creating the memory dealer
            mCount = mBulkCursor.requery(getObserver(), new CursorWindow(
                    false /* the window will be accessed across processes */));
            if (mCount != -1) {
                mPos = -1;
                mWindow = null;

                // super.requery() will call onChanged. Do it here instead of relying on the
                // observer from the far side so that observers can see a correct value for mCount
                // when responding to onChanged.
                super.requery();
                return true;
            } else {
                deactivate();
                return false;
            }
        } catch (Exception ex) {
            Log.e(TAG, "Unable to requery because the remote process exception " + ex.getMessage());
            deactivate();
            return false;
        }
    }

    /**
     * @hide
     * @deprecated
     */
    @Override
    public boolean deleteRow() {
        try {
            boolean result = mBulkCursor.deleteRow(mPos);
            if (result != false) {
                // The window contains the old value, discard it
                mWindow = null;
    
                // Fix up the position
                mCount = mBulkCursor.count();
                if (mPos < mCount) {
                    int oldPos = mPos;
                    mPos = -1;
                    moveToPosition(oldPos);
                } else {
                    mPos = mCount;
                }

                // Send the change notification
                onChange(true);
            }
            return result;
        } catch (RemoteException ex) {
            Log.e(TAG, "Unable to delete row because the remote process is dead");
            return false;
        }
    }

    @Override
    public String[] getColumnNames() {
        if (mColumns == null) {
            try {
                mColumns = mBulkCursor.getColumnNames();
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to fetch column names because the remote process is dead");
                return null;
            }
        }
        return mColumns;
    }

    /**
     * @hide
     * @deprecated
     */
    @Override
    public boolean commitUpdates(Map<? extends Long,
            ? extends Map<String,Object>> additionalValues) {
        if (!supportsUpdates()) {
            Log.e(TAG, "commitUpdates not supported on this cursor, did you include the _id column?");
            return false;
        }

        synchronized(mUpdatedRows) {
            if (additionalValues != null) {
                mUpdatedRows.putAll(additionalValues);
            }

            if (mUpdatedRows.size() <= 0) {
                return false;
            }

            try {
                boolean result = mBulkCursor.updateRows(mUpdatedRows);
    
                if (result == true) {
                    mUpdatedRows.clear();

                    // Send the change notification
                    onChange(true);
                }
                return result;
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to commit updates because the remote process is dead");
                return false;
            }
        }
    }

    @Override
    public Bundle getExtras() {
        try {
            return mBulkCursor.getExtras();
        } catch (RemoteException e) {
            // This should never happen because the system kills processes that are using remote
            // cursors when the provider process is killed.
            throw new RuntimeException(e);
        }
    }

    @Override
    public Bundle respond(Bundle extras) {
        try {
            return mBulkCursor.respond(extras);
        } catch (RemoteException e) {
            // the system kills processes that are using remote cursors when the provider process
            // is killed, but this can still happen if this is being called from the system process,
            // so, better to log and return an empty bundle.
            Log.w(TAG, "respond() threw RemoteException, returning an empty bundle.", e);
            return Bundle.EMPTY;
        }
    }
}
