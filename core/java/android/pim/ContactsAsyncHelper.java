/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.pim;

import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.Connection;

import android.content.ContentUris;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.provider.ContactsContract.Contacts;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import java.io.InputStream;

/**
 * Helper class for async access of images.
 */
public class ContactsAsyncHelper extends Handler {

    private static final boolean DBG = false;
    private static final String LOG_TAG = "ContactsAsyncHelper";

    /**
     * Interface for a WorkerHandler result return.
     */
    public interface OnImageLoadCompleteListener {
        /**
         * Called when the image load is complete.
         *
         * @param imagePresent true if an image was found
         */
        public void onImageLoadComplete(int token, Object cookie, ImageView iView,
                boolean imagePresent);
    }

    // constants
    private static final int EVENT_LOAD_IMAGE = 1;
    private static final int DEFAULT_TOKEN = -1;

    // static objects
    private static Handler sThreadHandler;
    private static ContactsAsyncHelper sInstance;

    static {
        sInstance = new ContactsAsyncHelper();
    }

    private static final class WorkerArgs {
        public Context context;
        public ImageView view;
        public Uri uri;
        public int defaultResource;
        public Object result;
        public Object cookie;
        public OnImageLoadCompleteListener listener;
        public CallerInfo info;
    }

    /**
     * public inner class to help out the ContactsAsyncHelper callers
     * with tracking the state of the CallerInfo Queries and image
     * loading.
     *
     * Logic contained herein is used to remove the race conditions
     * that exist as the CallerInfo queries run and mix with the image
     * loads, which then mix with the Phone state changes.
     */
    public static class ImageTracker {

        // Image display states
        public static final int DISPLAY_UNDEFINED = 0;
        public static final int DISPLAY_IMAGE = -1;
        public static final int DISPLAY_DEFAULT = -2;

        // State of the image on the imageview.
        private CallerInfo mCurrentCallerInfo;
        private int displayMode;

        public ImageTracker() {
            mCurrentCallerInfo = null;
            displayMode = DISPLAY_UNDEFINED;
        }

        /**
         * Used to see if the requested call / connection has a
         * different caller attached to it than the one we currently
         * have in the CallCard.
         */
        public boolean isDifferentImageRequest(CallerInfo ci) {
            // note, since the connections are around for the lifetime of the
            // call, and the CallerInfo-related items as well, we can
            // definitely use a simple != comparison.
            return (mCurrentCallerInfo != ci);
        }

        public boolean isDifferentImageRequest(Connection connection) {
            // if the connection does not exist, see if the
            // mCurrentCallerInfo is also null to match.
            if (connection == null) {
                if (DBG) Log.d(LOG_TAG, "isDifferentImageRequest: connection is null");
                return (mCurrentCallerInfo != null);
            }
            Object o = connection.getUserData();

            // if the call does NOT have a callerInfo attached
            // then it is ok to query.
            boolean runQuery = true;
            if (o instanceof CallerInfo) {
                runQuery = isDifferentImageRequest((CallerInfo) o);
            }
            return runQuery;
        }

        /**
         * Simple setter for the CallerInfo object.
         */
        public void setPhotoRequest(CallerInfo ci) {
            mCurrentCallerInfo = ci;
        }

        /**
         * Convenience method used to retrieve the URI
         * representing the Photo file recorded in the attached
         * CallerInfo Object.
         */
        public Uri getPhotoUri() {
            if (mCurrentCallerInfo != null) {
                return ContentUris.withAppendedId(Contacts.CONTENT_URI,
                        mCurrentCallerInfo.person_id);
            }
            return null;
        }

        /**
         * Simple setter for the Photo state.
         */
        public void setPhotoState(int state) {
            displayMode = state;
        }

        /**
         * Simple getter for the Photo state.
         */
        public int getPhotoState() {
            return displayMode;
        }
    }

    /**
     * Thread worker class that handles the task of opening the stream and loading
     * the images.
     */
    private class WorkerHandler extends Handler {
        public WorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            WorkerArgs args = (WorkerArgs) msg.obj;

            switch (msg.arg1) {
                case EVENT_LOAD_IMAGE:
                    InputStream inputStream = null;
                    try {
                        inputStream = Contacts.openContactPhotoInputStream(
                                args.context.getContentResolver(), args.uri, true);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Error opening photo input stream", e);
                    }

                    if (inputStream != null) {
                        args.result = Drawable.createFromStream(inputStream, args.uri.toString());

                        if (DBG) Log.d(LOG_TAG, "Loading image: " + msg.arg1 +
                                " token: " + msg.what + " image URI: " + args.uri);
                    } else {
                        args.result = null;
                        if (DBG) Log.d(LOG_TAG, "Problem with image: " + msg.arg1 +
                                " token: " + msg.what + " image URI: " + args.uri +
                                ", using default image.");
                    }
                    break;
                default:
            }

