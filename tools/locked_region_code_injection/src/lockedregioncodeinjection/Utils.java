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

import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

public class Utils {

    public static final int ASM_VERSION = Opcodes.ASM9;

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

    /**
     * Returns a single {@link LockTarget} from a string.  The target is a comma-separated list of
     * the target class, the request method, the release method, and a boolean which is true if this
     * is a scoped target and false if this is a legacy target.  The boolean is optional and
     * defaults to true.
     */
    public static LockTarget getScopedTarget(String arg) {
        String[] c = arg.split(",");
        if (c.length == 3) {
          return new LockTarget(c[0], c[1], c[2], true);
        } else if (c.length == 4) {
            if (c[3].equals("true")) {
                return new LockTarget(c[0], c[1], c[2], true);
            } else if (c[3].equals("false")) {
                return new LockTarget(c[0], c[1], c[2], false);
            } else {
                System.err.println("illegal target parameter \"" + c[3] + "\"");
            }
        }
        // Fall through
        throw new RuntimeException("invalid scoped target format");
    }
}
