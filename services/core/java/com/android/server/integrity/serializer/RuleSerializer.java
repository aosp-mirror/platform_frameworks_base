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

package com.android.server.integrity.serializer;

import android.content.integrity.Rule;

import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

/** A helper class to serialize rules from the {@link Rule} model. */
public interface RuleSerializer {

    /** Serialize rules to an output stream */
    void serialize(
            List<Rule> rules,
            Optional<Integer> formatVersion,
            OutputStream ruleFileOutputStream,
            OutputStream indexingFileOutputStream)
            throws RuleSerializeException;

    /** Serialize rules to a ByteArray. */
    byte[] serialize(List<Rule> rules, Optional<Integer> formatVersion)
            throws RuleSerializeException;
}
