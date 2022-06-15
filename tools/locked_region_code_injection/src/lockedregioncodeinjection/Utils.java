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

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;

public class Utils {

    public static final int ASM_VERSION = Opcodes.ASM6;

    /**
     * Reads a comma separated configuration similar to the Jack definition.
     */
    public static List<LockTarget> getTargetsFromLegacyJackConfig(String classList,
            String requestList, String resetList) {

        String[] classes = classList.split(",");
        String[] requests = requestList.split(",");
        String[] resets = resetList.split(",");

        int total = classes.length;
        assert requests.length == total;
        assert resets.length == total;

        List<LockTarget> config = new ArrayList<LockTarget>();

        for (int i = 0; i < total; i++) {
            config.add(new LockTarget(classes[i], requests[i], resets[i]));
        }

        return config;
    }
}
