# Falsing in SystemUI

Phones are easily and often accidentally-activated in owners' pockets ("falsing" or "pocket 
dialing"). Because a phone's screen can be turned on with a single tap, and because we have further
actions that be activated with basic tapping and swiping, it is critical that we
analyze touch events on the screen for intentional vs accidental behavior. With analysis, 
features within SystemUI have an opportunity to ignore or even undo accidental interactions as they
are occurring.

## Technical Details

The `FalsingManager` tracks all touch interactions happening on a phone's lock screen.

If you support any sort of touch gestures on the lock screen, you **must**, at a
minimum, inform the `FalsingManager` of what touches are on touch targets vs not (things that may be
 intentional). If you do not tell the `FalsingManager`, it will assume touches on your feature are
always accidental and penalize the session accordingly.

Individual touch targets do not _have_ to be separated out; it's acceptable to
wrap your whole feature in one virtual block that reports touches to the
`FalsingManager`, however more granular tracking will result in better results
across the whole lock screen.

You can _act_ on the results of the `FalsingManager`. Instead of only telling
the `FalsingManager` that touch events were on touch targets, you can further use the
returned results to decide if you want to respond to an owner's touch, if you
want to prompt them to confirm their action, or if you simply want to ignore the
touch.

The flow through the system looks like such:

1. Gesture on the screen.
2. The `FalsingManager` makes a note of all of the `MotionEvents`.
    * If no feature/touch target receives the `MotionEvents`, skip to 4. 
3. Your touch target receives the `MotionEvents`.
    * Once your feature is ready to respond to the gesture in a substantive manner, it queries
      the `FalsingManager`.
      - Dragging animations, touch ripples, and other purely visual effects should not query.
      - Query once you are ready to launch a new feature or dialogue, or are otherwise going to
        change the state of the UI. 
      - Generally, wait until `MotionEvent.ACTION_UP` to query or `View.OnClickListener#onClick`.
      - Only query once per gesture, at the end.
    * If the `FalsingManager` says it looks good, respond to the touch.
4. The `FalsingManager` checks to see if anyone queried about the gesture. If not, mark it as 
   accidental. 
   
There is also an event fired by the `FalsingManager` that can be listened to by anyone, that 
indicates that the the `FalsingManager` believes the phone is actively being pocket-dialed. When
fired, modal features, such as quick settings, keyguard bouncer, and others should retract 
themselves to prevent further pocket-dialing.  

## Falsing "Belief" and History

The `FalsingManager` maintains a recent history of false analyses. Using
Bayesian statistics, it updates a "belief" in  whether recent
gestures are intentional or not. Any gesture that it is not explicitly queried about is treated as
accidental, increasing the overall belief in
false-iness. Gestures that are explicitly queried and that pass the relevant heuristics
reduce belief that falsing is occurring. This information is tracked within the `HistoryTracker`.

Changes in belief may influence internal heurstics within the `FalsingManager`,
making it easier or harder for an owner to interact with their device. (An owner
will always be able to interact with their device, but we may require double
taps, or more deliberate swipes.)

## Responding to Touch Events

The methods below inform the `FalsingManager` that a tap is occurring within an expected touch 
target. Match the methods with the gesture you expect the device owner to use.

### Single Tap

`FalsingManager#isFalseTap(boolean robustCheck, double falsePenalty)`. This
method tells the `FalsingManager` that you want to validate a single tap. It
returns true if it thinks the tap should be rejected (i.e. the tap looks more
like a swipe) and false otherwise.

`robustCheck` determines what heuristics are used. If set to false, the method
performs a only very basic checking, checking that observed `MotionEvent`s are
all within some small x & y region ("touch slop").

When `robustCheck` is set to true, several more advanced rules are additionally
applied:

1.  If the device recognizes a face (i.e. face-auth) the tap is **accepted**.
2.  If the tap is the _second_ tap in recent history and looks like a valid Double Tap
    the tap is **accepted**. This works exactly like `FalsingManager#isFalseDoubleTap`.
3.  If the `HistoryTracker` reports strong belief in recent falsing, the tap is
    **rejected**.
4.  Otherwise the tap is **accepted**.

All the above rules are applied only after first confirming the gesture does
in fact look like a basic tap.

`falsePenalty` is a measure of how much the `HistoryTracker`'s belief should be
penalized in the event that the tap is rejected. This value is only used if
`robustCheck` is set to true.

A value of `0` means no change in belief. A value of `1` means a _very_ strong
confidence in a false tap. In general, as a single tap on the screen is not
verifiable, a small value should be supplied - on the order of `0.1`. Pass `0`
if you don't want to penalize belief at all. Pass a higher value
the earlier in the UX flow your interaction occurs. Once an owner is farther
along in a UX flow (multiple taps or swipes), its safer to assume that a single
accidental tap should cause less of a penalty.

### Double Tap

