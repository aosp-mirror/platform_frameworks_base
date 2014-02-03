/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app.maintenance;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

/**
 * Idle maintenance API.  Full docs TBW (to be written).
 */
public abstract class IdleService extends Service {
    private static final String TAG = "IdleService";

    static final int MSG_START = 1;
    static final int MSG_STOP = 2;
    static final int MSG_FINISH = 3;

    IdleHandler mHandler;
    IIdleCallback mCallbackBinder;
    int mToken;
    final Object mHandlerLock = new Object();

    void ensureHandler() {
        synchronized (mHandlerLock) {
            if (mHandler == null) {
                mHandler = new IdleHandler(getMainLooper());
            }
        }
    }

    /**
     * TBW: the idle service should supply an intent-filter handling this intent
     * <p>
     * <p class="note">The application must also protect the idle service with the
     * {@code "android.permission.BIND_IDLE_SERVICE"} permission to ensure that other
     * applications cannot maliciously bind to it.  If an idle service's manifest
     * declaration does not require that permission, it will never be invoked.
     * </p>
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.idle.IdleService";

    /**
     * Idle services must be protected with this permission:
     *
     * <pre class="prettyprint">
     *     <service android:name="MyIdleService"
     *              android:permission="android.permission.BIND_IDLE_SERVICE" >
     *         ...
     *     </service>
     * </pre>
     *
     * <p>If an idle service is declared in the manifest but not protected with this
     * permission, that service will be ignored by the OS.
     */
    public static final String PERMISSION_BIND =
            "android.permission.BIND_IDLE_SERVICE";

    // Trampoline: the callbacks are always run on the main thread
    IIdleService mBinder = new IIdleService.Stub() {
        @Override
        public void startIdleMaintenance(IIdleCallback callbackBinder, int token)
                throws RemoteException {
            ensureHandler();
            Message msg = mHandler.obtainMessage(MSG_START, token, 0, callbackBinder);
            mHandler.sendMessage(msg);
        }

        @Override
        public void stopIdleMaintenance(IIdleCallback callbackBinder, int token)
                throws RemoteException {
            ensureHandler();
            Message msg = mHandler.obtainMessage(MSG_STOP, token, 0, callbackBinder);
            mHandler.sendMessage(msg);
        }
    };

    /**
     * Your application may begin doing "idle" maintenance work in the background.
     * <p>
     * Your application may continue to run in the background until it receives a call
     * to {@link #onIdleStop()}, at which point you <i>must</i> cease doing work.  The
     * OS will hold a wakelock on your application's behalf from the time this method is
     * called until after the following call to {@link #onIdleStop()} returns.
     * </p>
     * <p>
     * Returning {@code false} from this method indicates that you have no ongoing work
     * to do at present.  The OS will respond by immediately calling {@link #onIdleStop()}
     * and returning your application to its normal stopped state.  Returning {@code true}
     * indicates that the application is indeed performing ongoing work, so the OS will
     * let your application run in this state until it's no longer appropriate.
     * </p>
     * <p>
     * You will always receive a matching call to {@link #onIdleStop()} even if your
     * application returns {@code false} from this method.
     *
     * @return {@code true} to indicate that the application wishes to perform some ongoing
     *     background work; {@code false} to indicate that it does not need to perform such
     *     work at present.
     */
    public abstract boolean onIdleStart();

    /**
     * Your app's maintenance opportunity is over.  Once the application returns from
     * this method, the wakelock held by the OS on its behalf will be released.
     */
    public abstract void onIdleStop();

    /**
     * Tell the OS that you have finished your idle work.  Calling this more than once,
     * or calling it when you have not received an {@link #onIdleStart()} callback, is
     * an error.
     *
     * <p>It is safe to call {@link #finishIdle()} from any thread.
     */
    public final void finishIdle() {
        ensureHandler();
        mHandler.sendEmptyMessage(MSG_FINISH);
    }

    class IdleHandler extends Handler {
        IdleHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START: {
                    // Call the concrete onIdleStart(), reporting its return value back to
                    // the OS.  If onIdleStart() throws, report it as a 'false' return but
                    // rethrow the exception at the offending app.
                    boolean result = false;
                    IIdleCallback callbackBinder = (IIdleCallback) msg.obj;
                    mCallbackBinder = callbackBinder;
                    final int token = mToken = msg.arg1;
                    try {
                        result = IdleService.this.onIdleStart();
                    } catch (Exception e) {
                        Log.e(TAG, "Unable to start idle workload", e);
                        throw new RuntimeException(e);
                    } finally {
                        // don't bother if the service already called finishIdle()
                        if (mCallbackBinder != null) {
                            try {
                                callbackBinder.acknowledgeStart(token, result);
                            } catch (RemoteException re) {
                                Log.e(TAG, "System unreachable to start idle workload");
                            }
                        }
                    }
                    break;
                }

                case MSG_STOP: {
                    // Structured just like MSG_START for the stop-idle bookend call.
                    IIdleCallback callbackBinder = (IIdleCallback) msg.obj;
                    final int token = msg.arg1;
                    try {
                        IdleService.this.onIdleStop();
                    } catch (Exception e) {
                        Log.e(TAG, "Unable to stop idle workload", e);
                        throw new RuntimeException(e);
                    } finally {
                        if (mCallbackBinder != null) {
                            try {
                                callbackBinder.acknowledgeStop(token);
                            } catch (RemoteException re) {
                                Log.e(TAG, "System unreachable to stop idle workload");
                            }
                        }
                    }
                    break;
                }

                case MSG_FINISH: {
                    if (mCallbackBinder != null) {
                        try {
                            mCallbackBinder.idleFinished(mToken);
                        } catch (RemoteException e) {
                            Log.e(TAG, "System unreachable to finish idling");
                        } finally {
                            mCallbackBinder = null;
                        }
                    } else {
                        Log.e(TAG, "finishIdle() called but the idle service is not started");
                    }
                    break;
                }

                default: {
                    Slog.w(TAG, "Unknown message " + msg.what);
                }
            }
        }
    }

    /** @hide */
    @Override
    public final IBinder onBind(Intent intent) {
        return mBinder.asBinder();
    }

}
