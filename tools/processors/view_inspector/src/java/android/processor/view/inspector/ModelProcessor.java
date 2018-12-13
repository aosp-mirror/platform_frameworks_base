/*
 * Copyright 2019 The Android Open Source Project
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

import javax.lang.model.element.Element;

/**
 * An interface for annotation processors that operate on a single element and a class model.
 */
public interface ModelProcessor {
    /**
     * Process the supplied element, mutating the model as needed.
     *
     * @param element The annotated element to operate on
     * @param model The model this element should be merged into
     */
    void process(Element element, InspectableClassModel model);
}
