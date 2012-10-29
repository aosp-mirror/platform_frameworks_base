/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

/**
 * Interface implemented by ViewGroup-derived layouts that implement
 * special logic for presenting security challenges to the user.
 */
public interface ChallengeLayout {
    /**
     * @return true if the security challenge area of this layout is currently visible
     */
    boolean isChallengeShowing();

    /**
     * @return true if the challenge area significantly overlaps other content
     */
    boolean isChallengeOverlapping();

    /**
     * Show or hide the challenge layout.
     *
     * If you want to show the challenge layout in bouncer mode where applicable,
     * use {@link #showBouncer()} instead.
     *
     * @param b true to show, false to hide
     */
    void showChallenge(boolean b);

    /**
     * Show the bouncer challenge. This may block access to other child views.
     */
    void showBouncer();
}
