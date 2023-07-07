/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.telephony.ims;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Binder;

import com.android.internal.telephony.ISipDialogStateCallback;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * This callback is used to notify listeners of SIP Dialog state changes.
 * @hide
 */
@SystemApi
public abstract class SipDialogStateCallback {

    private CallbackBinder mCallback;
    /**
    * @hide
    */
    public void attachExecutor(@NonNull @CallbackExecutor Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("SipDialogStateCallback Executor must be non-null");
        }
        mCallback = new CallbackBinder(this, executor);
    }

    private static class CallbackBinder extends ISipDialogStateCallback.Stub {
        private WeakReference<SipDialogStateCallback> mSipDialogStateCallbackWeakRef;
        private Executor mExecutor;

        private CallbackBinder(SipDialogStateCallback callback, Executor executor) {
            mSipDialogStateCallbackWeakRef = new WeakReference<SipDialogStateCallback>(callback);
            mExecutor = executor;
        }

        Executor getExecutor() {
            return mExecutor;
        }

        @Override
        public void onActiveSipDialogsChanged(List<SipDialogState> dialogs) {
            SipDialogStateCallback callback = mSipDialogStateCallbackWeakRef.get();
            if (callback == null || dialogs == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> callback.onActiveSipDialogsChanged(dialogs)));
        }
    }

    /**
     * The state of one or more SIP dialogs has changed.
     *
     * @param dialogs A List of SipDialogState objects representing the state of the active
     *               SIP Dialogs.
     */
    public abstract void onActiveSipDialogsChanged(@NonNull List<SipDialogState> dialogs);

    /**
     * An unexpected error has occurred and the Telephony process has crashed. This
     * has caused this callback to be deregistered. The callback must be re-registered
     * in order to continue listening to the IMS service state.
     */
    public abstract void onError();

    /**
     * The callback to notify the death of telephony process
     * @hide
     */
    public final void binderDied() {
        if (mCallback != null) {
            mCallback.getExecutor().execute(() -> onError());
        }
    }

    /**
     * Return the callback binder
     * @hide
     */
    public CallbackBinder getCallbackBinder() {
        return mCallback;
    }
}
