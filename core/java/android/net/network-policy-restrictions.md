# Data Saver vs Battery Saver

The tables below show whether an app has network access while on background depending on the status of Data Saver mode, Battery Saver mode, and the app's whitelist on those restricted modes.

### How to read the tables

The 2 topmost rows define the Battery Saver mode and whether the app is whitelisted or not for it.
The 2  leftmost columns define the Data Saver mode and whether the app is whitelisted, not whitelisted, or blacklisted for it.
The cells define the network status when the app is on background.

More specifically:

* **DS ON**: Data Saver Mode is on
* **DS OFF**: Data Saver Mode is off
* **BS ON**: Battery Saver Mode is on
* **BS OFF**: Battery Saver Mode is off
* **WL**: app is whitelisted
* **!WL**: app is not whitelisted
* **BL**: app is blacklisted
* **ok**: network access granted while app on background (NetworkInfo's state/detailed state should be `CONNECTED` / `CONNECTED`)
* **blk**: network access blocked while app on background (NetworkInfo's state/detailed state should be `DISCONNECTED` / `BLOCKED`)


## On metered networks

|         |       | BS   | ON    | BS   | OFF   |
|:-------:|-------|------|-------|------|-------|
|         |       | *WL* | *!WL* | *WL* | *!WL* |
| **DS**  |  *WL* |  ok  | blk   |  ok  |  ok   |
| **ON**  | *!WL* | blk  | blk   | blk  | blk   |
|         |  *BL* | blk  | blk   | blk  | blk   |
| **DS**  |  *WL* | blk  | blk   |  ok  |  ok   |
| **OFF** | *!WL* | blk  | blk   |  ok  |  ok   |
|         |  *BL* | blk  | blk   | blk  | blk   |


## On non-metered networks

|         |       | BS   | ON    | BS   | OFF   |
|:-------:|-------|------|-------|------|-------|
|         |       | *WL* | *!WL* | *WL* | *!WL* |
| **DS**  |  *WL* |  ok  | blk   |  ok  |  ok   |
| **ON**  | *!WL* |  ok  | blk   |  ok  |  ok   |
|         |  *BL* |  ok  | blk   |  ok  |  ok   |
| **DS**  |  *WL* |  ok  | blk   |  ok  |  ok   |
| **OFF** | *!WL* |  ok  | blk   |  ok  |  ok   |
|         |  *BL* |  ok  | blk   |  ok  |  ok   |
