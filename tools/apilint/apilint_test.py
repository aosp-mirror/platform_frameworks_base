#!/usr/bin/env python

# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import unittest

import apilint

def cls(pkg, name):
    return apilint.Class(apilint.Package(999, "package %s {" % pkg, None), 999,
                  "public final class %s {" % name, None)

_ri = apilint._retry_iterator

c1 = cls("android.app", "ActivityManager")
c2 = cls("android.app", "Notification")
c3 = cls("android.app", "Notification.Action")
c4 = cls("android.graphics", "Bitmap")

class UtilTests(unittest.TestCase):
    def test_retry_iterator(self):
        it = apilint._retry_iterator([1, 2, 3, 4])
        self.assertEqual(it.next(), 1)
        self.assertEqual(it.next(), 2)
        self.assertEqual(it.next(), 3)
        it.send("retry")
        self.assertEqual(it.next(), 3)
        self.assertEqual(it.next(), 4)
        with self.assertRaises(StopIteration):
            it.next()

    def test_retry_iterator_one(self):
        it = apilint._retry_iterator([1])
        self.assertEqual(it.next(), 1)
        it.send("retry")
        self.assertEqual(it.next(), 1)
        with self.assertRaises(StopIteration):
            it.next()

    def test_retry_iterator_one(self):
        it = apilint._retry_iterator([1])
        self.assertEqual(it.next(), 1)
        it.send("retry")
        self.assertEqual(it.next(), 1)
        with self.assertRaises(StopIteration):
            it.next()

    def test_skip_to_matching_class_found(self):
        it = _ri([c1, c2, c3, c4])
        self.assertEquals(apilint._skip_to_matching_class(it, c3),
                          c3)
        self.assertEqual(it.next(), c4)

    def test_skip_to_matching_class_not_found(self):
        it = _ri([c1, c2, c3, c4])
        self.assertEquals(apilint._skip_to_matching_class(it, cls("android.content", "ContentProvider")),
                          None)
        self.assertEqual(it.next(), c4)

    def test_yield_until_matching_class_found(self):
        it = _ri([c1, c2, c3, c4])
        self.assertEquals(list(apilint._yield_until_matching_class(it, c3)),
                          [c1, c2])
        self.assertEqual(it.next(), c4)

    def test_yield_until_matching_class_not_found(self):
        it = _ri([c1, c2, c3, c4])
        self.assertEquals(list(apilint._yield_until_matching_class(it, cls("android.content", "ContentProvider"))),
                          [c1, c2, c3])
        self.assertEqual(it.next(), c4)

    def test_yield_until_matching_class_None(self):
        it = _ri([c1, c2, c3, c4])
        self.assertEquals(list(apilint._yield_until_matching_class(it, None)),
                          [c1, c2, c3, c4])


faulty_current_txt = """
// Signature format: 2.0
package android.app {
  public final class Activity {
  }

  public final class WallpaperColors implements android.os.Parcelable {
    ctor public WallpaperColors(@NonNull android.os.Parcel);
    method public int describeContents();
    method public void writeToParcel(@NonNull android.os.Parcel, int);
    field @NonNull public static final android.os.Parcelable.Creator<android.app.WallpaperColors> CREATOR;
  }
}
""".strip().split('\n')

ok_current_txt = """
// Signature format: 2.0
package android.app {
  public final class Activity {
  }

  public final class WallpaperColors implements android.os.Parcelable {
    ctor public WallpaperColors();
    method public int describeContents();
    method public void writeToParcel(@NonNull android.os.Parcel, int);
    field @NonNull public static final android.os.Parcelable.Creator<android.app.WallpaperColors> CREATOR;
  }
}
""".strip().split('\n')

system_current_txt = """
// Signature format: 2.0
package android.app {
  public final class WallpaperColors implements android.os.Parcelable {
    method public int getSomething();
  }
}
""".strip().split('\n')



class BaseFileTests(unittest.TestCase):
    def test_base_file_avoids_errors(self):
        failures, _ = apilint.examine_stream(system_current_txt, ok_current_txt)
        self.assertEquals(failures, {})

    def test_class_with_base_finds_same_errors(self):
        failures_with_classes_with_base, _ = apilint.examine_stream("", faulty_current_txt,
                                                                    in_classes_with_base=[cls("android.app", "WallpaperColors")])
        failures_with_system_txt, _ = apilint.examine_stream(system_current_txt, faulty_current_txt)

        self.assertEquals(failures_with_classes_with_base.keys(), failures_with_system_txt.keys())

    def test_classes_with_base_is_emited(self):
        classes_with_base = []
        _, _ = apilint.examine_stream(system_current_txt, faulty_current_txt,
                                      out_classes_with_base=classes_with_base)
        self.assertEquals(map(lambda x: x.fullname, classes_with_base), ["android.app.WallpaperColors"])

