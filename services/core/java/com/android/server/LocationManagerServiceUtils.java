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
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.server.location.CallerIdentity;

import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * Shared utilities for LocationManagerService and GnssManager.
 */
public class LocationManagerServiceUtils {

    /**
     * Listener that can be linked to a binder.
     * @param <TListener> listener type
     * @param <TRequest> request type
     */
    public static class LinkedListener<TRequest, TListener> extends
            LinkedListenerBase {
        @Nullable protected final TRequest mRequest;
        private final TListener mListener;
        private final Consumer<TListener> mBinderDeathCallback;

        public LinkedListener(
                @Nullable TRequest request,
                @NonNull TListener listener,
                @NonNull CallerIdentity callerIdentity,
                @NonNull Consumer<TListener> binderDeathCallback) {
            super(callerIdentity);
            mListener = listener;
            mRequest = request;
            mBinderDeathCallback = binderDeathCallback;
        }

        @Nullable
        public TRequest getRequest() {
            return mRequest;
        }

        @Override
        public void binderDied() {
            mBinderDeathCallback.accept(mListener);
        }
    }

    /**
     * Skeleton class of listener that can be linked to a binder.
     */
    public abstract static class LinkedListenerBase implements IBinder.DeathRecipient {
        protected final CallerIdentity mCallerIdentity;

        LinkedListenerBase(@NonNull CallerIdentity callerIdentity) {
            mCallerIdentity = callerIdentity;
        }

        @Override
        public String toString() {
            return mCallerIdentity.toString();
        }

        public CallerIdentity getCallerIdentity() {
            return mCallerIdentity;
        }

        /**
         * Link listener (i.e. callback) to a binder, so that it will be called upon binder's death.
         */
        public boolean linkToListenerDeathNotificationLocked(IBinder binder) {
            try {
                binder.linkToDeath(this, 0 /* flags */);
                return true;
            } catch (RemoteException e) {
                return false;
            }
        }

        /**
         * Unlink death listener (i.e. callback) from binder.
         */
        public void unlinkFromListenerDeathNotificationLocked(IBinder binder) {
            try {
                binder.unlinkToDeath(this, 0 /* flags */);
            } catch (NoSuchElementException e) {
                // ignore
            }
        }
    }
}
