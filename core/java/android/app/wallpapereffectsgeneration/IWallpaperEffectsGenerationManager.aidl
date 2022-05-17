/**
 * Copyright (c) 2022, The Android Open Source Project
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

package android.app.wallpapereffectsgeneration;

import android.app.wallpapereffectsgeneration.CinematicEffectRequest;
import android.app.wallpapereffectsgeneration.CinematicEffectResponse;
import android.app.wallpapereffectsgeneration.ICinematicEffectListener;

/**
 * Used by {@link android.app.wallpapereffectsgeneration.WallpaperEffectsGenerationManager}
 * to to generate effects.
 *
 * @hide
 */
oneway interface IWallpaperEffectsGenerationManager {
  void generateCinematicEffect(in CinematicEffectRequest request,
        in ICinematicEffectListener listener);

  void returnCinematicEffectResponse(in CinematicEffectResponse response);
}