class ParseV2Stream(unittest.TestCase):
    def test_field_kinds(self):
        api = apilint._parse_stream("""
// Signature format: 2.0
package android {
  public enum SomeEnum {
    enum_constant public static final android.SomeEnum ENUM_CONST;
    field public static final int FIELD_CONST;
    property public final int someProperty;
    ctor public SomeEnum();
    method public Object? getObject();
  }
}
        """.strip().split('\n'))

        self.assertEquals(api['android.SomeEnum'].fields[0].split[0], 'enum_constant')
        self.assertEquals(api['android.SomeEnum'].fields[1].split[0], 'field')
        self.assertEquals(api['android.SomeEnum'].fields[2].split[0], 'property')
        self.assertEquals(api['android.SomeEnum'].ctors[0].split[0], 'ctor')
        self.assertEquals(api['android.SomeEnum'].methods[0].split[0], 'method')

class ParseV3Stream(unittest.TestCase):
    def test_field_kinds(self):
        api = apilint._parse_stream("""
// Signature format: 3.0
package a {

  public final class ContextKt {
    method public static inline <reified T> T! getSystemService(android.content.Context);
    method public static inline void withStyledAttributes(android.content.Context, android.util.AttributeSet? set = null, int[] attrs, @AttrRes int defStyleAttr = 0, @StyleRes int defStyleRes = 0, kotlin.jvm.functions.Function1<? super android.content.res.TypedArray,kotlin.Unit> block);
  }
}
        """.strip().split('\n'))
        self.assertEquals(api['a.ContextKt'].methods[0].name, 'getSystemService')
        self.assertEquals(api['a.ContextKt'].methods[0].split[:4], ['method', 'public', 'static', 'inline'])
        self.assertEquals(api['a.ContextKt'].methods[1].name, 'withStyledAttributes')
        self.assertEquals(api['a.ContextKt'].methods[1].split[:4], ['method', 'public', 'static', 'inline'])

class V2TokenizerTests(unittest.TestCase):
    def _test(self, raw, expected):
        self.assertEquals(apilint.V2Tokenizer(raw).tokenize(), expected)

    def test_simple(self):
        self._test("  method public some.Type someName(some.Argument arg, int arg);",
                   ['method', 'public', 'some.Type', 'someName', '(', 'some.Argument',
                    'arg', ',', 'int', 'arg', ')', ';'])
        self._test("class Some.Class extends SomeOther {",
                   ['class', 'Some.Class', 'extends', 'SomeOther', '{'])

    def test_varargs(self):
        self._test("name(String...)",
                   ['name', '(', 'String', '...', ')'])

    def test_kotlin(self):
        self._test("String? name(String!...)",
                   ['String', '?', 'name', '(', 'String', '!',  '...', ')'])

    def test_annotation(self):
        self._test("method @Nullable public void name();",
                   ['method', '@', 'Nullable', 'public', 'void', 'name', '(', ')', ';'])

    def test_annotation_args(self):
        self._test("@Some(val=1, other=2) class Class {",
                   ['@', 'Some', '(', 'val', '=', '1', ',', 'other', '=', '2', ')',
                    'class', 'Class', '{'])
    def test_comment(self):
        self._test("some //comment", ['some'])

    def test_strings(self):
        self._test(r'"" "foo" "\"" "\\"', ['""', '"foo"', r'"\""', r'"\\"'])

    def test_at_interface(self):
        self._test("public @interface Annotation {",
                   ['public', '@interface', 'Annotation', '{'])

    def test_array_type(self):
        self._test("int[][]", ['int', '[]', '[]'])

    def test_generics(self):
        self._test("<>foobar<A extends Object>",
                   ['<', '>', 'foobar', '<', 'A', 'extends', 'Object', '>'])

