/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.processor.property_cache;

public class ParamComposer {
    private String mType;
    private String mName;

    /** Creates ParamComposer with given type and name.
     *
     * @param type type of parameter.
     * @param name name of parameter.
     */
    public ParamComposer(String type, String name) {
        mType = type;
        mName = name;
    }

    /** Returns name of parameter.
     *
     * @return name of parameter.
     */
    public String getName() {
        if (mName != null) {
            return mName;
        }
        return Constants.EMPTY_STRING;
    }

    /** Returns name of parameter for next parameter followed by comma.
     *
     * @return name of parameter for next parameter if exists, empty string otherwise.
     */
    public String getNextName() {
        if (!getName().isEmpty()) {
            return ", " + getName();
        }
        return Constants.EMPTY_STRING;
    }

    /**
     * Returns type of parameter.
     *
     * @return type of parameter.
     */
    public String getType() {
        if (mType != null) {
            return mType;
        }
        return Constants.EMPTY_STRING;
    }

    /**
     * Returns type and name of parameter.
     *
     * @return type and name of parameter if exists, empty string otherwise.
     */
    public String getParam() {
        if (!getType().isEmpty() && !getName().isEmpty()) {
            return getType() + " " + getName();
        }
        return Constants.EMPTY_STRING;
    }

    /**
     * Returns type and name of parameter for next parameter followed by comma.
     *
     * @return type and name of parameter for next parameter if exists, empty string otherwise.
     */
    public String getNextParam() {
        if (!getType().isEmpty() && !getName().isEmpty()) {
            return ", " + getParam();
        }
        return Constants.EMPTY_STRING;
    }

    /**
     * Returns comment for parameter.
     *
     * @param description of parameter.
     * @return comment for parameter if exists, empty string otherwise.
     */
    public String getParamComment(String description) {
        if (!getType().isEmpty() && !getName().isEmpty()) {
            return "\n    * @param " + getName() + " - " + description;
        }
        return Constants.EMPTY_STRING;
    }
}