            // send the reply to the enclosing class.
            Message reply = ContactsAsyncHelper.this.obtainMessage(msg.what);
            reply.arg1 = msg.arg1;
            reply.obj = msg.obj;
            reply.sendToTarget();
        }
    }

    /**
     * Private constructor for static class
     */
    private ContactsAsyncHelper() {
        HandlerThread thread = new HandlerThread("ContactsAsyncWorker");
        thread.start();
        sThreadHandler = new WorkerHandler(thread.getLooper());
    }

    /**
     * Convenience method for calls that do not want to deal with listeners and tokens.
     */
    public static final void updateImageViewWithContactPhotoAsync(Context context,
            ImageView imageView, Uri person, int placeholderImageResource) {
        // Added additional Cookie field in the callee.
        updateImageViewWithContactPhotoAsync (null, DEFAULT_TOKEN, null, null, context,
                imageView, person, placeholderImageResource);
    }

    /**
     * Convenience method for calls that do not want to deal with listeners and tokens, but have
     * a CallerInfo object to cache the image to.
     */
    public static final void updateImageViewWithContactPhotoAsync(CallerInfo info, Context context,
            ImageView imageView, Uri person, int placeholderImageResource) {
        // Added additional Cookie field in the callee.
        updateImageViewWithContactPhotoAsync (info, DEFAULT_TOKEN, null, null, context,
                imageView, person, placeholderImageResource);
    }


    /**
     * Start an image load, attach the result to the specified CallerInfo object.
     * Note, when the query is started, we make the ImageView INVISIBLE if the
     * placeholderImageResource value is -1.  When we're given a valid (!= -1)
     * placeholderImageResource value, we make sure the image is visible.
     */
    public static final void updateImageViewWithContactPhotoAsync(CallerInfo info, int token,
            OnImageLoadCompleteListener listener, Object cookie, Context context,
            ImageView imageView, Uri person, int placeholderImageResource) {

        // in case the source caller info is null, the URI will be null as well.
        // just update using the placeholder image in this case.
        if (person == null) {
            if (DBG) Log.d(LOG_TAG, "target image is null, just display placeholder.");
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageResource(placeholderImageResource);
            return;
        }

        // Added additional Cookie field in the callee to handle arguments
        // sent to the callback function.

        // setup arguments
        WorkerArgs args = new WorkerArgs();
        args.cookie = cookie;
        args.context = context;
        args.view = imageView;
        args.uri = person;
        args.defaultResource = placeholderImageResource;
        args.listener = listener;
        args.info = info;

        // setup message arguments
        Message msg = sThreadHandler.obtainMessage(token);
        msg.arg1 = EVENT_LOAD_IMAGE;
        msg.obj = args;

        if (DBG) Log.d(LOG_TAG, "Begin loading image: " + args.uri +
                ", displaying default image for now.");

        // set the default image first, when the query is complete, we will
        // replace the image with the correct one.
        if (placeholderImageResource != -1) {
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageResource(placeholderImageResource);
        } else {
            imageView.setVisibility(View.INVISIBLE);
        }

        // notify the thread to begin working
        sThreadHandler.sendMessage(msg);
    }

    /**
     * Called when loading is done.
     */
    @Override
    public void handleMessage(Message msg) {
        WorkerArgs args = (WorkerArgs) msg.obj;
        switch (msg.arg1) {
            case EVENT_LOAD_IMAGE:
                boolean imagePresent = false;

                // if the image has been loaded then display it, otherwise set default.
                // in either case, make sure the image is visible.
                if (args.result != null) {
                    args.view.setVisibility(View.VISIBLE);
                    args.view.setImageDrawable((Drawable) args.result);
                    // make sure the cached photo data is updated.
                    if (args.info != null) {
                        args.info.cachedPhoto = (Drawable) args.result;
                    }
                    imagePresent = true;
                } else if (args.defaultResource != -1) {
                    args.view.setVisibility(View.VISIBLE);
                    args.view.setImageResource(args.defaultResource);
                }

                // Note that the data is cached.
                if (args.info != null) {
                    args.info.isCachedPhotoCurrent = true;
                }

                // notify the listener if it is there.
                if (args.listener != null) {
                    if (DBG) Log.d(LOG_TAG, "Notifying listener: " + args.listener.toString() +
                            " image: " + args.uri + " completed");
                    args.listener.onImageLoadComplete(msg.what, args.cookie, args.view,
                            imagePresent);
                }
                break;
            default:
        }
    }
}
