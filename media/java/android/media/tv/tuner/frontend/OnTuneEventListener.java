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

package android.media.tv.tuner.frontend;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.FrontendEventType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Listens for tune events.
 *
 * @hide
 */
@SystemApi
public interface OnTuneEventListener {

    /** @hide */
    @IntDef(prefix = "SIGNAL_", value = {SIGNAL_LOCKED, SIGNAL_NO_SIGNAL, SIGNAL_LOST_LOCK})
    @Retention(RetentionPolicy.SOURCE)
    @interface TuneEvent {}

    /** The frontend has locked to the signal specified by the tune method. */
    int SIGNAL_LOCKED = FrontendEventType.LOCKED;
    /** The frontend is unable to lock to the signal specified by the tune method. */
    int SIGNAL_NO_SIGNAL = FrontendEventType.NO_SIGNAL;
    /** The frontend has lost the lock to the signal specified by the tune method. */
    int SIGNAL_LOST_LOCK = FrontendEventType.LOST_LOCK;

    /** Tune Event from the frontend */
    void onTuneEvent(@TuneEvent int tuneEvent);
}
