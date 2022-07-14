# UserFileManager

This class is used to generate file paths and SharedPreferences that is compatible for multiple
users in SystemUI. Due to constraints in SystemUI, we can only read/write files as the system user.
Therefore, for secondary users, we want to store secondary user specific files into the system user
directory.

## Handling User Removal

This class will listen for Intent.ACTION_USER_REMOVED and remove directories that no longer
corresponding to active users. Additionally, upon start up, the class will run the same query for
deletion to ensure that there is no stale data.

