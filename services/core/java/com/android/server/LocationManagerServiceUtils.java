/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.location.CallerIdentity;

import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * Shared utilities for LocationManagerService and GnssManager.
 */
public class LocationManagerServiceUtils {

    private static final String TAG = "LocManagerServiceUtils";
    private static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * Listener that can be linked to a binder.
     * @param <TListener> listener type
     */
    public static class LinkedListener<TListener> extends
            LinkedListenerBase {
        private final TListener mListener;
        private final Consumer<TListener> mBinderDeathCallback;

        public LinkedListener(
                @NonNull TListener listener,
                String listenerName,
                @NonNull CallerIdentity callerIdentity,
                @NonNull Consumer<TListener> binderDeathCallback) {
            super(callerIdentity, listenerName);
            mListener = listener;
            mBinderDeathCallback = binderDeathCallback;
        }

        @Override
        public void binderDied() {
            if (D) Log.d(TAG, "Remote " + mListenerName + " died.");
            mBinderDeathCallback.accept(mListener);
        }
    }

    /**
     * Skeleton class of listener that can be linked to a binder.
     */
    public abstract static class LinkedListenerBase implements IBinder.DeathRecipient {
        protected final CallerIdentity mCallerIdentity;
        protected final String mListenerName;

        LinkedListenerBase(
                @NonNull CallerIdentity callerIdentity, @NonNull String listenerName) {
            mCallerIdentity = callerIdentity;
            mListenerName = listenerName;
        }

        @Override
        public String toString() {
            return mListenerName + "[" + mCallerIdentity.mPackageName + "(" + mCallerIdentity.mPid
                    + ")]";
        }

        public CallerIdentity getCallerIdentity() {
            return mCallerIdentity;
        }

        public String getListenerName() {
            return mListenerName;
        }

        /**
         * Link listener (i.e. callback) to a binder, so that it will be called upon binder's death.
         *
         * @param binder that calls listener upon death
         * @return true if listener is successfully linked to binder, false otherwise
         */
        public boolean linkToListenerDeathNotificationLocked(
                IBinder binder) {
            try {
                binder.linkToDeath(this, 0 /* flags */);
                return true;
            } catch (RemoteException e) {
                // if the remote process registering the listener is already dead, just swallow the
                // exception and return
                Log.w(TAG, "Could not link " + mListenerName + " death callback.", e);
                return false;
            }
        }

        /**
         * Unlink death listener (i.e. callback) from binder.
         *
         * @param binder that calls listener upon death
         * @return true if binder is successfully unlinked from binder, false otherwise
         */
        public boolean unlinkFromListenerDeathNotificationLocked(
                IBinder binder) {
            try {
                binder.unlinkToDeath(this, 0 /* flags */);
                return true;
            } catch (NoSuchElementException e) {
                // if the death callback isn't connected (it should be...), log error,
                // swallow the exception and return
                Log.w(TAG, "Could not unlink " + mListenerName + " death callback.", e);
                return false;
            }
        }

    }

    /**
     * Convert boolean foreground into "foreground" or "background" string.
     *
     * @param foreground boolean indicating foreground
     * @return "foreground" string if true, false otherwise
     */
    public static String foregroundAsString(boolean foreground) {
        return foreground ? "foreground" : "background";
    }


    /**
     * Classifies importance level as foreground or not.
     *
     * @param importance level as int
     * @return boolean indicating if importance level is foreground or greater
     */
    public static boolean isImportanceForeground(int importance) {
        return importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
    }

    /**
     * Get package importance level.
     *
     * @param packageName package name
     * @return package importance level as int
     */
    public static int getPackageImportance(String packageName, Context context) {
        return ((ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE)).getPackageImportance(packageName);
    }
}
