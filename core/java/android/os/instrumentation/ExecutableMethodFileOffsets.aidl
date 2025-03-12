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
 * Represents the location of the code for a compiled method within a process'
 * memory.
 * {@hide}
 */
@JavaDerive(toString=true)
parcelable ExecutableMethodFileOffsets {
  /**
   * The OS path of the containing file (could be virtual).
   */
  @utf8InCpp String containerPath;
  /**
   * The offset of the containing file within the process' memory.
   */
  long containerOffset;
  /**
   * The offset of the method within the containing file.
   */
  long methodOffset;
}
