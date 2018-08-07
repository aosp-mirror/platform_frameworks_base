Conventions for the protos in this directory:

1. As in the rest of Android, use 4 spaces to indent instead of 2.

1. For protos based on Java files, use the same package as the Java file. For
   example, `com.android.server.thing` instead of `com.android.server.thing.proto`.

1. If the proto describes the top level output of dumpsys, it should contain
   `Dump`. This makes it easy to understand that the proto is the dumpsys output
   of a certain service, not the data structure of that service, e.g.
   `WindowManagerServiceDumpProto` vs `WindowManagerServiceProto`.

   * Inner messages whose containing messages have the `Proto` suffix do not
     need to have a `Proto` suffix. E.g:

```
message FooProto {
    message Bar {
        ...
    }
}
```

     vs

```
message FooProto {
    message BarProto {
        ...
    }
}
```

1. If the proto represents the structure of an object, it should have `Proto` as
   its suffix. Please also include the full package path of the original object
   as a comment to the proto message.

1. Include units in the field names. For example, `screen_time_ms` vs
   `screen_time`, or `file_size_bytes` or `file_size_mebibytes` vs `file_size`.

1. Leave field numbers 50,000 - 100,000 reserved for OEMs.
