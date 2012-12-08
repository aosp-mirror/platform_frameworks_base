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

package com.android.internal.telephony;

import android.content.AsyncQueryHandler;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.location.CountryDetector;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.ContactsContract.CommonDataKinds.SipAddress;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.PhoneLookup;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.telephony.Rlog;

/**
 * Helper class to make it easier to run asynchronous caller-id lookup queries.
 * @see CallerInfo
 *
 * {@hide}
 */
public class CallerInfoAsyncQuery {
    private static final boolean DBG = false;
    private static final String LOG_TAG = "CallerInfoAsyncQuery";

    private static final int EVENT_NEW_QUERY = 1;
    private static final int EVENT_ADD_LISTENER = 2;
    private static final int EVENT_END_OF_QUEUE = 3;
    private static final int EVENT_EMERGENCY_NUMBER = 4;
    private static final int EVENT_VOICEMAIL_NUMBER = 5;

    private CallerInfoAsyncQueryHandler mHandler;

    // If the CallerInfo query finds no contacts, should we use the
    // PhoneNumberOfflineGeocoder to look up a "geo description"?
    // (TODO: This could become a flag in config.xml if it ever needs to be
    // configured on a per-product basis.)
    private static final boolean ENABLE_UNKNOWN_NUMBER_GEO_DESCRIPTION = true;

    /**
     * Interface for a CallerInfoAsyncQueryHandler result return.
     */
    public interface OnQueryCompleteListener {
        /**
         * Called when the query is complete.
         */
        public void onQueryComplete(int token, Object cookie, CallerInfo ci);
    }


    /**
     * Wrap the cookie from the WorkerArgs with additional information needed by our
     * classes.
     */
    private static final class CookieWrapper {
        public OnQueryCompleteListener listener;
        public Object cookie;
        public int event;
        public String number;
    }


    /**
     * Simple exception used to communicate problems with the query pool.
     */
    public static class QueryPoolException extends SQLException {
        public QueryPoolException(String error) {
            super(error);
        }
    }

    /**
     * Our own implementation of the AsyncQueryHandler.
     */
    private class CallerInfoAsyncQueryHandler extends AsyncQueryHandler {

        /**
         * The information relevant to each CallerInfo query.  Each query may have multiple
         * listeners, so each AsyncCursorInfo is associated with 2 or more CookieWrapper
         * objects in the queue (one with a new query event, and one with a end event, with
         * 0 or more additional listeners in between).
         */
        private Context mQueryContext;
        private Uri mQueryUri;
        private CallerInfo mCallerInfo;

        /**
         * Our own query worker thread.
         *
         * This thread handles the messages enqueued in the looper.  The normal sequence
         * of events is that a new query shows up in the looper queue, followed by 0 or
         * more add listener requests, and then an end request.  Of course, these requests
         * can be interlaced with requests from other tokens, but is irrelevant to this
         * handler since the handler has no state.
         *
         * Note that we depend on the queue to keep things in order; in other words, the
         * looper queue must be FIFO with respect to input from the synchronous startQuery
         * calls and output to this handleMessage call.
         *
         * This use of the queue is required because CallerInfo objects may be accessed
         * multiple times before the query is complete.  All accesses (listeners) must be
         * queued up and informed in order when the query is complete.
         */
        protected class CallerInfoWorkerHandler extends WorkerHandler {
            public CallerInfoWorkerHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                WorkerArgs args = (WorkerArgs) msg.obj;
                CookieWrapper cw = (CookieWrapper) args.cookie;

