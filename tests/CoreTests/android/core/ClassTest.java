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

import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.widget.Button;
import junit.framework.TestCase;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;


class ClassWithPrivateConstructor {
    private ClassWithPrivateConstructor() {
    }
}

public class ClassTest extends TestCase {

    @SmallTest
    public void testClass() throws Exception {
        // Now, never mind the fact that most of this stuff has to work
        // for the test harness to get this far....

        //System.out.println("Class.forName()");
        Class helloClass = Class.forName(ClassTest.class.getName());

        //System.out.println("Class.newInstance()");
        Object instance = helloClass.newInstance();
        assertNotNull(instance);

        //System.out.println("Class.forName() nonexisting class");
        try {
            Class.forName("this.class.DoesNotExist");
            fail("unexpected success");
        } catch (ClassNotFoundException ex) {
            // expected
        }

        //System.out.println("Class.newInstance() private constructor");
        try {
            Class.forName("android.core.ClassWithPrivateConstructor").newInstance();
            fail("unexpected success");
        } catch (IllegalAccessException ex) {
            // this is expected
        }

        //System.out.println("Class.getDeclaredMethod()");

        Method method = helloClass.getDeclaredMethod("method", (Class[]) null);

        method.invoke(new ClassTest(), (Object[]) null);

        //System.out.println("Class.getDeclaredMethod() w/ args");

        method = helloClass.getDeclaredMethod("methodWithArgs", Object.class);

        Object invokeArgs[] = new Object[1];
        invokeArgs[0] = "Hello";
        Object ret = method.invoke(new ClassTest(), invokeArgs);
        assertEquals(ret, invokeArgs[0]);

        //System.out.println("Class.getDeclaredMethod() -- private");

        method = helloClass.getDeclaredMethod("privateMethod", (Class[]) null);

        method.invoke(new ClassTest(), (Object[]) null);
        //fail("unexpected success");
        // TODO: I think this actually *should* succeed, because the
        // call to the private method is being made from the same class.
        // This needs to be replaced with a private call to a different
        // class.

        //System.out.println("Class.getSuperclass");
        Class objectClass = Class.forName("java.lang.Object");
        assertEquals(helloClass.getSuperclass().getSuperclass().getSuperclass(), objectClass);

        //System.out.println("Class.isAssignableFrom");
        assertTrue(objectClass.isAssignableFrom(helloClass));
        assertFalse(helloClass.isAssignableFrom(objectClass));

        //System.out.println("Class.getConstructor");

        Constructor constructor = helloClass.getConstructor((Class[]) null);
        assertNotNull(constructor);

        //System.out.println("Class.getModifiers");

        assertTrue(Modifier.isPublic(helloClass.getModifiers()));
        //System.out.println("Modifiers: " + Modifier.toString(helloClass.getModifiers()));

        //System.out.println("Class.getMethod");

        helloClass.getMethod("method", (Class[]) null);

        try {
            Class[] argTypes = new Class[1];
            argTypes[0] = helloClass;
            helloClass.getMethod("method", argTypes);
            fail("unexpected success");
        } catch (NoSuchMethodException ex) {
            // exception expected
        }

        // Test for public tracker issue 14
        SimpleClass obj = new SimpleClass();
        Field field = obj.getClass().getDeclaredField("str");
        field.set(obj, null);
    }

    public class SimpleClass {
        public String str;
    }

    public Object methodWithArgs(Object o) {
        return o;
    }

    boolean methodInvoked;

    public void method() {
        methodInvoked = true;
    }

    boolean privateMethodInvoked;

    public void privateMethod() {
        privateMethodInvoked = true;
    }

    // Regression for 1018067: Class.getMethods() returns the same method over
    // and over again from all base classes
    @MediumTest
    public void testClassGetMethodsNoDupes() {
        Method[] methods = Button.class.getMethods();
        Set<String> set = new HashSet<String>();

        for (int i = 0; i < methods.length; i++) {
            String signature = methods[i].toString();

            int par = signature.indexOf('(');
            int dot = signature.lastIndexOf('.', par);

            signature = signature.substring(dot + 1);

            assertFalse("Duplicate " + signature, set.contains(signature));
            set.add(signature);
        }
    }

    interface MyInterface {
        void foo();
    }

    interface MyOtherInterface extends MyInterface {
        void bar();
    }

    abstract class MyClass implements MyOtherInterface {
        public void gabba() {
        }

        public void hey() {
        }
    }

    // Check if we also reflect methods from interfaces
    @SmallTest
    public void testGetMethodsInterfaces() {
        Method[] methods = MyInterface.class.getMethods();
        assertTrue("Interface method must be there", hasMethod(methods, ".foo("));

        methods = MyOtherInterface.class.getMethods();
        assertTrue("Interface method must be there", hasMethod(methods, ".foo("));
        assertTrue("Interface method must be there", hasMethod(methods, ".bar("));

        methods = MyClass.class.getMethods();
        assertTrue("Interface method must be there", hasMethod(methods, ".foo("));
        assertTrue("Interface method must be there", hasMethod(methods, ".bar("));

        assertTrue("Declared method must be there", hasMethod(methods, ".gabba("));
        assertTrue("Declared method must be there", hasMethod(methods, ".hey("));

        assertTrue("Inherited method must be there", hasMethod(methods, ".toString("));
    }

