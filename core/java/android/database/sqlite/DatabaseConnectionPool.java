/*
 * Copyright (C) 20010 The Android Open Source Project
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

import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

/**
 * A connection pool to be used by readers.
 * Note that each connection can be used by only one reader at a time.
 */
/* package */ class DatabaseConnectionPool {

    private static final String TAG = "DatabaseConnectionPool";

    /** The default connection pool size. It is set based on the amount of memory the device has.
     * TODO: set this with 'a system call' which returns the amount of memory the device has
     */
    private static final int DEFAULT_CONNECTION_POOL_SIZE = 1;

    /** the pool size set for this {@link SQLiteDatabase} */
    private volatile int mMaxPoolSize = DEFAULT_CONNECTION_POOL_SIZE;

    /** The connection pool objects are stored in this member.
     * TODO: revisit this data struct as the number of pooled connections increase beyond
     * single-digit values.
     */
    private final ArrayList<PoolObj> mPool = new ArrayList<PoolObj>(mMaxPoolSize);

    /** the main database connection to which this connection pool is attached */
    private final SQLiteDatabase mParentDbObj;

    /* package */ DatabaseConnectionPool(SQLiteDatabase db) {
        this.mParentDbObj = db;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Max Pool Size: " + mMaxPoolSize);
        }
    }

    /**
     * close all database connections in the pool - even if they are in use!
     */
    /* package */ void close() {
        synchronized(mParentDbObj) {
            for (int i = mPool.size() - 1; i >= 0; i--) {
                mPool.get(i).mDb.close();
            }
            mPool.clear();
        }
    }

    /**
     * get a free connection from the pool
     *
     * @param sql if not null, try to find a connection inthe pool which already has cached
     * the compiled statement for this sql.
     * @return the Database connection that the caller can use
     */
    /* package */ SQLiteDatabase get(String sql) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            doAsserts();
        }

        SQLiteDatabase db = null;
        PoolObj poolObj = null;
        synchronized(mParentDbObj) {
            if (getFreePoolSize() == 0) {
                if (mMaxPoolSize == mPool.size()) {
                    // maxed out. can't open any more connections.
                    // let the caller wait on one of the pooled connections
                    if (mMaxPoolSize == 1) {
                        poolObj = mPool.get(0);
                    } else {
                        // get a random number between 0 and (mMaxPoolSize-1)
                        poolObj = mPool.get(
                                new Random(SystemClock.elapsedRealtime()).nextInt(mMaxPoolSize-1));
                    }
                    db = poolObj.mDb;
                } else {
                    // create a new connection and add it to the pool, since we haven't reached
                    // max pool size allowed
                    int poolSize = getPoolSize();
                    db = mParentDbObj.createPoolConnection((short)(poolSize + 1));
                    poolObj = new PoolObj(db);
                    mPool.add(poolSize, poolObj);
                }
            } else {
                // there are free connections available. pick one
                for (int i = mPool.size() - 1; i >= 0; i--) {
                    poolObj = mPool.get(i);
                    if (!poolObj.isFree()) {
                        continue;
                    }
                    // it is free - but does its database object already have the given sql in its
                    // statement-cache?
                    db = poolObj.mDb;
                    if (sql == null || db.isSqlInStatementCache(sql)) {
                        // found a free connection we can use
                        break;
                    }
                    // haven't found a database object which has the given sql in its
                    // statement-cache
                }
            }

            assert poolObj != null;
            assert poolObj.mDb == db;

            poolObj.acquire();
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "END get-connection: " + toString() + poolObj.toString());
        }
        return db;
        // TODO if a thread acquires a connection and dies without releasing the connection, then
        // there could be a connection leak.
    }

    /**
     * release the given database connection back to the pool.
     * @param db the connection to be released
     */
    /* package */ void release(SQLiteDatabase db) {
        PoolObj poolObj;
        synchronized(mParentDbObj) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                assert db.mConnectionNum > 0;
                doAsserts();
                assert mPool.get(db.mConnectionNum - 1).mDb == db;
            }

            poolObj = mPool.get(db.mConnectionNum - 1);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "BEGIN release-conn: " + toString() + poolObj.toString());
            }

            if (poolObj.isFree()) {
                throw new IllegalStateException("Releasing object already freed: " +
                        db.mConnectionNum);
            }

            poolObj.release();
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "END release-conn: " + toString() + poolObj.toString());
        }
    }

    /**
     * Returns a list of all database connections in the pool (both free and busy connections).
     * This method is used when "adb bugreport" is done.
     */
    /* package */ ArrayList<SQLiteDatabase> getConnectionList() {
        ArrayList<SQLiteDatabase> list = new ArrayList<SQLiteDatabase>();
        synchronized(mParentDbObj) {
            for (int i = mPool.size() - 1; i >= 0; i--) {
                list.add(mPool.get(i).mDb);
            }
        }
        return list;
    }

    /* package */ int getPoolSize() {
        synchronized(mParentDbObj) {
            return mPool.size();
        }
    }

    private int getFreePoolSize() {
        int count = 0;
        for (int i = mPool.size() - 1; i >= 0; i--) {
            if (mPool.get(i).isFree()) {
                count++;
            }
        }
        return count++;
    }

    @Override
    public String toString() {
        return "db: " + mParentDbObj.getPath() +
                ", threadid = " + Thread.currentThread().getId() +
                ", totalsize = " + mPool.size() + ", #free = " + getFreePoolSize() +
                ", maxpoolsize = " + mMaxPoolSize;
    }

    private void doAsserts() {
        for (int i = 0; i < mPool.size(); i++) {
            mPool.get(i).verify();
            assert mPool.get(i).mDb.mConnectionNum == (i + 1);
        }
    }

    /* package */ void setMaxPoolSize(int size) {
        synchronized(mParentDbObj) {
            mMaxPoolSize = size;
        }
    }

    /* package */ int getMaxPoolSize() {
        synchronized(mParentDbObj) {
            return mMaxPoolSize;
        }
    }

    /**
     * represents objects in the connection pool.
     */
    private static class PoolObj {

        private final SQLiteDatabase mDb;
        private boolean mFreeBusyFlag = FREE;
        private static final boolean FREE = true;
        private static final boolean BUSY = false;

        /** the number of threads holding this connection */
        // @GuardedBy("this")
        private int mNumHolders = 0;

        /** contains the threadIds of the threads holding this connection.
         * used for debugging purposes only.
         */
        // @GuardedBy("this")
        private HashSet<Long> mHolderIds = new HashSet<Long>();

        public PoolObj(SQLiteDatabase db) {
            mDb = db;
        }

        private synchronized void acquire() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                assert isFree();
                long id = Thread.currentThread().getId();
                assert !mHolderIds.contains(id);
                mHolderIds.add(id);
            }

            mNumHolders++;
            mFreeBusyFlag = BUSY;
        }

        private synchronized void release() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                long id = Thread.currentThread().getId();
                assert mHolderIds.size() == mNumHolders;
                assert mHolderIds.contains(id);
                mHolderIds.remove(id);
            }

            mNumHolders--;
            if (mNumHolders == 0) {
                mFreeBusyFlag = FREE;
            }
        }

        private synchronized boolean isFree() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                verify();
            }
            return (mFreeBusyFlag == FREE);
        }

        private synchronized void verify() {
            if (mFreeBusyFlag == FREE) {
                assert mNumHolders == 0;
            } else {
                assert mNumHolders > 0;
            }
        }

        @Override
        public String toString() {
            StringBuilder buff = new StringBuilder();
            buff.append(", conn # ");
            buff.append(mDb.mConnectionNum);
            buff.append(", mCountHolders = ");
            synchronized(this) {
                buff.append(mNumHolders);
                buff.append(", freeBusyFlag = ");
                buff.append(mFreeBusyFlag);
                for (Long l : mHolderIds) {
                    buff.append(", id = " + l);
                }
            }
            return buff.toString();
        }
    }
}
