# View Compiler

This directory contains an experimental compiler for layout files.

It will take a layout XML file and produce a CompiledLayout.java file with a
specialized layout inflation function.

To use it, let's assume you had a layout in `my_layout.xml` and your app was in
the Java language package `com.example.myapp`. Run the following command:

    viewcompiler my_layout.xml --package com.example.myapp --out CompiledView.java

This will produce a `CompiledView.java`, which can then be compiled into your
Android app. Then to use it, in places where you would have inflated
`R.layouts.my_layout`, instead call `CompiledView.inflate`.

Precompiling views like this generally improves the time needed to inflate them.

This tool is still in its early stages and has a number of limitations.
* Currently only one layout can be compiled at a time.
* `merge` and `include` nodes are not supported.
* View compilation is a manual process that requires code changes in the
  application.
* This only works for apps that do not use a custom layout inflater.
* Other limitations yet to be discovered.

## DexBuilder Tests

The DexBuilder has several low-level end to end tests to verify generated DEX
code validates, runs, and has the correct behavior. There are, unfortunately, a
number of pieces that must be added to generate new tests. Here are the
components:

* `dex_testcase_generator` - Written in C++ using `DexBuilder`. This runs as a
  build step produce the DEX files that will be tested on device. See the
  `genrule` named `generate_dex_testcases` in `Android.bp`. These files are then
  copied over to the device by TradeFed when running tests.
* `DexBuilderTest` - This is a Java Language test harness that loads the
  generated DEX files and exercises methods in the file.

To add a new DEX file test, follow these steps:
1. Modify `dex_testcase_generator` to produce the DEX file.
2. Add the filename to the `out` list of the `generate_dex_testcases` rule in
   `Android.bp`.
3. Add a new `push` option to `AndroidTest.xml` to copy the DEX file to the
   device.
4. Modify `DexBuilderTest.java` to load and exercise the new test.

In each case, you should be able to cargo-cult the existing test cases.

In general, you can probably get by without adding a new generated DEX file, and
instead add more methods to the files that are already generated. In this case,
you can skip all of steps 2 and 3 above, and simplify steps 1 and 4.
