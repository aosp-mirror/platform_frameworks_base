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

import android.database.sqlite.SQLiteMisuseException;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Config;
import android.util.Log;

import java.util.Map;


/**
 * Wraps a BulkCursor around an existing Cursor making it remotable.
 *
 * {@hide}
 */
public final class CursorToBulkCursorAdaptor extends BulkCursorNative 
        implements IBinder.DeathRecipient {
    private static final String TAG = "Cursor";
    private final CrossProcessCursor mCursor;
    private CursorWindow mWindow;
    private final String mProviderName;
    private final boolean mReadOnly;
    private ContentObserverProxy mObserver;

    private static final class ContentObserverProxy extends ContentObserver 
            {
        protected IContentObserver mRemote;

        public ContentObserverProxy(IContentObserver remoteObserver, DeathRecipient recipient) {
            super(null);
            mRemote = remoteObserver;
            try {
                remoteObserver.asBinder().linkToDeath(recipient, 0);
            } catch (RemoteException e) {
                // Do nothing, the far side is dead
            }
        }
        
        public boolean unlinkToDeath(DeathRecipient recipient) {
            return mRemote.asBinder().unlinkToDeath(recipient, 0);
        }

        @Override
        public boolean deliverSelfNotifications() {
            // The far side handles the self notifications.
            return false;
        }

        @Override
        public void onChange(boolean selfChange) {
            try {
                mRemote.onChange(selfChange);
            } catch (RemoteException ex) {
                // Do nothing, the far side is dead
            }
        }
    }

    public CursorToBulkCursorAdaptor(Cursor cursor, IContentObserver observer, String providerName,
            boolean allowWrite, CursorWindow window) {
        try {
            mCursor = (CrossProcessCursor) cursor;
            if (mCursor instanceof AbstractWindowedCursor) {
                AbstractWindowedCursor windowedCursor = (AbstractWindowedCursor) cursor;
                if (windowedCursor.hasWindow()) {
                    if (Log.isLoggable(TAG, Log.VERBOSE) || Config.LOGV) {
                        Log.v(TAG, "Cross process cursor has a local window before setWindow in "
                                + providerName, new RuntimeException());
                    }
                }
                windowedCursor.setWindow(window);
            } else {
                mWindow = window;
                mCursor.fillWindow(0, window);
            }
        } catch (ClassCastException e) {
            // TODO Implement this case.
            throw new UnsupportedOperationException(
                    "Only CrossProcessCursor cursors are supported across process for now", e);
        }
        mProviderName = providerName;
        mReadOnly = !allowWrite;

        createAndRegisterObserverProxy(observer);
    }
    
    public void binderDied() {
        mCursor.close();
        if (mWindow != null) {
            mWindow.close();
        }
    }
    
    public CursorWindow getWindow(int startPos) {
        mCursor.moveToPosition(startPos);
        
        if (mWindow != null) {
            if (startPos < mWindow.getStartPosition() ||
                    startPos >= (mWindow.getStartPosition() + mWindow.getNumRows())) {
                mCursor.fillWindow(startPos, mWindow);
            }            
            return mWindow;
        } else {
            return ((AbstractWindowedCursor)mCursor).getWindow();
        }
    }

    public void onMove(int position) {
        mCursor.onMove(mCursor.getPosition(), position);
    }

    public int count() {
        return mCursor.getCount();
    }

    public String[] getColumnNames() {
        return mCursor.getColumnNames();
    }

    public void deactivate() {
        maybeUnregisterObserverProxy();
        mCursor.deactivate();
    }

    public void close() {
        maybeUnregisterObserverProxy();
        mCursor.close();
    }

    public int requery(IContentObserver observer, CursorWindow window) {
        if (mWindow == null) {
            ((AbstractWindowedCursor)mCursor).setWindow(window);
        }
        try {
            if (!mCursor.requery()) {
                return -1;
            }
        } catch (IllegalStateException e) {
            IllegalStateException leakProgram = new IllegalStateException(
                    mProviderName + " Requery misuse db, mCursor isClosed:" +
                    mCursor.isClosed(), e);
            throw leakProgram;
        }
        
        if (mWindow != null) {
            mCursor.fillWindow(0, window);
            mWindow = window;
        }
        maybeUnregisterObserverProxy();
        createAndRegisterObserverProxy(observer);
        return mCursor.getCount();
    }

    public boolean getWantsAllOnMoveCalls() {
        return mCursor.getWantsAllOnMoveCalls();
    }

    /**
     * Create a ContentObserver from the observer and register it as an observer on the
     * underlying cursor.
     * @param observer the IContentObserver that wants to monitor the cursor
     * @throws IllegalStateException if an observer is already registered
     */
    private void createAndRegisterObserverProxy(IContentObserver observer) {
        if (mObserver != null) {
            throw new IllegalStateException("an observer is already registered");
        }
        mObserver = new ContentObserverProxy(observer, this);
        mCursor.registerContentObserver(mObserver);
    }

    /** Unregister the observer if it is already registered. */
    private void maybeUnregisterObserverProxy() {
        if (mObserver != null) {
            mCursor.unregisterContentObserver(mObserver);
            mObserver.unlinkToDeath(this);
            mObserver = null;
        }
    }

    public boolean updateRows(Map<? extends Long, ? extends Map<String, Object>> values) {
        if (mReadOnly) {
            Log.w("ContentProvider", "Permission Denial: modifying "
                    + mProviderName
                    + " from pid=" + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return false;
        }
        return mCursor.commitUpdates(values);
    }

    public boolean deleteRow(int position) {
        if (mReadOnly) {
            Log.w("ContentProvider", "Permission Denial: modifying "
                    + mProviderName
                    + " from pid=" + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return false;
        }
        if (mCursor.moveToPosition(position) == false) {
            return false;
        }
        return mCursor.deleteRow();
    }

    public Bundle getExtras() {
        return mCursor.getExtras();
    }

    public Bundle respond(Bundle extras) {
        return mCursor.respond(extras);
    }
}
