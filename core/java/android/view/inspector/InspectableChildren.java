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
 * Marks a getter for an inspectable node's inspectable children.
 *
 * This annotation can be applied to any getter that returns a collection of objects, either an
 * array, an {@link Iterable} or a {@link java.util.Iterator}. The getter may return null, which
 * will be treated as an empty collection. Additionally, the inspector will discard any null
 * entries in the collection.
 *
 * By default, this annotation is inherited. At runtime, the inspector introspects on the class
 * hierachy and uses the annotated getter from the bottommost class, if different from any
 * annoated getters of the parent class. If a class inherits from a parent class with an annotated
 * getter, but does not include this annotation, the child class will be traversed using the
 * getter annotated on the parent. This holds true even if the child class overrides the getter.
 *
 * @see InspectionHelper#traverseChildren(Object, ChildTraverser)
 * @see InspectionHelper#hasChildTraversal()
 * @hide
 */
@Target({METHOD})
@Retention(SOURCE)
public @interface InspectableChildren {
}
