/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.content.IClipboard;
import android.content.IOnPrimaryClipChangedListener;
import android.content.Context;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

/**
 * Implementation of the clipboard for copy and paste.
 */
public class ClipboardService extends IClipboard.Stub {
    private ClipData mPrimaryClip;
    private final RemoteCallbackList<IOnPrimaryClipChangedListener> mPrimaryClipListeners
            = new RemoteCallbackList<IOnPrimaryClipChangedListener>();

    /**
     * Instantiates the clipboard.
     */
    public ClipboardService(Context context) { }

    public void setPrimaryClip(ClipData clip) {
        synchronized (this) {
            if (clip != null && clip.getItemCount() <= 0) {
                throw new IllegalArgumentException("No items");
            }
            mPrimaryClip = clip;
            final int n = mPrimaryClipListeners.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mPrimaryClipListeners.getBroadcastItem(i).dispatchPrimaryClipChanged();
                } catch (RemoteException e) {

                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
            mPrimaryClipListeners.finishBroadcast();
        }
    }
    
    public ClipData getPrimaryClip() {
        synchronized (this) {
            return mPrimaryClip;
        }
    }

    public ClipDescription getPrimaryClipDescription() {
        synchronized (this) {
            return new ClipDescription(mPrimaryClip);
        }
    }

    public boolean hasPrimaryClip() {
        synchronized (this) {
            return mPrimaryClip != null;
        }
    }

    public void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener) {
        synchronized (this) {
            mPrimaryClipListeners.register(listener);
        }
    }

    public void removePrimaryClipChangedListener(IOnPrimaryClipChangedListener listener) {
        synchronized (this) {
            mPrimaryClipListeners.unregister(listener);
        }
    }

    public boolean hasClipboardText() {
        synchronized (this) {
            if (mPrimaryClip != null) {
                CharSequence text = mPrimaryClip.getItem(0).getText();
                return text != null && text.length() > 0;
            }
            return false;
        }
    }
}
