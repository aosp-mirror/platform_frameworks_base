/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.IClipboard;
import android.content.IOnPrimaryClipChangedListener;
import android.os.PermissionEnforcer;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.internal.util.Preconditions;

/**
 * Fake implementation of {@code ClipboardManager} since the real implementation is tightly
 * coupled with many other internal services.
 */
public class FakeClipboardService extends IClipboard.Stub {
    private final RemoteCallbackList<IOnPrimaryClipChangedListener> mListeners =
            new RemoteCallbackList<>();

    private ClipData mPrimaryClip;
    private String mPrimaryClipSource;

    public FakeClipboardService(Context context) {
        super(PermissionEnforcer.fromContext(context));
    }

    public static class Lifecycle extends SystemService {
        private FakeClipboardService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new FakeClipboardService(getContext());
            publishBinderService(Context.CLIPBOARD_SERVICE, mService);
        }
    }

    private static void checkArguments(int userId, int deviceId) {
        Preconditions.checkArgument(userId == UserHandle.USER_SYSTEM,
                "Fake only supports USER_SYSTEM user");
        Preconditions.checkArgument(deviceId == Context.DEVICE_ID_DEFAULT,
                "Fake only supports DEVICE_ID_DEFAULT device");
    }

    private void dispatchPrimaryClipChanged() {
        mListeners.broadcast((listener) -> {
            try {
                listener.dispatchPrimaryClipChanged();
            } catch (RemoteException ignored) {
            }
        });
    }

    @Override
    public void setPrimaryClip(ClipData clip, String callingPackage, String attributionTag,
            int userId, int deviceId) {
        checkArguments(userId, deviceId);
        mPrimaryClip = clip;
        mPrimaryClipSource = callingPackage;
        dispatchPrimaryClipChanged();
    }

    @Override
    @android.annotation.EnforcePermission(android.Manifest.permission.SET_CLIP_SOURCE)
    public void setPrimaryClipAsPackage(ClipData clip, String callingPackage, String attributionTag,
            int userId, int deviceId, String sourcePackage) {
        setPrimaryClipAsPackage_enforcePermission();
        checkArguments(userId, deviceId);
        mPrimaryClip = clip;
        mPrimaryClipSource = sourcePackage;
        dispatchPrimaryClipChanged();
    }

    @Override
    public void clearPrimaryClip(String callingPackage, String attributionTag, int userId,
            int deviceId) {
        checkArguments(userId, deviceId);
        mPrimaryClip = null;
        mPrimaryClipSource = null;
        dispatchPrimaryClipChanged();
    }

    @Override
    public ClipData getPrimaryClip(String pkg, String attributionTag, int userId, int deviceId) {
        checkArguments(userId, deviceId);
        return mPrimaryClip;
    }

    @Override
    public ClipDescription getPrimaryClipDescription(String callingPackage, String attributionTag,
            int userId, int deviceId) {
        checkArguments(userId, deviceId);
        return (mPrimaryClip != null) ? mPrimaryClip.getDescription() : null;
    }

    @Override
    public boolean hasPrimaryClip(String callingPackage, String attributionTag, int userId,
            int deviceId) {
        checkArguments(userId, deviceId);
        return mPrimaryClip != null;
    }

    @Override
    public void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener,
            String callingPackage, String attributionTag, int userId, int deviceId) {
        checkArguments(userId, deviceId);
        mListeners.register(listener);
    }

    @Override
    public void removePrimaryClipChangedListener(IOnPrimaryClipChangedListener listener,
            String callingPackage, String attributionTag, int userId, int deviceId) {
        checkArguments(userId, deviceId);
        mListeners.unregister(listener);
    }

    @Override
    public boolean hasClipboardText(String callingPackage, String attributionTag, int userId,
            int deviceId) {
        checkArguments(userId, deviceId);
        return (mPrimaryClip != null) && (mPrimaryClip.getItemCount() > 0)
                && (mPrimaryClip.getItemAt(0).getText() != null);
    }

    @Override
    @android.annotation.EnforcePermission(android.Manifest.permission.SET_CLIP_SOURCE)
    public String getPrimaryClipSource(String callingPackage, String attributionTag, int userId,
            int deviceId) {
        getPrimaryClipSource_enforcePermission();
        checkArguments(userId, deviceId);
        return mPrimaryClipSource;
    }

    @Override
    public boolean areClipboardAccessNotificationsEnabledForUser(int userId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setClipboardAccessNotificationsEnabledForUser(boolean enable, int userId) {
        throw new UnsupportedOperationException();
    }
}
