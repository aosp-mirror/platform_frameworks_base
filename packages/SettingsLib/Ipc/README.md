# Service IPC library

This library provides a kind of IPC (inter-process communication) framework
based on Android
[bound service](https://developer.android.com/develop/background-work/services/bound-services)
with [Messenger](https://developer.android.com/reference/android/os/Messenger).

Following benefits are offered by the library to improve and simplify IPC
development:

-   Enforce permission check for every API implementation to avoid security
    vulnerability.
-   Allow modular API development for better code maintenance (no more huge
    Service class).
-   Prevent common mistakes, e.g. Service context leaking, ServiceConnection
    management.

## Overview

In this manner of IPC,
[Service](https://developer.android.com/reference/android/app/Service) works
with [Handler](https://developer.android.com/reference/android/os/Handler) to
deal with different types of
[Message](https://developer.android.com/reference/android/os/Message) objects.

Under the hood, each API is represented as a `Message` object:

-   [what](https://developer.android.com/reference/android/os/Message#what):
    used to identify API.
-   [data](https://developer.android.com/reference/android/os/Message#getData\(\)):
    payload of the API parameters and result.

This could be mapped to the `ApiHandler` interface abstraction exactly.
Specifically, the API implementation needs to provide:

-   An unique id for the API.
-   How to marshall/unmarshall the request and response.
-   Whether the given request is permitted.

## Threading model

`MessengerService` starts a dedicated
[HandlerThread](https://developer.android.com/reference/android/os/HandlerThread)
to handle requests. `ApiHandler` implementation uses Kotlin `suspend`, which
allows flexible threading model on top of the
[Kotlin coroutines](https://kotlinlang.org/docs/coroutines-overview.html).

## Usage

The service provider should extend `MessengerService` and provide API
implementations. In `AndroidManifest.xml`, declare `<service>` with permission,
intent filter, etc. if needed.

Meanwhile, the service client implements `MessengerServiceClient` with API
descriptors to make requests.

Here is an example:

```kotlin
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.runBlocking

class EchoService :
  MessengerService(
    listOf(EchoApiImpl),
    PermissionChecker { _, _, _ -> true },
  )

class EchoServiceClient(context: Context) : MessengerServiceClient(context) {
  override val serviceIntentFactory: () -> Intent
    get() = { Intent("example.intent.action.ECHO") }

  fun echo(data: String?): String? =
    runBlocking { invoke(context.packageName, EchoApi, data).await() }
}

object EchoApi : ApiDescriptor<String?, String?> {
  private val codec =
    object : MessageCodec<String?> {
      override fun encode(data: String?) =
        Bundle(1).apply { putString("data", data) }

      override fun decode(data: Bundle): String? = data.getString("data", null)
    }

  override val id: Int
    get() = 1

  override val requestCodec: MessageCodec<String?>
    get() = codec

  override val responseCodec: MessageCodec<String?>
    get() = codec
}

// This is not needed by EchoServiceClient.
object EchoApiImpl : ApiHandler<String?, String?>,
                     ApiDescriptor<String?, String?> by EchoApi {
  override suspend fun invoke(
    application: Application,
    myUid: Int,
    callingUid: Int,
    request: String?,
  ): String? = request

  override fun hasPermission(
    application: Application,
    myUid: Int,
    callingUid: Int,
    request: String?,
  ): Boolean = (request?.length ?: 0) <= 5
}
```
