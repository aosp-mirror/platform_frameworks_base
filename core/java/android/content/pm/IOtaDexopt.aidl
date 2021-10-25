/*
**
** Copyright 2016, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.content.pm;

/**
 * A/B OTA dexopting service.
 *
 * {@hide}
 */
interface IOtaDexopt {
    /**
     * Prepare for A/B OTA dexopt. Initialize internal structures.
     *
     * Calls to the other methods are only valid after a call to prepare. You may not call
     * prepare twice without a cleanup call.
     */
    void prepare();

    /**
     * Clean up all internal state.
     */
    void cleanup();

    /**
     * Check whether all updates have been performed.
     */
    boolean isDone();

    /**
     * Return the progress (0..1) made in this session. When {@link #isDone() isDone} returns
     * true, the progress value will be 1.
     */
    float getProgress();

    /**
     * Optimize the next package. Note: this command is synchronous, that is, only returns after
     * the package has been dexopted (or dexopting failed).
     *
     * Note: this will be removed after a transition period. Use nextDexoptCommand instead.
     */
    void dexoptNextPackage();

    /**
     * Get the optimization parameters for the next package.
     */
    String nextDexoptCommand();
}