`FalsingManager#isFalseDoubleTap()`. This method tells the `FalsingManager` that
your UI wants to validate a double tap. There are no parameters to pass to this method.
Call this when you explicitly receive and want to verify a double tap, _not_ a single tap.

Note that `FalsingManager#isFalseTap(boolean robustCheck, double falsePenalty)`
will also check for double taps when `robustCheck` is set to true. If you are
willing to use single taps, use that instead.

### Swipes and Other Gestures

`FalsingManager#isFalseTouch(@Classifier.InteractionType int interactionType)`.
Use this for any non-tap interactions. This includes expanding notifications,
expanding quick settings, pulling up the bouncer, and more. You must pass
the type of interaction you are evaluating when calling it. A large set of
heuristics will be applied to analyze the gesture, and the exact rules vary depending upon
the `InteractionType`.

### Ignoring A Gesture

`FalsingCollector#avoidGesture()`. Tell the `FalsingManager` to pretend like the
observed gesture never happened. **This method must be called when the observed
`MotionEvent` is `MotionEvent.ACTION_DOWN`.** Attempting to call this method
later in a gesture will not work.

Notice that this method is actually a method on `FalsingCollector`. It is
forcefully telling the `FalsingManager` to wholly pretend the gesture never
happened. This is intended for security and PII sensitive gestures, such as
password inputs. Please don't use this as a shortcut for avoiding the
FalsingManager. Falsing works better the more behavior it is told about.

### Other Considerations

Please try to call the `FalsingManager` only once per gesture. Wait until you
are ready to act on the owner's action, and then query the `FalsingManager`. The `FalsingManager`
will update its belief in pocket dialing based only on the last call made, so multiple calls per
gesture are not well defined.

The `FalsingManager` does not update its belief in pocket-dialing until after a gesture completes.
That is to say, if the owner makes a bad tap on your feature, the "belief" in pocket dialing will
not incorporate this new data after processing on the final `ACTION_UP` or `ACTION_CANCEL` event
occurs.

If you expect a mix of taps, double taps, and swipes on your feature, segment them
accordingly. Figure out which `FalsingManager` method you need to call first, rather than relying
on multiple calls to the `FalsingManager` to act as a sieve.

Don't:
```
if (!mFalsingManager.isFalseTap(false, 0)) {
  // its a tap
} else if (!mFalsingManager.isFalseTouch(GESTURE_A) {
  // do thing a
} else if (!mFalsingManager.isFalseTouch(GESTURE_B) {
  // do thing b
} else {
  // must be a false.
}
``` 

Do:
```
void onTap() {
  if (!mFalsingManager.isFalseTap(false, 0)) {
    // its a tap
}

void onGestureA() {
  if (!mFalsingManager.isFalseTouch(GESTURE_A) {
    // do thing a
  }
}

void onGestureB() {
  if (!mFalsingManager.isFalseTouch(GESTURE_B) {
    // do thing b
  }
}
``` 


## Influencing Belief

`FalsingCollector#updateFalseConfidence(FalsingClassifier.Result result)`. This
method allows you to directly change the `FalsingManager`'s belief in the state
of pocket dialing. If the owner does something unusual with their phone that you
think indicates pocket dialing, you can call:

```
    mFalsingCollector.updateFalseConfidence(
      FalsingClassifier.Result.falsed(0.6, "Owner is doing something fishy"));
```

A belief value of `1` indicates a 100% confidence of false behavior. A belief
value of `0` would make no change in the `FalsingManager` and should be avoided
as it simply creates noise in the logs. Generally, a middle value between the
two extremes makes sense.

A good example of where this is used is in the "Pattern" password input. We
avoid recording those gestures in the `FalsingManager`, but we have the pattern input update
the `FalsingManager` directly in some cases. If the owner simply taps on the pattern input, we 
record it as a false, (patterns are always 4 "cells" long, so single "cell" inputs are penalized).

Conversely, if you think the owner does something that deserves a nice reward:

```
    mFalsingCollector.updateFalseConfidence(
       FalsingClassifier.Result.passed(0.6));
```

Again, useful on password inputs where the FalsingManager is avoiding recording
the gesture. This is used on the "pin" password input, to recognize successful
taps on the input buttons.

## Global Falsing Event

If the `FalsingManager`'s belief in falsing crosses some internally defined
threshold, it will fire an event that other parts of the system can listen for.
This even indicates that the owner is likely actively pocket-dialing, and any
currently open activities on the phone should retract themselves.

To subscribe to this event, call
`FalsingManager#addFalsingBeliefListener(FalsingBeliefListener listener)`.
`FalsingBeliefListener` is a simple one method interface that will be called
after when activities should retract themselves.

**Do Listen For This**. Your code will work without it, but it is a handy,
universal signal that will save the phone owner a lot of accidents. A simple
implementation looks like:

```
    mFalsingManager.addFalsingBeliefListener(MyFeatureClass::hide);
```
