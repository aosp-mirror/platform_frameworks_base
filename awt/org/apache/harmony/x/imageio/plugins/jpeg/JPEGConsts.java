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
 * @author Rustem V. Rafikov
 * @version $Revision: 1.2 $
 */
package org.apache.harmony.x.imageio.plugins.jpeg;

public class JPEGConsts {

    private JPEGConsts() {}

    public static final int SOI = 0xD8;

    //-- IJG (Independed JPEG Group) color spaces
    public static final int JCS_UNKNOW = 0;
    public static final int JCS_GRAYSCALE = 1;
    public static final int JCS_RGB = 2;
    public static final int JCS_YCbCr = 3;
    public static final int JCS_CMYK = 4;
    public static final int JCS_YCC = 5;
    public static final int JCS_RGBA = 6;
    public static final int JCS_YCbCrA = 7;
    public static final int JCS_YCCA = 10;
    public static final int JCS_YCCK = 11;

    public static int[][] BAND_OFFSETS = {{}, {0}, {0, 1}, {0, 1, 2}, {0, 1, 2, 3}};

    public static final float DEFAULT_JPEG_COMPRESSION_QUALITY = 0.75f;
}
