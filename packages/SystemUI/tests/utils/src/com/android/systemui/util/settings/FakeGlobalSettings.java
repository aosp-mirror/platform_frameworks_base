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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;

import kotlinx.coroutines.CoroutineDispatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeGlobalSettings implements GlobalSettings {
    private final Map<String, String> mValues = new HashMap<>();
    private final Map<String, List<ContentObserver>> mContentObserversAllUsers = new HashMap<>();

    public static final Uri CONTENT_URI = Uri.parse("content://settings/fake_global");

    public FakeGlobalSettings() {
    }

    @Override
    public ContentResolver getContentResolver() {
        throw new UnsupportedOperationException(
                "GlobalSettings.getContentResolver is not implemented, but you may find "
                        + "GlobalSettings.registerContentObserver helpful instead.");
    }

    @Override
    public CoroutineDispatcher getBackgroundDispatcher() {
        throw new UnsupportedOperationException(
                "GlobalSettings.getBackgroundDispatcher is not implemented, but you may find "
                        + "GlobalSettings.getBackgroundDispatcher helpful instead.");
    }

    @Override
    public void registerContentObserverSync(Uri uri, boolean notifyDescendants,
            ContentObserver settingsObserver) {
        List<ContentObserver> observers;
        mContentObserversAllUsers.putIfAbsent(uri.toString(), new ArrayList<>());
        observers = mContentObserversAllUsers.get(uri.toString());
        observers.add(settingsObserver);
    }

    @Override
    public void unregisterContentObserverSync(ContentObserver settingsObserver) {
        for (Map.Entry<String, List<ContentObserver>> entry :
                mContentObserversAllUsers.entrySet()) {
            entry.getValue().remove(settingsObserver);
        }
    }

    @Override
    public Uri getUriFor(String name) {
        return Uri.withAppendedPath(CONTENT_URI, name);
    }

    @Override
    public String getString(String name) {
        return mValues.get(getUriFor(name).toString());
    }

    @Override
    public boolean putString(String name, String value) {
        return putString(name, value, null, false);
    }

    @Override
    public boolean putString(@NonNull String name, @Nullable String value, @Nullable String tag,
            boolean makeDefault) {
        String key = getUriFor(name).toString();
        mValues.put(key, value);

        Uri uri = getUriFor(name);
        for (ContentObserver observer :
                mContentObserversAllUsers.getOrDefault(uri.toString(), new ArrayList<>())) {
            observer.dispatchChange(false, List.of(uri), 0);
        }
        return true;
    }
}
