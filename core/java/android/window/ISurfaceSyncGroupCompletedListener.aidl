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

package android.window;

/**
 * A listener used to notify when a SurfaceSyncGroup has completed. This doesn't indicate anything
 * about the state of what's on screen, but means everything in the SurfaceSyncGroup has
 * completed, including all children. This is similar to
 * {@link SurfaceSyncGroup#addSyncCompleteCallback}, except allows the callback to be invoked to
 * another process.
 *
 * @hide
 */
interface ISurfaceSyncGroupCompletedListener {
    /**
     * Invoked when the SurfaceSyncGroup has completed.
     */
    oneway void onSurfaceSyncGroupComplete();
}