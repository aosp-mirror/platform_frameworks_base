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

package com.android.systemui.doze;

import static java.util.Objects.requireNonNull;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.AmbientDisplayConfiguration;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/** Handles updating the doze state when doze is suppressed. */
public final class DozeSuppressedHandler implements DozeMachine.Part {

    private static final String TAG = DozeSuppressedHandler.class.getSimpleName();
    private static final boolean DEBUG = DozeService.DEBUG;

    private final ContentResolver mResolver;
    private final AmbientDisplayConfiguration mConfig;
    private final DozeMachine mMachine;
    private final DozeSuppressedSettingObserver mSettingObserver;
    private final Handler mHandler = new Handler();

    public DozeSuppressedHandler(Context context, AmbientDisplayConfiguration config,
            DozeMachine machine) {
        this(context, config, machine, null);
    }

    @VisibleForTesting
    DozeSuppressedHandler(Context context, AmbientDisplayConfiguration config, DozeMachine machine,
            DozeSuppressedSettingObserver observer) {
        mResolver = context.getContentResolver();
        mConfig = requireNonNull(config);
        mMachine = requireNonNull(machine);
        if (observer == null) {
            mSettingObserver = new DozeSuppressedSettingObserver(mHandler);
        } else {
            mSettingObserver = observer;
        }
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                mSettingObserver.register();
                break;
            case FINISH:
                mSettingObserver.unregister();
                break;
            default:
                // no-op
        }
    }

    /**
     * Listens to changes to the DOZE_SUPPRESSED secure setting and updates the doze state
     * accordingly.
     */
    final class DozeSuppressedSettingObserver extends ContentObserver {
        private boolean mRegistered;

        private DozeSuppressedSettingObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (userId != ActivityManager.getCurrentUser()) {
                return;
            }
            final DozeMachine.State nextState;
            if (mConfig.alwaysOnEnabled(UserHandle.USER_CURRENT)
                    && !mConfig.dozeSuppressed(UserHandle.USER_CURRENT)) {
                nextState = DozeMachine.State.DOZE_AOD;
            } else {
                nextState = DozeMachine.State.DOZE;
            }
            mMachine.requestState(nextState);
        }

        void register() {
            if (mRegistered) {
                return;
            }
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.SUPPRESS_DOZE),
                    false, this, UserHandle.USER_CURRENT);
            Log.d(TAG, "Register");
            mRegistered = true;
        }

        void unregister() {
            if (!mRegistered) {
                return;
            }
            mResolver.unregisterContentObserver(this);
            Log.d(TAG, "Unregister");
            mRegistered = false;
        }
    }
}
