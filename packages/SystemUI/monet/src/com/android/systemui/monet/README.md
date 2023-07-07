Subpackages, i.e. folders, in this directory have their source of truth in the internal Google
source code repository.

The code is manually copied because:

- Alternatives are automatically syncing Material Color Utilities from the internal Google source
  repository, or GitHub.
- Having Android's core automatically taking updates from code that changes regularly, and can
  easily affect design, is undesirable.

To update the code:

1. Copy Material Color Utilities (libmonet internally) from the internal Google source repository to
   this directory.
2. Replace package definitions. Use Find and Replace in directory, replace
   com.google.ux.material.libmonet with com.android.systemui.monet.
3. Remove @CanIgnoreReturnValue and @CheckReturnValue, both usage and imports. Currently only in
   Scheme.java. Chose removal because only Android /external libraries depend on the library with
   these annotations.
4. Copy tests for Material Color Utilities to
   frameworks/base/packages/SystemUI/tests/src/com/android/systemui/monet/
5. Remove HctRoundTripTest (~3 minutes to run) and QuantizerCelebiTest (depends on internal source
   repository code for handling images)
6. Reformat regular and test code to Android bracing/spacing style.
   In Android Studio, right click on the package, and choose Reformat Code.
7. Change tests to subclass SysuiTestCase and use an annotation such as @SmallTest / @MediumTest.
