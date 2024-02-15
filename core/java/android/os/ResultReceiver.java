/*
 * Copyright (C) 2009 The Android Open Source Project
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

import com.android.internal.os.IResultReceiver;

/**
 * Generic interface for receiving a callback result from someone.  Use this
 * by creating a subclass and implement {@link #onReceiveResult}, which you can
 * then pass to others and send through IPC, and receive results they
 * supply with {@link #send}.
 *
 * <p>Note: the implementation underneath is just a simple wrapper around
 * a {@link Binder} that is used to perform the communication.  This means
 * semantically you should treat it as such: this class does not impact process
 * lifecycle management (you must be using some higher-level component to tell
 * the system that your process needs to continue running), the connection will
 * break if your process goes away for any reason, etc.</p>
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class ResultReceiver implements Parcelable {
    final boolean mLocal;
    final Handler mHandler;
    
    IResultReceiver mReceiver;
    
    class MyRunnable implements Runnable {
        final int mResultCode;
        final Bundle mResultData;
        
        MyRunnable(int resultCode, Bundle resultData) {
            mResultCode = resultCode;
            mResultData = resultData;
        }
        
        public void run() {
            onReceiveResult(mResultCode, mResultData);
        }
    }
    
    class MyResultReceiver extends IResultReceiver.Stub {
        public void send(int resultCode, Bundle resultData) {
            if (mHandler != null) {
                mHandler.post(new MyRunnable(resultCode, resultData));
            } else {
                onReceiveResult(resultCode, resultData);
            }
        }
    }
    
    /**
     * Create a new ResultReceive to receive results.  Your
     * {@link #onReceiveResult} method will be called from the thread running
     * <var>handler</var> if given, or from an arbitrary thread if null.
     */
    public ResultReceiver(Handler handler) {
        mLocal = true;
        mHandler = handler;
    }
    
    /**
     * Deliver a result to this receiver.  Will call {@link #onReceiveResult},
     * always asynchronously if the receiver has supplied a Handler in which
     * to dispatch the result.
     * @param resultCode Arbitrary result code to deliver, as defined by you.
     * @param resultData Any additional data provided by you.
     */
    public void send(int resultCode, Bundle resultData) {
        if (mLocal) {
            if (mHandler != null) {
                mHandler.post(new MyRunnable(resultCode, resultData));
            } else {
                onReceiveResult(resultCode, resultData);
            }
            return;
        }
        
        if (mReceiver != null) {
            try {
                mReceiver.send(resultCode, resultData);
            } catch (RemoteException e) {
            }
        }
    }
    
    /**
     * Override to receive results delivered to this object.
     * 
     * @param resultCode Arbitrary result code delivered by the sender, as
     * defined by the sender.
     * @param resultData Any additional data provided by the sender.
     */
    protected void onReceiveResult(int resultCode, Bundle resultData) {
    }
    
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        synchronized (this) {
            if (mReceiver == null) {
                mReceiver = new MyResultReceiver();
            }
            out.writeStrongBinder(mReceiver.asBinder());
        }
    }

    ResultReceiver(Parcel in) {
        mLocal = false;
        mHandler = null;
        mReceiver = IResultReceiver.Stub.asInterface(in.readStrongBinder());
    }
    
    public static final @android.annotation.NonNull Parcelable.Creator<ResultReceiver> CREATOR
            = new Parcelable.Creator<ResultReceiver>() {
        public ResultReceiver createFromParcel(Parcel in) {
            return new ResultReceiver(in);
        }
        public ResultReceiver[] newArray(int size) {
            return new ResultReceiver[size];
        }
    };
}
