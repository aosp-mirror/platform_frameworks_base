/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.os;
import android.os.RemoteException;

/**
 * The Binder transaction failed because it was too large.
 * <p>
 * During a remote procedure call, the arguments and the return value of the call
 * are transferred as {@link Parcel} objects stored in the Binder transaction buffer.
 * If the arguments or the return value are too large to fit in the transaction buffer,
 * then the call will fail.  {@link TransactionTooLargeException} is thrown as a
 * heuristic when a transaction is large, and it fails, since these are the transactions
 * which are most likely to overfill the transaction buffer.
 * </p><p>
 * The Binder transaction buffer has a limited fixed size, currently 1MB, which
 * is shared by all transactions in progress for the process.  Consequently this
 * exception can be thrown when there are many transactions in progress even when
 * most of the individual transactions are of moderate size.
 * </p><p>
 * There are two possible outcomes when a remote procedure call throws
 * {@link TransactionTooLargeException}.  Either the client was unable to send
 * its request to the service (most likely if the arguments were too large to fit in
 * the transaction buffer), or the service was unable to send its response back
 * to the client (most likely if the return value was too large to fit
 * in the transaction buffer).  It is not possible to tell which of these outcomes
 * actually occurred.  The client should assume that a partial failure occurred.
 * </p><p>
 * The key to avoiding {@link TransactionTooLargeException} is to keep all
 * transactions relatively small.  Try to minimize the amount of memory needed to create
 * a {@link Parcel} for the arguments and the return value of the remote procedure call.
 * Avoid transferring huge arrays of strings or large bitmaps.
 * If possible, try to break up big requests into smaller pieces.
 * </p><p>
 * If you are implementing a service, it may help to impose size or complexity
 * constraints on the queries that clients can perform.  For example, if the result set
 * could become large, then don't allow the client to request more than a few records
 * at a time.  Alternately, instead of returning all of the available data all at once,
 * return the essential information first and make the client ask for additional information
 * later as needed.
 * </p>
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class TransactionTooLargeException extends RemoteException {
    public TransactionTooLargeException() {
        super();
    }

    public TransactionTooLargeException(String msg) {
        super(msg);
    }
}
