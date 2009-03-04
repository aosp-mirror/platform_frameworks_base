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
package org.apache.harmony.awt;

import java.awt.*;

//???AWT
//import org.apache.harmony.awt.datatransfer.*;
import org.apache.harmony.awt.internal.nls.Messages;
import org.apache.harmony.awt.wtk.*;


public final class ContextStorage {

    private static volatile boolean multiContextMode = false;
    private volatile boolean shutdownPending = false;

    private static final ContextStorage globalContext = new ContextStorage();

    private Toolkit toolkit;
    private ComponentInternals componentInternals;
    //???AWT: private DTK dtk;
    private WTK wtk;
    private GraphicsEnvironment graphicsEnvironment;

    private class ContextLock {}
    private final Object contextLock = new ContextLock();
    private final Synchronizer synchronizer = new Synchronizer();

    public static void activateMultiContextMode() {
        // TODO: checkPermission
        multiContextMode = true;
    }

    public static void setDefaultToolkit(Toolkit newToolkit) {
        // TODO: checkPermission
        getCurrentContext().toolkit = newToolkit;
    }

    public static Toolkit getDefaultToolkit() {
        return getCurrentContext().toolkit;
    }

    //???AWT
    /*
    public static void setDTK(DTK dtk) {
        // TODO: checkPermission
        getCurrentContext().dtk = dtk;
    }

    public static DTK getDTK() {
        return getCurrentContext().dtk;
    }
    */

    public static Synchronizer getSynchronizer() {
        return getCurrentContext().synchronizer;
    }

    public static ComponentInternals getComponentInternals() {
        return getCurrentContext().componentInternals;
    }

    static void setComponentInternals(ComponentInternals internals) {
        // TODO: checkPermission
        getCurrentContext().componentInternals = internals;
    }

    public static Object getContextLock() {
        return getCurrentContext().contextLock;
    }

    public static WindowFactory getWindowFactory() {
        return getCurrentContext().wtk.getWindowFactory();
    }

    public static void setWTK(WTK wtk) {
        getCurrentContext().wtk = wtk;
    }

    public static NativeIM getNativeIM() {
        return getCurrentContext().wtk.getNativeIM();
    }

    public static NativeEventQueue getNativeEventQueue() {
        return getCurrentContext().wtk.getNativeEventQueue();
    }

    public static GraphicsEnvironment getGraphicsEnvironment() {
        return getCurrentContext().graphicsEnvironment;
    }

    public static void setGraphicsEnvironment(GraphicsEnvironment environment) {
        getCurrentContext().graphicsEnvironment = environment;
    }

    private static ContextStorage getCurrentContext() {
        return multiContextMode ? getContextThreadGroup().context : globalContext;
    }

    private static ContextThreadGroup getContextThreadGroup() {

        Thread thread = Thread.currentThread();
        ThreadGroup group = thread.getThreadGroup();
        while (group != null) {
            if (group instanceof ContextThreadGroup) {
                return (ContextThreadGroup)group;
            }
            group = group.getParent();
        }
        // awt.59=Application has run out of context thread group
        throw new RuntimeException(Messages.getString("awt.59")); //$NON-NLS-1$
    }
    
    public static boolean shutdownPending() {
        return getCurrentContext().shutdownPending;
    }

    void shutdown() {
        if (!multiContextMode) {
            return;
        }
        shutdownPending = true;

        //???AWT: componentInternals.shutdown();

        synchronized(contextLock) {
            toolkit = null;
            componentInternals = null;
            //???AWT: dtk = null;
            wtk = null;
            graphicsEnvironment = null;
        }
    }
    
}
