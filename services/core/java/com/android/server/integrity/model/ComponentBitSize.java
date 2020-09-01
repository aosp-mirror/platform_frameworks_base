/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.integrity.model;

import android.content.integrity.Rule;

/**
 * A helper class containing information about the binary representation of different {@link Rule}
 * components.
 */
public final class ComponentBitSize {
    public static final int FORMAT_VERSION_BITS = 8;

    public static final int EFFECT_BITS = 3;
    public static final int KEY_BITS = 4;
    public static final int OPERATOR_BITS = 3;
    public static final int CONNECTOR_BITS = 2;
    public static final int SEPARATOR_BITS = 3;
    public static final int VALUE_SIZE_BITS = 8;
    public static final int IS_HASHED_BITS = 1;

    public static final int ATOMIC_FORMULA_START = 0;
    public static final int COMPOUND_FORMULA_START = 1;
    public static final int COMPOUND_FORMULA_END = 2;
    public static final int INSTALLER_ALLOWED_BY_MANIFEST_START = 3;

    public static final int DEFAULT_FORMAT_VERSION = 1;
    public static final int SIGNAL_BIT = 1;

    public static final int BYTE_BITS = 8;
}
