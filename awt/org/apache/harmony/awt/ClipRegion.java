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
 * @author Pavel Dolgov, Anton Avtamonov
 * @version $Revision$
 */
package org.apache.harmony.awt;

import java.awt.Component;
import java.awt.Rectangle;

import org.apache.harmony.awt.gl.MultiRectArea;
import org.apache.harmony.awt.internal.nls.Messages;

public class ClipRegion extends Rectangle {
    private final MultiRectArea clip;

    public ClipRegion(final MultiRectArea clip) {
        this.clip = new MultiRectArea(clip);
        setBounds(clip.getBounds());
    }

    public MultiRectArea getClip() {
        return clip;
    }

    @Override
    public String toString() {
        String str = clip.toString();
        int i = str.indexOf('[');
        str = str.substring(i);
        if (clip.getRectCount() == 1) {
            str = str.substring(1, str.length() - 1);
        }
        return getClass().getName() + str;
    }


    public void convertRegion(final Component child, final Component parent) {
        convertRegion(child, clip, parent);
    }

    public void intersect(final Rectangle rect) {
        clip.intersect(rect);
    }

    @Override
    public boolean isEmpty() {
        return clip.isEmpty();
    }

    public static void convertRegion(final Component child,
                                     final MultiRectArea region,
                                     final Component parent) {
        int x = 0, y = 0;
        Component c = child;
        //???AWT
        /*
        for (; c != null && c != parent; c = c.getParent()) {
            x += c.getX();
            y += c.getY();
        }
        */
        if (c == null) {
            // awt.51=Component expected to be a parent
            throw new IllegalArgumentException(Messages.getString("awt.51")); //$NON-NLS-1$
        }
        region.translate(x, y);
    }
}
