/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.util.settings;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.UserHandle;
import android.util.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeSettings implements SecureSettings, GlobalSettings, SystemSettings {
    private final Map<SettingsKey, String> mValues = new HashMap<>();
    private final Map<SettingsKey, List<ContentObserver>> mContentObservers =
            new HashMap<>();
    private final Map<String, List<ContentObserver>> mContentObserversAllUsers = new HashMap<>();

    public static final Uri CONTENT_URI = Uri.parse("content://settings/fake");

    public FakeSettings() {
    }

    public FakeSettings(String initialKey, String initialValue) {
        putString(initialKey, initialValue);
    }

    public FakeSettings(Map<String, String> initialValues) {
        for (Map.Entry<String, String> kv : initialValues.entrySet()) {
            putString(kv.getKey(), kv.getValue());
        }
    }

    @Override
    public ContentResolver getContentResolver() {
        return null;
    }

    @Override
    public void registerContentObserverForUser(Uri uri, boolean notifyDescendents,
            ContentObserver settingsObserver, int userHandle) {
        List<ContentObserver> observers;
        if (userHandle == UserHandle.USER_ALL) {
            mContentObserversAllUsers.putIfAbsent(uri.toString(), new ArrayList<>());
            observers = mContentObserversAllUsers.get(uri.toString());
        } else {
            SettingsKey key = new SettingsKey(userHandle, uri.toString());
            mContentObservers.putIfAbsent(key, new ArrayList<>());
            observers = mContentObservers.get(key);
        }
        observers.add(settingsObserver);
    }

    @Override
    public void unregisterContentObserver(ContentObserver settingsObserver) {
        for (SettingsKey key : mContentObservers.keySet()) {
            List<ContentObserver> observers = mContentObservers.get(key);
            observers.remove(settingsObserver);
        }
        for (String key : mContentObserversAllUsers.keySet()) {
            List<ContentObserver> observers = mContentObserversAllUsers.get(key);
            observers.remove(settingsObserver);
        }
    }

    @Override
    public Uri getUriFor(String name) {
        return Uri.withAppendedPath(CONTENT_URI, name);
    }

    @Override
    public int getUserId() {
        return UserHandle.USER_CURRENT;
    }

    @Override
    public String getString(String name) {
        return getStringForUser(name, getUserId());
    }

    @Override
    public String getStringForUser(String name, int userHandle) {
        return mValues.get(new SettingsKey(userHandle, getUriFor(name).toString()));
    }

    @Override
    public boolean putString(String name, String value, boolean overrideableByRestore) {
        return putStringForUser(name, value, null, false, getUserId(), overrideableByRestore);
    }

    @Override
    public boolean putString(String name, String value) {
        return putString(name, value, false);
    }

    @Override
    public boolean putStringForUser(String name, String value, int userHandle) {
        return putStringForUser(name, value, null, false, userHandle, false);
    }

    @Override
    public boolean putStringForUser(String name, String value, String tag, boolean makeDefault,
            int userHandle, boolean overrideableByRestore) {
        SettingsKey key = new SettingsKey(userHandle, getUriFor(name).toString());
        mValues.put(key, value);

        Uri uri = getUriFor(name);
        for (ContentObserver observer : mContentObservers.getOrDefault(key, new ArrayList<>())) {
            observer.dispatchChange(false, List.of(uri), userHandle);
        }
        for (ContentObserver observer :
                mContentObserversAllUsers.getOrDefault(uri.toString(), new ArrayList<>())) {
            observer.dispatchChange(false, List.of(uri), userHandle);
        }
        return true;
    }

    @Override
    public boolean putString(String name, String value, String tag, boolean makeDefault) {
        return putString(name, value);
    }

    private static class SettingsKey extends Pair<Integer, String> {
        SettingsKey(Integer first, String second) {
            super(first, second);
        }
    }
}
