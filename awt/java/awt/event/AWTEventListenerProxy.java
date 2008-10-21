/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Michael Danilov
 * @version $Revision$
 */
package java.awt.event;

import java.awt.AWTEvent;

import java.util.EventListenerProxy;

import org.apache.harmony.awt.internal.nls.Messages;

public class AWTEventListenerProxy extends EventListenerProxy implements AWTEventListener {

    private AWTEventListener listener;
    private long eventMask;

    public AWTEventListenerProxy(long eventMask, AWTEventListener listener) {
        super(listener);

        // awt.193=Listener can't be zero
        assert listener != null : Messages.getString("awt.193"); //$NON-NLS-1$

        this.listener = listener;
        this.eventMask = eventMask;
    }

    public void eventDispatched(AWTEvent evt) {
        listener.eventDispatched(evt);
    }

    public long getEventMask() {
        return eventMask;
    }

}
