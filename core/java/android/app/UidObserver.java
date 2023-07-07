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
 * Default implementation of {@link IUidObserver} for which all methods are
 * no-op. Subclasses can override the select methods they're interested in
 * handling.
 *
 * @hide
 */
public class UidObserver extends IUidObserver.Stub {
    @Override
    public void onUidActive(int uid) {
    }

    @Override
    public void onUidCachedChanged(int uid, boolean cached) {
    }

    @Override
    public void onUidGone(int uid, boolean disabled) {
    }

    @Override
    public void onUidIdle(int uid, boolean disabled) {
    }

    @Override
    public void onUidProcAdjChanged(int uid, int adj) {
    }

    @Override
    public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
    }
}
