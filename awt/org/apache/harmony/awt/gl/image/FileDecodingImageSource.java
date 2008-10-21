/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */
/*
 * Created on 20.01.2005
 */
package org.apache.harmony.awt.gl.image;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

public class FileDecodingImageSource extends DecodingImageSource {
  String filename;

  public FileDecodingImageSource(String file) {
    SecurityManager security = System.getSecurityManager();
    if (security != null) {
        security.checkRead(file);
    }

    filename = file;
  }

  @Override
protected boolean checkConnection() {
      SecurityManager security = System.getSecurityManager();
      if (security != null) {
          try {
            security.checkRead(filename);
          } catch (SecurityException e) {
              return false;
          }
      }

      return true;
  }

  @Override
protected InputStream getInputStream() {
    try {
      // BEGIN android-modified
      return new BufferedInputStream(new FileInputStream(filename), 8192);
      // END android-modified
    } catch (FileNotFoundException e) {
      return null;
    }
  }

}
