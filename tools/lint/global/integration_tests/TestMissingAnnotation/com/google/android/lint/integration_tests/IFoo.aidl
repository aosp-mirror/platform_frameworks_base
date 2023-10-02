package com.google.android.lint.integration_tests;

interface IFoo {

    @EnforcePermission("INTERNET")
    void Method();
}
