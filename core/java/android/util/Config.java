/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.util;

/**
 * @deprecated This class is not useful, it just returns the same value for
 * all constants, and has always done this.  Do not use it.
 */
@Deprecated
public final class Config {
    /** @hide */ public Config() {}

    /**
     * @deprecated Always false.
     */
    @Deprecated
    public static final boolean DEBUG = false;

    /**
     * @deprecated Always true.
     */
    @Deprecated
    public static final boolean RELEASE = true;

    /**
     * @deprecated Always false.
     */
    @Deprecated
    public static final boolean PROFILE = false;

    /**
     * @deprecated Always false.
     */
    @Deprecated
    public static final boolean LOGV = false;

    /**
     * @deprecated Always true.
     */
    @Deprecated
    public static final boolean LOGD = true;
}
