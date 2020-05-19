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

package com.android.server.notification;

import android.util.StatsEvent;

/**
 * Wrapper for StatsEvent that enables unit testing.
 */
public class SysUiStatsEvent {

    static class Builder {
        private final StatsEvent.Builder mBuilder;

        protected Builder(StatsEvent.Builder builder) {
            mBuilder = builder;
        }

        public StatsEvent build() {
            return mBuilder.build();
        }

        public Builder setAtomId(int atomId) {
            mBuilder.setAtomId(atomId);
            return this;
        }

        public Builder writeInt(int value) {
            mBuilder.writeInt(value);
            return this;
        }

        public Builder addBooleanAnnotation(byte annotation, boolean value) {
            mBuilder.addBooleanAnnotation(annotation, value);
            return this;
        }

        public Builder writeString(String value) {
            mBuilder.writeString(value);
            return this;
        }

        public Builder writeBoolean(boolean value) {
            mBuilder.writeBoolean(value);
            return this;
        }
    }

    static class BuilderFactory {
        Builder newBuilder() {
            return new Builder(StatsEvent.newBuilder());
        }
    }
}