                if (cw == null) {
                    // Normally, this should never be the case for calls originating
                    // from within this code.
                    // However, if there is any code that this Handler calls (such as in
                    // super.handleMessage) that DOES place unexpected messages on the
                    // queue, then we need pass these messages on.
                    if (DBG) Rlog.d(LOG_TAG, "Unexpected command (CookieWrapper is null): " + msg.what +
                            " ignored by CallerInfoWorkerHandler, passing onto parent.");

                    super.handleMessage(msg);
                } else {

                    if (DBG) Rlog.d(LOG_TAG, "Processing event: " + cw.event + " token (arg1): " + msg.arg1 +
                        " command: " + msg.what + " query URI: " + sanitizeUriToString(args.uri));

                    switch (cw.event) {
                        case EVENT_NEW_QUERY:
                            //start the sql command.
                            super.handleMessage(msg);
                            break;

                        // shortcuts to avoid query for recognized numbers.
                        case EVENT_EMERGENCY_NUMBER:
                        case EVENT_VOICEMAIL_NUMBER:

                        case EVENT_ADD_LISTENER:
                        case EVENT_END_OF_QUEUE:
                            // query was already completed, so just send the reply.
                            // passing the original token value back to the caller
                            // on top of the event values in arg1.
                            Message reply = args.handler.obtainMessage(msg.what);
                            reply.obj = args;
                            reply.arg1 = msg.arg1;

                            reply.sendToTarget();

                            break;
                        default:
                    }
                }
            }
        }


        /**
         * Asynchronous query handler class for the contact / callerinfo object.
         */
        private CallerInfoAsyncQueryHandler(Context context) {
            super(context.getContentResolver());
        }

        @Override
        protected Handler createHandler(Looper looper) {
            return new CallerInfoWorkerHandler(looper);
        }

        /**
         * Overrides onQueryComplete from AsyncQueryHandler.
         *
         * This method takes into account the state of this class; we construct the CallerInfo
         * object only once for each set of listeners. When the query thread has done its work
         * and calls this method, we inform the remaining listeners in the queue, until we're
         * out of listeners.  Once we get the message indicating that we should expect no new
         * listeners for this CallerInfo object, we release the AsyncCursorInfo back into the
         * pool.
         */
        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            if (DBG) Rlog.d(LOG_TAG, "##### onQueryComplete() #####   query complete for token: " + token);

            //get the cookie and notify the listener.
            CookieWrapper cw = (CookieWrapper) cookie;
            if (cw == null) {
                // Normally, this should never be the case for calls originating
                // from within this code.
                // However, if there is any code that calls this method, we should
                // check the parameters to make sure they're viable.
                if (DBG) Rlog.d(LOG_TAG, "Cookie is null, ignoring onQueryComplete() request.");
                return;
            }

            if (cw.event == EVENT_END_OF_QUEUE) {
                release();
                return;
            }

            // check the token and if needed, create the callerinfo object.
            if (mCallerInfo == null) {
                if ((mQueryContext == null) || (mQueryUri == null)) {
                    throw new QueryPoolException
                            ("Bad context or query uri, or CallerInfoAsyncQuery already released.");
                }

                // adjust the callerInfo data as needed, and only if it was set from the
                // initial query request.
                // Change the callerInfo number ONLY if it is an emergency number or the
                // voicemail number, and adjust other data (including photoResource)
                // accordingly.
                if (cw.event == EVENT_EMERGENCY_NUMBER) {
                    // Note we're setting the phone number here (refer to javadoc
                    // comments at the top of CallerInfo class).
                    mCallerInfo = new CallerInfo().markAsEmergency(mQueryContext);
                } else if (cw.event == EVENT_VOICEMAIL_NUMBER) {
                    mCallerInfo = new CallerInfo().markAsVoiceMail();
                } else {
                    mCallerInfo = CallerInfo.getCallerInfo(mQueryContext, mQueryUri, cursor);
                    if (DBG) Rlog.d(LOG_TAG, "==> Got mCallerInfo: " + mCallerInfo);

                    CallerInfo newCallerInfo = CallerInfo.doSecondaryLookupIfNecessary(
                            mQueryContext, cw.number, mCallerInfo);
                    if (newCallerInfo != mCallerInfo) {
                        mCallerInfo = newCallerInfo;
                        if (DBG) Rlog.d(LOG_TAG, "#####async contact look up with numeric username"
                                + mCallerInfo);
                    }

                    // Final step: look up the geocoded description.
                    if (ENABLE_UNKNOWN_NUMBER_GEO_DESCRIPTION) {
                        // Note we do this only if we *don't* have a valid name (i.e. if
                        // no contacts matched the phone number of the incoming call),
                        // since that's the only case where the incoming-call UI cares
                        // about this field.
                        //
                        // (TODO: But if we ever want the UI to show the geoDescription
                        // even when we *do* match a contact, we'll need to either call
                        // updateGeoDescription() unconditionally here, or possibly add a
                        // new parameter to CallerInfoAsyncQuery.startQuery() to force
                        // the geoDescription field to be populated.)

                        if (TextUtils.isEmpty(mCallerInfo.name)) {
                            // Actually when no contacts match the incoming phone number,
                            // the CallerInfo object is totally blank here (i.e. no name
                            // *or* phoneNumber).  So we need to pass in cw.number as
                            // a fallback number.
                            mCallerInfo.updateGeoDescription(mQueryContext, cw.number);
                        }
                    }

                    // Use the number entered by the user for display.
                    if (!TextUtils.isEmpty(cw.number)) {
                        CountryDetector detector = (CountryDetector) mQueryContext.getSystemService(
                                Context.COUNTRY_DETECTOR);
                        mCallerInfo.phoneNumber = PhoneNumberUtils.formatNumber(cw.number,
                                mCallerInfo.normalizedNumber,
                                detector.detectCountry().getCountryIso());
                    }
                }

                if (DBG) Rlog.d(LOG_TAG, "constructing CallerInfo object for token: " + token);

                //notify that we can clean up the queue after this.
                CookieWrapper endMarker = new CookieWrapper();
                endMarker.event = EVENT_END_OF_QUEUE;
                startQuery(token, endMarker, null, null, null, null, null);
            }

