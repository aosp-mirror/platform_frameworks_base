README
======

This Network Usage sample app does the following:

-- Downloads an XML feed from StackOverflow.com for the most recent posts tagged "android".

-- Parses the XML feed, combines feed elements with HTML markup, and displays the resulting HTML in the UI.

-- Lets users control their network data usage through a settings UI. Users can choose to fetch the feed
   when any network connection is available, or only when a Wi-Fi connection is available.

-- Detects when there is a change in the device's connection status and responds accordingly. For example, if
   the device loses its network connection, the app will not attempt to download the feed.
