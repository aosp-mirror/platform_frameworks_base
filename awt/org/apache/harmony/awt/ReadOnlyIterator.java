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

import java.util.Iterator;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * ReadOnlyIterator
 */
public final class ReadOnlyIterator<E> implements Iterator<E> {

    private final Iterator<E> it;

    public ReadOnlyIterator(Iterator<E> it) {
        if (it == null) {
            throw new NullPointerException();
        }
        this.it = it;
    }

    public void remove() {
        // awt.50=Iterator is read-only
        throw new UnsupportedOperationException(Messages.getString("awt.50")); //$NON-NLS-1$
    }

    public boolean hasNext() {
        return it.hasNext();
    }

    public E next() {
        return it.next();
    }
}
