/**
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.soundtrigger;

import android.util.Slog;

import com.android.server.utils.EventLogger.Event;

import java.util.UUID;

public abstract class SoundTriggerEvent extends Event {

    @Override
    public Event printLog(int type, String tag) {
        switch (type) {
            case ALOGI:
                Slog.i(tag, eventToString());
                break;
            case ALOGE:
                Slog.e(tag, eventToString());
                break;
            case ALOGW:
                Slog.w(tag, eventToString());
                break;
            case ALOGV:
            default:
                Slog.v(tag, eventToString());
        }
        return this;
    }

    public static class ServiceEvent extends SoundTriggerEvent {
        public enum Type {
            ATTACH,
            LIST_MODULE,
            DETACH,
        }

        private final Type mType;
        private final String mPackageName;
        private final String mErrorString;

        public ServiceEvent(Type type) {
            this(type, null, null);
        }

        public ServiceEvent(Type type, String packageName) {
            this(type, packageName, null);
        }

        public ServiceEvent(Type type, String packageName, String errorString) {
            mType = type;
            mPackageName = packageName;
            mErrorString = errorString;
        }

        @Override
        public String eventToString() {
            var res = new StringBuilder(String.format("%-12s", mType.name()));
            if (mErrorString != null) {
                res.append(" ERROR: ").append(mErrorString);
            }
            if (mPackageName != null) {
                res.append(" for: ").append(mPackageName);
            }
            return res.toString();
        }
    }

    public static class SessionEvent extends SoundTriggerEvent {
        public enum Type {
            // Downward calls
            START_RECOGNITION,
            STOP_RECOGNITION,
            LOAD_MODEL,
            UNLOAD_MODEL,
            UPDATE_MODEL,
            DELETE_MODEL,
            START_RECOGNITION_SERVICE,
            STOP_RECOGNITION_SERVICE,
            GET_MODEL_STATE,
            SET_PARAMETER,
            GET_MODULE_PROPERTIES,
            DETACH,
            // Callback events
            RECOGNITION,
            RESUME,
            RESUME_FAILED,
            PAUSE,
            PAUSE_FAILED,
            RESOURCES_AVAILABLE,
            MODULE_DIED
        }

        private final UUID mModelUuid;
        private final Type mType;
        private final String mErrorString;

        public SessionEvent(Type type, UUID modelUuid, String errorString) {
            mType = type;
            mModelUuid = modelUuid;
            mErrorString = errorString;
        }

        public SessionEvent(Type type, UUID modelUuid) {
            this(type, modelUuid, null);
        }

        @Override
        public String eventToString() {
            var res = new StringBuilder(String.format("%-25s", mType.name()));
            if (mErrorString != null) {
                res.append(" ERROR: ").append(mErrorString);
            }
            if (mModelUuid != null) {
                res.append(" for: ").append(mModelUuid);
            }
            return res.toString();
        }
    }
}
