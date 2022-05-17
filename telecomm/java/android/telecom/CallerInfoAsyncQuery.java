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

package android.telecom;

import android.app.ActivityManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract.PhoneLookup;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

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
    private static final int EVENT_GET_GEO_DESCRIPTION = 6;

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
        @UnsupportedAppUsage
        private CookieWrapper() {
        }

        public OnQueryCompleteListener listener;
        public Object cookie;
        public int event;
        public String number;
        public String geoDescription;

        public int subId;
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
     * @return {@link ContentResolver} for the "current" user.
     */
    static ContentResolver getCurrentProfileContentResolver(Context context) {

        if (DBG) Log.d(LOG_TAG, "Trying to get current content resolver...");

        final int currentUser = ActivityManager.getCurrentUser();
        final int myUser = UserManager.get(context).getProcessUserId();

        if (DBG) Log.d(LOG_TAG, "myUser=" + myUser + "currentUser=" + currentUser);

        if (myUser != currentUser) {
            final Context otherContext;
            try {
                otherContext = context.createPackageContextAsUser(context.getPackageName(),
                        /* flags =*/ 0, UserHandle.of(currentUser));
                return otherContext.getContentResolver();
            } catch (NameNotFoundException e) {
                Log.e(LOG_TAG, e, "Can't find self package");
                // Fall back to the primary user.
            }
        }
        return context.getContentResolver();
    }

    /**
     * Our own implementation of the AsyncQueryHandler.
     */
    private class CallerInfoAsyncQueryHandler extends AsyncQueryHandler {

        /*
         * The information relevant to each CallerInfo query.  Each query may have multiple
         * listeners, so each AsyncCursorInfo is associated with 2 or more CookieWrapper
         * objects in the queue (one with a new query event, and one with a end event, with
         * 0 or more additional listeners in between).
         */

        /**
         * Context passed by the caller.
         *
         * NOTE: The actual context we use for query may *not* be this context; since we query
         * against the "current" contacts provider.  In the constructor we pass the "current"
         * context resolver (obtained via {@link #getCurrentProfileContentResolver) and pass it
         * to the super class.
         */
        private Context mContext;
        private Uri mQueryUri;
        private CallerInfo mCallerInfo;
        private List<Runnable> mPendingListenerCallbacks = new ArrayList<>();

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
                    Log.i(LOG_TAG, "Unexpected command (CookieWrapper is null): " + msg.what +
                            " ignored by CallerInfoWorkerHandler, passing onto parent.");

                    super.handleMessage(msg);
                } else {

                    Log.d(LOG_TAG, "Processing event: " + cw.event + " token (arg1): " + msg.arg1 +
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
                        case EVENT_GET_GEO_DESCRIPTION:
                            handleGeoDescription(msg);
                            break;
                        default:
                    }
                }
            }

            private void handleGeoDescription(Message msg) {
                WorkerArgs args = (WorkerArgs) msg.obj;
                CookieWrapper cw = (CookieWrapper) args.cookie;
                if (!TextUtils.isEmpty(cw.number) && cw.cookie != null && mContext != null) {
                    final long startTimeMillis = SystemClock.elapsedRealtime();
                    cw.geoDescription = CallerInfo.getGeoDescription(mContext, cw.number);
                    final long duration = SystemClock.elapsedRealtime() - startTimeMillis;
                    if (duration > 500) {
                        if (DBG) Log.d(LOG_TAG, "[handleGeoDescription]" +
                                "Spends long time to retrieve Geo description: " + duration);
                    }
                }
                Message reply = args.handler.obtainMessage(msg.what);
                reply.obj = args;
                reply.arg1 = msg.arg1;
                reply.sendToTarget();
            }
        }


        /**
         * Asynchronous query handler class for the contact / callerinfo object.
         */
        private CallerInfoAsyncQueryHandler(Context context) {
            super(getCurrentProfileContentResolver(context));
            mContext = context;
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
            Log.d(LOG_TAG, "##### onQueryComplete() #####   query complete for token: " + token);

            //get the cookie and notify the listener.
            CookieWrapper cw = (CookieWrapper) cookie;
            if (cw == null) {
                // Normally, this should never be the case for calls originating
                // from within this code.
                // However, if there is any code that calls this method, we should
                // check the parameters to make sure they're viable.
                Log.i(LOG_TAG, "Cookie is null, ignoring onQueryComplete() request.");
                if (cursor != null) {
                    cursor.close();
                }
                return;
            }

            if (cw.event == EVENT_END_OF_QUEUE) {
                for (Runnable r : mPendingListenerCallbacks) {
                    r.run();
                }
                mPendingListenerCallbacks.clear();

                release();
                if (cursor != null) {
                    cursor.close();
                }
                return;
            }

            // If the cw.event == EVENT_GET_GEO_DESCRIPTION, means it would not be the 1st
            // time entering the onQueryComplete(), mCallerInfo should not be null.
            if (cw.event == EVENT_GET_GEO_DESCRIPTION) {
                if (mCallerInfo != null) {
                    mCallerInfo.geoDescription = cw.geoDescription;
                }
                // notify that we can clean up the queue after this.
                CookieWrapper endMarker = new CookieWrapper();
                endMarker.event = EVENT_END_OF_QUEUE;
                startQuery(token, endMarker, null, null, null, null, null);
            }

            // check the token and if needed, create the callerinfo object.
            if (mCallerInfo == null) {
                if ((mContext == null) || (mQueryUri == null)) {
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
                    mCallerInfo = new CallerInfo().markAsEmergency(mContext);
                } else if (cw.event == EVENT_VOICEMAIL_NUMBER) {
                    mCallerInfo = new CallerInfo().markAsVoiceMail(mContext, cw.subId);
                } else {
                    mCallerInfo = CallerInfo.getCallerInfo(mContext, mQueryUri, cursor);
                    if (DBG) Log.d(LOG_TAG, "==> Got mCallerInfo: " + mCallerInfo);

                    CallerInfo newCallerInfo = CallerInfo.doSecondaryLookupIfNecessary(
                            mContext, cw.number, mCallerInfo);
                    if (newCallerInfo != mCallerInfo) {
                        mCallerInfo = newCallerInfo;
                        if (DBG) Log.d(LOG_TAG, "#####async contact look up with numeric username"
                                + mCallerInfo);
                    }

                    // Use the number entered by the user for display.
                    if (!TextUtils.isEmpty(cw.number)) {
                        mCallerInfo.setPhoneNumber(PhoneNumberUtils.formatNumber(cw.number,
                                mCallerInfo.normalizedNumber,
                                CallerInfo.getCurrentCountryIso(mContext)));
                    }

                    // This condition refer to the google default code for geo.
                    // If the number exists in Contacts, the CallCard would never show
                    // the geo description, so it would be unnecessary to query it.
                    if (ENABLE_UNKNOWN_NUMBER_GEO_DESCRIPTION) {
                        if (TextUtils.isEmpty(mCallerInfo.getName())) {
                            if (DBG) Log.d(LOG_TAG, "start querying geo description");
                            cw.event = EVENT_GET_GEO_DESCRIPTION;
                            startQuery(token, cw, null, null, null, null, null);
                            return;
                        }
                    }
                }

                if (DBG) Log.d(LOG_TAG, "constructing CallerInfo object for token: " + token);

                //notify that we can clean up the queue after this.
                CookieWrapper endMarker = new CookieWrapper();
                endMarker.event = EVENT_END_OF_QUEUE;
                startQuery(token, endMarker, null, null, null, null, null);
            }

            //notify the listener that the query is complete.
            if (cw.listener != null) {
                mPendingListenerCallbacks.add(new Runnable() {
                    @Override
                    public void run() {
                        if (DBG) Log.d(LOG_TAG, "notifying listener: "
                                + cw.listener.getClass().toString() + " for token: " + token
                                + mCallerInfo);
                        cw.listener.onQueryComplete(token, cw.cookie, mCallerInfo);
                    }
                });
            } else {
                Log.w(LOG_TAG, "There is no listener to notify for this query.");
            }

            if (cursor != null) {
               cursor.close();
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

        if (DBG) Log.d(LOG_TAG, "starting query for URI: " + contactRef + " handler: " + c.toString());

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

        int subId = SubscriptionManager.getDefaultSubscriptionId();
        return startQuery(token, context, number, listener, cookie, subId);
    }

    /**
     * Factory method to start the query based on a number with specific subscription.
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
            OnQueryCompleteListener listener, Object cookie, int subId) {

        if (DBG) {
            Log.d(LOG_TAG, "##### CallerInfoAsyncQuery startQuery()... #####");
            Log.d(LOG_TAG, "- number: " + /*number*/ "xxxxxxx");
            Log.d(LOG_TAG, "- cookie: " + cookie);
        }

        // Construct the URI object and query params, and start the query.

        final Uri contactRef = PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI.buildUpon()
                .appendPath(number)
                .appendQueryParameter(PhoneLookup.QUERY_PARAMETER_SIP_ADDRESS,
                        String.valueOf(PhoneNumberUtils.isUriNumber(number)))
                .build();

        if (DBG) {
            Log.d(LOG_TAG, "==> contactRef: " + sanitizeUriToString(contactRef));
        }

        CallerInfoAsyncQuery c = new CallerInfoAsyncQuery();
        c.allocate(context, contactRef);

        //create cookieWrapper, start query
        CookieWrapper cw = new CookieWrapper();
        cw.listener = listener;
        cw.cookie = cookie;
        cw.number = number;
        cw.subId = subId;

        // check to see if these are recognized numbers, and use shortcuts if we can.
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        boolean isEmergencyNumber = false;
        try {
            isEmergencyNumber = tm.isEmergencyNumber(number);
        } catch (IllegalStateException ise) {
            // Ignore the exception that Telephony is not up. Use PhoneNumberUtils API now.
            // Ideally the PhoneNumberUtils API needs to be removed once the
            // telphony service not up issue can be fixed (b/187412989)
            isEmergencyNumber = PhoneNumberUtils.isLocalEmergencyNumber(context, number);
        }
        if (isEmergencyNumber) {
            cw.event = EVENT_EMERGENCY_NUMBER;
        } else if (PhoneNumberUtils.isVoiceMailNumber(context, subId, number)) {
            cw.event = EVENT_VOICEMAIL_NUMBER;
        } else {
            cw.event = EVENT_NEW_QUERY;
        }

        c.mHandler.startQuery(token,
                              cw,  // cookie
                              contactRef,  // uri
                              null,  // projection
                              null,  // selection
                              null,  // selectionArgs
                              null);  // orderBy
        return c;
    }

    /**
     * Method to add listeners to a currently running query
     */
    public void addQueryListener(int token, OnQueryCompleteListener listener, Object cookie) {

        if (DBG) Log.d(LOG_TAG, "adding listener to query: "
                + sanitizeUriToString(mHandler.mQueryUri) + " handler: " + mHandler.toString());

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
        mHandler.mQueryUri = contactRef;
    }

    /**
     * Releases the relevant data.
     */
    @UnsupportedAppUsage
    private void release() {
        mHandler.mContext = null;
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
