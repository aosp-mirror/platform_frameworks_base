/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.provider.Settings;
import android.service.notification.IConditionProvider;
import android.service.notification.ConditionProviderService;
import android.util.Slog;

import com.android.internal.R;

public class ConditionProviders extends ManagedServices {

    public ConditionProviders(Context context, Handler handler,
            Object mutex, UserProfiles userProfiles) {
        super(context, handler, mutex, userProfiles);
    }

    @Override
    protected Config getConfig() {
        Config c = new Config();
        c.caption = "condition provider";
        c.serviceInterface = ConditionProviderService.SERVICE_INTERFACE;
        c.secureSettingName = Settings.Secure.ENABLED_CONDITION_PROVIDERS;
        c.bindPermission = android.Manifest.permission.BIND_CONDITION_PROVIDER_SERVICE;
        c.settingsAction = Settings.ACTION_CONDITION_PROVIDER_SETTINGS;
        c.clientLabel = R.string.condition_provider_service_binding_label;
        return c;
    }

    @Override
    protected IInterface asInterface(IBinder binder) {
        return IConditionProvider.Stub.asInterface(binder);
    }

    @Override
    protected void onServiceAdded(IInterface service) {
        Slog.d(TAG, "onServiceAdded " + service);
    }
}
