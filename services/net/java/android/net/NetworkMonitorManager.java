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

package android.net;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

/**
 * A convenience wrapper for INetworkMonitor.
 *
 * Wraps INetworkMonitor calls, making them a bit more friendly to use. Currently handles:
 * - Clearing calling identity
 * - Ignoring RemoteExceptions
 * - Converting to stable parcelables
 *
 * By design, all methods on INetworkMonitor are asynchronous oneway IPCs and are thus void. All the
 * wrapper methods in this class return a boolean that callers can use to determine whether
 * RemoteException was thrown.
 */
public class NetworkMonitorManager {

    @NonNull private final INetworkMonitor mNetworkMonitor;
    @NonNull private final String mTag;

    public NetworkMonitorManager(@NonNull INetworkMonitor networkMonitorManager,
            @NonNull String tag) {
        mNetworkMonitor = networkMonitorManager;
        mTag = tag;
    }

    public NetworkMonitorManager(@NonNull INetworkMonitor networkMonitorManager) {
        this(networkMonitorManager, NetworkMonitorManager.class.getSimpleName());
    }

    private void log(String s, Throwable e) {
        Log.e(mTag, s, e);
    }

    // CHECKSTYLE:OFF Generated code

    public boolean start() {
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkMonitor.start();
            return true;
        } catch (RemoteException e) {
            log("Error in start", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public boolean launchCaptivePortalApp() {
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkMonitor.launchCaptivePortalApp();
            return true;
        } catch (RemoteException e) {
            log("Error in launchCaptivePortalApp", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public boolean notifyCaptivePortalAppFinished(int response) {
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkMonitor.notifyCaptivePortalAppFinished(response);
            return true;
        } catch (RemoteException e) {
            log("Error in notifyCaptivePortalAppFinished", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public boolean setAcceptPartialConnectivity() {
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkMonitor.setAcceptPartialConnectivity();
            return true;
        } catch (RemoteException e) {
            log("Error in setAcceptPartialConnectivity", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public boolean forceReevaluation(int uid) {
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkMonitor.forceReevaluation(uid);
            return true;
        } catch (RemoteException e) {
            log("Error in forceReevaluation", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public boolean notifyPrivateDnsChanged(PrivateDnsConfigParcel config) {
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkMonitor.notifyPrivateDnsChanged(config);
            return true;
        } catch (RemoteException e) {
            log("Error in notifyPrivateDnsChanged", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public boolean notifyDnsResponse(int returnCode) {
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkMonitor.notifyDnsResponse(returnCode);
            return true;
        } catch (RemoteException e) {
            log("Error in notifyDnsResponse", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public boolean notifyNetworkConnected(LinkProperties lp, NetworkCapabilities nc) {
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkMonitor.notifyNetworkConnected(lp, nc);
            return true;
        } catch (RemoteException e) {
            log("Error in notifyNetworkConnected", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public boolean notifyNetworkDisconnected() {
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkMonitor.notifyNetworkDisconnected();
            return true;
        } catch (RemoteException e) {
            log("Error in notifyNetworkDisconnected", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public boolean notifyLinkPropertiesChanged(LinkProperties lp) {
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkMonitor.notifyLinkPropertiesChanged(lp);
            return true;
        } catch (RemoteException e) {
            log("Error in notifyLinkPropertiesChanged", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public boolean notifyNetworkCapabilitiesChanged(NetworkCapabilities nc) {
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkMonitor.notifyNetworkCapabilitiesChanged(nc);
            return true;
        } catch (RemoteException e) {
            log("Error in notifyNetworkCapabilitiesChanged", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // CHECKSTYLE:ON Generated code
}
