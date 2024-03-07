# Ravenwood and Mockito

Last update: 2023-11-13

- As of 2023-11-13, `external/mockito` is based on version 2.x.
- Mockito didn't support static mocking before 3.4.0.
  See: https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html#48

- Latest Mockito is 5.*. According to https://github.com/mockito/mockito:
  `Mockito 3 does not introduce any breaking API changes, but now requires Java 8 over Java 6 for Mockito 2. Mockito 4 removes deprecated API. Mockito 5 switches the default mockmaker to mockito-inline, and now requires Java 11.`

- Mockito now supports Android natively.
  See: https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html#0.1
  - But it's unclear at this point to omakoto@ how the `mockito-android` module is built.

- Potential plan:
  - Ideal option:
    - If we can update `external/mockito`, that'd be great, but it may not work because
      Mockito has removed the deprecated APIs.
  - Second option:
    - Import the latest mockito as `external/mockito-new`, and require ravenwood
      to use this one.
    - The latest mockito needs be exposed to all of 1) device tests, 2) host tests, and 3) ravenwood tests.
    - This probably will require the latest `bytebuddy` and `objenesis`.