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

import com.android.server.notification.SysUiStatsEvent.Builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Wrapper for SysUiStatsEvent that implements validation.
 */
public class WrappedSysUiStatsEvent {

    static class WrappedBuilder extends Builder {
        private ArrayList<Object> mValues;
        private HashMap<Integer, HashMap<Byte, Object>> mAnnotations;
        private int mAtomId;
        private int mLastIndex;
        private boolean mBuilt;

        WrappedBuilder(StatsEvent.Builder builder) {
            super(builder);
            mValues = new ArrayList<>();
            mAnnotations = new HashMap<>();
            mValues.add(0); // proto fields are 1-based
        }

        @Override
        public Builder setAtomId(int atomId) {
            mAtomId = atomId;
            super.setAtomId(atomId);
            return this;
        }

        @Override
        public Builder writeInt(int value) {
            addValue(Integer.valueOf(value));
            super.writeInt(value);
            return this;
        }

        @Override
        public Builder addBooleanAnnotation(byte annotation, boolean value) {
            addAnnotation(annotation, Boolean.valueOf(value));
            super.addBooleanAnnotation(annotation, value);
            return this;
        }

        @Override
        public Builder writeString(String value) {
            addValue(value);
            super.writeString(value);
            return this;
        }

        @Override
        public Builder writeBoolean(boolean value) {
            addValue(Boolean.valueOf(value));
            super.writeBoolean(value);
            return this;
        }

        @Override
        public StatsEvent build() {
            mBuilt = true;
            return super.build();
        }

        public Object getValue(int index) {
            return index < mValues.size() ? mValues.get(index) : null;
        }

        /** useful to make assertTrue() statements more readable. */
        public boolean getBoolean(int index) {
            return (Boolean) mValues.get(index);
        }

        /** useful to make assertTrue() statements more readable. */
        public int getInt(int index) {
            return (Integer) mValues.get(index);
        }

        /** useful to make assertTrue() statements more readable. */
        public String getString(int index) {
            return (String) mValues.get(index);
        }

        private void addValue(Object value) {
            mLastIndex = mValues.size();
            mValues.add(value);
        }

        private void addAnnotation(byte annotation, Object value) {
            Integer key = Integer.valueOf(mLastIndex);
            if (!mAnnotations.containsKey(key)) {
                mAnnotations.put(key, new HashMap<>());
            }
            mAnnotations.get(key).put(Byte.valueOf(annotation), value);
        }

        public boolean getBooleanAnnotation(int i, byte a) {
            return ((Boolean) mAnnotations.get(Integer.valueOf(i)).get(Byte.valueOf(a)))
                    .booleanValue();
        }

        public int getAtomId() {
            return mAtomId;
        }
    }

    static class WrappedBuilderFactory extends SysUiStatsEvent.BuilderFactory {
        public List<WrappedBuilder> builders;

        WrappedBuilderFactory() {
            builders = new ArrayList<>();
        }

        @Override
        Builder newBuilder() {
            WrappedBuilder b = new WrappedBuilder(StatsEvent.newBuilder());
            builders.add(b);
            return b;
        }
    }
}
