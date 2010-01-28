/*
 * Copyright (C) 2010 The Android Open Source Project
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

/**
 * TODO: Make this a public API?  Let's see how it goes with a few use
 * cases first.
 * @hide
 */
public abstract class RemoteCallback implements Parcelable {
    final Handler mHandler;
    final IRemoteCallback mTarget;
    
    class DeliverResult implements Runnable {
        final Bundle mResult;
        
        DeliverResult(Bundle result) {
            mResult = result;
        }
        
        public void run() {
            onResult(mResult);
        }
    }
    
    class LocalCallback extends IRemoteCallback.Stub {
        public void sendResult(Bundle bundle) {
            mHandler.post(new DeliverResult(bundle));
        }
    }
    
    static class RemoteCallbackProxy extends RemoteCallback {
        RemoteCallbackProxy(IRemoteCallback target) {
            super(target);
        }
        
        protected void onResult(Bundle bundle) {
        }
    }
    
    public RemoteCallback(Handler handler) {
        mHandler = handler;
        mTarget = new LocalCallback();
    }
    
     RemoteCallback(IRemoteCallback target) {
        mHandler = null;
        mTarget = target;
    }
    
    public void sendResult(Bundle bundle) throws RemoteException {
        mTarget.sendResult(bundle);
    }
    
    protected abstract void onResult(Bundle bundle);
    
    public boolean equals(Object otherObj) {
        if (otherObj == null) {
            return false;
        }
        try {
            return mTarget.asBinder().equals(((RemoteCallback)otherObj)
                    .mTarget.asBinder());
        } catch (ClassCastException e) {
        }
        return false;
    }
    
    public int hashCode() {
        return mTarget.asBinder().hashCode();
    }
    
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(mTarget.asBinder());
    }

    public static final Parcelable.Creator<RemoteCallback> CREATOR
            = new Parcelable.Creator<RemoteCallback>() {
        public RemoteCallback createFromParcel(Parcel in) {
            IBinder target = in.readStrongBinder();
            return target != null ? new RemoteCallbackProxy(
                    IRemoteCallback.Stub.asInterface(target)) : null;
        }

        public RemoteCallback[] newArray(int size) {
            return new RemoteCallback[size];
        }
    };
}
