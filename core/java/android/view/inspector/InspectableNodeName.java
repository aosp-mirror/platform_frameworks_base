/*
 * Copyright 2018 The Android Open Source Project
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

package android.view.inspector;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.TestApi;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks the node name to display to a developer in the inspection tree.
 *
 * This annotation is optional to marking a class as inspectable. If it is omitted, the node name
 * will be inferred using the semantics of {@link Class#getSimpleName()}. The fully qualified class
 * name is always available in the tree, this is for display purposes only. If a class is inflated
 * from XML and the tag it inflates from does not match its simple name, this annotation should be
 * used to inform the inspector to display the XML tag name in the inspection tree view.
 *
 * This annotation does not inherit. If a class extends an annotated parent class, but does not
 * annotate itself, its node name will be inferred from its Java name.
 *
 * @see InspectionCompanion#getNodeName()
 * @hide
 */
@Target({TYPE})
@Retention(SOURCE)
@TestApi
public @interface InspectableNodeName {
    /**
     * The display name for nodes of this type.
     *
     * @return The name for nodes of this type
     */
    String value();
}
