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

package com.android.server.appbinding.finders;

import android.Manifest.permission;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.service.carrier.CarrierMessagingClientService;
import android.service.carrier.ICarrierMessagingClientService;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.CollectionUtils;
import com.android.server.appbinding.AppBindingConstants;

import java.util.function.BiConsumer;

/**
 * Find the CarrierMessagingClientService service within the default SMS app.
 */
public class CarrierMessagingClientServiceFinder
        extends AppServiceFinder<CarrierMessagingClientService, ICarrierMessagingClientService> {

    private final RoleManager mRoleManager;

    public CarrierMessagingClientServiceFinder(Context context,
            BiConsumer<AppServiceFinder, Integer> listener,
            Handler callbackHandler) {
        super(context, listener, callbackHandler);

        mRoleManager = context.getSystemService(RoleManager.class);
    }

    @Override
    protected boolean isEnabled(AppBindingConstants constants) {
        return constants.SMS_SERVICE_ENABLED
                && mContext.getResources().getBoolean(R.bool.config_useSmsAppService);
    }

    @Override
    public String getAppDescription() {
        return "[Default SMS app]";
    }

    @Override
    protected Class<CarrierMessagingClientService> getServiceClass() {
        return CarrierMessagingClientService.class;
    }

    @Override
    public ICarrierMessagingClientService asInterface(IBinder obj) {
        return ICarrierMessagingClientService.Stub.asInterface(obj);
    }

    @Override
    protected String getServiceAction() {
        return TelephonyManager.ACTION_CARRIER_MESSAGING_CLIENT_SERVICE;
    }

    @Override
    protected String getServicePermission() {
        return permission.BIND_CARRIER_MESSAGING_CLIENT_SERVICE;
    }

    @Override
    public String getTargetPackage(int userId) {
        final String ret = CollectionUtils.firstOrNull(mRoleManager.getRoleHoldersAsUser(
                RoleManager.ROLE_SMS, UserHandle.of(userId)));

        if (DEBUG) {
            Slog.d(TAG, "getTargetPackage()=" + ret);
        }

        return ret;
    }

    @Override
    public void startMonitoring() {
        mRoleManager.addOnRoleHoldersChangedListenerAsUser(
                BackgroundThread.getExecutor(), mRoleHolderChangedListener, UserHandle.ALL);
    }

    @Override
    protected String validateService(ServiceInfo service) {
        final String packageName = service.packageName;
        final String process = service.processName;

        if (process == null || TextUtils.equals(packageName, process)) {
            return "Service must not run on the main process";
        }
        return null; // Null means accept this service.
    }

    @Override
    public int getBindFlags(AppBindingConstants constants) {
        return constants.SMS_APP_BIND_FLAGS;
    }

    private final OnRoleHoldersChangedListener mRoleHolderChangedListener = (role, user) -> {
        if (RoleManager.ROLE_SMS.equals(role)) {
            mListener.accept(CarrierMessagingClientServiceFinder.this, user.getIdentifier());
        }
    };
}
