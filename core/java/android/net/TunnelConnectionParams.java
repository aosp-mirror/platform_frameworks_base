/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.net;

/**
 * TunnelConnectionParams represents a configuration to set up a tunnel connection.
 *
 * <p>Concrete implementations for a control plane protocol should implement this interface.
 * Subclasses should be immutable data classes containing connection, authentication and
 * authorization parameters required to establish a tunnel connection.
 *
 * @see android.net.ipsec.ike.IkeTunnelConnectionParams
 */
// TODO:b/186071626 Remove TunnelConnectionParams when non-updatable API stub can resolve
// IkeTunnelConnectionParams
public interface TunnelConnectionParams {}
