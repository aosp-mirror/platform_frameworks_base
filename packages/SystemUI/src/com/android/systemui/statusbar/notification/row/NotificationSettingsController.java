/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerExecutor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;

import javax.inject.Inject;

/**
 * Centralized controller for listening to Secure Settings changes and informing in-process
 * listeners, on a background thread.
 */
@SysUISingleton
public class NotificationSettingsController implements Dumpable {

    private final static String TAG = "NotificationSettingsController";
    private final UserTracker mUserTracker;
    private final UserTracker.Callback mCurrentUserTrackerCallback;
    private final Handler mHandler;
    private final ContentObserver mContentObserver;
    private final SecureSettings mSecureSettings;
    private final HashMap<Uri, ArrayList<Listener>> mListeners = new HashMap<>();

    @Inject
    public NotificationSettingsController(UserTracker userTracker,
            @Background Handler handler,
            SecureSettings secureSettings,
            DumpManager dumpManager) {
        mUserTracker = userTracker;
        mHandler = handler;
        mSecureSettings = secureSettings;
        mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                synchronized (mListeners) {
                    if (mListeners.containsKey(uri)) {
                        for (Listener listener : mListeners.get(uri)) {
                            notifyListener(listener, uri);
                        }
                    }
                }
            }
        };

        mCurrentUserTrackerCallback = new UserTracker.Callback() {
            @Override
            public void onUserChanged(int newUser, Context userContext) {
                synchronized (mListeners) {
                    if (mListeners.size() > 0) {
                        mSecureSettings.unregisterContentObserver(mContentObserver);
                        for (Uri uri : mListeners.keySet()) {
                            mSecureSettings.registerContentObserverForUser(
                                    uri, false, mContentObserver, newUser);
                        }
                    }
                }
            }
        };
        mUserTracker.addCallback(mCurrentUserTrackerCallback, new HandlerExecutor(handler));

        dumpManager.registerNormalDumpable(TAG, this);
    }

    /**
     * Register callback whenever the given secure settings changes.
     *
     * On registration, will call back on the provided handler with the current value of
     * the setting.
     */
    public void addCallback(@NonNull Uri uri, @NonNull Listener listener) {
        if (uri == null || listener == null) {
            return;
        }
        synchronized (mListeners) {
            ArrayList<Listener> currentListeners = mListeners.get(uri);
            if (currentListeners == null) {
                currentListeners = new ArrayList<>();
            }
            if (!currentListeners.contains(listener)) {
                currentListeners.add(listener);
            }
            mListeners.put(uri, currentListeners);
            if (currentListeners.size() == 1) {
                mSecureSettings.registerContentObserverForUser(
                        uri, false, mContentObserver, mUserTracker.getUserId());
            }
        }
        mHandler.post(() -> notifyListener(listener, uri));

    }

    public void removeCallback(Uri uri, Listener listener) {
        synchronized (mListeners) {
            ArrayList<Listener> currentListeners = mListeners.get(uri);

            if (currentListeners != null) {
                currentListeners.remove(listener);
            }
            if (currentListeners == null || currentListeners.size() == 0) {
                mListeners.remove(uri);
            }

            if (mListeners.size() == 0) {
                mSecureSettings.unregisterContentObserver(mContentObserver);
            }
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        synchronized (mListeners) {
            pw.println("Settings Uri Listener List:");
            for (Uri uri : mListeners.keySet()) {
                pw.println("   Uri=" + uri);
                for (Listener listener : mListeners.get(uri)) {
                    pw.println("      Listener=" + listener.getClass().getName());
                }
            }
        }
    }

    private void notifyListener(Listener listener, Uri uri) {
        final String setting = uri == null ? null : uri.getLastPathSegment();
        int userId = mUserTracker.getUserId();
        listener.onSettingChanged(uri, userId, mSecureSettings.getStringForUser(setting, userId));
    }

    /**
     * Listener invoked whenever settings are changed.
     */
    public interface Listener {
        void onSettingChanged(@NonNull Uri setting, int userId, @Nullable String value);
    }
}