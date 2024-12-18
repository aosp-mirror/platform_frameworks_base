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

package com.android.server.clipboard;

import android.annotation.Nullable;
import android.content.ClipData;

import com.android.server.LocalServices;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ArcClipboardMonitor implements Consumer<ClipData> {
    private static final String TAG = "ArcClipboardMonitor";

    public interface ArcClipboardBridge {
        /**
         * Called when a clipboard content is updated.
         */
        void onPrimaryClipChanged(ClipData data);

        /**
         * Passes the callback to set a new clipboard content with a uid.
         */
        void setHandler(BiConsumer<ClipData, Integer> setAndroidClipboard);
    }

    private ArcClipboardBridge mBridge;
    private BiConsumer<ClipData, Integer> mAndroidClipboardSetter;

    ArcClipboardMonitor(final BiConsumer<ClipData, Integer> setAndroidClipboard) {
        mAndroidClipboardSetter = setAndroidClipboard;
        LocalServices.addService(ArcClipboardMonitor.class, this);
    }

    @Override
    public void accept(final @Nullable ClipData clip) {
        if (mBridge != null) {
            mBridge.onPrimaryClipChanged(clip);
        }
    }

    /**
     * Sets the other end of the clipboard bridge.
     */
    public void setClipboardBridge(ArcClipboardBridge bridge) {
        mBridge = bridge;
        mBridge.setHandler(mAndroidClipboardSetter);
    }
}
