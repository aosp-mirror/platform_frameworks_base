/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.inputmethodservice;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.InputChannel;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.IMultiClientInputMethod;
import com.android.internal.inputmethod.IMultiClientInputMethodPrivilegedOperations;
import com.android.internal.inputmethod.MultiClientInputMethodPrivilegedOperations;

import java.lang.annotation.Retention;
import java.lang.ref.WeakReference;

final class MultiClientInputMethodServiceDelegateImpl {
    private static final String TAG = "MultiClientInputMethodServiceDelegateImpl";

    private final Object mLock = new Object();

    @Retention(SOURCE)
    @IntDef({InitializationPhase.INSTANTIATED,
            InitializationPhase.ON_BIND_CALLED,
            InitializationPhase.INITIALIZE_CALLED,
            InitializationPhase.ON_UNBIND_CALLED,
            InitializationPhase.ON_DESTROY_CALLED})
    private @interface InitializationPhase {
        int INSTANTIATED = 1;
        int ON_BIND_CALLED = 2;
        int INITIALIZE_CALLED = 3;
        int ON_UNBIND_CALLED  = 4;
        int ON_DESTROY_CALLED = 5;
    }

    @GuardedBy("mLock")
    @InitializationPhase
    private int mInitializationPhase;

    private final MultiClientInputMethodPrivilegedOperations mPrivOps =
            new MultiClientInputMethodPrivilegedOperations();

    private final MultiClientInputMethodServiceDelegate.ServiceCallback mServiceCallback;

    private final Context mContext;

    MultiClientInputMethodServiceDelegateImpl(Context context,
            MultiClientInputMethodServiceDelegate.ServiceCallback serviceCallback) {
        mInitializationPhase = InitializationPhase.INSTANTIATED;
        mContext = context;
        mServiceCallback = serviceCallback;
    }

    void onDestroy() {
        synchronized (mLock) {
            switch (mInitializationPhase) {
                case InitializationPhase.INSTANTIATED:
                case InitializationPhase.ON_UNBIND_CALLED:
                    mInitializationPhase = InitializationPhase.ON_DESTROY_CALLED;
                    break;
                default:
                    Log.e(TAG, "unexpected state=" + mInitializationPhase);
                    break;
            }
        }
    }

    private static final class ServiceImpl extends IMultiClientInputMethod.Stub {
        private final WeakReference<MultiClientInputMethodServiceDelegateImpl> mImpl;

        ServiceImpl(MultiClientInputMethodServiceDelegateImpl service) {
            mImpl = new WeakReference<>(service);
        }

        @Override
        public void initialize(IMultiClientInputMethodPrivilegedOperations privOps) {
            final MultiClientInputMethodServiceDelegateImpl service = mImpl.get();
            if (service == null) {
                return;
            }
            synchronized (service.mLock) {
                switch (service.mInitializationPhase) {
                    case InitializationPhase.ON_BIND_CALLED:
                        service.mPrivOps.set(privOps);
                        service.mInitializationPhase = InitializationPhase.INITIALIZE_CALLED;
                        service.mServiceCallback.initialized();
                        break;
                    default:
                        Log.e(TAG, "unexpected state=" + service.mInitializationPhase);
                        break;
                }
            }
        }

        @Override
        public void addClient(int clientId, int uid, int pid, int selfReportedDisplayId) {
            final MultiClientInputMethodServiceDelegateImpl service = mImpl.get();
            if (service == null) {
                return;
            }
            service.mServiceCallback.addClient(clientId, uid, pid, selfReportedDisplayId);
        }

        @Override
        public void removeClient(int clientId) {
            final MultiClientInputMethodServiceDelegateImpl service = mImpl.get();
            if (service == null) {
                return;
            }
            service.mServiceCallback.removeClient(clientId);
        }
    }

    IBinder onBind(Intent intent) {
        synchronized (mLock) {
            switch (mInitializationPhase) {
                case InitializationPhase.INSTANTIATED:
                    mInitializationPhase = InitializationPhase.ON_BIND_CALLED;
                    return new ServiceImpl(this);
                default:
                    Log.e(TAG, "unexpected state=" + mInitializationPhase);
                    break;
            }
        }
        return null;
    }

    boolean onUnbind(Intent intent) {
        synchronized (mLock) {
            switch (mInitializationPhase) {
                case InitializationPhase.ON_BIND_CALLED:
                case InitializationPhase.INITIALIZE_CALLED:
                    mInitializationPhase = InitializationPhase.ON_UNBIND_CALLED;
                    mPrivOps.dispose();
                    break;
                default:
                    Log.e(TAG, "unexpected state=" + mInitializationPhase);
                    break;
            }
        }
        return false;
    }

    IBinder createInputMethodWindowToken(int displayId) {
        return mPrivOps.createInputMethodWindowToken(displayId);
    }

    void acceptClient(int clientId,
            MultiClientInputMethodServiceDelegate.ClientCallback clientCallback,
            KeyEvent.DispatcherState dispatcherState, Looper looper) {
        final InputChannel[] channels = InputChannel.openInputChannelPair("MSIMS-session");
        final InputChannel writeChannel = channels[0];
        final InputChannel readChannel = channels[1];
        try {
            final MultiClientInputMethodClientCallbackAdaptor callbackAdaptor =
                    new MultiClientInputMethodClientCallbackAdaptor(clientCallback, looper,
                            dispatcherState, readChannel);
            mPrivOps.acceptClient(clientId, callbackAdaptor.createIInputMethodSession(),
                    callbackAdaptor.createIMultiClientInputMethodSession(), writeChannel);
        } finally {
            writeChannel.dispose();
        }
    }

    void reportImeWindowTarget(int clientId, int targetWindowHandle, IBinder imeWindowToken) {
        mPrivOps.reportImeWindowTarget(clientId, targetWindowHandle, imeWindowToken);
    }

    boolean isUidAllowedOnDisplay(int displayId, int uid) {
        return mPrivOps.isUidAllowedOnDisplay(displayId, uid);
    }

    void setActive(int clientId, boolean active) {
        mPrivOps.setActive(clientId, active);
    }
}
