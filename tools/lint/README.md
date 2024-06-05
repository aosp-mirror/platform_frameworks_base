# Android Lint Checks for AOSP

Custom Android Lint checks are written here to be executed against java modules
in AOSP. These checks are broken down into two subdirectories:

1. [Global Checks](#android-global-lint-checker)
2. [Framework Checks](#android-framework-lint-checker)

# [Android Global Lint Checker](/global)
Checks written here are executed for the entire tree. The `AndroidGlobalLintChecker`
build target produces a jar file that is included in the overall build output
(`AndroidGlobalLintChecker.jar`).  This file is then downloaded as a prebuilt under the
`prebuilts/cmdline-tools` subproject, and included by soong with all invocations of lint.

## How to add new global lint checks
1. Write your detector with its issues and put it into
   `global/checks/src/main/java/com/google/android/lint`.
2. Add your detector's issues into `AndroidGlobalIssueRegistry`'s `issues`
   field.
3. Write unit tests for your detector in one file and put it into
   `global/checks/test/java/com/google/android/lint`.
4. Have your change reviewed and merged.  Once your change is merged,
   obtain a build number from a successful build that includes your change.
5. Run `prebuilts/cmdline-tools/update-android-global-lint-checker.sh
   <build_number>`. The script will create a commit that you can upload for
   approval to the `prebuilts/cmdline-tools` subproject.
6. Done! Your lint check should be applied in lint report builds across the
   entire tree!

# [Android Framework Lint Checker](/framework)

Checks written here are going to be executed for modules that opt in to those (e.g. any
`services.XXX` module) and results will be automatically reported on CLs on gerrit.

## How to add new framework lint checks

1. Write your detector with its issues and put it into
   `framework/checks/src/main/java/com/google/android/lint`.
2. Add your detector's issues into `AndroidFrameworkIssueRegistry`'s `issues` field.
3. Write unit tests for your detector in one file and put it into
   `framework/checks/test/java/com/google/android/lint`.
4. Done! Your lint checks should be applied in lint report builds for modules that include
   `AndroidFrameworkLintChecker`.

## How to run lint against your module

1. Add the following `lint` attribute to the module definition, e.g. `services.autofill`:
```
java_library_static {
    name: "services.autofill",
    ...
    lint: {
        extra_check_modules: ["AndroidFrameworkLintChecker"],
    },
}
```
2. Run the following command to verify that the report is being correctly built:
```
m out/soong/.intermediates/frameworks/base/services/autofill/services.autofill/android_common/lint/lint-report.html
```
   (Lint report can be found in the same path, i.e. `out/../lint-report.html`)

3. Now lint issues should appear on gerrit!

**Notes:**

- Lint report will not be produced if you just build the module, i.e. `m services.autofill` will not
  build the lint report.
- If you want to build lint reports for more than 1 module and they include a common module in their
  `defaults` field, e.g. `platform_service_defaults`, you can add the `lint` property to that common
  module instead of adding it in every module.
- If you want to run a single lint type, use the `ANDROID_LINT_CHECK`
  environment variable with the id of the lint. For example:
  `ANDROID_LINT_CHECK=UnusedTokenOfOriginalCallingIdentity m out/[...]/lint-report.html`

# How to apply automatic fixes suggested by lint

See [lint_fix](fix/README.md)

# Create or update a baseline

Baseline files can be used to silence known errors (and warnings) that are deemed to be safe. When
there is a lint-baseline.xml file in the root folder of the java library, soong will
automatically use it. You can override the file using lint properties too.

```
java_library {
    lint: {
        baseline_filename: "my-baseline.xml", // default: lint-baseline.xml;
    }
}
```

When using soong to create a lint report (as described above), it also creates a reference
baseline file. This contains all lint errors and warnings that were found. So the next time
you run lint, if you use this baseline file, there should be 0 findings.

After the previous invocation, you can find the baseline here:

```
out/soong/.intermediates/frameworks/base/services/autofill/services.autofill/android_common/lint/lint-baseline.xml
```

As noted above, this baseline file contains warnings too, which might be undesirable. For example,
CI tools might surface these warnings in code reviews. In order to create this file without
warnings, we need to pass another flag to lint: `--nowarn`. One option is to add the flag to your
Android.bp file and then run lint again:

```
  lint: {
    extra_check_modules: ["AndroidFrameworkLintChecker"],
    flags: ["--nowarn"],
  }
```

# Documentation

- [Android Lint Docs](https://googlesamples.github.io/android-custom-lint-rules/)
- [Lint Check Unit Testing](https://googlesamples.github.io/android-custom-lint-rules/api-guide/unit-testing.md.html)
- [Android Lint source files](https://source.corp.google.com/studio-main/tools/base/lint/libs/lint-api/src/main/java/com/android/tools/lint/)
- [PSI source files](https://github.com/JetBrains/intellij-community/tree/master/java/java-psi-api/src/com/intellij/psi)
- [UAST source files](https://upsource.jetbrains.com/idea-ce/structure/idea-ce-7b9b8cc138bbd90aec26433f82cd2c6838694003/uast/uast-common/src/org/jetbrains/uast)
- [IntelliJ plugin for viewing PSI tree of files](https://plugins.jetbrains.com/plugin/227-psiviewer)
