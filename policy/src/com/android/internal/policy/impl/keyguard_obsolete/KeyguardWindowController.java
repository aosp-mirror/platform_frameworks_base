/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard_obsolete;

/**
 * Interface passed to the keyguard view, for it to call up to control
 * its containing window.
 */
public interface KeyguardWindowController {
    /**
     * Control whether the window needs input -- that is if it has
     * text fields and thus should allow input method interaction.
     */
    void setNeedsInput(boolean needsInput);
}
