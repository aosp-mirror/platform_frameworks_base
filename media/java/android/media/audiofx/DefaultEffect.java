/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media.audiofx;

/**
 * DefaultEffect is the base class for controlling default audio effects linked into the
 * Android audio framework.
 * <p>DefaultEffects are effects that get attached automatically to all AudioTracks,
 * AudioRecords, and MediaPlayer instances meeting some criteria.
 * <p>Applications should not use the DefaultEffect class directly but one of its derived classes
 * to control specific types of defaults:
 * <ul>
 *   <li> {@link android.media.audiofx.SourceDefaultEffect}</li>
 *   <li> {@link android.media.audiofx.StreamDefaultEffect}</li>
 * </ul>
 * <p>Creating a DefaultEffect object will register the corresponding effect engine as a default
 * for the specified criteria. Whenever an audio session meets the criteria, an AudioEffect will
 * be created and attached to it using the specified priority.
 * @hide
 */

public abstract class DefaultEffect {
    /**
     * System wide unique default effect ID.
     */
    int mId;
}
