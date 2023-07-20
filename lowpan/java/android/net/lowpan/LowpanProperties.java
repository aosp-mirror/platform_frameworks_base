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

package android.net.lowpan;

/** {@hide} */
public final class LowpanProperties {

    public static final LowpanProperty<int[]> KEY_CHANNEL_MASK =
            new LowpanStandardProperty("android.net.lowpan.property.CHANNEL_MASK", int[].class);

    public static final LowpanProperty<Integer> KEY_MAX_TX_POWER =
            new LowpanStandardProperty("android.net.lowpan.property.MAX_TX_POWER", Integer.class);

    /** @hide */
    private LowpanProperties() {}

    /** @hide */
    static final class LowpanStandardProperty<T> extends LowpanProperty<T> {
        private final String mName;
        private final Class<T> mType;

        LowpanStandardProperty(String name, Class<T> type) {
            mName = name;
            mType = type;
        }

        @Override
        public String getName() {
            return mName;
        }

        @Override
        public Class<T> getType() {
            return mType;
        }

        @Override
        public String toString() {
            return getName();
        }
    }
}
