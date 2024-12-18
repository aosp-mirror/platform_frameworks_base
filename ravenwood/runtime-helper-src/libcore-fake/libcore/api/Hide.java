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

package libcore.api;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that an API is hidden by default, in a similar fashion to the
 * <pre>@hide</pre> javadoc tag.
 *
 * <p>Note that, in order for this to work, metalava has to be invoked with
 * the flag {@code --hide-annotation libcore.api.Hide}.
 *
 * <p>This annotation should be used in {@code .annotated.java} stub files which
 * contain API inclusion information about {@code libcore/ojluni} classes, to
 * avoid patching the source files with <pre>@hide</pre> javadoc tags. All
 * build targets which consume these stub files should also apply the above
 * metalava flag.
 *
 * @hide
 */
@Target({TYPE, FIELD, METHOD, CONSTRUCTOR, ANNOTATION_TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface Hide {
}
