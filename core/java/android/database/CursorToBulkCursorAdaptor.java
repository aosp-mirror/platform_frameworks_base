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
import android.os.RemoteException;
import android.util.Log;


/**
 * Wraps a BulkCursor around an existing Cursor making it remotable.
 * <p>
 * If the wrapped cursor is a {@link AbstractWindowedCursor} then it owns
 * the cursor window.  Otherwise, the adaptor takes ownership of the
 * cursor itself and ensures it gets closed as needed during deactivation
 * and requeries.
 * </p>
 *
 * {@hide}
 */
public final class CursorToBulkCursorAdaptor extends BulkCursorNative 
        implements IBinder.DeathRecipient {
    private static final String TAG = "Cursor";

    private final Object mLock = new Object();
    private final String mProviderName;
    private ContentObserverProxy mObserver;

    /**
     * The cursor that is being adapted.
     * This field is set to null when the cursor is closed.
     */
    private CrossProcessCursor mCursor;

    /**
     * The cursor window used by the cross process cursor.
     * This field is always null for abstract windowed cursors since they are responsible
     * for managing the lifetime of their window.
     */
    private CursorWindow mWindowForNonWindowedCursor;
    private boolean mWindowForNonWindowedCursorWasFilled;

    private static final class ContentObserverProxy extends ContentObserver {
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

    public CursorToBulkCursorAdaptor(Cursor cursor, IContentObserver observer,
            String providerName) {
        try {
            mCursor = (CrossProcessCursor) cursor;
        } catch (ClassCastException e) {
            throw new UnsupportedOperationException(
                    "Only CrossProcessCursor cursors are supported across process for now", e);
        }
        mProviderName = providerName;

        synchronized (mLock) {
            createAndRegisterObserverProxyLocked(observer);
        }
    }

    private void closeWindowForNonWindowedCursorLocked() {
        if (mWindowForNonWindowedCursor != null) {
            mWindowForNonWindowedCursor.close();
            mWindowForNonWindowedCursor = null;
            mWindowForNonWindowedCursorWasFilled = false;
        }
    }

    private void disposeLocked() {
        if (mCursor != null) {
            unregisterObserverProxyLocked();
            mCursor.close();
            mCursor = null;
        }

        closeWindowForNonWindowedCursorLocked();
    }

    private void throwIfCursorIsClosed() {
        if (mCursor == null) {
            throw new StaleDataException("Attempted to access a cursor after it has been closed.");
        }
    }

    @Override
    public void binderDied() {
        synchronized (mLock) {
            disposeLocked();
        }
    }

    @Override
    public CursorWindow getWindow(int startPos) {
        synchronized (mLock) {
            throwIfCursorIsClosed();

            CursorWindow window;
            if (mCursor instanceof AbstractWindowedCursor) {
                AbstractWindowedCursor windowedCursor = (AbstractWindowedCursor)mCursor;
                window = windowedCursor.getWindow();
                if (window == null) {
                    window = new CursorWindow(mProviderName, false /*localOnly*/);
                    windowedCursor.setWindow(window);
                }

                mCursor.moveToPosition(startPos);
            } else {
                window = mWindowForNonWindowedCursor;
                if (window == null) {
                    window = new CursorWindow(mProviderName, false /*localOnly*/);
                    mWindowForNonWindowedCursor = window;
                }

                mCursor.moveToPosition(startPos);

                if (!mWindowForNonWindowedCursorWasFilled
                        || startPos < window.getStartPosition()
                        || startPos >= window.getStartPosition() + window.getNumRows()) {
                    mCursor.fillWindow(startPos, window);
                    mWindowForNonWindowedCursorWasFilled = true;
                }
            }

            // Acquire a reference before returning from this RPC.
            // The Binder proxy will decrement the reference count again as part of writing
            // the CursorWindow to the reply parcel as a return value.
            if (window != null) {
                window.acquireReference();
            }
            return window;
        }
    }

    @Override
    public void onMove(int position) {
        synchronized (mLock) {
            throwIfCursorIsClosed();

            mCursor.onMove(mCursor.getPosition(), position);
        }
    }

    @Override
    public int count() {
        synchronized (mLock) {
            throwIfCursorIsClosed();

            return mCursor.getCount();
        }
    }

    @Override
    public String[] getColumnNames() {
        synchronized (mLock) {
            throwIfCursorIsClosed();

            return mCursor.getColumnNames();
        }
    }

    @Override
    public void deactivate() {
        synchronized (mLock) {
            if (mCursor != null) {
                unregisterObserverProxyLocked();
                mCursor.deactivate();
            }

            closeWindowForNonWindowedCursorLocked();
        }
    }

    @Override
    public void close() {
        synchronized (mLock) {
            disposeLocked();
        }
    }

    @Override
    public int requery(IContentObserver observer) {
        synchronized (mLock) {
            throwIfCursorIsClosed();

            closeWindowForNonWindowedCursorLocked();

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

            unregisterObserverProxyLocked();
            createAndRegisterObserverProxyLocked(observer);
            return mCursor.getCount();
        }
    }

    @Override
    public boolean getWantsAllOnMoveCalls() {
        synchronized (mLock) {
            throwIfCursorIsClosed();

            return mCursor.getWantsAllOnMoveCalls();
        }
    }

    /**
     * Create a ContentObserver from the observer and register it as an observer on the
     * underlying cursor.
     * @param observer the IContentObserver that wants to monitor the cursor
     * @throws IllegalStateException if an observer is already registered
     */
    private void createAndRegisterObserverProxyLocked(IContentObserver observer) {
        if (mObserver != null) {
            throw new IllegalStateException("an observer is already registered");
        }
        mObserver = new ContentObserverProxy(observer, this);
        mCursor.registerContentObserver(mObserver);
    }

    /** Unregister the observer if it is already registered. */
    private void unregisterObserverProxyLocked() {
        if (mObserver != null) {
            mCursor.unregisterContentObserver(mObserver);
            mObserver.unlinkToDeath(this);
            mObserver = null;
        }
    }

    @Override
    public Bundle getExtras() {
        synchronized (mLock) {
            throwIfCursorIsClosed();

            return mCursor.getExtras();
        }
    }

    @Override
    public Bundle respond(Bundle extras) {
        synchronized (mLock) {
            throwIfCursorIsClosed();

            return mCursor.respond(extras);
        }
    }
}
