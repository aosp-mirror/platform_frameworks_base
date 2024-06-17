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

package com.android.server.audio;

import android.annotation.NonNull;
import android.media.AudioAttributes;

public interface PlayerFocusEnforcer {

    /**
     * Ducks the players associated with the "loser" focus owner (i.e. same UID). Returns true if
     * at least one active player was found and ducked, false otherwise.
     * @param winner
     * @param loser
     * @return
     */
    boolean duckPlayers(@NonNull FocusRequester winner, @NonNull FocusRequester loser,
                               boolean forceDuck);

    /**
     * Restore the initial state of any players that had had a volume ramp applied as the result
     * of a duck or fade out through {@link #duckPlayers(FocusRequester, FocusRequester, boolean)}
     * or {@link #fadeOutPlayers(FocusRequester, FocusRequester)}
     * @param winner
     */
    void restoreVShapedPlayers(@NonNull FocusRequester winner);

    /**
     * Mute players at the beginning of a call
     * @param usagesToMute array of {@link android.media.AudioAttributes} usages to mute
     */
    void mutePlayersForCall(int[] usagesToMute);

    /**
     * Unmute players at the end of a call
     */
    void unmutePlayersForCall();

    /**
     * Fade out whatever is still playing after the non-transient focus change
     * @param winner the new non-transient focus owner
     * @param loser the previous focus owner
     * @return true if there were any active players for the loser that qualified for being
     *         faded out (because of audio attributes, or player types), and as such were faded
     *         out.
     */
    boolean fadeOutPlayers(@NonNull FocusRequester winner, @NonNull FocusRequester loser);

    /**
     * Mark this UID as no longer playing a role in focus enforcement
     * @param uid
     */
    void forgetUid(int uid);

    /**
     * Get the fade out duration currently active for the given usage
     * @param aa The {@link android.media.AudioAttributes}
     * @return fade out duration in milliseconds
     */
    long getFadeOutDurationMillis(@NonNull AudioAttributes aa);

    /**
     * Returns the delay to fade-in the offending players
     * @param aa The {@link android.media.AudioAttributes}
     * @return delay in milliseconds
     */
    long getFadeInDelayForOffendersMillis(@NonNull AudioAttributes aa);

    /**
     * Check if the fade should be enforced
     *
     * @return {@code true} if fade should be enforced, {@code false} otherwise
     */
    boolean shouldEnforceFade();
}