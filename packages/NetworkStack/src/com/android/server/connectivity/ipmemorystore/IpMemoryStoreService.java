/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.connectivity.ipmemorystore;

import static android.net.ipmemorystore.Status.ERROR_DATABASE_CANNOT_BE_OPENED;
import static android.net.ipmemorystore.Status.ERROR_GENERIC;
import static android.net.ipmemorystore.Status.ERROR_ILLEGAL_ARGUMENT;
import static android.net.ipmemorystore.Status.SUCCESS;

import static com.android.server.connectivity.ipmemorystore.IpMemoryStoreDatabase.EXPIRY_ERROR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.IIpMemoryStore;
import android.net.ipmemorystore.Blob;
import android.net.ipmemorystore.IOnBlobRetrievedListener;
import android.net.ipmemorystore.IOnL2KeyResponseListener;
import android.net.ipmemorystore.IOnNetworkAttributesRetrieved;
import android.net.ipmemorystore.IOnSameNetworkResponseListener;
import android.net.ipmemorystore.IOnStatusListener;
import android.net.ipmemorystore.NetworkAttributes;
import android.net.ipmemorystore.NetworkAttributesParcelable;
import android.net.ipmemorystore.SameL3NetworkResponse;
import android.net.ipmemorystore.Status;
import android.net.ipmemorystore.StatusParcelable;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Implementation for the IP memory store.
 * This component offers specialized services for network components to store and retrieve
 * knowledge about networks, and provides intelligence that groups level 2 networks together
 * into level 3 networks.
 *
 * @hide
 */
public class IpMemoryStoreService extends IIpMemoryStore.Stub {
    private static final String TAG = IpMemoryStoreService.class.getSimpleName();
    private static final int MAX_CONCURRENT_THREADS = 4;
    private static final boolean DBG = true;

    @NonNull
    final Context mContext;
    @Nullable
    final SQLiteDatabase mDb;
    @NonNull
    final ExecutorService mExecutor;

    /**
     * Construct an IpMemoryStoreService object.
     * This constructor will block on disk access to open the database.
     * @param context the context to access storage with.
     */
    public IpMemoryStoreService(@NonNull final Context context) {
        // Note that constructing the service will access the disk and block
        // for some time, but it should make no difference to the clients. Because
        // the interface is one-way, clients fire and forget requests, and the callback
        // will get called eventually in any case, and the framework will wait for the
        // service to be created to deliver subsequent requests.
        // Avoiding this would mean the mDb member can't be final, which means the service would
        // have to test for nullity, care for failure, and allow for a wait at every single access,
        // which would make the code a lot more complex and require all methods to possibly block.
        mContext = context;
        SQLiteDatabase db;
        final IpMemoryStoreDatabase.DbHelper helper = new IpMemoryStoreDatabase.DbHelper(context);
        try {
            db = helper.getWritableDatabase();
            if (null == db) Log.e(TAG, "Unexpected null return of getWriteableDatabase");
        } catch (final SQLException e) {
            Log.e(TAG, "Can't open the Ip Memory Store database", e);
            db = null;
        } catch (final Exception e) {
            Log.wtf(TAG, "Impossible exception Ip Memory Store database", e);
            db = null;
        }
        mDb = db;
        // The work-stealing thread pool executor will spawn threads as needed up to
        // the max only when there is no free thread available. This generally behaves
        // exactly like one would expect it intuitively :
        // - When work arrives, it will spawn a new thread iff there are no available threads
        // - When there is no work to do it will shutdown threads after a while (the while
        //   being equal to 2 seconds (not configurable) when max threads are spun up and
        //   twice as much for every one less thread)
        // - When all threads are busy the work is enqueued and waits for any worker
        //   to become available.
        // Because the stealing pool is made for very heavily parallel execution of
        // small tasks that spawn others, it creates a queue per thread that in this
        // case is overhead. However, the three behaviors above make it a superior
        // choice to cached or fixedThreadPoolExecutor, neither of which can actually
        // enqueue a task waiting for a thread to be free. This can probably be solved
        // with judicious subclassing of ThreadPoolExecutor, but that's a lot of dangerous
        // complexity for little benefit in this case.
        mExecutor = Executors.newWorkStealingPool(MAX_CONCURRENT_THREADS);
    }

