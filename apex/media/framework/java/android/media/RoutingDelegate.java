/*
 * Copyright (C) 2018 The Android Open Source Project
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

class RoutingDelegate implements AudioRouting.OnRoutingChangedListener {
    private AudioRouting mAudioRouting;
    private AudioRouting.OnRoutingChangedListener mOnRoutingChangedListener;
    private Handler mHandler;

    RoutingDelegate(final AudioRouting audioRouting,
                    final AudioRouting.OnRoutingChangedListener listener,
                    Handler handler) {
        mAudioRouting = audioRouting;
        mOnRoutingChangedListener = listener;
        mHandler = handler;
    }

    public AudioRouting.OnRoutingChangedListener getListener() {
        return mOnRoutingChangedListener;
    }

    public Handler getHandler() {
        return mHandler;
    }

    @Override
    public void onRoutingChanged(AudioRouting router) {
        if (mOnRoutingChangedListener != null) {
            mOnRoutingChangedListener.onRoutingChanged(mAudioRouting);
        }
    }
}
