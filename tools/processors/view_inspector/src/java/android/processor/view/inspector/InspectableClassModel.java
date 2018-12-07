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

package android.processor.view.inspector;

import com.squareup.javapoet.ClassName;

import java.util.Optional;

/**
 * Model of an inspectable class derived from annotations.
 *
 * This class does not use any {javax.lang.model} objects to facilitate building models for testing
 * {@link InspectionCompanionGenerator}.
 */
public final class InspectableClassModel {
    private final ClassName mClassName;
    private Optional<String> mNodeName = Optional.empty();

    /**
     * @param className The name of the modeled class
     */
    public InspectableClassModel(ClassName className) {
        mClassName = className;
    }

    public ClassName getClassName() {
        return mClassName;
    }

    public Optional<String> getNodeName() {
        return mNodeName;
    }

    public void setNodeName(Optional<String> nodeName) {
        mNodeName = nodeName;
    }
}
