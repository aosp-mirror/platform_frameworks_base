/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.android;

import com.android.ide.common.rendering.api.SessionParams;

/**
 * This contains all known keys for the {@link SessionParams#getFlag(SessionParams.Key)}.
 * <p/>
 * The IDE has its own copy of this class which may be newer or older than this one.
 * <p/>
 * Constants should never be modified or removed from this class.
 */
public final class SessionParamsFlags {

    public static final SessionParams.Key<String> FLAG_KEY_ROOT_TAG =
            new SessionParams.Key<String>("rootTag", String.class);

    // Disallow instances.
    private SessionParamsFlags() {}
}
