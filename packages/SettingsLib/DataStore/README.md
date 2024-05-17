# Datastore library

This library provides consistent API for data management (including backup,
restore, and metrics logging) on Android platform.

Notably, it is designed to be flexible and could be utilized for a wide range of
data store besides the settings preferences.

## Overview

In the high-level design, a persistent datastore aims to support two key
characteristics:

-   **observable**: triggers backup and metrics logging whenever data is
    changed.
-   **transferable**: offers users with a seamless experience by backing up and
    restoring data on to new devices.

More specifically, Android framework supports
[data backup](https://developer.android.com/guide/topics/data/backup) to
preserve user experiences on a new device. And the
[observer pattern](https://en.wikipedia.org/wiki/Observer_pattern) allows to
monitor data change.

### Backup and restore

Currently, the Android backup framework provides
[BackupAgentHelper](https://developer.android.com/reference/android/app/backup/BackupAgentHelper)
and
[BackupHelper](https://developer.android.com/reference/android/app/backup/BackupHelper)
to facilitate data backup. However, there are several caveats to consider when
implementing `BackupHelper`:

-   *performBackup*: The data is updated incrementally but it is not well
    documented. The `ParcelFileDescriptor` state parameters are normally ignored
    and data is updated even there is no change.
-   *restoreEntity*: The implementation must take care not to seek or close the
    underlying data source, nor read more than `size()` bytes from the stream
    when restore (see
    [BackupDataInputStream](https://developer.android.com/reference/android/app/backup/BackupDataInputStream)).
    It is possible that a `BackupHelper` interferes with the restore process of
    other `BackupHelper`s.
-   *writeNewStateDescription*: Existing implementations rarely notice that this
    callback is invoked after *all* entities are restored. Instead, they check
    if necessary data are all restored in the `restoreEntity` (e.g.
    [BatteryBackupHelper](https://cs.android.com/android/platform/superproject/main/+/main:packages/apps/Settings/src/com/android/settings/fuelgauge/BatteryBackupHelper.java;l=144;drc=cca804e1ed504e2d477be1e3db00fb881ca32736)),
    which is not robust sometimes.

The datastore library will mitigate these problems by providing alternative
APIs. For instance, library users make use of `InputStream` / `OutputStream` to
back up and restore data directly.

### Observer pattern

In the current implementation, the Android backup framework requires a manual
call to
[BackupManager.dataChanged](https://developer.android.com/reference/android/app/backup/BackupManager#dataChanged\(\)).
However, it's often observed that this API call is forgotten when using
`SharedPreferences`. Additionally, there's a common need to log metrics when
data changed. To address these limitations, datastore API employed the observer
pattern.

### API design and advantages

Datastore must extend the `BackupRestoreStorage` class (subclass of
[BackupHelper](https://developer.android.com/reference/android/app/backup/BackupHelper)).
The data in a datastore is group by entity, which is represented by
`BackupRestoreEntity`. Basically, a datastore implementation only needs to focus
on the `BackupRestoreEntity`.

If the datastore is key-value based (e.g. `SharedPreferences`), implements the
`KeyedObservable` interface to offer fine-grained observer. Otherwise,
implements `Observable`. There are builtin thread-safe implementations of the
two interfaces (`KeyedDataObservable` / `DataObservable`). If it is Kotlin, use
delegation to simplify the code.

Keep in mind that the implementation should call `KeyedObservable.notifyChange`
/ `Observable.notifyChange` whenever internal data is changed, so that the
registered observer will be notified properly.

For `SharedPreferences` use case, leverage the `SharedPreferencesStorage`
directly. To back up other file based storage, extend the
`BackupRestoreFileStorage` class.

Here are some highlights of the library:

-   The restore `InputStream` will ensure bounded data are read, and close the
    stream is no-op. That being said, all entities are isolated.
-   Data checksum is computed automatically, unchanged data will not be sent to
    Android backup system.
-   Data compression is supported:
    -   ZIP best compression is enabled by default, no extra effort needs to be
        taken.
    -   It is safe to switch between compression and no compression in future,
        the backup data will add 1 byte header to recognize the codec.
    -   To support other compression algorithms, simply wrap over the
        `InputStream` and `OutputStream`. Actually, the checksum is computed in
        this way by
        [CheckedInputStream](https://developer.android.com/reference/java/util/zip/CheckedInputStream)
        and
        [CheckedOutputStream](https://developer.android.com/reference/java/util/zip/CheckedOutputStream),
        see `BackupRestoreStorage` implementation for more details.
-   Enhanced forward compatibility for file is enabled: If a backup includes
    data that didn't exist in earlier versions of the app, the data can still be
    successfully restored in those older versions. This is achieved by extending
    the `BackupRestoreFileStorage` class, and `BackupRestoreFileArchiver` will
    treat each file as an entity and do the backup / restore.
-   Manual `BackupManager.dataChanged` call is unnecessary now, the framework
    will invoke the API automatically.

## Usages

This section provides [examples](example/ExampleStorage.kt) of datastore.

Here is a datastore with a string data:

```kotlin
class ExampleStorage : ObservableBackupRestoreStorage() {
  @Volatile // field is manipulated by multiple threads, synchronization might be needed
  var data: String? = null
    private set

  @AnyThread
  fun setData(data: String?) {
    this.data = data
    // call notifyChange to trigger backup and metrics logging whenever data is changed
    if (data != null) {
      notifyChange(ChangeReason.UPDATE)
    } else {
      notifyChange(ChangeReason.DELETE)
    }
  }

  override val name: String
    get() = "ExampleStorage"

  override fun createBackupRestoreEntities(): List<BackupRestoreEntity> =
    listOf(StringEntity("data"))

  override fun enableRestore(): Boolean {
    return true // check condition like flag, environment, etc.
  }

  override fun enableBackup(backupContext: BackupContext): Boolean {
    return true // check condition like flag, environment, etc.
  }

  @BinderThread
  private inner class StringEntity(override val key: String) : BackupRestoreEntity {
    override fun backup(backupContext: BackupContext, outputStream: OutputStream) =
      if (data != null) {
        outputStream.write(data!!.toByteArray(UTF_8))
        EntityBackupResult.UPDATE
      } else {
        EntityBackupResult.DELETE // delete existing backup data
      }

    override fun restore(restoreContext: RestoreContext, inputStream: InputStream) {
      // DO NOT call setData API here, which will trigger notifyChange unexpectedly.
      // Under the hood, the datastore library will call notifyChange(ChangeReason.RESTORE)
      // later to notify observers.
      data = String(inputStream.readBytes(), UTF_8)
      // Handle restored data in onRestoreFinished() callback
    }
  }

  override fun onRestoreFinished() {
    // TODO: Update state with the restored data. Use this callback instead of "restore()" in
    //       case the restore action involves several entities.
    // NOTE: The library will call notifyChange(ChangeReason.RESTORE) for you
  }
}
```

And this is a datastore with key value data:

```kotlin
class ExampleKeyValueStorage :
  BackupRestoreStorage(), KeyedObservable<String> by KeyedDataObservable() {
  // thread safe data structure
  private val map = ConcurrentHashMap<String, String>()

  override val name: String
    get() = "ExampleKeyValueStorage"

  fun updateData(key: String, value: String?) {
    if (value != null) {
      map[key] = value
      notifyChange(ChangeReason.UPDATE)
    } else {
      map.remove(key)
      notifyChange(ChangeReason.DELETE)
    }
  }

  override fun createBackupRestoreEntities(): List<BackupRestoreEntity> =
    listOf(createMapBackupRestoreEntity())

  private fun createMapBackupRestoreEntity() =
    object : BackupRestoreEntity {
      override val key: String
        get() = "map"

      override fun backup(
        backupContext: BackupContext,
        outputStream: OutputStream,
      ): EntityBackupResult {
        // Use TreeMap to achieve predictable and stable order, so that data will not be
        // updated to Android backup backend if there is only order change.
        val copy = TreeMap(map)
        if (copy.isEmpty()) return EntityBackupResult.DELETE
        val dataOutputStream = DataOutputStream(outputStream)
        dataOutputStream.writeInt(copy.size)
        for ((key, value) in copy) {
          dataOutputStream.writeUTF(key)
          dataOutputStream.writeUTF(value)
        }
        return EntityBackupResult.UPDATE
      }

      override fun restore(restoreContext: RestoreContext, inputStream: InputStream) {
        val dataInputString = DataInputStream(inputStream)
        repeat(dataInputString.readInt()) {
          val key = dataInputString.readUTF()
          val value = dataInputString.readUTF()
          map[key] = value
        }
      }
    }
}
```

All the datastore should be added in the application class:

```kotlin
class ExampleApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    BackupRestoreStorageManager.getInstance(this)
      .add(ExampleStorage(), ExampleKeyValueStorage())
  }
}
```

Additionally, inject datastore to the custom `BackupAgentHelper` class:

```kotlin
class ExampleBackupAgent : BackupAgentHelper() {
  override fun onCreate() {
    super.onCreate()
    BackupRestoreStorageManager.getInstance(this).addBackupAgentHelpers(this)
  }

  override fun onRestoreFinished() {
    BackupRestoreStorageManager.getInstance(this).onRestoreFinished()
  }
}
```

## Development

Please preserve the code coverage ratio during development. The current line
coverage is **100% (444/444)** and branch coverage is **93.6% (176/188)**.
