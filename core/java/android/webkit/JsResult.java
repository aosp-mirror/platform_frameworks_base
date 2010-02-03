/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.webkit;


public class JsResult {
    // This prevents a user from interacting with the result before WebCore is
    // ready to handle it.
    private boolean mReady;
    // Tells us if the user tried to confirm or cancel the result before WebCore
    // is ready.
    private boolean mTriedToNotifyBeforeReady;
    // This is a basic result of a confirm or prompt dialog.
    protected boolean mResult;
    /**
     *  This is the caller of the prompt and is the object that is waiting.
     *  @hide
     */
    protected final CallbackProxy mProxy;
    // This is the default value of the result.
    private final boolean mDefaultValue;

    /**
     * Handle the result if the user cancelled the dialog.
     */
    public final void cancel() {
        mResult = false;
        wakeUp();
    }

    /**
     * Handle a confirmation response from the user.
     */
    public final void confirm() {
        mResult = true;
        wakeUp();
    }

    /*package*/ JsResult(CallbackProxy proxy, boolean defaultVal) {
        mProxy = proxy;
        mDefaultValue = defaultVal;
    }

    /*package*/ final boolean getResult() {
        return mResult;
    }

    /*package*/ final void setReady() {
        mReady = true;
        if (mTriedToNotifyBeforeReady) {
            wakeUp();
        }
    }

    /*package*/ void handleDefault() {
        setReady();
        mResult = mDefaultValue;
        wakeUp();
    }

    /* Wake up the WebCore thread. */
    protected final void wakeUp() {
        if (mReady) {
            synchronized (mProxy) {
                mProxy.notify();
            }
        } else {
            mTriedToNotifyBeforeReady = true;
        }
    }
}
