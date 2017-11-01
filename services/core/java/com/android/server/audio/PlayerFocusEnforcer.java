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

public interface PlayerFocusEnforcer {

    /**
     * Ducks the players associated with the "loser" focus owner (i.e. same UID). Returns true if
     * at least one active player was found and ducked, false otherwise.
     * @param winner
     * @param loser
     * @return
     */
    public boolean duckPlayers(FocusRequester winner, FocusRequester loser);

    public void unduckPlayers(FocusRequester winner);

    public void mutePlayersForCall(int[] usagesToMute);

    public void unmutePlayersForCall();
}