    /**
     * Shutdown the memory store service, cancelling running tasks and dropping queued tasks.
     *
     * This is provided to give a way to clean up, and is meant to be available in case of an
     * emergency shutdown.
     */
    public void shutdown() {
        // By contrast with ExecutorService#shutdown, ExecutorService#shutdownNow tries
        // to cancel the existing tasks, and does not wait for completion. It does not
        // guarantee the threads can be terminated in any given amount of time.
        mExecutor.shutdownNow();
        if (mDb != null) mDb.close();
    }

    /** Helper function to make a status object */
    private StatusParcelable makeStatus(final int code) {
        return new Status(code).toParcelable();
    }

    /**
     * Store network attributes for a given L2 key.
     *
     * @param l2Key The L2 key for the L2 network. Clients that don't know or care about the L2
     *              key and only care about grouping can pass a unique ID here like the ones
     *              generated by {@code java.util.UUID.randomUUID()}, but keep in mind the low
     *              relevance of such a network will lead to it being evicted soon if it's not
     *              refreshed. Use findL2Key to try and find a similar L2Key to these attributes.
     * @param attributes The attributes for this network.
     * @param listener A listener to inform of the completion of this call, or null if the client
     *        is not interested in learning about success/failure.
     * Through the listener, returns the L2 key. This is useful if the L2 key was not specified.
     * If the call failed, the L2 key will be null.
     */
    // Note that while l2Key and attributes are non-null in spirit, they are received from
    // another process. If the remote process decides to ignore everything and send null, this
    // process should still not crash.
    @Override
    public void storeNetworkAttributes(@Nullable final String l2Key,
            @Nullable final NetworkAttributesParcelable attributes,
            @Nullable final IOnStatusListener listener) {
        // Because the parcelable is 100% mutable, the thread may not see its members initialized.
        // Therefore either an immutable object is created on this same thread before it's passed
        // to the executor, or there need to be a write barrier here and a read barrier in the
        // remote thread.
        final NetworkAttributes na = null == attributes ? null : new NetworkAttributes(attributes);
        mExecutor.execute(() -> {
            try {
                final int code = storeNetworkAttributesAndBlobSync(l2Key, na,
                        null /* clientId */, null /* name */, null /* data */);
                if (null != listener) listener.onComplete(makeStatus(code));
            } catch (final RemoteException e) {
                // Client at the other end died
            }
        });
    }

    /**
     * Store a binary blob associated with an L2 key and a name.
     *
     * @param l2Key The L2 key for this network.
     * @param clientId The ID of the client.
     * @param name The name of this data.
     * @param blob The data to store.
     * @param listener The listener that will be invoked to return the answer, or null if the
     *        is not interested in learning about success/failure.
     * Through the listener, returns a status to indicate success or failure.
     */
    @Override
    public void storeBlob(@Nullable final String l2Key, @Nullable final String clientId,
            @Nullable final String name, @Nullable final Blob blob,
            @Nullable final IOnStatusListener listener) {
        final byte[] data = null == blob ? null : blob.data;
        mExecutor.execute(() -> {
            try {
                final int code = storeNetworkAttributesAndBlobSync(l2Key,
                        null /* NetworkAttributes */, clientId, name, data);
                if (null != listener) listener.onComplete(makeStatus(code));
            } catch (final RemoteException e) {
                // Client at the other end died
            }
        });
    }

