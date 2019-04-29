/**
 * Copyright (C) 2018 The LineageOS project
 * Copyright (C) 2019 The PixelExperience project
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

package com.android.internal.util.custom.cutout;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import android.provider.Settings;

public class CutoutFullscreenController {
    private Set<String> mApps = new HashSet<>();
    private Context mContext;

    private final boolean isAvailable;

    public CutoutFullscreenController(Context context) {
        mContext = context;
        final Resources resources = mContext.getResources();

        isAvailable = CutoutUtils.hasCutout(context);

        if (!isAvailable) {
            return;
        }

        SettingsObserver observer = new SettingsObserver(
                new Handler(Looper.getMainLooper()));
        observer.observe();
    }

    public boolean isSupported() {
        return isAvailable;
    }

    public boolean shouldForceCutoutFullscreen(String packageName) {
        return isSupported() && mApps.contains(packageName);
    }

    public Set<String> getApps() {
        return mApps;
    }

    public void addApp(String packageName) {
        mApps.add(packageName);
        Settings.System.putString(mContext.getContentResolver(),
                Settings.System.FORCE_FULLSCREEN_CUTOUT_APPS, String.join(",", mApps));
    }

    public void removeApp(String packageName) {
        mApps.remove(packageName);
        Settings.System.putString(mContext.getContentResolver(),
                Settings.System.FORCE_FULLSCREEN_CUTOUT_APPS, String.join(",", mApps));
    }

    public void setApps(Set<String> apps) {
        mApps = apps;
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();

            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.FORCE_FULLSCREEN_CUTOUT_APPS), false, this,
                    UserHandle.USER_ALL);

            update();
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        public void update() {
            ContentResolver resolver = mContext.getContentResolver();

            String apps = Settings.System.getStringForUser(resolver,
                    Settings.System.FORCE_FULLSCREEN_CUTOUT_APPS,
                    UserHandle.USER_CURRENT);
            if (apps != null) {
                setApps(new HashSet<>(Arrays.asList(apps.split(","))));
            } else {
                setApps(new HashSet<>());
            }
        }
    }
}