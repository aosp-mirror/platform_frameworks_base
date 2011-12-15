var ANDROID_TAGS = {
  type: {
    'article': 'Article',
    'tutorial': 'Tutorial',
    'sample': 'Sample',
    'video': 'Video',
    'library': 'Code Library'
  },
  topic: {
    'accessibility': 'Accessibility',
    'accountsync': 'Accounts &amp; Sync',
    'bestpractice': 'Best Practices',
    'communication': 'Communication',
    'compatibility': 'Compatibility',
    'data': 'Data Access',
    'drawing': 'Canvas Drawing',
    'gamedev': 'Game Development',
    'gl': 'OpenGL ES',
    'input': 'Input Methods',
    'intent': 'Intents',
    'layout': 'Layouts/Views',
    'media': 'Multimedia',
    'multitasking': 'Multi-tasking',
    'newfeature': 'New Features',
    'performance': 'Performance',
    'search': 'Search',
    'testing': 'Testing',
    'ui': 'User Interface',
    'web': 'Web Content',
    'widgets': 'App Widgets'
  },
  misc: {
    'external': 'External',
    'new': 'New',
    'updated': 'Updated'
  }
};

var ANDROID_RESOURCES = [

//////////////////////////
/// TECHNICAL ARTICLES ///
//////////////////////////

  {
    tags: ['article', 'performance', 'bestpractice'],
    path: 'articles/avoiding-memory-leaks.html',
    title: {
      en: 'Avoiding Memory Leaks'
    },
    description: {
      en: 'Mobile devices often have limited memory, and memory leaks can cause your application to waste this valuable resource without your knowledge. This article provides tips to help you avoid common causes of memory leaks on the Android platform.'
    }
  },
  {
    tags: ['article', 'compatibility'],
    path: 'articles/backward-compatibility.html',
    title: {
      en: 'Backward Compatibility'
    },
    description: {
      en: 'The Android platform strives to ensure backwards compatibility. However, sometimes you want to use new features which aren\'t supported on older platforms. This article discusses strategies for selectively using these features based on availability, allowing you to keep your applications portable across a wide range of devices.'
    }
  },
  {
    tags: ['article', 'intent'],
    path: 'articles/can-i-use-this-intent.html',
    title: {
      en: 'Can I Use this Intent?'
    },
    description: {
      en: 'Android offers a very powerful and yet easy-to-use message type called an intent. You can use intents to turn applications into high-level libraries and make code modular and reusable. While it is nice to be able to make use of a loosely coupled API, there is no guarantee that the intent you send will be received by another application. This article describes a technique you can use to find out whether the system contains any application capable of responding to the intent you want to use.'
    }
  },
  {
    tags: ['article', 'input'],
    path: 'articles/creating-input-method.html',
    title: {
      en: 'Creating an Input Method'
    },
    description: {
      en: 'Input Method Editors (IMEs) provide the mechanism for entering text into text fields and other Views. Android devices come bundled with at least one IME, but users can install additional IMEs. This article covers the basics of developing an IME for the Android platform.'
    }
  },
  {
    tags: ['article', 'drawing', 'ui'],
    path: 'articles/drawable-mutations.html',
    title: {
      en: 'Drawable Mutations'
    },
    description: {
      en: 'Drawables are pluggable drawing containers that allow applications to display graphics. This article explains some common pitfalls when trying to modify the properties of multiple Drawables.'
    }
  },
  {
    tags: ['article', 'bestpractice', 'ui'],
    path: 'articles/faster-screen-orientation-change.html',
    title: {
      en: 'Faster Screen Orientation Change'
    },
    description: {
      en: 'When an Android device changes its orientation, the default behavior is to automatically restart the current activity with a new configuration. However, this can become a bottleneck in applications that access a large amount of external data. This article discusses how to gracefully handle this situation without resorting to manually processing configuration changes.'
    }
  },
  {
    tags: ['article', 'compatibility'],
    path: 'articles/future-proofing.html',
    title: {
      en: 'Future-Proofing Your Apps'
    },
    description: {
      en: 'A collection of common sense advice to help you ensure that your applications don\'t break when new versions of the Android platform are released.'
    }
  },
  {
    tags: ['article', 'input'],
    path: 'articles/gestures.html',
    title: {
      en: 'Gestures'
    },
    description: {
      en: 'Touch screens allow users to perform gestures, such as tapping, dragging, flinging, or sliding, to perform various actions. The gestures API enables your application to recognize even complicated gestures with ease. This article explains how to integrate this API into an application.'
    }
  },
  {
    tags: ['article', 'gamedev', 'gl'],
    path: 'articles/glsurfaceview.html',
    title: {
      en: 'Introducing GLSurfaceView'
    },
    description: {
      en: 'This article provides an overview of GLSurfaceView, a class that makes it easy to implement 2D or 3D OpenGL rendering inside of an Android application.'
    }
  },
  {
    tags: ['article', 'ui', 'layout'],
    path: 'articles/layout-tricks-reuse.html',
    title: {
      en: 'Layout Tricks: Creating Reusable UI Components'
    },
    description: {
      en: 'Learn how to combine multiple standard UI widgets into a single high-level component, which can be reused throughout your application.'
    }
  },
  {
    tags: ['article', 'layout', 'ui', 'performance', 'bestpractice'],
    path: 'articles/layout-tricks-efficiency.html',
    title: {
      en: 'Layout Tricks: Creating Efficient Layouts'
    },
    description: {
      en: 'Learn how to optimize application layouts as this article walks you through converting a LinearLayout into a RelativeLayout, and analyzes the resulting implications on performance.'
    }
  },
  {
    tags: ['article', 'layout', 'ui', 'performance', 'bestpractice'],
    path: 'articles/layout-tricks-stubs.html',
    title: {
      en: 'Layout Tricks: Using ViewStubs'
    },
    description: {
      en: 'Learn about using ViewStubs inside an application\'s layout in order to inflate rarely used UI elements, without the performance implications which would otherwise be caused by using the <code>&lt;include&gt;</code> tag.'
    }
  },
  {
    tags: ['article', 'layout', 'ui', 'performance', 'bestpractice'],
    path: 'articles/layout-tricks-merge.html',
    title: {
      en: 'Layout Tricks: Merging Layouts'
    },
    description: {
      en: 'Learn how to use the <code>&lt;merge&gt;</code> tag in your XML layouts in order to avoid unnecessary levels of hierarchy within an application\'s view tree.'
    }
  },
  {
    tags: ['article', 'ui', 'performance'],
    path: 'articles/listview-backgrounds.html',
    title: {
      en: 'ListView Backgrounds: An Optimization'
    },
    description: {
      en: 'ListViews are very popular widgets within the Android framework. This article describes some of the optimizations used by the ListView widget, and how to avoid some common issues that this causes when trying to use a custom background.'
    }
  },
  {
    tags: ['article', 'ui'],
    path: 'articles/live-folders.html',
    title: {
      en: 'Live Folders'
    },
    description: {
      en: 'Live Folders allow users to display any source of data on their home screen without launching an application. This article discusses how to export an application\'s data in a format suitable for display inside of a live folder.'
    }
  },
  {
    tags: ['article', 'ui'],
    path: 'articles/live-wallpapers.html',
    title: {
      en: 'Live Wallpapers'
    },
    description: {
      en: 'Live wallpapers are richer, animated, interactive backgrounds that users can display in their home screens. Learn how to create a live wallpaper and bundle it in an application that users can install on their devices.'
    }
  },
  {
    tags: ['article', 'bestpractice', 'multitasking'],
    path: 'articles/multitasking-android-way.html',
    title: {
      en: 'Multitasking the Android Way'
    },
    description: {
      en: 'This article describes best practices and user experience guidelines for multi-tasking on Android.'
    }
  },
  {
    tags: ['article', 'input'],
    path: 'articles/on-screen-inputs.html',
    title: {
      en: 'Onscreen Input Methods'
    },
    description: {
      en: 'The Input Method Framework (IMF) allows users to take advantage of on-screen input methods, such as software keyboards. This article provides an overview of Input Method Editors (IMEs) and how applications interact with them.'
    }
  },
  {
    tags: ['article', 'performance', 'bestpractice'],
    path: 'articles/painless-threading.html',
    title: {
      en: 'Painless Threading'
    },
    description: {
      en: 'This article discusses the threading model used by Android applications and how applications can ensure best UI performance by spawning worker threads to handle long-running operations, rather than handling them in the main thread. The article also explains the API that your application can use to interact with Android UI toolkit components running on the main thread and spawn managed worker threads.'
    }
  },
  {
    tags: ['article', 'ui', 'search'],
    path: 'articles/qsb.html',
    title: {
      en: 'Quick Search Box'
    },
    description: {
      en: 'Quick Search Box (QSB) is a powerful, system-wide search framework. QSB makes it possible for users to quickly and easily find what they\'re looking for, both on their devices and on the web. This article discusses how to work with the QSB framework to add new search results for an installed application.'
    }
  },
  {
    tags: ['article', 'input', 'search', 'ui'],
    path: 'articles/speech-input.html',
    title: {
      en: 'Speech Input'
    },
    description: {
      en: 'This articles describes the basics of integrating speech recognition into Android applications.'
    }
  },
  {
    tags: ['article', 'compatibility', 'multitasking'],
    path: 'articles/service-api-changes-starting-with.html',
    title: {
      en: 'Service API changes starting with Android 2.0'
    },
    description: {
      en: 'This article describes the changes and improvements to services introduced in Android 2.0, as well as strategies for compatibility with older versions of the platform.'
    }
  },
  {
    tags: ['article', 'ui'],
    path: 'articles/touch-mode.html',
    title: {
      en: 'Touch Mode'
    },
    description: {
      en: 'This article explains the touch mode, one of the most important principles of Android\'s UI toolkit. Whenever a user interacts with a device\'s touch screen, the system enters touch mode. While simple in concept, there are important implications touch mode that are often overlooked.'
    }
  },
  {
    tags: ['article', 'performance', 'bestpractice'],
    path: 'articles/track-mem.html',
    title: {
      en: 'Tracking Memory Allocations'
    },
    description: {
      en: 'This article discusses how to use the Allocation Tracker tool to observe memory allocations and avoid performance problems that would otherwise be caused by ignoring the effect of Dalvik\'s garbage collector.'
    }
  },
  {
    tags: ['article'],
    path: 'articles/ui-1.5.html',
    title: {
      en: 'UI Framework Changes in Android 1.5'
    },
    description: {
      en: 'Explore the UI changes that were introduced in Android 1.5, compared with the UI provided in Android 1.0 and 1.1.'
    }
  },
  {
    tags: ['article'],
    path: 'articles/ui-1.6.html',
    title: {
      en: 'UI Framework Changes in Android 1.6'
    },
    description: {
      en: 'Explore the UI changes that were introduced in Android 1.6, compared with the UI provided in Android 1.5. In particular, this article discusses changes to RelativeLayouts and click listeners.'
    }
  },
  {
    tags: ['article', 'ui', 'bestpractice'],
    path: 'articles/timed-ui-updates.html',
    title: {
      en: 'Updating the UI from a Timer'
    },
    description: {
      en: 'Learn about how to use Handlers as a more efficient replacement for java.util.Timer on the Android platform.'
    }
  },
  {
    tags: ['article', 'ui', 'accessibility'],
    path: 'articles/tts.html',
    title: {
      en: 'Using Text-to-Speech'
    },
    description: {
      en: 'The text-to-speech API lets your application "speak" to users, in any of several languages. This article provides an overview of the TTS API and how you use to add speech capabilities to your application.'
    }
  },
  {
    tags: ['article', 'accountsync', 'data'],
    path: 'articles/contacts.html',
    title: {
      en: 'Using the Contacts API'
    },
    description: {
      en: 'Android provides a Contacts API for managing and integrating contacts from multiple accounts and data sources and allows apps to read various information about individual contacts.'
    }
  },
  {
    tags: ['article', 'ui', 'web'],
    path: 'articles/using-webviews.html',
    title: {
      en: 'Using WebViews'
    },
    description: {
      en: 'WebViews allow an application to dynamically display HTML and execute JavaScript, without relinquishing control to a separate browser application. This article introduces the WebView classes and provides a sample application that demonstrates its use.'
    }
  },
  {
    tags: ['article', 'ui'],
    path: 'articles/wikinotes-linkify.html',
    title: {
      en: 'WikiNotes: Linkify your Text!'
    },
    description: {
      en: 'This article introduces WikiNotes for Android, part of the Apps for Android project. It covers the use of Linkify to turn ordinary text views into richer, link-oriented content that causes Android intents to fire when a link is selected.'
    }
  },
  {
    tags: ['article', 'intent'],
    path: 'articles/wikinotes-intents.html',
    title: {
      en: 'WikiNotes: Routing Intents'
    },
    description: {
      en: 'This article illustrates how an application, in this case the WikiNotes sample app, can use intents to route various types of linked text to the application that handles that type of data. For example, an app can use intents to route a linked telephone number to a dialer app and a web URL to a browser.'
    }
  },
  {
    tags: ['article', 'ui', 'performance'],
    path: 'articles/window-bg-speed.html',
    title: {
      en: 'Window Backgrounds & UI Speed'
    },
    description: {
      en: 'Some Android applications need to squeeze every bit of performance out of the UI toolkit and there are many ways to do so. In this article, you will discover how to speed up the drawing and the perceived startup time of your activities. Both of these techniques rely on a single feature, the window\'s background drawable.'
    }
  },
  {
    tags: ['article', 'performance', 'bestpractice'],
    path: 'articles/zipalign.html',
    title: {
      en: 'Zipalign: an Easy Optimization'
    },
    description: {
      en: 'The Android SDK includes a tool called zipalign that optimizes the way an application is packaged. Running zipalign against your application enables Android to interact with it more efficiently at run time and thus has the potential to make it and the overall system run faster. This article provides a high-level overview of the zipalign tool and its use.'
    }
  },

///////////////////
/// SAMPLE CODE ///
///////////////////
 
  {
    tags: ['sample'],
    path: 'samples/AccelerometerPlay/index.html',
    title: {
      en: 'Accelerometer Play'
    },
    description: {
      en: 'An example of using the accelerometer to integrate the device\'s acceleration to a position using the Verlet method. This is illustrated with a very simple particle system comprised of a few iron balls freely moving on an inclined wooden table. The inclination of the virtual table is controlled by the device\'s accelerometer.'
    }
  },
  {
    tags: ['sample', 'new', 'ui', 'compatibility', 'newfeature'],
    path: 'samples/ActionBarCompat/index.html',
    title: {
      en: 'Action Bar Compatibility'
    },
    description: {
      en: 'Shows how to use the action bar on both pre-API 11 and API 11+ devices, maximizing code re-use.'
    }
  },
  {
    tags: ['sample', 'new'],
    path: 'samples/AndroidBeamDemo/index.html',
    title: {
      en: 'Android Beam Demo'
    },
    description: {
      en: 'An example of how to use the Android Beam feature to send messages between two Android-powered devices (API level 14 or later) that support NFC.'
    }
  },
  {
    tags: ['sample', 'layout', 'ui', 'updated'],
    path: 'samples/ApiDemos/index.html',
    title: {
      en: 'API Demos'
    },
    description: {
      en: 'A variety of small applications that demonstrate an extensive collection of framework topics.'
    }
  },
  {
    tags: ['sample', 'layout', 'ui', 'fragment', 'loader'],
    path: 'samples/Support4Demos/index.html',
    title: {
      en: 'API 4+ Support Demos'
    },
    description: {
      en: 'A variety of small applications that demonstrate the use of the helper classes in the Android API 4+ Support Library (classes which work down to API level 4 or version 1.6 of the platform).'
    }
  },
  {
    tags: ['sample', 'layout', 'ui'],
    path: 'samples/Support13Demos/index.html',
    title: {
      en: 'API 13+ Support Demos'
    },
    description: {
      en: 'A variety of small applications that demonstrate the use of the helper classes in the Android API 13+ Support Library (classes which work down to API level 13 or version 3.2 of the platform).'
    }
  },
  {
    tags: ['sample', 'data', 'newfeature', 'accountsync'],
    path: 'samples/BackupRestore/index.html',
    title: {
      en: 'Backup and Restore'
    },
    description: {
      en: 'Illustrates a few different approaches that an application developer can take when integrating with the Android Backup Manager using the BackupAgent API introduced in Android 2.2.'
    }
  },
  {
    tags: ['sample', 'communication'],
    path: 'samples/BluetoothChat/index.html',
    title: {
      en: 'Bluetooth Chat'
    },
    description: {
      en: 'An application for two-way text messaging over Bluetooth.'
    }
  },
  {
    tags: ['sample', 'communication', 'new'],
    path: 'samples/BluetoothHDP/index.html',
    title: {
      en: 'Bluetooth HDP Demo'
    },
    description: {
      en: 'A sample application that demonstrates how to communicate with a Bluetooth Health Device Profile (HDP) device.'
    }
  },
  {
    tags: ['sample', 'accountsync'],
    path: 'samples/BusinessCard/index.html',
    title: {
      en: 'BusinessCard'
    },
    description: {
      en: 'An application that demonstrates how to launch the built-in contact picker from within an activity. This sample also uses reflection to ensure that the correct version of the contacts API is used, depending on which API level the application is running under.'
    }
  },
  {
    tags: ['sample', 'accountsync'],
    path: 'samples/ContactManager/index.html',
    title: {
      en: 'Contact Manager'
    },
    description: {
      en: 'An application that demonstrates how to query the system contacts provider  using the <code>ContactsContract</code> API, as well as insert contacts into a specific account.'
    }
  },
  {
    tags: ['sample', 'ui'],
    path: 'samples/CubeLiveWallpaper/index.html',
    title: {
      en: 'Cube Live Wallpaper'
    },
    description: {
      en: 'An application that demonstrates how to create a live wallpaper and  bundle it in an application that users can install on their devices.'
    }
  },
  {
    tags: ['sample', 'new'],
    path: 'samples/training/device-management-policy/index.html',
    title: {
      en: 'Device Policy Management'
    },
    description: {
      en: 'This is a security-aware sample application that demonstrates the enforcement of device administration policies on Android 2.2 or above platforms.'
    }
  },
  {
    tags: ['sample'],
    path: 'samples/Home/index.html',
    title: {
      en: 'Home'
    },
    description: {
      en: 'A home screen replacement application.'
    }
  },
  {
    tags: ['sample', 'updated', 'newfeature', 'ui'],
    path: 'samples/HoneycombGallery/index.html',
    title: {
      en: 'Honeycomb Gallery'
    },
    description: {
      en: 'An image gallery application that demonstrates a variety of new APIs in Android 3.0 (Honeycomb). In addition to providing a tablet-optimized design, it also supports handsets running Android 4.0 (Ice Cream Sandwich) and beyond, so is a good example of how to reuse Fragments to support different screen sizes.'
    }
  },
  {
    tags: ['sample', 'gamedev', 'media'],
    path: 'samples/JetBoy/index.html',
    title: {
      en: 'JetBoy'
    },
    description: {
      en: 'A game that demonstrates the SONiVOX JET interactive music technology, with <code><a href="/reference/android/media/JetPlayer.html">JetPlayer</a></code>.'
    }
  },
  {
    tags: ['sample', 'gamedev', 'media'],
    path: 'samples/LunarLander/index.html',
    title: {
      en: 'Lunar Lander'
    },
    description: {
      en: 'A classic Lunar Lander game.'
    }
  },
  {
    tags: ['sample', 'new'],
    path: 'samples/training/ads-and-ux/index.html',
    title: {
      en: 'Mobile Advertisement Integration'
    },
    description: {
      en: 'This sample demonstrates the integration of a mobile ad SDK with your application.'
    }
  },
  {
    tags: ['sample', 'ui', 'bestpractice', 'layout'],
    path: 'samples/MultiResolution/index.html',
    title: {
      en: 'Multiple Resolutions'
    },
    description: {
      en: 'A sample application that shows how to use resource directory qualifiers to provide different resources for different screen configurations.'
    }
  },
  {
    tags: ['sample', 'new', 'bestpractices'],
    path: 'samples/newsreader/index.html',
    title: {
      en: 'News Reader'
    },
    description: {
      en: 'A sample app demonstrating best practices to support multiple screen sizes and densities.'
    }
  },
  {
    tags: ['sample', 'data'],
    path: 'samples/NFCDemo/index.html',
    title: {
      en: 'NFC Demo'
    },
    description: {
      en: 'An application for reading NFC Forum Type 2 Tags using the NFC APIs'
    }
  },
  {
    tags: ['sample', 'data'],
    path: 'samples/NotePad/index.html',
    title: {
      en: 'Note Pad'
    },
    description: {
      en: 'An application for saving notes. Similar (but not identical) to the <a href="/resources/tutorials/notepad/index.html">Notepad tutorial</a>.'
    }
  },
  {
    tags: ['sample', 'media', 'updated'],
    path: 'samples/RandomMusicPlayer/index.html',
    title: {
      en: 'Random Music Player'
    },
    description: {
      en: 'Demonstrates how to write a multimedia application that plays music from the device and from URLs. It manages media playback from a service and can play music in the background, respecting audio focus changes. Also shows how to use the new Remote Control APIs in API level 14.'
    }
  },
  {
    tags: ['sample', 'newfeature', 'performance', 'gamedev', 'gl', 'updated'],
    path: 'samples/RenderScript/index.html',
    title: {
      en: 'RenderScript'
    },
    description: {
      en: 'A set of samples that demonstrate how to use various features of the RenderScript APIs.'
    }
  },
  {
    tags: ['sample', 'input', 'new'],
    path: 'samples/SpellChecker/SampleSpellCheckerService/index.html',
    title: {
      en: 'Spell Checker Service'
    },
    description: {
      en: 'An example spell checker service, using the <a href="'+toRoot+'reference/android/service/textservice/SpellCheckerService.html"><code>SpellCheckerService</code></a>.'
    }
  },
  {
    tags: ['sample', 'input', 'new'],
    path: 'samples/SpellChecker/HelloSpellChecker/index.html',
    title: {
      en: 'Spell Checker Client'
    },
    description: {
        en: 'An example spell checker client, using the <a href="'+toRoot+'reference/android/view/textservice/TextServicesManager.html"><code>TextServicesManager</code></a> and <a href="'+toRoot+'reference/android/view/textservice/SpellCheckerSession.html"><code>SpellCheckerSession</code></a>.'
    }
  },
  {
    tags: ['sample', 'accountsync', 'updated'],
    path: 'samples/SampleSyncAdapter/index.html',
    title: {
      en: 'SampleSyncAdapter'
    },
    description: {
      en: 'Demonstrates how an application can communicate with a cloud-based service and synchronize its data with data stored locally in a content provider. The sample uses two related parts of the Android framework &mdash; the account manager and the synchronization manager (through a sync adapter).'
    }
  },
  {
    tags: ['sample', 'ui', 'search'],
    path: 'samples/SearchableDictionary/index.html',
    title: {
      en: 'Searchable Dictionary v2'
    },
    description: {
      en: 'A sample application that demonstrates Android\'s search framework, including how to provide search suggestions for Quick Search Box.'
    }
  },
  {
    tags: ['sample'],
    path: 'samples/SipDemo/index.html',
    title: {
      en: 'SIP Demo'
    },
    description: {
      en: 'A demo application highlighting how to make internet-based calls with the SIP API.'
    }
  },
  {
    tags: ['sample', 'layout', 'ui'],
    path: 'samples/Snake/index.html',
    title: {
      en: 'Snake'
    },
    description: {
      en: 'An implementation of the classic game "Snake."'
    }
  },
  {
    tags: ['sample', 'input'],
    path: 'samples/SoftKeyboard/index.html',
    title: {
      en: 'Soft Keyboard'
    },
    description: {
      en: 'An example of writing an input method for a software keyboard.'
    }
  },
  {
    tags: ['sample', 'testing'],
    path: 'samples/Spinner/index.html',
    title: {
      en: 'Spinner'
    },
    description: {
      en: 'A simple application that serves as an application under test for the SpinnerTest example.'
    }
  },
  {
    tags: ['sample', 'testing'],
    path: 'samples/SpinnerTest/index.html',
    title: {
      en: 'SpinnerTest'
    },
    description: {
      en: 'The test application for the Activity Testing tutorial. It tests the Spinner example application.'
    }
  },
  {
    tags: ['sample', 'newfeature', 'widgets'],
    path: 'samples/StackWidget/index.html',
    title: {
      en: 'StackView Widget'
    },
    description: {
      en: 'Demonstrates how to create a simple collection widget containing a StackView.'
    }
  },
  {
    tags: ['sample', 'newfeature'],
    path: 'samples/TicTacToeLib/index.html',
    title: {
      en: 'TicTacToeLib'
    },
    description: {
      en: 'An example of an Android library project, a type of project that lets you store and manage shared code and resources in one place, then make them available to your other Android applications.'
    }
  },
  {
    tags: ['sample', 'newfeature',],
    path: 'samples/TicTacToeMain/index.html',
    title: {
      en: 'TicTacToeMain'
    },
    description: {
      en: 'Demonstrates how an application can make use of shared code and resources stored in an Android library project.'
    }
  },
  {
    tags: ['sample', 'communication', 'new'],
    path: 'samples/ToyVpn/index.html',
    title: {
      en: 'Toy VPN Client'
    },
    description: {
      en: 'A sample application that illustrates the creation of a custom VPN client.'
    }
  },
  {
    tags: ['sample', 'newfeature'],
    path: 'samples/USB/index.html',
    title: {
      en: 'USB'
    },
    description: {
      en: 'A set of samples that demonstrate how to use various features of the USB APIs.'
    }
  },
  {
    tags: ['sample', 'data', 'new'],
    path: 'samples/VoicemailProviderDemo/index.html',
    title: {
      en: 'Voicemail Provider'
    },
    description: {
      en: 'A sample application to demonstrate how to use voicemail content provider APIs in Android 4.0.'
    }
  },
  {
    tags: ['sample','newfeature', 'new'],
    path: 'samples/WiFiDirectDemo/index.html',
    title: {
      en: 'Wi-Fi Direct Demo'
    },
    description: {
      en: 'A demo application to demonstrate how to use Wi-Fi Direct APIs.'
    }
  },
  {
    tags: ['sample', 'ui', 'widgets'],
    path: 'samples/Wiktionary/index.html',
    title: {
      en: 'Wiktionary'
    },
    description: {
      en: 'An example of creating interactive widgets for display on the Android home screen.'
    }
  },
  {
    tags: ['sample', 'ui', 'widgets'],
    path: 'samples/WiktionarySimple/index.html',
    title: {
      en: 'Wiktionary (Simplified)'
    },
    description: {
      en: 'A simple Android home screen widgets example.'
    }
  },
  {
    tags: ['sample', 'widgets', 'newfeature'],
    path: 'samples/WeatherListWidget/index.html',
    title: {
      en: 'Weather List Widget'
    },
    description: {
      en: 'A more complex collection-widget example which uses a ContentProvider as its data source.'
    }
  },
  {
    tags: ['sample', 'layout'],
    path: 'samples/XmlAdapters/index.html',
    title: {
      en: 'XML Adapters'
    },
    description: {
      en: 'Binding data to views using XML Adapters examples.'
    }
  },
  {
    tags: ['sample', 'new', 'accessibility'],
    path: 'samples/TtsEngine/index.html',
    title: {
      en: 'Text To Speech Engine'
    },
    description: {
      en: 'An example Text To Speech engine written using the Android text to speech engine API in Android 4.0.'
    }
  },

/////////////////
/// TUTORIALS ///
/////////////////

  {
    tags: ['tutorial'],
    path: 'tutorials/hello-world.html',
    title: {
      en: 'Hello World'
    },
    description: {
      en: 'Beginning basic application development with the Android SDK.'
    }
  },
  {
    tags: ['tutorial', 'ui', 'layout'],
    path: 'tutorials/views/index.html',
    title: {
      en: 'Hello Views'
    },
    description: {
      en: 'A walk-through of the various types of layouts and views available in the Android SDK.'
    }
  },
  {
    tags: ['tutorial', 'ui', 'bestpractice'],
    path: 'tutorials/localization/index.html',
    title: {
      en: 'Hello Localization'
    },
    description: {
      en: 'The basics of localizing your applications for multiple languages and locales.'
    }
  },
  {
    tags: ['tutorial', 'data'],
    path: 'tutorials/notepad/index.html',
    title: {
      en: 'Notepad Tutorial'
    },
    description: {
      en: 'A multi-part tutorial discussing intermediate-level concepts such as data access.'
    }
  },
  {
    tags: ['tutorial', 'gl'],
    path: 'tutorials/opengl/opengl-es10.html',
    title: {
      en: 'OpenGL ES 1.0'
    },
    description: {
      en: 'The basics of implementing an application using the OpenGL ES 1.0 APIs.'
    }
  },
  {
    tags: ['tutorial', 'gl'],
    path: 'tutorials/opengl/opengl-es20.html',
    title: {
      en: 'OpenGL ES 2.0'
    },
    description: {
      en: 'The basics of implementing an application using the OpenGL ES 2.0 APIs.'
    }
  },
  {
    tags: ['tutorial', 'testing'],
    path: 'tutorials/testing/helloandroid_test.html',
    title: {
      en: 'Hello Testing'
    },
    description: {
      en: 'A basic introduction to the Android testing framework.'
    }
  },
  {
    tags: ['tutorial', 'testing'],
    path: 'tutorials/testing/activity_test.html',
    title: {
      en: 'Activity Testing'
    },
    description: {
      en: 'A more advanced demonstration of the Android testing framework and tools.'
    }
  }
];
