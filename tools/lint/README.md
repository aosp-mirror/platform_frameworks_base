# Android Framework Lint Checker

Custom lint checks written here are going to be executed for modules that opt in to those (e.g. any
`services.XXX` module) and results will be automatically reported on CLs on gerrit.

## How to add new lint checks

1. Write your detector with its issues and put it into
   `checks/src/main/java/com/google/android/lint`.
2. Add your detector's issues into `AndroidFrameworkIssueRegistry`'s `issues` field.
3. Write unit tests for your detector in one file and put it into
   `checks/test/java/com/google/android/lint`.
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

## Create or update a baseline

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
warnings, we need to pass another flag to lint: `--nowarn`. The easiest way to do this is to
locally change the soong code in
[lint.go](http://cs/aosp-master/build/soong/java/lint.go;l=451;rcl=2e778d5bc4a8d1d77b4f4a3029a4a254ad57db75)
adding `cmd.Flag("--nowarn")` and running lint again.

## Documentation

- [Android Lint Docs](https://googlesamples.github.io/android-custom-lint-rules/)
- [Android Lint source files](https://source.corp.google.com/studio-main/tools/base/lint/libs/lint-api/src/main/java/com/android/tools/lint/)
- [PSI source files](https://github.com/JetBrains/intellij-community/tree/master/java/java-psi-api/src/com/intellij/psi)
- [UAST source files](https://upsource.jetbrains.com/idea-ce/structure/idea-ce-7b9b8cc138bbd90aec26433f82cd2c6838694003/uast/uast-common/src/org/jetbrains/uast)
- [IntelliJ plugin for viewing PSI tree of files](https://plugins.jetbrains.com/plugin/227-psiviewer)
