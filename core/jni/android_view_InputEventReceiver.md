# Batched consumption #

Most apps draw once per vsync. Therefore, apps can only respond to 1 input event per frame. If multiple input events come in during the period of 1 vsync, it would be wasteful to deliver them all at once to the app. For this reason, input events are batched to only deliver 1 event per frame to the app.

The batching process works in the following manner:

1. `InputDispatcher` sends an event to the app
2. The app's `Looper` is notified about the available event.
3. The `handleEvent` callback is executed. Events are read from fd.
4. If a batched input event is available, `InputConsumer::hasPendingBatch` returns true. No event is sent to the app at this point.
5. The app is notified that a batched event is available for consumption, and schedules a runnable via the `Choreographer` to consume it a short time before the next frame
6. When the scheduled runnable is executed, it doesn't just consume the batched input. It proactively tries to consume everything that has come in to the socket.
7. The batched event is sent to the app, along with any of the other events that have come in.

Let's discuss the specifics of some of these steps.

## 1. Consuming events in `handleEvent` callback ##

The app is notified about the available event via the `Looper` callback `handleEvent`. When the app's input socket becomes readable (e.g., it has unread events), the looper will execute `handleEvent`. At this point, the app is expected to read in the events that have come in to the socket. The function `handleEvent` will continue to trigger as long as there are unread events in the socket. Thus, the app could choose to read events 1 at a time, or all at once. If there are no more events in the app's socket, handleEvent will no longer execute.

Even though it is perfectly valid for the app to read events 1 at a time, it is more efficient to read them all at once. Therefore, whenever the events are available, the app will try to completely drain the socket.

To consume the events inside `handleEvent`, the app calls `InputConsumer::consume(.., consumeBatches=false, frameTime=-1, ..)`. That is, when `handleEvent` runs, there is no information about the upcoming frameTime, and we dont want to consume the batches because there may be other events that come in before the 'consume batched input' runnable runs.

If a batched event comes in at this point (typically, any MOVE event that has source = TOUCHSCREEN), the `consume` function above would actually return a `NULL` event with status `WOULD_BLOCK`. When this happens, the caller (`NativeInputEventReceiver`) is responsible for checking whether `InputConsumer::hasPendingBatch` is set to true. If so, the caller is responsible for scheduling a runnable to consume these batched events.

## 2. Consuming batched events ##

In the previous section, we learned that the app can read events inside the `handleEvent` callback. The other time when the app reads events is when the 'consume batched input' runnable is executed. This runnable is scheduled via the Choreographer by requesting a `CALLBACK_INPUT` event.

Before the batched events are consumed, the socket is drained once again. This is an optimization.

To consume the events inside 'consume batched input' runnable, the app calls `InputConsumer::consume(.., consumeBatches=true, frameTime=<valid frame time>, ..)`. At this point, the `consume` function will return all batched events up to the `frameTime` point. There may be batched events remaining.

## 3. Key points ##

Some of the behaviours above should be highlighted, because they may be unexpected.

1. Even if events have been read by `InputConsumer`, `consume` will return `NULL` event with status `WOULD_BLOCK` if those events caused a new batch to be started.

2. Events are read from the fd outside of the regular `handleEvent` case, during batched consumption.

3. The function `handleEvent` will always execute as long as there are unread events in the fd

4. The `consume` function is called in 1 of 2 possible ways:
   - `consumeBatches=false, frameTime=-1`
   - `consumeBatches=true, frameTime=<valid time>`

   I.e., it is never called with `consumeBatches=true, frameTime=-1`.
