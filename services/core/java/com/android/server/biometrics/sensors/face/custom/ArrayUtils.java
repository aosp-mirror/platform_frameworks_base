/*
* Copyright (C) 2022 The Pixel Experience Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.server.biometrics.sensors.face.custom;

import java.util.ArrayList;

public class ArrayUtils {
    public static ArrayList<Byte> toByteArrayList(byte[] in) {
        if (in == null) {
            return null;
        }
        ArrayList<Byte> out = new ArrayList<>(in.length);
        for (byte c : in) {
            out.add(c);
        }
        return out;
    }

    public static ArrayList<Integer> toIntArrayList(int[] in) {
        if (in == null) {
            return null;
        }
        ArrayList<Integer> out = new ArrayList<>(in.length);
        for (int c : in) {
            out.add(c);
        }
        return out;
    }

    public static int[] toIntArray(ArrayList<Integer> in) {
        if (in == null) {
            return null;
        }
        int[] out = new int[in.size()];
        for (int i = 0; i < in.size(); i++) {
            out[i] = in.get(i);
        }
        return out;
    }

    public static byte[] toByteArray(ArrayList<Byte> in) {
        if (in == null) {
            return null;
        }
        byte[] out = new byte[in.size()];
        for (int i = 0; i < in.size(); i++) {
            out[i] = in.get(i);
        }
        return out;
    }
}