    /**
     * Helper method for storeNetworkAttributes and storeBlob.
     *
     * Either attributes or none of clientId, name and data may be null. This will write the
     * passed data if non-null, and will write attributes if non-null, but in any case it will
     * bump the relevance up.
     * Returns a success code from Status.
     */
    private int storeNetworkAttributesAndBlobSync(@Nullable final String l2Key,
            @Nullable final NetworkAttributes attributes,
            @Nullable final String clientId,
            @Nullable final String name, @Nullable final byte[] data) {
        if (null == l2Key) return ERROR_ILLEGAL_ARGUMENT;
        if (null == attributes && null == data) return ERROR_ILLEGAL_ARGUMENT;
        if (null != data && (null == clientId || null == name)) return ERROR_ILLEGAL_ARGUMENT;
        if (null == mDb) return ERROR_DATABASE_CANNOT_BE_OPENED;
        try {
            final long oldExpiry = IpMemoryStoreDatabase.getExpiry(mDb, l2Key);
            final long newExpiry = RelevanceUtils.bumpExpiryDate(
                    oldExpiry == EXPIRY_ERROR ? System.currentTimeMillis() : oldExpiry);
            final int errorCode =
                    IpMemoryStoreDatabase.storeNetworkAttributes(mDb, l2Key, newExpiry, attributes);
            // If no blob to store, the client is interested in the result of storing the attributes
            if (null == data) return errorCode;
            // Otherwise it's interested in the result of storing the blob
            return IpMemoryStoreDatabase.storeBlob(mDb, l2Key, clientId, name, data);
        } catch (Exception e) {
            if (DBG) {
                Log.e(TAG, "Exception while storing for key {" + l2Key
                        + "} ; NetworkAttributes {" + (null == attributes ? "null" : attributes)
                        + "} ; clientId {" + (null == clientId ? "null" : clientId)
                        + "} ; name {" + (null == name ? "null" : name)
                        + "} ; data {" + Utils.byteArrayToString(data) + "}", e);
            }
        }
        return ERROR_GENERIC;
    }

    /**
     * Returns the best L2 key associated with the attributes.
     *
     * This will find a record that would be in the same group as the passed attributes. This is
     * useful to choose the key for storing a sample or private data when the L2 key is not known.
     * If multiple records are group-close to these attributes, the closest match is returned.
     * If multiple records have the same closeness, the one with the smaller (unicode codepoint
     * order) L2 key is returned.
     * If no record matches these attributes, null is returned.
     *
     * @param attributes The attributes of the network to find.
     * @param listener The listener that will be invoked to return the answer.
     * Through the listener, returns the L2 key if one matched, or null.
     */
    @Override
    public void findL2Key(@Nullable final NetworkAttributesParcelable attributes,
            @Nullable final IOnL2KeyResponseListener listener) {
        if (null == listener) return;
        mExecutor.execute(() -> {
            try {
                if (null == attributes) {
                    listener.onL2KeyResponse(makeStatus(ERROR_ILLEGAL_ARGUMENT), null);
                    return;
                }
                if (null == mDb) {
                    listener.onL2KeyResponse(makeStatus(ERROR_ILLEGAL_ARGUMENT), null);
                    return;
                }
                final String key = IpMemoryStoreDatabase.findClosestAttributes(mDb,
                        new NetworkAttributes(attributes));
                listener.onL2KeyResponse(makeStatus(SUCCESS), key);
            } catch (final RemoteException e) {
                // Client at the other end died
            }
        });
    }

