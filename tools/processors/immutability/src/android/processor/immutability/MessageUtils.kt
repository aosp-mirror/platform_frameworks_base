/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.processor.immutability

object MessageUtils {

    fun classNotImmutableFailure(className: String) = "$className should be marked @Immutable"

    fun memberNotMethodFailure() = "Member must be a method"

    fun nonInterfaceClassFailure() = "Class was not an interface"

    fun nonInterfaceReturnFailure(prefix: String, index: Int = -1) =
        if (prefix.isEmpty()) {
            "Type at index $index was not an interface"
        } else {
            "$prefix was not an interface"
        }

    fun genericTypeKindFailure(typeName: CharSequence) = "TypeKind $typeName unsupported"

    fun arrayFailure() = "Array types are not supported as they can be mutated by callers"

    fun nonInterfaceReturnFailure() = "Must return an interface"

    fun voidReturnFailure() = "Cannot return void"

    fun staticNonFinalFailure() = "Static member must be final"
}