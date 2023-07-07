# UserFileManager

This class is used to generate file paths and SharedPreferences that is compatible for specific OS
users in SystemUI. Due to constraints in SystemUI, we can only read/write files as the system user.
Therefore, for secondary users, we want to store secondary user specific files into the system user
directory.


## Usages

Inject UserFileManager into your class.

### fun getFile(fileName: String, userId: Int): File
Add a file name and user id. You can retrieve the current user id from UserTracker. This will
return a java.io File object that contains the file path to write/read to.

i.e. `fileManager.getFile("example.xml", userTracker.userId)`

### fun getSharedPreferences(fileName: String, mode: Int, userId: Int): SharedPreferences
Add a file name, user id, and PreferencesMode. You can retrieve the current user id from
UserTracker. This returns SharedPreferences object that is tied to the specific user. Note that if
the SharedPreferences file does not exist, one will be created automatically. See
[SharedPreferences documentation](https://developer.android.com/reference/android/content/Context#getSharedPreferences(java.lang.String,%20int))
for more details.

i.e. `fileManager.getSharedPreferences("prefs.xml", userTracker.userId, 0)`

## Handling User Removal

This class will listen for Intent.ACTION_USER_REMOVED and remove directories that no longer
corresponding to active users. Additionally, upon start up, the class will run the same query for
deletion to ensure that there is no stale data.