    /**
     * Returns whether, to the best of the store's ability to tell, the two specified L2 keys point
     * to the same L3 network. Group-closeness is used to determine this.
     *
     * @param l2Key1 The key for the first network.
     * @param l2Key2 The key for the second network.
     * @param listener The listener that will be invoked to return the answer.
     * Through the listener, a SameL3NetworkResponse containing the answer and confidence.
     */
    @Override
    public void isSameNetwork(@Nullable final String l2Key1, @Nullable final String l2Key2,
            @Nullable final IOnSameNetworkResponseListener listener) {
        if (null == listener) return;
        mExecutor.execute(() -> {
            try {
                if (null == l2Key1 || null == l2Key2) {
                    listener.onSameNetworkResponse(makeStatus(ERROR_ILLEGAL_ARGUMENT), null);
                    return;
                }
                if (null == mDb) {
                    listener.onSameNetworkResponse(makeStatus(ERROR_ILLEGAL_ARGUMENT), null);
                    return;
                }
                try {
                    final NetworkAttributes attr1 =
                            IpMemoryStoreDatabase.retrieveNetworkAttributes(mDb, l2Key1);
                    final NetworkAttributes attr2 =
                            IpMemoryStoreDatabase.retrieveNetworkAttributes(mDb, l2Key2);
                    if (null == attr1 || null == attr2) {
                        listener.onSameNetworkResponse(makeStatus(SUCCESS),
                                new SameL3NetworkResponse(l2Key1, l2Key2,
                                        -1f /* never connected */).toParcelable());
                        return;
                    }
                    final float confidence = attr1.getNetworkGroupSamenessConfidence(attr2);
                    listener.onSameNetworkResponse(makeStatus(SUCCESS),
                            new SameL3NetworkResponse(l2Key1, l2Key2, confidence).toParcelable());
                } catch (Exception e) {
                    listener.onSameNetworkResponse(makeStatus(ERROR_GENERIC), null);
                }
            } catch (final RemoteException e) {
                // Client at the other end died
            }
        });
    }

    /**
     * Retrieve the network attributes for a key.
     * If no record is present for this key, this will return null attributes.
     *
     * @param l2Key The key of the network to query.
     * @param listener The listener that will be invoked to return the answer.
     * Through the listener, returns the network attributes and the L2 key associated with
     *         the query.
     */
    @Override
    public void retrieveNetworkAttributes(@Nullable final String l2Key,
            @Nullable final IOnNetworkAttributesRetrieved listener) {
        if (null == listener) return;
        mExecutor.execute(() -> {
            try {
                if (null == l2Key) {
                    listener.onNetworkAttributesRetrieved(
                            makeStatus(ERROR_ILLEGAL_ARGUMENT), l2Key, null);
                    return;
                }
                if (null == mDb) {
                    listener.onNetworkAttributesRetrieved(
                            makeStatus(ERROR_DATABASE_CANNOT_BE_OPENED), l2Key, null);
                    return;
                }
                try {
                    final NetworkAttributes attributes =
                            IpMemoryStoreDatabase.retrieveNetworkAttributes(mDb, l2Key);
                    listener.onNetworkAttributesRetrieved(makeStatus(SUCCESS), l2Key,
                            null == attributes ? null : attributes.toParcelable());
                } catch (final Exception e) {
                    listener.onNetworkAttributesRetrieved(makeStatus(ERROR_GENERIC), l2Key, null);
                }
            } catch (final RemoteException e) {
                // Client at the other end died
            }
        });
    }

    /**
     * Retrieve previously stored private data.
     * If no data was stored for this L2 key and name this will return null.
     *
     * @param l2Key The L2 key.
     * @param clientId The id of the client that stored this data.
     * @param name The name of the data.
     * @param listener The listener that will be invoked to return the answer.
     * Through the listener, returns the private data if any or null if none, with the L2 key
     *         and the name of the data associated with the query.
     */
    @Override
    public void retrieveBlob(@NonNull final String l2Key, @NonNull final String clientId,
            @NonNull final String name, @NonNull final IOnBlobRetrievedListener listener) {
        if (null == listener) return;
        mExecutor.execute(() -> {
            try {
                if (null == l2Key) {
                    listener.onBlobRetrieved(makeStatus(ERROR_ILLEGAL_ARGUMENT), l2Key, name, null);
                    return;
                }
                if (null == mDb) {
                    listener.onBlobRetrieved(makeStatus(ERROR_DATABASE_CANNOT_BE_OPENED), l2Key,
                            name, null);
                    return;
                }
                try {
                    final Blob b = new Blob();
                    b.data = IpMemoryStoreDatabase.retrieveBlob(mDb, l2Key, clientId, name);
                    listener.onBlobRetrieved(makeStatus(SUCCESS), l2Key, name, b);
                } catch (final Exception e) {
                    listener.onBlobRetrieved(makeStatus(ERROR_GENERIC), l2Key, name, null);
                }
            } catch (final RemoteException e) {
                // Client at the other end died
            }
        });
    }
}
