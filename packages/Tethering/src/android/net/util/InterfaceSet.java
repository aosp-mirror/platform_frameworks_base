/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;


/**
 * @hide
 */
public class InterfaceSet {
    public final Set<String> ifnames;

    public InterfaceSet(String... names) {
        final Set<String> nameSet = new HashSet<>();
        for (String name : names) {
            if (name != null) nameSet.add(name);
        }
        ifnames = Collections.unmodifiableSet(nameSet);
    }

    @Override
    public String toString() {
        final StringJoiner sj = new StringJoiner(",", "[", "]");
        for (String ifname : ifnames) sj.add(ifname);
        return sj.toString();
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null
                && obj instanceof InterfaceSet
                && ifnames.equals(((InterfaceSet) obj).ifnames);
    }
}
