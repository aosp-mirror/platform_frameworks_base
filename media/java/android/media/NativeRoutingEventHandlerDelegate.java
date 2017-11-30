/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.media;

import android.os.Handler;

/**
 * Helper class {@link AudioTrack}, {@link AudioRecord}, {@link MediaPlayer} and {@link MediaRecorder}
 * to handle the forwarding of native events to the appropriate listener
 * (potentially) handled in a different thread.
 * @hide
 */
class NativeRoutingEventHandlerDelegate {
    private AudioRouting mAudioRouting;
    private AudioRouting.OnRoutingChangedListener mOnRoutingChangedListener;
    private Handler mHandler;

    NativeRoutingEventHandlerDelegate(final AudioRouting audioRouting,
            final AudioRouting.OnRoutingChangedListener listener, Handler handler) {
        mAudioRouting = audioRouting;
        mOnRoutingChangedListener = listener;
        mHandler = handler;
    }

    void notifyClient() {
        if (mHandler != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mOnRoutingChangedListener != null) {
                        mOnRoutingChangedListener.onRoutingChanged(mAudioRouting);
                    }
                }
            });
        }
    }
}
