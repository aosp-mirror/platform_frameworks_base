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
 * @author Igor V. Stolyarov
 * @version $Revision$
 */
/*
 * Created on 10.02.2005
 *
 */
package org.apache.harmony.awt.gl.image;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.Permission;

public class URLDecodingImageSource extends DecodingImageSource {

    URL url;

    public URLDecodingImageSource(URL url){
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkConnect(url.getHost(), url.getPort());
            try {
                Permission p = url.openConnection().getPermission();
                security.checkPermission(p);
            } catch (IOException e) {
            }
        }
        this.url = url;
    }

    @Override
    protected boolean checkConnection() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            try {
                security.checkConnect(url.getHost(), url.getPort());
                return true;
            } catch (SecurityException e) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected InputStream getInputStream() {
        try{
            URLConnection uc = url.openConnection();
            // BEGIN android-modified
            return new BufferedInputStream(uc.getInputStream(), 8192);
            // END android-modified
        }catch(IOException e){
            return null;
        }
    }

}
