/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.pm.parsing;

import com.android.internal.pm.parsing.pkg.ParsedPackage;

import java.io.File;

/** @hide */
public interface IPackageCacher {

    /**
     * Returns the cached parse result for {@code packageFile} for parse flags {@code flags},
     * or {@code null} if no cached result exists.
     */
    ParsedPackage getCachedResult(File packageFile, int flags);

    /**
     * Caches the parse result for {@code packageFile} with flags {@code flags}.
     */
    void cacheResult(File packageFile, int flags, ParsedPackage parsed);
}
