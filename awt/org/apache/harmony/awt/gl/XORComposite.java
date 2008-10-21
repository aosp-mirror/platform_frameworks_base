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
 * @author Igor V. Stolyarov
 * @version $Revision$
 * Created on 21.11.2005
 *
 */
package org.apache.harmony.awt.gl;

import java.awt.Color;
import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;

public class XORComposite implements Composite {

    Color xorcolor;

    public XORComposite(Color xorcolor){
        this.xorcolor = xorcolor;
    }

    public CompositeContext createContext(ColorModel srcCM, ColorModel dstCM,
            RenderingHints hints) {

        return new ICompositeContext(this, srcCM, dstCM);
    }

    public Color getXORColor(){
        return xorcolor;
    }
}
