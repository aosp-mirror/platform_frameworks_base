The file "jbmr2-encrypted-settings-abcd.ab" in this directory is an encrypted
"adb backup" archive of the settings provider package.  It was generated on a
Nexus 4 running Android 4.3 (API 18), and so predates the Android 4.4 changes
to the PBKDF2 implementation.  The archive's encryption password, entered on-screen,
is "abcd" (with no quotation marks).

'adb restore' decrypts and applies the restored archive successfully on a device
running Android 4.3, but fails to restore correctly on a device running Android 4.4,
reporting an invalid password in logcat.  This is the situation reported in bug
<https://code.google.com/p/android/issues/detail?id=63880>.

The file "kk-fixed-encrypted-settings-abcd.ab" is a similar encrypted "adb backup"
archive, using the same key, generated on a Nexus 4 running Android 4.4 with a fix
to this bug in place.  This archive should be successfully restorable on any
version of Android which incorporates the fix.

These archives can be used as an ongoing test to verify that historical encrypted
archives from various points in Android's history can be successfully restored.
