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

## Documentation

- [Android Lint Docs](https://googlesamples.github.io/android-custom-lint-rules/)
- [Android Lint source files](https://source.corp.google.com/studio-main/tools/base/lint/libs/lint-api/src/main/java/com/android/tools/lint/)
- [PSI source files](https://github.com/JetBrains/intellij-community/tree/master/java/java-psi-api/src/com/intellij/psi)
- [UAST source files](https://upsource.jetbrains.com/idea-ce/structure/idea-ce-7b9b8cc138bbd90aec26433f82cd2c6838694003/uast/uast-common/src/org/jetbrains/uast)
- [IntelliJ plugin for viewing PSI tree of files](https://plugins.jetbrains.com/plugin/227-psiviewer)
