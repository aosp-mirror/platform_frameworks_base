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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a getter of a property on an inspectable node.
 *
 * This annotation is inherited by default. If a child class doesn't add it to a getter, but a
 * parent class does, the property will be inspected, even if the child overrides the definition
 * of the getter. If a child class defines a property of the same name of a property on the parent
 * but on a different getter, the inspector will use the child's getter when inspecting instances
 * of the child, and the parent's otherwise.
 *
 * @see InspectionHelper#mapProperties(PropertyMapper)
 * @see InspectionHelper#readProperties(Object, PropertyReader)
 * @hide
 */
@Target({METHOD})
@Retention(SOURCE)
public @interface InspectableProperty {
    /**
     * The name of the property.
     *
     * If left empty (the default), the property name will be inferred from the name of the getter
     * method.
     *
     * @return The name of the property.
     */
    String value() default "";
}
