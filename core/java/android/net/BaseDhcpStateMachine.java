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
 * limitations under the License.
 */

package android.net;

import com.android.internal.util.StateMachine;

/**
 * Interface that must be implemented by DHCP state machines.
 *
 * This is an abstract class instead of a Java interface so that callers can just declare an object
 * of this type and be able to call all the methods defined by either StateMachine or this class.
 *
 * @hide
 */
public abstract class BaseDhcpStateMachine extends StateMachine {
    protected BaseDhcpStateMachine(String tag) {
        super(tag);
    }
    public abstract void registerForPreDhcpNotification();
    public abstract void doQuit();
}
