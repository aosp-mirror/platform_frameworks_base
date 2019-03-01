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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.HashMap;

/**
 * Safe identifier for a window.  This currently allows you to retrieve and observe
 * the input focus state of the window.  Most applications will
 * not use this, instead relying on the simpler (and more efficient) methods available
 * on {@link View}.  This classes is useful when window input interactions need to be
 * done across processes: the class itself is a Parcelable that can be passed to other
 * processes for them to interact with your window, and it provides a limited safe API
 * that doesn't allow the other process to negatively harm your window.
 */
public class WindowId implements Parcelable {
    @NonNull
    private final IWindowId mToken;

    /**
     * Subclass for observing changes to the focus state of an {@link WindowId}.
     * You should use the same instance of this class for observing multiple
     * {@link WindowId} objects, since this class is fairly heavy-weight -- the
     * base class includes all of the mechanisms for connecting to and receiving updates
     * from the window.
     */
    public static abstract class FocusObserver {
        final IWindowFocusObserver.Stub mIObserver = new IWindowFocusObserver.Stub() {

            @Override
            public void focusGained(IBinder inputToken) {
                WindowId token;
                synchronized (mRegistrations) {
                    token = mRegistrations.get(inputToken);
                }
                if (mHandler != null) {
                    mHandler.sendMessage(mHandler.obtainMessage(1, token));
                } else {
                    onFocusGained(token);
                }
            }

            @Override
            public void focusLost(IBinder inputToken) {
                WindowId token;
                synchronized (mRegistrations) {
                    token = mRegistrations.get(inputToken);
                }
                if (mHandler != null) {
                    mHandler.sendMessage(mHandler.obtainMessage(2, token));
                } else {
                    onFocusLost(token);
                }
            }
        };

        final HashMap<IBinder, WindowId> mRegistrations = new HashMap<>();

        class H extends Handler {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case 1:
                        onFocusGained((WindowId)msg.obj);
                        break;
                    case 2:
                        onFocusLost((WindowId)msg.obj);
                        break;
                    default:
                        super.handleMessage(msg);
                }
            }
        }

        final Handler mHandler;

        /**
         * Construct a new observer.  This observer will be configured so that all
         * of its callbacks are dispatched on the current calling thread.
         */
        public FocusObserver() {
            mHandler = new H();
        }

        /**
         * Called when one of the monitored windows gains input focus.
         */
        public abstract void onFocusGained(WindowId token);

        /**
         * Called when one of the monitored windows loses input focus.
         */
        public abstract void onFocusLost(WindowId token);
    }

    /**
     * Retrieve the current focus state of the associated window.
     */
    public boolean isFocused() {
        try {
            return mToken.isFocused();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Start monitoring for changes in the focus state of the window.
     */
    public void registerFocusObserver(FocusObserver observer) {
        synchronized (observer.mRegistrations) {
            if (observer.mRegistrations.containsKey(mToken.asBinder())) {
                throw new IllegalStateException(
                        "Focus observer already registered with input token");
            }
            observer.mRegistrations.put(mToken.asBinder(), this);
            try {
                mToken.registerFocusObserver(observer.mIObserver);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Stop monitoring changes in the focus state of the window.
     */
    public void unregisterFocusObserver(FocusObserver observer) {
        synchronized (observer.mRegistrations) {
            if (observer.mRegistrations.remove(mToken.asBinder()) == null) {
                throw new IllegalStateException("Focus observer not registered with input token");
            }
            try {
                mToken.unregisterFocusObserver(observer.mIObserver);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Comparison operator on two IntentSender objects, such that true
     * is returned then they both represent the same operation from the
     * same package.
     */
    @Override
    public boolean equals(@Nullable Object otherObj) {
        if (otherObj instanceof WindowId) {
            return mToken.asBinder().equals(((WindowId) otherObj).mToken.asBinder());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mToken.asBinder().hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("IntentSender{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(": ");
        sb.append(mToken.asBinder());
        sb.append('}');
        return sb.toString();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(mToken.asBinder());
    }

    public static final @android.annotation.NonNull Parcelable.Creator<WindowId> CREATOR = new Parcelable.Creator<WindowId>() {
        @Override
        public WindowId createFromParcel(Parcel in) {
            IBinder target = in.readStrongBinder();
            return target != null ? new WindowId(target) : null;
        }

        @Override
        public WindowId[] newArray(int size) {
            return new WindowId[size];
        }
    };

    /** @hide */
    @NonNull
    public IWindowId getTarget() {
        return mToken;
    }

    /** @hide */
    public WindowId(@NonNull IWindowId target) {
        mToken = target;
    }

    /** @hide */
    public WindowId(@NonNull IBinder target) {
        mToken = IWindowId.Stub.asInterface(target);
    }
}
