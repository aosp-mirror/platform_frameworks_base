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

package com.android.server.location;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;

import android.content.Context;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ILocationProvider;
import com.android.internal.location.ProviderRequest;
import com.android.internal.os.TransferPipe;
import com.android.server.LocationManagerService;
import com.android.server.ServiceWatcher;

/**
 * Proxy for ILocationProvider implementations.
 */
public class LocationProviderProxy implements LocationProviderInterface {
    private static final String TAG = "LocationProviderProxy";
    private static final boolean D = LocationManagerService.D;

    private final Context mContext;
    private final String mName;
    private final ServiceWatcher mServiceWatcher;

    private Object mLock = new Object();

    // cached values set by the location manager, synchronized on mLock
    private ProviderProperties mProperties;
    private boolean mEnabled = false;
    private ProviderRequest mRequest = null;
    private WorkSource mWorksource = new WorkSource();

    public static LocationProviderProxy createAndBind(
            Context context, String name, String action,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId, Handler handler) {
        LocationProviderProxy proxy = new LocationProviderProxy(context, name, action,
                overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId,
                handler);
        if (proxy.bind()) {
            return proxy;
        } else {
            return null;
        }
    }

    private LocationProviderProxy(Context context, String name, String action,
            int overlaySwitchResId, int defaultServicePackageNameResId,
            int initialPackageNamesResId, Handler handler) {
        mContext = context;
        mName = name;
        mServiceWatcher = new ServiceWatcher(mContext, TAG + "-" + name, action, overlaySwitchResId,
                defaultServicePackageNameResId, initialPackageNamesResId,
                mNewServiceWork, handler);
    }

    private boolean bind () {
        return mServiceWatcher.start();
    }

    public String getConnectedPackageName() {
        return mServiceWatcher.getBestPackageName();
    }

    /**
     * Work to apply current state to a newly connected provider.
     * Remember we can switch the service that implements a providers
     * at run-time, so need to apply current state.
     */
    private Runnable mNewServiceWork = new Runnable() {
        @Override
        public void run() {
            if (D) Log.d(TAG, "applying state to connected service");

            boolean enabled;
            final ProviderProperties[] properties = new ProviderProperties[1];
            ProviderRequest request;
            WorkSource source;
            synchronized (mLock) {
                enabled = mEnabled;
                request = mRequest;
                source = mWorksource;
            }


            mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() {
                @Override
                public void run(IBinder binder) {
                    ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                    try {
                        // load properties from provider
                        properties[0] = service.getProperties();
                        if (properties[0] == null) {
                            Log.e(TAG, mServiceWatcher.getBestPackageName() +
                                    " has invalid location provider properties");
                        }

                        // apply current state to new service
                        if (enabled) {
                            service.enable();
                            if (request != null) {
                                service.setRequest(request, source);
                            }
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, e);
                    } catch (Exception e) {
                        // never let remote service crash system server
                        Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
                    }
                }
            });

            synchronized (mLock) {
                mProperties = properties[0];
            }
        }
    };

    @Override
    public String getName() {
        return mName;
    }

    @Override
    public ProviderProperties getProperties() {
        synchronized (mLock) {
            return mProperties;
        }
    }

    @Override
    public void enable() {
        synchronized (mLock) {
            mEnabled = true;
        }
        mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() {
            @Override
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    service.enable();
                } catch (RemoteException e) {
                    Log.w(TAG, e);
                } catch (Exception e) {
                    // never let remote service crash system server
                    Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
                }
            }
        });
    }

    @Override
    public void disable() {
        synchronized (mLock) {
            mEnabled = false;
        }
        mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() {
            @Override
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    service.disable();
                } catch (RemoteException e) {
                    Log.w(TAG, e);
                } catch (Exception e) {
                    // never let remote service crash system server
                    Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
                }
            }
        });
    }

    @Override
    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    @Override
    public void setRequest(ProviderRequest request, WorkSource source) {
        synchronized (mLock) {
            mRequest = request;
            mWorksource = source;
        }
        mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() {
            @Override
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    service.setRequest(request, source);
                } catch (RemoteException e) {
                    Log.w(TAG, e);
                } catch (Exception e) {
                    // never let remote service crash system server
                    Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
                }
            }
        });
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.append("REMOTE SERVICE");
        pw.append(" name=").append(mName);
        pw.append(" pkg=").append(mServiceWatcher.getBestPackageName());
        pw.append(" version=").append("" + mServiceWatcher.getBestVersion());
        pw.append('\n');
        if (!mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() {
            @Override
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    TransferPipe.dumpAsync(service.asBinder(), fd, args);
                } catch (IOException | RemoteException e) {
                    pw.println("Failed to dump location provider: " + e);
                }
            }
        })) {
            pw.println("service down (null)");
        }
    }

    @Override
    public int getStatus(Bundle extras) {
        final int[] result = new int[] {LocationProvider.TEMPORARILY_UNAVAILABLE};
        mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() {
            @Override
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    result[0] = service.getStatus(extras);
                } catch (RemoteException e) {
                    Log.w(TAG, e);
                } catch (Exception e) {
                    // never let remote service crash system server
                    Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
                }
            }
        });
        return result[0];
    }

    @Override
    public long getStatusUpdateTime() {
        final long[] result = new long[] {0L};
        mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() {
            @Override
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    result[0] = service.getStatusUpdateTime();
                } catch (RemoteException e) {
                    Log.w(TAG, e);
                } catch (Exception e) {
                    // never let remote service crash system server
                    Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
                }
            }
        });
        return result[0];
    }

    @Override
    public boolean sendExtraCommand(String command, Bundle extras) {
        final boolean[] result = new boolean[] {false};
        mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() {
            @Override
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    result[0] = service.sendExtraCommand(command, extras);
                } catch (RemoteException e) {
                    Log.w(TAG, e);
                } catch (Exception e) {
                    // never let remote service crash system server
                    Log.e(TAG, "Exception from " + mServiceWatcher.getBestPackageName(), e);
                }
            }
        });
        return result[0];
    }
 }