class V2ParserTests(unittest.TestCase):
    def _cls(self, raw):
        pkg = apilint.Package(999, "package pkg {", None)
        return apilint.Class(pkg, 1, raw, '', sig_format=2)

    def _method(self, raw, cls=None):
        if not cls:
            cls = self._cls("class Class {")
        return apilint.Method(cls, 1, raw, '', sig_format=2)

    def _field(self, raw):
        cls = self._cls("class Class {")
        return apilint.Field(cls, 1, raw, '', sig_format=2)

    def test_parse_package(self):
        pkg = apilint.Package(999, "package wifi.p2p {", None)
        self.assertEquals("wifi.p2p", pkg.name)

    def test_class(self):
        cls = self._cls("@Deprecated @IntRange(from=1, to=2) public static abstract class Some.Name extends Super<Class> implements Interface<Class> {")
        self.assertTrue('deprecated' in cls.split)
        self.assertTrue('static' in cls.split)
        self.assertTrue('abstract' in cls.split)
        self.assertTrue('class' in cls.split)
        self.assertEquals('Super', cls.extends)
        self.assertEquals('Interface', cls.implements)
        self.assertEquals('pkg.Some.Name', cls.fullname)

    def test_enum(self):
        cls = self._cls("public enum Some.Name {")
        self._field("enum_constant public static final android.ValueType COLOR;")

    def test_interface(self):
        cls = self._cls("@Deprecated @IntRange(from=1, to=2) public interface Some.Name extends Interface<Class> {")
        self.assertTrue('deprecated' in cls.split)
        self.assertTrue('interface' in cls.split)
        self.assertEquals('Interface', cls.extends)
        self.assertEquals('Interface', cls.implements)
        self.assertEquals('pkg.Some.Name', cls.fullname)

    def test_at_interface(self):
        cls = self._cls("@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.PARAMETER, java.lang.annotation.ElementType.CONSTRUCTOR, java.lang.annotation.ElementType.LOCAL_VARIABLE}) @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface SuppressLint {")
        self.assertTrue('@interface' in cls.split)
        self.assertEquals('pkg.SuppressLint', cls.fullname)

    def test_parse_method(self):
        m = self._method("method @Deprecated public static native <T> Class<T>[][] name("
                         + "Class<T[]>[][], Class<T[][][]>[][]...) throws Exception, T;")
        self.assertTrue('static' in m.split)
        self.assertTrue('public' in m.split)
        self.assertTrue('method' in m.split)
        self.assertTrue('native' in m.split)
        self.assertTrue('deprecated' in m.split)
        self.assertEquals('java.lang.Class[][]', m.typ)
        self.assertEquals('name', m.name)
        self.assertEquals(['java.lang.Class[][]', 'java.lang.Class[][]...'], m.args)
        self.assertEquals(['java.lang.Exception', 'T'], m.throws)

    def test_ctor(self):
        m = self._method("ctor @Deprecated <T> ClassName();")
        self.assertTrue('ctor' in m.split)
        self.assertTrue('deprecated' in m.split)
        self.assertEquals('ctor', m.typ)
        self.assertEquals('ClassName', m.name)

    def test_parse_annotation_method(self):
        cls = self._cls("@interface Annotation {")
        self._method('method abstract String category() default "";', cls=cls)
        self._method('method abstract boolean deepExport() default false;', cls=cls)
        self._method('method abstract ViewDebug.FlagToString[] flagMapping() default {};', cls=cls)
        self._method('method abstract ViewDebug.FlagToString[] flagMapping() default (double)java.lang.Float.NEGATIVE_INFINITY;', cls=cls)

    def test_parse_string_field(self):
        f = self._field('field @Deprecated public final String SOME_NAME = "value";')
        self.assertTrue('field' in f.split)
        self.assertTrue('deprecated' in f.split)
        self.assertTrue('final' in f.split)
        self.assertEquals('java.lang.String', f.typ)
        self.assertEquals('SOME_NAME', f.name)
        self.assertEquals('value', f.value)

    def test_parse_field(self):
        f = self._field('field public Object SOME_NAME;')
        self.assertTrue('field' in f.split)
        self.assertEquals('java.lang.Object', f.typ)
        self.assertEquals('SOME_NAME', f.name)
        self.assertEquals(None, f.value)

    def test_parse_int_field(self):
        f = self._field('field public int NAME = 123;')
        self.assertTrue('field' in f.split)
        self.assertEquals('int', f.typ)
        self.assertEquals('NAME', f.name)
        self.assertEquals('123', f.value)

    def test_parse_quotient_field(self):
        f = self._field('field public int NAME = (0.0/0.0);')
        self.assertTrue('field' in f.split)
        self.assertEquals('int', f.typ)
        self.assertEquals('NAME', f.name)
        self.assertEquals('( 0.0 / 0.0 )', f.value)

    def test_kotlin_types(self):
        self._field('field public List<Integer[]?[]!>?[]![]? NAME;')
        self._method("method <T?> Class<T!>?[]![][]? name(Type!, Type argname,"
                         + "Class<T?>[][]?[]!...!) throws Exception, T;")
        self._method("method <T> T name(T a = 1, T b = A(1), Lambda f = { false }, N? n = null, "
                         + """double c = (1/0), float d = 1.0f, String s = "heyo", char c = 'a');""")

    def test_kotlin_operator(self):
        self._method('method public operator void unaryPlus(androidx.navigation.NavDestination);')
        self._method('method public static operator androidx.navigation.NavDestination get(androidx.navigation.NavGraph, @IdRes int id);')
        self._method('method public static operator <T> T get(androidx.navigation.NavigatorProvider, kotlin.reflect.KClass<T> clazz);')

    def test_kotlin_property(self):
        self._field('property public VM value;')
        self._field('property public final String? action;')

    def test_kotlin_varargs(self):
        self._method('method public void error(int p = "42", Integer int2 = "null", int p1 = "42", vararg String args);')

    def test_kotlin_default_values(self):
        self._method('method public void foo(String! = null, String! = "Hello World", int = 42);')
        self._method('method void method(String, String firstArg = "hello", int secondArg = "42", String thirdArg = "world");')
        self._method('method void method(String, String firstArg = "hello", int secondArg = "42");')
        self._method('method void method(String, String firstArg = "hello");')
        self._method('method void edit(android.Type, boolean commit = false, Function1<? super Editor,kotlin.Unit> action);')
        self._method('method <K, V> LruCache<K,V> lruCache(int maxSize, Function2<? super K,? super V,java.lang.Integer> sizeOf = { _, _ -> 1 }, Function1<? extends V> create = { (V)null }, Function4<kotlin.Unit> onEntryRemoved = { _, _, _, _ ->  });')
        self._method('method android.Bitmap? drawToBitmap(android.View, android.Config config = android.graphics.Bitmap.Config.ARGB_8888);')
        self._method('method void emptyLambda(Function0<kotlin.Unit> sizeOf = {});')
        self._method('method void method1(int p = 42, Integer? int2 = null, int p1 = 42, String str = "hello world", java.lang.String... args);')
        self._method('method void method2(int p, int int2 = (2 * int) * some.other.pkg.Constants.Misc.SIZE);')
        self._method('method void method3(String str, int p, int int2 = double(int) + str.length);')
        self._method('method void print(test.pkg.Foo foo = test.pkg.Foo());')

    def test_type_use_annotation(self):
        self._method('method public static int codePointAt(char @NonNull [], int);')
        self._method('method @NonNull public java.util.Set<java.util.Map.@NonNull Entry<K,V>> entrySet();')

        m = self._method('method @NonNull public java.lang.annotation.@NonNull Annotation @NonNull [] getAnnotations();')
        self.assertEquals('java.lang.annotation.Annotation[]', m.typ)

        m = self._method('method @NonNull public abstract java.lang.annotation.@NonNull Annotation @NonNull [] @NonNull [] getParameterAnnotations();')
        self.assertEquals('java.lang.annotation.Annotation[][]', m.typ)

        m = self._method('method @NonNull public @NonNull String @NonNull [] split(@NonNull String, int);')
        self.assertEquals('java.lang.String[]', m.typ)

class PackageTests(unittest.TestCase):
    def _package(self, raw):
        return apilint.Package(123, raw, "blame")

    def test_regular_package(self):
        p = self._package("package an.pref.int {")
        self.assertEquals('an.pref.int', p.name)

    def test_annotation_package(self):
        p = self._package("package @RestrictTo(a.b.C) an.pref.int {")
        self.assertEquals('an.pref.int', p.name)

    def test_multi_annotation_package(self):
        p = self._package("package @Rt(a.b.L_G_P) @RestrictTo(a.b.C) an.pref.int {")
        self.assertEquals('an.pref.int', p.name)

if __name__ == "__main__":
    unittest.main()
