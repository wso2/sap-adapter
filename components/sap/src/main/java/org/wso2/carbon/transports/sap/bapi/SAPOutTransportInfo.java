/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.transports.sap.bapi;

import org.apache.axiom.om.OMElement;
import org.apache.axis2.transport.OutTransportInfo;

/**
 * This class is used to handle the synchronous BAPI listener's response payload.
 */
public class SAPOutTransportInfo implements OutTransportInfo {

    private OMElement payload;
    private String protocol;

    public void setPayload(OMElement payload) {

        this.payload = payload;
    }

    public OMElement getPayload() {

        return this.payload;
    }

    public void setProtocol(String protocol) {

        this.protocol = protocol;
    }

    public String getProtocol() {

        return this.protocol;
    }

    @Override
    public void setContentType(String s) {

    }
}
