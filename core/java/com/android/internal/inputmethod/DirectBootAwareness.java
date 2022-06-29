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

package com.android.internal.inputmethod;


import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;

import java.lang.annotation.Retention;

/**
 * Specifies the decided filtering mode regarding IMEs' DirectBoot awareness when querying IMEs.
 */
@Retention(SOURCE)
@IntDef({DirectBootAwareness.AUTO, DirectBootAwareness.ANY})
public @interface DirectBootAwareness {
    /**
     * The same semantics as {@link android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO}, that
     * is, if the user to be queried is still locked, then only DirectBoot-aware IMEs will be
     * matched.  If the user to be queried is already unlocked, then IMEs will not be filtered out
     * based on their DirectBoot awareness.
     */
    int AUTO = 0;
    /**
     * The same semantics as specifying <strong>both</strong>
     * {@link android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE} and
     * {@link android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE}, that is, IME will never
     * be filtered out based on their DirectBoot awareness, no matter whether the user to be queried
     * is still locked or already unlocked.
     */
    int ANY = 1;
}
