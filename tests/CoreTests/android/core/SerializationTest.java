/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.core;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests serialization of user-level classes.
 */
public class SerializationTest extends TestCase {

    static class MySerializable implements Serializable {}

    @SmallTest
    public void testSerialization() throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ObjectOutputStream oout = new ObjectOutputStream(bout);
        oout.writeObject(new MySerializable());
        oout.close();

        ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
        Object o = new ObjectInputStream(bin).readObject();
        assertTrue(o instanceof MySerializable);
    }
}
