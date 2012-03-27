/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.media.effect;

/**
 * Some effects may issue callbacks to inform the host of changes to the effect state. This is the
 * listener interface for receiving those callbacks.
 */
public interface EffectUpdateListener {

    /**
     * Called when the effect state is updated.
     *
     * @param effect The effect that has been updated.
     * @param info A value that gives more information about the update. See the effect's
     *             documentation for more details on what this object is.
     */
    public void onEffectUpdated(Effect effect, Object info);

}

