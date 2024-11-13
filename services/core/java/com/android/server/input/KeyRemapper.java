/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.input;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;
import android.util.FeatureFlagUtils;

import com.android.internal.annotations.GuardedBy;

import java.util.Map;

/**
 * A component of {@link InputManagerService} responsible for managing key remappings.
 *
 * @hide
 */
final class KeyRemapper {

    private static final int MSG_UPDATE_EXISTING_KEY_REMAPPING = 1;
    private static final int MSG_REMAP_KEY = 2;
    private static final int MSG_CLEAR_ALL_REMAPPING = 3;

    private final Context mContext;
    private final NativeInputManagerService mNative;
    // The PersistentDataStore should be locked before use.
    @GuardedBy("mDataStore")
    private final PersistentDataStore mDataStore;
    private final Handler mHandler;

    KeyRemapper(Context context, NativeInputManagerService nativeService,
                PersistentDataStore dataStore, Looper looper) {
        mContext = context;
        mNative = nativeService;
        mDataStore = dataStore;
        mHandler = new Handler(looper, this::handleMessage);
    }

    public void systemRunning() {
        Message.obtain(mHandler, MSG_UPDATE_EXISTING_KEY_REMAPPING).sendToTarget();
    }

    public void remapKey(int fromKey, int toKey) {
        if (!supportRemapping()) {
            return;
        }
        Message msg = Message.obtain(mHandler, MSG_REMAP_KEY, fromKey, toKey);
        mHandler.sendMessage(msg);
    }

    public void clearAllKeyRemappings() {
        if (!supportRemapping()) {
            return;
        }
        Message msg = Message.obtain(mHandler, MSG_CLEAR_ALL_REMAPPING);
        mHandler.sendMessage(msg);
    }

    public Map<Integer, Integer> getKeyRemapping() {
        if (!supportRemapping()) {
            return new ArrayMap<>();
        }
        synchronized (mDataStore) {
            return mDataStore.getKeyRemapping();
        }
    }

    private void setKeyRemapping(Map<Integer, Integer> keyRemapping) {
        int index = 0;
        int[] fromKeycodesArr = new int[keyRemapping.size()];
        int[] toKeycodesArr = new int[keyRemapping.size()];
        for (Map.Entry<Integer, Integer> entry : keyRemapping.entrySet()) {
            fromKeycodesArr[index] = entry.getKey();
            toKeycodesArr[index] = entry.getValue();
            index++;
        }
        mNative.setKeyRemapping(fromKeycodesArr, toKeycodesArr);
    }

    private void remapKeyInternal(int fromKey, int toKey) {
        synchronized (mDataStore) {
            try {
                if (fromKey == toKey) {
                    mDataStore.clearMappedKey(fromKey);
                } else {
                    mDataStore.remapKey(fromKey, toKey);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
            setKeyRemapping(mDataStore.getKeyRemapping());
        }
    }

    private void clearAllRemappingsInternal() {
        synchronized (mDataStore) {
            try {
                Map<Integer, Integer> keyRemapping = mDataStore.getKeyRemapping();
                for (int fromKey : keyRemapping.keySet()) {
                    mDataStore.clearMappedKey(fromKey);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
            setKeyRemapping(mDataStore.getKeyRemapping());
        }
    }

    public void updateExistingKeyMapping() {
        if (!supportRemapping()) {
            return;
        }
        setKeyRemapping(getKeyRemapping());
    }

    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_UPDATE_EXISTING_KEY_REMAPPING:
                updateExistingKeyMapping();
                return true;
            case MSG_REMAP_KEY:
                remapKeyInternal(msg.arg1, msg.arg2);
                return true;
            case MSG_CLEAR_ALL_REMAPPING:
                clearAllRemappingsInternal();
                return true;
        }
        return false;
    }

    private boolean supportRemapping() {
        return FeatureFlagUtils.isEnabled(mContext,
                FeatureFlagUtils.SETTINGS_NEW_KEYBOARD_MODIFIER_KEY);
    }
}
