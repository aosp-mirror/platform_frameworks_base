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

package android.os.instrumentation;

/**
 * Represents a JVM method, where class fields that make up its signature.
 * {@hide}
 */
@JavaDerive(toString=true)
parcelable MethodDescriptor {
  /**
    * Fully qualified class in reverse.domain.Naming
    */
  @utf8InCpp String fullyQualifiedClassName;
  /**
    * Name of the method.
    */
  @utf8InCpp String methodName;
  /**
    * Fully qualified types of method parameters, or string representations if primitive e.g. "int".
    */
  @utf8InCpp String[] fullyQualifiedParameters;
}
