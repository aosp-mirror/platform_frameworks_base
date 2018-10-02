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
