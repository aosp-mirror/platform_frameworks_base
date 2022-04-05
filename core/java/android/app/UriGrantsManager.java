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
 * limitations under the License
 */

package android.app;

import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Singleton;

/**
 * Class for managing an app's permission to access a particular {@link android.net.Uri}.
 *
 * @hide
 */
@SystemService(Context.URI_GRANTS_SERVICE)
public class UriGrantsManager {

    private final Context mContext;

    UriGrantsManager(Context context, Handler handler) {
        mContext = context;
    }

    /** @hide */
    public static IUriGrantsManager getService() {
        return IUriGrantsManagerSingleton.get();
    }

    private static final Singleton<IUriGrantsManager> IUriGrantsManagerSingleton =
            new Singleton<IUriGrantsManager>() {
                @Override
                protected IUriGrantsManager create() {
                    final IBinder b = ServiceManager.getService(Context.URI_GRANTS_SERVICE);
                    return IUriGrantsManager.Stub.asInterface(b);
                }
            };

    /**
     * Permits an application to clear the persistent URI permissions granted to another.
     *
     * <p>Typically called by Settings, requires {@code CLEAR_APP_GRANTED_URI_PERMISSIONS}.
     *
     * @param packageName application to clear its granted permissions
     *
     * @hide
     */
    public void clearGrantedUriPermissions(String packageName) {
        try {
            getService().clearGrantedUriPermissions(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Permits an application to get the persistent URI permissions granted to another.
     *
     * <p>Typically called by Settings or DocumentsUI, requires
     * {@code GET_APP_GRANTED_URI_PERMISSIONS}.
     *
     * @param packageName application to look for the granted permissions, or {@code null} to get
     * granted permissions for all applications
     * @return list of granted URI permissions
     *
     * @hide
     */
    public ParceledListSlice<GrantedUriPermission> getGrantedUriPermissions(
            @Nullable String packageName) {
        try {
            @SuppressWarnings("unchecked")
            final ParceledListSlice<GrantedUriPermission> castedList = getService()
                    .getGrantedUriPermissions(packageName, mContext.getUserId());
            return castedList;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
