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

package com.android.systemui;

import android.app.Notification;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Map;

public abstract class SystemUI implements SysUiServiceProvider {
    public Context mContext;
    public Map<Class<?>, Object> mComponents;

    public abstract void start();

    protected void onConfigurationChanged(Configuration newConfig) {
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
    }

    protected void onBootCompleted() {
    }

    @SuppressWarnings("unchecked")
    public <T> T getComponent(Class<T> interfaceType) {
        return (T) (mComponents != null ? mComponents.get(interfaceType) : null);
    }

    public <T, C extends T> void putComponent(Class<T> interfaceType, C component) {
        if (mComponents != null) {
            mComponents.put(interfaceType, component);
        }
    }

    public static void overrideNotificationAppName(Context context, Notification.Builder n) {
        final Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                context.getString(com.android.internal.R.string.android_system_label));

        n.addExtras(extras);
    }
}
