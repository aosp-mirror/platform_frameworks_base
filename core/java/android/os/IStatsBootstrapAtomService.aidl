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

import android.os.StatsBootstrapAtom;

/**
 * IBootstrapAtomService interface exposes an interface for processes that launch in the
 * bootstrap namespace to push atoms to statsd.
 *
 * @hide
 */
oneway interface IStatsBootstrapAtomService {
    /**
     * Push an atom to StatsBootstrapAtomService, which will forward it to statsd.
     *
     * @param atom - parcelled representation of the atom to log.
     *
     * Errors are reported as service specific errors.
     */
    void reportBootstrapAtom(in StatsBootstrapAtom atom);
}