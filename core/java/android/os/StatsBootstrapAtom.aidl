/*
 * Copyright 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.os;

import android.os.StatsBootstrapAtomValue;

/*
 * Generic encapsulation of an atom for bootstrap processes to log.
 *
 * @hide
 */
parcelable StatsBootstrapAtom {
    /*
     * Atom ID. Must be between 1 - 10,000.
     */
    int atomId;
    /*
     * Vector of fields in the order of the atom definition.
     */
    StatsBootstrapAtomValue[] values;
 }