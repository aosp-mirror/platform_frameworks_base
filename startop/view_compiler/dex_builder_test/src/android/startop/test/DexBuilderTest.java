/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.startop.test;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import com.google.common.io.ByteStreams;
import dalvik.system.InMemoryDexClassLoader;
import dalvik.system.PathClassLoader;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Test;

// Adding tests here requires changes in several other places. See README.md in
// the view_compiler directory for more information.
public class DexBuilderTest {
  static ClassLoader loadDexFile(String filename) throws Exception {
    return new PathClassLoader("/data/local/tmp/dex-builder-test/" + filename,
        ClassLoader.getSystemClassLoader());
  }

  public void hello() {}

  @Test
  public void loadTrivialDex() throws Exception {
    ClassLoader loader = loadDexFile("trivial.dex");
    loader.loadClass("android.startop.test.testcases.Trivial");
  }

  @Test
  public void return5() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("return5");
    Assert.assertEquals(5, method.invoke(null));
  }

  @Test
  public void returnInteger5() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("returnInteger5");
    Assert.assertEquals(5, method.invoke(null));
  }

  @Test
  public void returnParam() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("returnParam", int.class);
    Assert.assertEquals(5, method.invoke(null, 5));
    Assert.assertEquals(42, method.invoke(null, 42));
  }

  @Test
  public void returnStringLength() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("returnStringLength", String.class);
    Assert.assertEquals(13, method.invoke(null, "Hello, World!"));
  }

  @Test
  public void returnIfZero() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("returnIfZero", int.class);
    Assert.assertEquals(5, method.invoke(null, 0));
    Assert.assertEquals(3, method.invoke(null, 17));
  }

  @Test
  public void returnIfNotZero() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("returnIfNotZero", int.class);
    Assert.assertEquals(3, method.invoke(null, 0));
    Assert.assertEquals(5, method.invoke(null, 17));
  }

  @Test
  public void backwardsBranch() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("backwardsBranch");
    Assert.assertEquals(2, method.invoke(null));
  }

  @Test
  public void returnNull() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("returnNull");
    Assert.assertEquals(null, method.invoke(null));
  }

  @Test
  public void makeString() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("makeString");
    Assert.assertEquals("Hello, World!", method.invoke(null));
  }

  @Test
  public void returnStringIfZeroAB() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("returnStringIfZeroAB", int.class);
    Assert.assertEquals("a", method.invoke(null, 0));
    Assert.assertEquals("b", method.invoke(null, 1));
  }

  @Test
  public void returnStringIfZeroBA() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("returnStringIfZeroBA", int.class);
    Assert.assertEquals("b", method.invoke(null, 0));
    Assert.assertEquals("a", method.invoke(null, 1));
  }

  @Test
  public void invokeStaticReturnObject() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("invokeStaticReturnObject", int.class, int.class);
    Assert.assertEquals("10", method.invoke(null, 10, 10));
    Assert.assertEquals("a", method.invoke(null, 10, 16));
    Assert.assertEquals("5", method.invoke(null, 5, 16));
  }

  @Test
  public void invokeVirtualReturnObject() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("invokeVirtualReturnObject", String.class, int.class);
    Assert.assertEquals("bc", method.invoke(null, "abc", 1));
  }

  @Test
  public void castObjectToString() throws Exception {
    ClassLoader loader = loadDexFile("simple.dex");
    Class clazz = loader.loadClass("android.startop.test.testcases.SimpleTests");
    Method method = clazz.getMethod("castObjectToString", Object.class);
    Assert.assertEquals("abc", method.invoke(null, "abc"));
    boolean castFailed = false;
    try {
      method.invoke(null, 5);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof ClassCastException) {
        castFailed = true;
      } else {
        throw e;
      }
    }
    Assert.assertTrue(castFailed);
  }
}
