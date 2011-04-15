/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.webkit.webdriver;

/**
 * Thrown when a {@link android.webkit.webdriver.WebElement} is not found in the
 * DOM of the page.
 * @hide
 */
public class WebElementNotFoundException extends RuntimeException {

    public WebElementNotFoundException() {
        super();
    }

    public WebElementNotFoundException(String reason) {
        super(reason);
    }

    public WebElementNotFoundException(String reason, Throwable cause) {
        super(reason, cause);
    }

    public WebElementNotFoundException(Throwable cause) {
        super(cause);
    }
}