    private boolean hasMethod(Method[] methods, String signature) {
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].toString().contains(signature)) {
                return true;
            }
        }

        return false;
    }

    // Test for Class.getPackage();
    @SmallTest
    public void testClassGetPackage() {
        assertNotNull("Package must be non-null", getClass().getPackage());
        assertEquals("Package must have expected name", "android.core", getClass().getPackage().getName());
        assertEquals("Package must have expected title", "Unknown", getClass().getPackage().getSpecificationTitle());

        Package p = java.lang.Object.class.getPackage();
        assertNotNull("Package must be non-null", p);
        assertEquals("Package must have expected name", "java.lang", p.getName());
        assertSame("Package object must be same for each call", p, java.lang.Object.class.getPackage());
    }
    
    // Regression test for #1123708: Problem with getCanonicalName(),
    // getSimpleName(), and getPackage().
    //
    // A couple of interesting cases need to be checked: Top-level classes,
    // member classes, local classes, and anonymous classes. Also, boundary
    // cases with '$' in the class names are checked, since the '$' is used
    // as the separator between outer and inner class, so this might lead
    // to problems (it did in the previous implementation).
    // 
    // Caution: Adding local or anonymous classes elsewhere in this
    // file might affect the test.
    private class MemberClass {
        // This space intentionally left blank.
    }

    private class Mi$o$oup {
        // This space intentionally left blank.
    }
    
    @SmallTest
    public void testVariousClassNames() {
        Class<?> clazz = this.getClass();
        String pkg = (clazz.getPackage() == null ? "" : clazz.getPackage().getName() + ".");

        // Simple, top-level class
        
        assertEquals("Top-level class name must be correct", pkg + "ClassTest", clazz.getName());
        assertEquals("Top-level class simple name must be correct", "ClassTest", clazz.getSimpleName());
        assertEquals("Top-level class canonical name must be correct", pkg + "ClassTest", clazz.getCanonicalName());

        clazz = MemberClass.class;
        
        assertEquals("Member class name must be correct", pkg + "ClassTest$MemberClass", clazz.getName());
        assertEquals("Member class simple name must be correct", "MemberClass", clazz.getSimpleName());
        assertEquals("Member class canonical name must be correct", pkg + "ClassTest.MemberClass", clazz.getCanonicalName());
        
        class LocalClass {
            // This space intentionally left blank.
        }

        clazz = LocalClass.class;

        assertEquals("Local class name must be correct", pkg + "ClassTest$1LocalClass", clazz.getName());
        assertEquals("Local class simple name must be correct", "LocalClass", clazz.getSimpleName());
        assertNull("Local class canonical name must be null", clazz.getCanonicalName());

        clazz = new Object() { }.getClass();

        assertEquals("Anonymous class name must be correct", pkg + "ClassTest$1", clazz.getName());
        assertEquals("Anonymous class simple name must be empty", "", clazz.getSimpleName());
        assertNull("Anonymous class canonical name must be null", clazz.getCanonicalName());

        // Weird special cases with dollar in name.

        clazz = Mou$$aka.class;
        
        assertEquals("Top-level class name must be correct", pkg + "Mou$$aka", clazz.getName());
        assertEquals("Top-level class simple name must be correct", "Mou$$aka", clazz.getSimpleName());
        assertEquals("Top-level class canonical name must be correct", pkg + "Mou$$aka", clazz.getCanonicalName());
        
        clazz = Mi$o$oup.class;
        
        assertEquals("Member class name must be correct", pkg + "ClassTest$Mi$o$oup", clazz.getName());
        assertEquals("Member class simple name must be correct", "Mi$o$oup", clazz.getSimpleName());
        assertEquals("Member class canonical name must be correct", pkg + "ClassTest.Mi$o$oup", clazz.getCanonicalName());
        
        class Ma$hedPotatoe$ {
            // This space intentionally left blank.
        }

        clazz = Ma$hedPotatoe$.class;
        
        assertEquals("Member class name must be correct", pkg + "ClassTest$1Ma$hedPotatoe$", clazz.getName());
        assertEquals("Member class simple name must be correct", "Ma$hedPotatoe$", clazz.getSimpleName());
        assertNull("Member class canonical name must be null", clazz.getCanonicalName());
    }

    @SmallTest
    public void testLocalMemberClass() {
        Class<?> clazz = this.getClass();

        assertFalse("Class must not be member", clazz.isMemberClass());  
        assertFalse("Class must not be local", clazz.isLocalClass());  
        
        clazz = MemberClass.class;

        assertTrue("Class must be member", clazz.isMemberClass());  
        assertFalse("Class must not be local", clazz.isLocalClass());  
        
        class OtherLocalClass {
            // This space intentionally left blank.
        }

        clazz = OtherLocalClass.class;

        assertFalse("Class must not be member", clazz.isMemberClass());  
        assertTrue("Class must be local", clazz.isLocalClass());  
        
        clazz = new Object() { }.getClass();

        assertFalse("Class must not be member", clazz.isMemberClass());  
        assertFalse("Class must not be local", clazz.isLocalClass());  
    }

}

class Mou$$aka {
    // This space intentionally left blank.
}