            //notify the listener that the query is complete.
            if (cw.listener != null) {
                if (DBG) Rlog.d(LOG_TAG, "notifying listener: " + cw.listener.getClass().toString() +
                             " for token: " + token + mCallerInfo);
                cw.listener.onQueryComplete(token, cw.cookie, mCallerInfo);
            }
        }
    }

    /**
     * Private constructor for factory methods.
     */
    private CallerInfoAsyncQuery() {
    }


    /**
     * Factory method to start query with a Uri query spec
     */
    public static CallerInfoAsyncQuery startQuery(int token, Context context, Uri contactRef,
            OnQueryCompleteListener listener, Object cookie) {

        CallerInfoAsyncQuery c = new CallerInfoAsyncQuery();
        c.allocate(context, contactRef);

        if (DBG) Rlog.d(LOG_TAG, "starting query for URI: " + contactRef + " handler: " + c.toString());

        //create cookieWrapper, start query
        CookieWrapper cw = new CookieWrapper();
        cw.listener = listener;
        cw.cookie = cookie;
        cw.event = EVENT_NEW_QUERY;

        c.mHandler.startQuery(token, cw, contactRef, null, null, null, null);

        return c;
    }

    /**
     * Factory method to start the query based on a number.
     *
     * Note: if the number contains an "@" character we treat it
     * as a SIP address, and look it up directly in the Data table
     * rather than using the PhoneLookup table.
     * TODO: But eventually we should expose two separate methods, one for
     * numbers and one for SIP addresses, and then have
     * PhoneUtils.startGetCallerInfo() decide which one to call based on
     * the phone type of the incoming connection.
     */
    public static CallerInfoAsyncQuery startQuery(int token, Context context, String number,
            OnQueryCompleteListener listener, Object cookie) {
        if (DBG) {
            Rlog.d(LOG_TAG, "##### CallerInfoAsyncQuery startQuery()... #####");
            Rlog.d(LOG_TAG, "- number: " + /*number*/ "xxxxxxx");
            Rlog.d(LOG_TAG, "- cookie: " + cookie);
        }

        // Construct the URI object and query params, and start the query.

        Uri contactRef;
        String selection;
        String[] selectionArgs;

        if (PhoneNumberUtils.isUriNumber(number)) {
            // "number" is really a SIP address.
            if (DBG) Rlog.d(LOG_TAG, "  - Treating number as a SIP address: " + /*number*/ "xxxxxxx");

            // We look up SIP addresses directly in the Data table:
            contactRef = Data.CONTENT_URI;

            // Note Data.DATA1 and SipAddress.SIP_ADDRESS are equivalent.
            //
            // Also note we use "upper(data1)" in the WHERE clause, and
            // uppercase the incoming SIP address, in order to do a
            // case-insensitive match.
            //
            // TODO: need to confirm that the use of upper() doesn't
            // prevent us from using the index!  (Linear scan of the whole
            // contacts DB can be very slow.)
            //
            // TODO: May also need to normalize by adding "sip:" as a
            // prefix, if we start storing SIP addresses that way in the
            // database.

            selection = "upper(" + Data.DATA1 + ")=?"
                    + " AND "
                    + Data.MIMETYPE + "='" + SipAddress.CONTENT_ITEM_TYPE + "'";
            selectionArgs = new String[] { number.toUpperCase() };

        } else {
            // "number" is a regular phone number.  Use the PhoneLookup table:
            contactRef = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            selection = null;
            selectionArgs = null;
        }

        if (DBG) {
            Rlog.d(LOG_TAG, "==> contactRef: " + sanitizeUriToString(contactRef));
            Rlog.d(LOG_TAG, "==> selection: " + selection);
            if (selectionArgs != null) {
                for (int i = 0; i < selectionArgs.length; i++) {
                    Rlog.d(LOG_TAG, "==> selectionArgs[" + i + "]: " + selectionArgs[i]);
                }
            }
        }

        CallerInfoAsyncQuery c = new CallerInfoAsyncQuery();
        c.allocate(context, contactRef);

        //create cookieWrapper, start query
        CookieWrapper cw = new CookieWrapper();
        cw.listener = listener;
        cw.cookie = cookie;
        cw.number = number;

        // check to see if these are recognized numbers, and use shortcuts if we can.
        if (PhoneNumberUtils.isLocalEmergencyNumber(number, context)) {
            cw.event = EVENT_EMERGENCY_NUMBER;
        } else if (PhoneNumberUtils.isVoiceMailNumber(number)) {
            cw.event = EVENT_VOICEMAIL_NUMBER;
        } else {
            cw.event = EVENT_NEW_QUERY;
        }

        c.mHandler.startQuery(token,
                              cw,  // cookie
                              contactRef,  // uri
                              null,  // projection
                              selection,  // selection
                              selectionArgs,  // selectionArgs
                              null);  // orderBy
        return c;
    }

    /**
     * Method to add listeners to a currently running query
     */
    public void addQueryListener(int token, OnQueryCompleteListener listener, Object cookie) {

        if (DBG) Rlog.d(LOG_TAG, "adding listener to query: " + sanitizeUriToString(mHandler.mQueryUri) +
                " handler: " + mHandler.toString());

        //create cookieWrapper, add query request to end of queue.
        CookieWrapper cw = new CookieWrapper();
        cw.listener = listener;
        cw.cookie = cookie;
        cw.event = EVENT_ADD_LISTENER;

        mHandler.startQuery(token, cw, null, null, null, null, null);
    }

    /**
     * Method to create a new CallerInfoAsyncQueryHandler object, ensuring correct
     * state of context and uri.
     */
    private void allocate(Context context, Uri contactRef) {
        if ((context == null) || (contactRef == null)){
            throw new QueryPoolException("Bad context or query uri.");
        }
        mHandler = new CallerInfoAsyncQueryHandler(context);
        mHandler.mQueryContext = context;
        mHandler.mQueryUri = contactRef;
    }

    /**
     * Releases the relevant data.
     */
    private void release() {
        mHandler.mQueryContext = null;
        mHandler.mQueryUri = null;
        mHandler.mCallerInfo = null;
        mHandler = null;
    }

    private static String sanitizeUriToString(Uri uri) {
        if (uri != null) {
            String uriString = uri.toString();
            int indexOfLastSlash = uriString.lastIndexOf('/');
            if (indexOfLastSlash > 0) {
                return uriString.substring(0, indexOfLastSlash) + "/xxxxxxx";
            } else {
                return uriString;
            }
        } else {
            return "";
        }
    }
}
