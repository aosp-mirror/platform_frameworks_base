/*
 * Copyright (C) 2010 The Android Open Source Project
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
package android.os;

/**
 * The exception that is thrown when an application attempts
 * to perform a networking operation on its main thread.
 *
 * <p>This is only thrown for applications targeting the Honeycomb
 * SDK or higher.  Applications targeting earlier SDK versions
 * are allowed to do networking on their main event loop threads,
 * but it's heavily discouraged.  See the document
 * <a href="{@docRoot}guide/practices/design/responsiveness.html">
 * Designing for Responsiveness</a>.
 *
 * <p>Also see {@link StrictMode}.
 */
public class NetworkOnMainThreadException extends RuntimeException {
}
