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
import java.awt.ActiveEvent;

import org.apache.harmony.awt.internal.nls.Messages;

public class InvocationEvent extends AWTEvent implements ActiveEvent {

    private static final long serialVersionUID = 436056344909459450L;

    public static final int INVOCATION_FIRST = 1200;

    public static final int INVOCATION_DEFAULT = 1200;

    public static final int INVOCATION_LAST = 1200;

    protected Runnable runnable;

    protected Object notifier;

    protected boolean catchExceptions;

    private long when;
    private Throwable throwable;

    public InvocationEvent(Object source, Runnable runnable) {
        this(source, runnable, null, false);
    }

    public InvocationEvent(Object source, Runnable runnable, 
                           Object notifier, boolean catchExceptions) {
        this(source, INVOCATION_DEFAULT, runnable, notifier, catchExceptions);
    }

    protected InvocationEvent(Object source, int id, Runnable runnable,
            Object notifier, boolean catchExceptions)
    {
        super(source, id);

        // awt.18C=Cannot invoke null runnable
        assert runnable != null : Messages.getString("awt.18C"); //$NON-NLS-1$

        if (source == null) {
            // awt.18D=Source is null
            throw new IllegalArgumentException(Messages.getString("awt.18D")); //$NON-NLS-1$
        }
        this.runnable = runnable;
        this.notifier = notifier;
        this.catchExceptions = catchExceptions;

        throwable = null;
        when = System.currentTimeMillis();
    }

    public void dispatch() {
        if (!catchExceptions) {
            runAndNotify();
        } else {
            try {
                runAndNotify();
            } catch (Throwable t) {
                throwable = t;
            }
        }
    }

    private void runAndNotify() {
        if (notifier != null) {
            synchronized(notifier) {
                try {
                    runnable.run();
                } finally {
                    notifier.notifyAll();
                }
            }
        } else {
            runnable.run();
        }
    }

    public Exception getException() {
        return (throwable != null && throwable instanceof Exception) ?
                (Exception)throwable : null;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public long getWhen() {
        return when;
    }

    @Override
    public String paramString() {
        /* The format is based on 1.5 release behavior 
         * which can be revealed by the following code:
         * 
         * InvocationEvent e = new InvocationEvent(new Component(){},
         *       new Runnable() { public void run(){} });
         * System.out.println(e);
         */

        return ((id == INVOCATION_DEFAULT ? "INVOCATION_DEFAULT" : "unknown type") + //$NON-NLS-1$ //$NON-NLS-2$
                ",runnable=" + runnable + //$NON-NLS-1$
                ",notifier=" + notifier + //$NON-NLS-1$
                ",catchExceptions=" + catchExceptions + //$NON-NLS-1$
                ",when=" + when); //$NON-NLS-1$
    }

}
