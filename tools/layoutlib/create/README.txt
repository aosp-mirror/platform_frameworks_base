# Copyright (C) 2008 The Android Open Source Project


- Description -
---------------

makeLayoutLib generates a library used by the Eclipse graphical layout editor
to perform layout.



- Usage -
---------

 ./makeLayoutLib path/to/android.jar destination.jar



- Implementation Notes -
------------------------

The goal of makeLayoutLib is to list all the classes from the input jar and create a new
jar that only keeps certain classes and create stubs for all their dependencies.

First the input jar is parsed to find all the classes defined.

In the Main(), the following list of classes are hardcoded (TODO config file later):
- keep all classes that derive from android.view.View.
- keep all classes in the android.view and android.widget packages (sub-packages excluded).
- keep specific classes such as android.policy.PhoneLayoutInflater.

For each class to keep, their dependencies are examined using BCEL.
A dependency is defined as a class needed to instantiate the given class that should be kept,
directly or indirectly. So a dependency is a class that is used by the input class, that is
defined in the input jar and that is not part of the current JRE.

Dependencies are computed recursively.

Once all dependencies are found, the final jar can be created.
There are three kind of classes to write:
- classes that are to be kept as-is. They are just dumped in the new jar unchanged.
- classes that are to be kept yet contain native methods or fields.
- classes that are just dependencies. We don't want to expose their implementation in the final
  jar.

The implementation of native methods and all methods of mock classes is replaced by a stub
that throws UnsupportedOperationException.

Incidentally, the access level of native and mock classes needs to be changed in order for
native methods to be later overridden. Methods that are "final private native" must become
non-final, non-native and at most protected. Package-default access is changed to public.
Classes that are final are made non-final. Abstract methods are left untouched.



----
20080617 Replace Class

Some classes are basically wrappers over native objects.
Subclassing doesn't work as most methods are either static or we don't
control object creation. In this scenario the idea is to be able to
replace classes in the final jar.

Example: android.graphics.Paint would get renamed to OriginalPaint
in the generated jar. Then in the bridge we'll introduce a replacement
Paint class that derives from OriginalPaint.

This won't rename/replace the inner static methods of a given class.



