/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package lockedregioncodeinjection;

import java.util.List;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.analysis.BasicValue;

public class LockTargetState extends BasicValue {
    private final List<LockTarget> lockTargets;

    /**
     * @param type
     */
    public LockTargetState(Type type, List<LockTarget> lockTargets) {
        super(type);
        this.lockTargets = lockTargets;
    }

    public List<LockTarget> getTargets() {
        return lockTargets;
    }
}
