/*
 * Copyright 2019 The Android Open Source Project
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

package android.bluetooth;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.CloseGuard;
import android.util.Log;

import java.util.List;
/**
 * Connector for Bluetooth profile proxies to bind manager service and
 * profile services
 * @param <T> The Bluetooth profile interface for this connection.
 * @hide
 */
@SuppressLint("AndroidFrameworkBluetoothPermission")
public abstract class BluetoothProfileConnector<T> {
    private final CloseGuard mCloseGuard = new CloseGuard();
    private final int mProfileId;
    private BluetoothProfile.ServiceListener mServiceListener;
    private final BluetoothProfile mProfileProxy;
    private Context mContext;
    private final String mProfileName;
    private final String mServiceName;
    private volatile T mService;

    private final IBluetoothStateChangeCallback mBluetoothStateChangeCallback =
            new IBluetoothStateChangeCallback.Stub() {
        public void onBluetoothStateChange(boolean up) {
            if (up) {
                doBind();
            } else {
                doUnbind();
            }
        }
    };

    private @Nullable ComponentName resolveSystemService(@NonNull Intent intent,
            @NonNull PackageManager pm) {
        List<ResolveInfo> results = pm.queryIntentServices(intent,
                PackageManager.ResolveInfoFlags.of(0));
        if (results == null) {
            return null;
        }
        ComponentName comp = null;
        for (int i = 0; i < results.size(); i++) {
            ResolveInfo ri = results.get(i);
            if ((ri.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                continue;
            }
            ComponentName foundComp = new ComponentName(ri.serviceInfo.applicationInfo.packageName,
                    ri.serviceInfo.name);
            if (comp != null) {
                throw new IllegalStateException("Multiple system services handle " + intent
                        + ": " + comp + ", " + foundComp);
            }
            comp = foundComp;
        }
        return comp;
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            logDebug("Proxy object connected");
            mService = getServiceInterface(service);

            if (mServiceListener != null) {
                mServiceListener.onServiceConnected(mProfileId, mProfileProxy);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            logDebug("Proxy object disconnected");
            doUnbind();
            if (mServiceListener != null) {
                mServiceListener.onServiceDisconnected(mProfileId);
            }
        }
    };

    BluetoothProfileConnector(BluetoothProfile profile, int profileId, String profileName,
            String serviceName) {
        mProfileId = profileId;
        mProfileProxy = profile;
        mProfileName = profileName;
        mServiceName = serviceName;
    }

    /** {@hide} */
    @Override
    public void finalize() {
        mCloseGuard.warnIfOpen();
        doUnbind();
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private boolean doBind() {
        synchronized (mConnection) {
            if (mService == null) {
                logDebug("Binding service...");
                mCloseGuard.open("doUnbind");
                try {
                    Intent intent = new Intent(mServiceName);
                    ComponentName comp = resolveSystemService(intent, mContext.getPackageManager());
                    intent.setComponent(comp);
                    if (comp == null || !mContext.bindServiceAsUser(intent, mConnection, 0,
                            UserHandle.CURRENT)) {
                        logError("Could not bind to Bluetooth Service with " + intent);
                        return false;
                    }
                } catch (SecurityException se) {
                    logError("Failed to bind service. " + se);
                    return false;
                }
            }
        }
        return true;
    }

    private void doUnbind() {
        synchronized (mConnection) {
            if (mService != null) {
                logDebug("Unbinding service...");
                mCloseGuard.close();
                try {
                    mContext.unbindService(mConnection);
                } catch (IllegalArgumentException ie) {
                    logError("Unable to unbind service: " + ie);
                } finally {
                    mService = null;
                }
            }
        }
    }

    void connect(Context context, BluetoothProfile.ServiceListener listener) {
        mContext = context;
        mServiceListener = listener;
        IBluetoothManager mgr = BluetoothAdapter.getDefaultAdapter().getBluetoothManager();

        // Preserve legacy compatibility where apps were depending on
        // registerStateChangeCallback() performing a permissions check which
        // has been relaxed in modern platform versions
        if (context.getApplicationInfo().targetSdkVersion <= Build.VERSION_CODES.R
                && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH)
                        != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Need BLUETOOTH permission");
        }

        if (mgr != null) {
            try {
                mgr.registerStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException re) {
                logError("Failed to register state change callback. " + re);
            }
        }
        doBind();
    }

    void disconnect() {
        mServiceListener = null;
        IBluetoothManager mgr = BluetoothAdapter.getDefaultAdapter().getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.unregisterStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException re) {
                logError("Failed to unregister state change callback" + re);
            }
        }
        doUnbind();
    }

    T getService() {
        return mService;
    }

    /**
     * This abstract function is used to implement method to get the
     * connected Bluetooth service interface.
     * @param service the connected binder service.
     * @return T the binder interface of {@code service}.
     * @hide
     */
    public abstract T getServiceInterface(IBinder service);

    private void logDebug(String log) {
        Log.d(mProfileName, log);
    }

    private void logError(String log) {
        Log.e(mProfileName, log);
    }
}
