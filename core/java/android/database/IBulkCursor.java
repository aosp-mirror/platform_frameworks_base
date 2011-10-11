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

import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * This interface provides a low-level way to pass bulk cursor data across
 * both process and language boundaries. Application code should use the Cursor
 * interface directly.
 *
 * {@hide}
 */
public interface IBulkCursor extends IInterface  {
    /**
     * Returns a BulkCursorWindow, which either has a reference to a shared
     * memory segment with the rows, or an array of JSON strings.
     */
    public CursorWindow getWindow(int startPos) throws RemoteException;

    public void onMove(int position) throws RemoteException;

    /**
     * Returns the number of rows in the cursor.
     *
     * @return the number of rows in the cursor.
     */
    public int count() throws RemoteException;

    /**
     * Returns a string array holding the names of all of the columns in the
     * cursor in the order in which they were listed in the result.
     *
     * @return the names of the columns returned in this query.
     */
    public String[] getColumnNames() throws RemoteException;

    public void deactivate() throws RemoteException;

    public void close() throws RemoteException;

    public int requery(IContentObserver observer) throws RemoteException;

    boolean getWantsAllOnMoveCalls() throws RemoteException;

    Bundle getExtras() throws RemoteException;

    Bundle respond(Bundle extras) throws RemoteException;

    /* IPC constants */
    static final String descriptor = "android.content.IBulkCursor";

    static final int GET_CURSOR_WINDOW_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    static final int COUNT_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int GET_COLUMN_NAMES_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int DEACTIVATE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int REQUERY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 6;
    static final int ON_MOVE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 7;
    static final int WANTS_ON_MOVE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 8;
    static final int GET_EXTRAS_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 9;
    static final int RESPOND_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 10;
    static final int CLOSE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 11;
}
