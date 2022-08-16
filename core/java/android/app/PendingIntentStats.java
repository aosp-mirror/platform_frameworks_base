/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app;

/**
 * Data about pending intents - size and count, per UID that sent the intent.
 * @hide
 */
public class PendingIntentStats {
    public final int uid;
    public final int count;
    public final int sizeKb;

    public PendingIntentStats(int uid, int count, int sizeKb) {
        this.uid = uid;
        this.count = count;
        this.sizeKb = sizeKb;
    }
}
