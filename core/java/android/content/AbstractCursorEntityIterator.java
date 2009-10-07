package android.content;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.RemoteException;

/**
 * An abstract class that makes it easy to implement an EntityIterator over a cursor.
 * The user must implement {@link #newEntityFromCursorLocked}, which runs inside of a
 * database transaction.
 * @hide
 */
public abstract class AbstractCursorEntityIterator implements EntityIterator {
    private final Cursor mEntityCursor;
    private final SQLiteDatabase mDb;
    private volatile Entity mNextEntity;
    private volatile boolean mIsClosed;

    public AbstractCursorEntityIterator(SQLiteDatabase db, Cursor entityCursor) {
        mEntityCursor = entityCursor;
        mDb = db;
        mNextEntity = null;
        mIsClosed = false;
    }

    /**
     * If there are entries left in the cursor then advance the cursor and use the new row to
     * populate mNextEntity. If the cursor is at the end or if advancing it causes the cursor
     * to become at the end then set mEntityCursor to null. If newEntityFromCursor returns null
     * then continue advancing until it either returns a non-null Entity or the cursor reaches
     * the end.
     */
    private void fillEntityIfAvailable() {
        while (mNextEntity == null) {
            if (!mEntityCursor.moveToNext()) {
                // the cursor is at then end, bail out
                return;
            }
            // This may return null if newEntityFromCursor is not able to create an entity
            // from the current cursor position. In that case this method will loop and try
            // the next cursor position
            mNextEntity = newEntityFromCursorLocked(mEntityCursor);
        }
        mDb.beginTransaction();
        try {
            int position = mEntityCursor.getPosition();
            mNextEntity = newEntityFromCursorLocked(mEntityCursor);
            int newPosition = mEntityCursor.getPosition();
            if (newPosition != position) {
                throw new IllegalStateException("the cursor position changed during the call to"
                        + "newEntityFromCursorLocked, from " + position + " to " + newPosition);
            }
        } finally {
            mDb.endTransaction();
        }
    }

    /**
     * Checks if there are more Entities accessible via this iterator. This may not be called
     * if the iterator is already closed.
     * @return true if the call to next() will return an Entity.
     */
    public boolean hasNext() {
        if (mIsClosed) {
            throw new IllegalStateException("calling hasNext() when the iterator is closed");
        }
        fillEntityIfAvailable();
        return mNextEntity != null;
    }

    /**
     * Returns the next Entity that is accessible via this iterator. This may not be called
     * if the iterator is already closed.
     * @return the next Entity that is accessible via this iterator
     */
    public Entity next() {
        if (mIsClosed) {
            throw new IllegalStateException("calling next() when the iterator is closed");
        }
        if (!hasNext()) {
            throw new IllegalStateException("you may only call next() if hasNext() is true");
        }

        try {
            return mNextEntity;
        } finally {
            mNextEntity = null;
        }
    }

    public void reset() throws RemoteException {
        if (mIsClosed) {
            throw new IllegalStateException("calling reset() when the iterator is closed");
        }
        mEntityCursor.moveToPosition(-1);
        mNextEntity = null;
    }

    /**
     * Closes this iterator making it invalid. If is invalid for the user to call any public
     * method on the iterator once it has been closed.
     */
    public void close() {
        if (mIsClosed) {
            throw new IllegalStateException("closing when already closed");
        }
        mIsClosed = true;
        mEntityCursor.close();
    }

    /**
     * Returns a new Entity from the current cursor position. This is called from within a
     * database transaction. If a new entity cannot be created from this cursor position (e.g.
     * if the row that is referred to no longer exists) then this may return null. The cursor
     * is guaranteed to be pointing to a valid row when this call is made. The implementation
     * of newEntityFromCursorLocked is not allowed to change the position of the cursor.
     * @param cursor from where to read the data for the Entity
     * @return an Entity that corresponds to the current cursor position or null
     */
    public abstract Entity newEntityFromCursorLocked(Cursor cursor);
}
