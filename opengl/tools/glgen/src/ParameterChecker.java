/*
 * Copyright (C) 2006 The Android Open Source Project
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

import java.io.BufferedReader;
import java.util.HashMap;

public class ParameterChecker {

    HashMap<String,String[]> map = new HashMap<String,String[]>();

    public ParameterChecker(BufferedReader reader) throws Exception {
        String s;
        while ((s = reader.readLine()) != null) {
            String[] tokens = s.split("\\s");
            map.put(tokens[0], tokens);
        }
    }

    public String[] getChecks(String functionName) {
        String[] checks = map.get(functionName);
        if (checks == null &&
            (functionName.endsWith("fv") ||
             functionName.endsWith("xv") ||
             functionName.endsWith("iv"))) {
            functionName = functionName.substring(0, functionName.length() - 2);
            checks = map.get(functionName);
        }
        return checks;
    }
}
