/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package android.telecom;

/**
 * Interface used to identify entities with which another entity can participate in a conference
 * call with.  The {@link ConnectionService} implementation will only recognize
 * {@link Conferenceable}s which are {@link Connection}s or {@link Conference}s.
 */
public abstract class Conferenceable {
    Conferenceable() {}
}
