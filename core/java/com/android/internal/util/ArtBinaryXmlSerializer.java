/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.util;

import android.annotation.NonNull;

import com.android.modules.utils.BinaryXmlSerializer;
import com.android.modules.utils.FastDataOutput;

import java.io.DataOutput;
import java.io.OutputStream;

/**
 * {@inheritDoc}
 * <p>
 * This encodes large code-points using 4-byte sequences and <em>is not</em> compatible with the
 * {@link DataOutput} API contract, which specifies that large code-points must be encoded with
 * 3-byte sequences.
 */
public class ArtBinaryXmlSerializer extends BinaryXmlSerializer {
    @NonNull
    @Override
    protected FastDataOutput obtainFastDataOutput(@NonNull OutputStream os) {
        return ArtFastDataOutput.obtain(os);
    }
}
