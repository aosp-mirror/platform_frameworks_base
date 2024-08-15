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
import android.os.Trace;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
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
    private final Handler mMainHandler;
    private final Handler mBackgroundHandler;
    private final ContentObserver mContentObserver;
    private final SecureSettings mSecureSettings;
    private final HashMap<Uri, ArrayList<Listener>> mListeners = new HashMap<>();

    @Inject
    public NotificationSettingsController(UserTracker userTracker,
            @Main Handler mainHandler,
            @Background Handler backgroundHandler,
            SecureSettings secureSettings,
            DumpManager dumpManager) {
        mUserTracker = userTracker;
        mMainHandler = mainHandler;
        mBackgroundHandler = backgroundHandler;
        mSecureSettings = secureSettings;
        mContentObserver = new ContentObserver(mBackgroundHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                Trace.traceBegin(Trace.TRACE_TAG_APP, TAG + ".ContentObserver.onChange");
                super.onChange(selfChange, uri);
                synchronized (mListeners) {
                    if (mListeners.containsKey(uri)) {
                        int userId = mUserTracker.getUserId();
                        String value = getCurrentSettingValue(uri, userId);
                        for (Listener listener : mListeners.get(uri)) {
                            mMainHandler.post(() -> listener.onSettingChanged(uri, userId, value));
                        }
                    }
                }
                Trace.traceEnd(Trace.TRACE_TAG_APP);
            }
        };

        mCurrentUserTrackerCallback = new UserTracker.Callback() {
            @Override
            public void onUserChanged(int newUser, Context userContext) {
                Trace.traceBegin(Trace.TRACE_TAG_APP, TAG + ".UserTracker.Callback.onUserChanged");

                synchronized (mListeners) {
                    if (mListeners.size() > 0) {
                        mSecureSettings.unregisterContentObserverSync(mContentObserver);
                        for (Uri uri : mListeners.keySet()) {
                            mSecureSettings.registerContentObserverForUserSync(
                                    uri, false, mContentObserver, newUser);
                        }
                    }
                }
                Trace.traceEnd(Trace.TRACE_TAG_APP);
            }
        };
        mUserTracker.addCallback(
                mCurrentUserTrackerCallback, new HandlerExecutor(mBackgroundHandler));

        dumpManager.registerNormalDumpable(TAG, this);
    }

    /**
     * Register a callback whenever the given secure settings changes.
     *
     * On registration, will trigger the listener on the main thread with the current value of
     * the setting.
     */
    @Main
    public void addCallback(@NonNull Uri uri, @NonNull Listener listener) {
        if (uri == null || listener == null) {
            return;
        }
        Trace.traceBegin(Trace.TRACE_TAG_APP, TAG + ".addCallback");
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
                mBackgroundHandler.post(() -> {
                    mSecureSettings.registerContentObserverForUserSync(
                            uri, false, mContentObserver, mUserTracker.getUserId());
                });
            }
        }
        mBackgroundHandler.post(() -> {
            int userId = mUserTracker.getUserId();
            String value = getCurrentSettingValue(uri, userId);
            mMainHandler.post(() -> listener.onSettingChanged(uri, userId, value));
        });
        Trace.traceEnd(Trace.TRACE_TAG_APP);
    }

    public void removeCallback(Uri uri, Listener listener) {
        Trace.traceBegin(Trace.TRACE_TAG_APP, TAG + ".removeCallback");

        synchronized (mListeners) {
            ArrayList<Listener> currentListeners = mListeners.get(uri);

            if (currentListeners != null) {
                currentListeners.remove(listener);
            }
            if (currentListeners == null || currentListeners.size() == 0) {
                mListeners.remove(uri);
            }

            if (mListeners.size() == 0) {
                mBackgroundHandler.post(() -> {
                    mSecureSettings.unregisterContentObserverSync(mContentObserver);
                });
            }
        }
        Trace.traceEnd(Trace.TRACE_TAG_APP);
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        Trace.traceBegin(Trace.TRACE_TAG_APP, TAG + ".dump");

        synchronized (mListeners) {
            pw.println("Settings Uri Listener List:");
            for (Uri uri : mListeners.keySet()) {
                pw.println("   Uri=" + uri);
                for (Listener listener : mListeners.get(uri)) {
                    pw.println("      Listener=" + listener.getClass().getName());
                }
            }
        }
        Trace.traceEnd(Trace.TRACE_TAG_APP);
    }

    private String getCurrentSettingValue(Uri uri, int userId) {
        final String setting = uri == null ? null : uri.getLastPathSegment();
        return mSecureSettings.getStringForUser(setting, userId);
    }

    /**
     * Listener invoked whenever settings are changed.
     */
    public interface Listener {
        @MainThread
        void onSettingChanged(@NonNull Uri setting, int userId, @Nullable String value);
    }
}