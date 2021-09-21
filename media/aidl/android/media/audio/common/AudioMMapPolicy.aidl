/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.media.audio.common;

/**
 * Audio MMAP policy describe how the aaudio MMAP feature is used.
 * {@hide}
 */
@Backing(type="int")
@VintfStability
enum AudioMMapPolicy {
    /**
     * The policy is unspecified.
     */
    UNSPECIFIED = 0,
    /**
     * The MMAP feature is disabled and never used.
     */
    NEVER       = 1,
    /**
     * If MMAP feature works then uses it. Otherwise, fall back to something else.
     */
    AUTO        = 2,
    /**
     * The MMAP feature must be used. If not available then fail.
     */
    ALWAYS      = 3,
}
