/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.util.IntArray;

import com.android.server.LocalServices;

/**
 * Class for system services to access extra AudioManager functionality. The
 * AudioService is responsible for registering an implementation with
 * {@link LocalServices}.
 *
 * @hide
 */
public abstract class AudioManagerInternal {

    public abstract void setRingerModeDelegate(RingerModeDelegate delegate);

    public abstract int getRingerModeInternal();

    public abstract void setRingerModeInternal(int ringerMode, String caller);

    public abstract void silenceRingerModeInternal(String caller);

    public abstract void updateRingerModeAffectedStreamsInternal();

    public abstract void setAccessibilityServiceUids(IntArray uids);

    /**
     * Add the UID for a new assistant service
     *
     * @param uid UID of the newly available assistants
     * @param owningUid UID of the actual assistant app, if {@code uid} is a isolated proc
     */
    public abstract void addAssistantServiceUid(int uid, int owningUid);

    /**
     * Remove the UID for an existing assistant service
     *
     * @param uid UID of the currently available assistant
     */
    public abstract void removeAssistantServiceUid(int uid);

    /**
     * Set the currently active assistant service UIDs
     * @param activeUids active UIDs of the assistant service
     */
    public abstract void setActiveAssistantServicesUids(IntArray activeUids);

    /**
     * Called by {@link com.android.server.inputmethod.InputMethodManagerService} to notify the UID
     * of the currently used {@link android.inputmethodservice.InputMethodService}.
     *
     * <p>The caller is expected to take care of any performance implications, e.g. by using a
     * background thread to call this method.</p>
     *
     * @param uid UID of the currently used {@link android.inputmethodservice.InputMethodService}.
     *            {@link android.os.Process#INVALID_UID} if no IME is active.
     */
    public abstract void setInputMethodServiceUid(int uid);

    public interface RingerModeDelegate {
        /** Called when external ringer mode is evaluated, returns the new internal ringer mode */
        int onSetRingerModeExternal(int ringerModeOld, int ringerModeNew, String caller,
                int ringerModeInternal, VolumePolicy policy);

        /** Called when internal ringer mode is evaluated, returns the new external ringer mode */
        int onSetRingerModeInternal(int ringerModeOld, int ringerModeNew, String caller,
                int ringerModeExternal, VolumePolicy policy);

        boolean canVolumeDownEnterSilent();

        int getRingerModeAffectedStreams(int streams);
    }
}
