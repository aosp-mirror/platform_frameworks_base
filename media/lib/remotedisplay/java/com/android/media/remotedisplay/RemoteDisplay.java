/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.media.remotedisplay;

import android.annotation.SystemApi;
import android.media.RemoteDisplayState.RemoteDisplayInfo;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Represents a remote display that has been discovered.
 *
 * @hide
 */
@SystemApi
public class RemoteDisplay {
    private final RemoteDisplayInfo mMutableInfo;
    private RemoteDisplayInfo mImmutableInfo;

    /**
     * Status code: Indicates that the remote display is not available.
     */
    public static final int STATUS_NOT_AVAILABLE = RemoteDisplayInfo.STATUS_NOT_AVAILABLE;

    /**
     * Status code: Indicates that the remote display is in use by someone else.
     */
    public static final int STATUS_IN_USE = RemoteDisplayInfo.STATUS_IN_USE;

    /**
     * Status code: Indicates that the remote display is available for new connections.
     */
    public static final int STATUS_AVAILABLE = RemoteDisplayInfo.STATUS_AVAILABLE;

    /**
     * Status code: Indicates that the remote display is current connecting.
     */
    public static final int STATUS_CONNECTING = RemoteDisplayInfo.STATUS_CONNECTING;

    /**
     * Status code: Indicates that the remote display is connected and is mirroring
     * display contents.
     */
    public static final int STATUS_CONNECTED = RemoteDisplayInfo.STATUS_CONNECTED;

    /**
     * Volume handling: Output volume can be changed.
     */
    public static final int PLAYBACK_VOLUME_VARIABLE =
            RemoteDisplayInfo.PLAYBACK_VOLUME_VARIABLE;

    /**
     * Volume handling: Output volume is fixed.
     */
    public static final int PLAYBACK_VOLUME_FIXED =
            RemoteDisplayInfo.PLAYBACK_VOLUME_FIXED;

    /**
     * Creates a remote display with the specified name and id.
     */
    public RemoteDisplay(String id, String name) {
        if (TextUtils.isEmpty(id)) {
            throw new IllegalArgumentException("id must not be null or empty");
        }
        mMutableInfo = new RemoteDisplayInfo(id);
        setName(name);
    }

    public String getId() {
        return mMutableInfo.id;
    }

    public String getName() {
        return mMutableInfo.name;
    }

    public void setName(String name) {
        if (!Objects.equals(mMutableInfo.name, name)) {
            mMutableInfo.name = name;
            mImmutableInfo = null;
        }
    }

    public String getDescription() {
        return mMutableInfo.description;
    }

    public void setDescription(String description) {
        if (!Objects.equals(mMutableInfo.description, description)) {
            mMutableInfo.description = description;
            mImmutableInfo = null;
        }
    }

    public int getStatus() {
        return mMutableInfo.status;
    }

    public void setStatus(int status) {
        if (mMutableInfo.status != status) {
            mMutableInfo.status = status;
            mImmutableInfo = null;
        }
    }

    public int getVolume() {
        return mMutableInfo.volume;
    }

    public void setVolume(int volume) {
        if (mMutableInfo.volume != volume) {
            mMutableInfo.volume = volume;
            mImmutableInfo = null;
        }
    }

    public int getVolumeMax() {
        return mMutableInfo.volumeMax;
    }

    public void setVolumeMax(int volumeMax) {
        if (mMutableInfo.volumeMax != volumeMax) {
            mMutableInfo.volumeMax = volumeMax;
            mImmutableInfo = null;
        }
    }

    public int getVolumeHandling() {
        return mMutableInfo.volumeHandling;
    }

    public void setVolumeHandling(int volumeHandling) {
        if (mMutableInfo.volumeHandling != volumeHandling) {
            mMutableInfo.volumeHandling = volumeHandling;
            mImmutableInfo = null;
        }
    }

    public int getPresentationDisplayId() {
        return mMutableInfo.presentationDisplayId;
    }

    public void setPresentationDisplayId(int presentationDisplayId) {
        if (mMutableInfo.presentationDisplayId != presentationDisplayId) {
            mMutableInfo.presentationDisplayId = presentationDisplayId;
            mImmutableInfo = null;
        }
    }

    @Override
    public String toString() {
        return "RemoteDisplay{" + mMutableInfo.toString() + "}";
    }

    RemoteDisplayInfo getInfo() {
        if (mImmutableInfo == null) {
            mImmutableInfo = new RemoteDisplayInfo(mMutableInfo);
        }
        return mImmutableInfo;
    }
}
