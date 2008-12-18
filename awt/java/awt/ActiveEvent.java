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

package java.awt;

/**
 * This interface defines events that know how to dispatch themselves. Such
 * event can be placed upon the event queue and its dispatch method will be
 * called when the event is dispatched.
 * 
 * @since Android 1.0
 */
public interface ActiveEvent {

    /**
     * Dispatches the event to the listeners of the event's source, or does
     * whatever it is this event is supposed to do.
     */
    public void dispatch();

}
