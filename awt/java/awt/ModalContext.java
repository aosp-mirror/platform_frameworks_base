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
 * @author Pavel Dolgov
 * @version $Revision$
 */
package java.awt;

/**
 *
 * The context for nested event loop. It can be dialog, popup menu etc.
 */
class ModalContext {

    private boolean running = false;

    private final Toolkit toolkit;

    ModalContext() {
        toolkit = Toolkit.getDefaultToolkit();
    }

    /**
     * Set up and run modal loop in this context
     *
     */
    void runModalLoop() {
        running = true;
        toolkit.dispatchThread.runModalLoop(this);
    }

    /**
     * Leave the modal loop running in this context
     * This method doesn't stops the loop immediately,
     * it just sets the flag that says the modal loop to stop
     *
     */
    void endModalLoop() {
        running = false;
    }

    /**
     *
     * @return modal loop is currently running in this context
     */
    boolean isModalLoopRunning() {
        return running;
    }

}
