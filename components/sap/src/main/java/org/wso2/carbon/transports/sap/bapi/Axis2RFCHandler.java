/*
 *  Copyright (c) 2005-2008, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.carbon.transports.sap.bapi;

import com.sap.conn.jco.JCoField;
import com.sap.conn.jco.JCoFieldIterator;
import com.sap.conn.jco.JCoRecord;
import com.sap.conn.jco.JCoRuntimeException;
import com.sap.conn.jco.JCoStructure;
import com.sap.conn.jco.JCoTable;
import com.sap.conn.jco.server.JCoServerFunctionHandler;
import com.sap.conn.jco.server.JCoServerContext;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.AbapException;
import com.sap.conn.jco.AbapClassException;
import org.apache.axis2.AxisFault;
import org.apache.axiom.om.OMElement;
import org.apache.axis2.Constants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.axis2.transport.base.threads.WorkerPool;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.engine.AxisEngine;
import org.apache.axiom.soap.SOAPEnvelope;
import org.wso2.carbon.transports.sap.SAPConstants;
import org.wso2.carbon.transports.sap.bapi.util.RFCConstants;

import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.Objects;

/**
 * This class handles BAPI calls returned from the SAP gateway.
 * <p>
 * This class encapsulates workers for each bapi/rfc request recieved. Workers are responsible for
 * handling  and converting them to SOAP format
 * </p>
 */
public class Axis2RFCHandler implements JCoServerFunctionHandler {

    private static final Log log = LogFactory.getLog(Axis2RFCHandler.class);

    private WorkerPool workerPool;
    private BAPIEndpoint endpoint;

    public Axis2RFCHandler(BAPIEndpoint endpoint, WorkerPool workerPool) {
        this.endpoint = endpoint;
        this.workerPool = workerPool;
    }

    /**
     * handle bapi requests coming through SAP gateway
     *
     * @param jCoServerContext JCO Server environment configuration
     * @param jCoFunction      bAPI/rfc function being called
     * @throws AbapException
     * @throws AbapClassException
     */
    public void handleRequest(JCoServerContext jCoServerContext,
                              JCoFunction jCoFunction) throws AbapException, AbapClassException {

        if (log.isDebugEnabled()) {
            log.debug("New BAPI function call received");
        }
        String xml = jCoFunction.toXML();
        SAPOutTransportInfo bapiOutTransportInfo = new SAPOutTransportInfo();
        bapiOutTransportInfo.setProtocol(SAPConstants.SAP_BAPI_PROTOCOL_NAME);
        workerPool.execute(new BAPIWorker(xml, jCoFunction, bapiOutTransportInfo));
        if (endpoint.isSyncBapiListener()) {
            try {
                synchronized (bapiOutTransportInfo) {
                    int responseTimeout = endpoint.getResponseTimeout();
                    if (log.isDebugEnabled()){
                        log.debug(" Waiting for response for JCoFunction: " + jCoFunction.toXML() + " with response "
                                  + "timeout: " + responseTimeout);
                    }
                    bapiOutTransportInfo.wait(responseTimeout);

                    if (Objects.isNull(jCoFunction.getExportParameterList())) {
                        log.error("Trying to respond to a function that has no Export parameters defined"
                                  + " in the function definition");
                        return;
                    }
                    OMElement payload = bapiOutTransportInfo.getPayload();

                    // check whether the payload is null, this means that the payload has not been set from the
                    // mediation sequence.
                    if (Objects.isNull(payload)) {
                        JCoStructure returnStructure = null;
                        try {
                            returnStructure = jCoFunction.getExportParameterList().getStructure("RETURN");
                        } catch (JCoRuntimeException exp) {
                            if (!(exp.getKey().equals("JCO_ERROR_FIELD_NOT_FOUND"))) {
                                log.warn("Error retrieving RETURN structure", exp);
                                return;
                            }
                        }

                        if (returnStructure != null) {

                            JCoFieldIterator e = returnStructure.getFieldIterator();
                            while (e.hasNextField()) {
                                JCoField field = e.nextField();
                                if ("TYPE".equals(field.getName())) {
                                    field.setValue("E");
                                } else if ("MESSAGE".equals(field.getName())) {
                                    field.setValue("Did not receive a response within " + responseTimeout + "ms");
                                }
                            }
                            jCoFunction.getExportParameterList().setValue("RETURN", returnStructure);
                        } else {
                            if (log.isDebugEnabled()){
                                log.debug("RETURN structure is NULL");
                            }
                        }
                        return;
                    }
                    OMElement exportParameters = payload.getFirstElement();
                    if (log.isDebugEnabled()){
                        log.debug("Setting payload: " + payload + " to the Export "
                                  + "parameters: " + jCoFunction.getExportParameterList());
                    }
                    boolean found = false;
                    if (Objects.isNull(exportParameters)){
                        log.error("Payload with no root element found. A response will not be sent back.");
                    }
                    if (exportParameters.getLocalName().equals("BAPI_EXPORT")) {
                        Iterator childElements = exportParameters.getChildElements();
                        if (Objects.isNull(childElements)) {
                            log.warn("No children elements found under \"BAPI_EXPORT\" element!");
                            return;
                        }
                        while (childElements.hasNext()) {
                            found = true;
                            OMElement childElement = (OMElement) childElements.next();
                            String qName = getQname(childElement);
                            String name = getElementName(childElement);
                            switch (qName) {
                                case RFCConstants.STRUCTURE:
                                    JCoStructure structure = processStructure(childElement, jCoFunction, name);
                                    jCoFunction.getExportParameterList().setValue(name, structure);
                                    break;
                                case RFCConstants.TABLE:
                                    JCoTable table = processTable(childElement, jCoFunction, name);
                                    jCoFunction.getExportParameterList().setValue(name,table);
                                    break;
                                case RFCConstants.FIELD:
                                    String fieldValue = getFieldValue(childElement, name);
                                    jCoFunction.getExportParameterList().setValue(name, fieldValue);
                                    break;
                                default:
                                    log.warn("Unknown meta data type tag :" + qName + " detected. " +
                                             "This meta data element will be discarded!");
                            }
                        }
                        if (!found){
                            log.warn("No children elements found under 'BAPI_EXPORT'. A response will not be sent back.");
                        }
                    } else {
                        log.error("Invalid root element found: '" + exportParameters.getLocalName() + "' "
                                  + "instead of mandatory element: 'BAPI_EXPORT'. A response will not be sent back.'");
                    }
                }
            } catch (Throwable e) {
                log.error("Error while processing request. A response will not be sent back.", e);
            }
        }
    }

    public static String getQname(OMElement childElement) {
        return childElement.getQName().toString();
    }

    private static JCoTable processTable(OMElement element, JCoFunction function, String tableName)
            throws AxisFault {
        JCoTable table = function.getExportParameterList().getTable(tableName);
        if (table == null) {
            log.error("Didn't find the specified table : " + tableName + " on the RFC" +
                      " repository. This table will be ignored");
        }
        processTable(element, table);
        return table;
    }

    private static void processTable(OMElement element, JCoTable jcoTable) throws AxisFault {
        Iterator itr = element.getChildElements();
        boolean found = false;
        while (itr.hasNext()) {
            found = true;
            OMElement childElement = (OMElement) itr.next();
            String qName = getQname(childElement);
            if (qName.equals("row")) {
                String id = childElement.getAttributeValue(RFCConstants.ID_Q);
                processRow(childElement, jcoTable, id);
            } else {
                log.warn("Invalid meta data type element found : " + qName + " .This meta data " +
                         "type will be ignored");
            }
        }
        if (!found){
            log.warn("Table: " + element.getLocalName() + " with no children found. This table will be ignored.");
        }
    }

    private static JCoStructure processStructure(OMElement element, JCoFunction function, String structName)
            throws AxisFault {
        JCoStructure jCoStructure = function.getExportParameterList().getStructure(structName);
        if (jCoStructure != null) {
            processRecord(element, jCoStructure);
        } else {
            log.error("Didn't find the specified structure : " + structName + " on the RFC" +
                      " repository. This structure will be ignored");
        }
        return jCoStructure;
    }

    private static void processStructure(OMElement element, JCoStructure jCoStructure, String structName)
            throws AxisFault {
        if (jCoStructure != null) {
            processRecord(element, jCoStructure);
        } else {
            log.error("Didn't find the specified structure : " + structName + " on the RFC"
                      + " repository. This structure will be ignored");
        }
    }

    public static String getElementName(OMElement element) throws AxisFault {
        String name = element.getAttributeValue(RFCConstants.NAME_Q);
        if (name == null) {
            String message = "Attribute value of qualified name : 'name' for the element: '" + element.getLocalName()
                             + "' is null. A response will not be sent back";
            throw new AxisFault(message);
        }
        return name;
    }


    private static void processRow(OMElement element, JCoTable table, String id) throws AxisFault {
        int rowId;
        try {
            rowId = Integer.parseInt(id);
        } catch (NumberFormatException ex) {
            log.warn("Row ID should be an integer, found " + id + ". Skipping row", ex);
            return;
        }

        if (table.getNumRows() <= rowId) {
            //which mean this is a new row
            table.appendRow();
        } else {
            //handle existing row
            table.setRow(rowId);
        }

        processRecord(element, table);

    }

    private static void processRecord(OMElement element, JCoRecord record) throws AxisFault {
        Iterator itr = element.getChildElements();
        boolean found = false;
        while (itr.hasNext()) {
            found = true;
            OMElement childElement = (OMElement) itr.next();
            String qName = getQname(childElement);
            switch (qName) {
                case RFCConstants.STRUCTURE:
                    String structureName = getElementName(childElement);
                    processStructure(childElement, record.getStructure(structureName), structureName);
                    break;
                case RFCConstants.TABLE:
                    String tableName = getElementName(childElement);
                    processTable(childElement, record.getTable(tableName));
                    break;
                case RFCConstants.FIELD:
                    processField(childElement, record);
                    break;
                default:
                    log.warn("Invalid meta data type element found : " + qName + " .This meta data " +
                             "type will be ignored");
            }
        }
        if (!found){
            log.warn("Record: " + element.getLocalName() + " with no children found. This record will be ignored.");
        }
    }

    public static String getFieldValue(OMElement element, String name) throws AxisFault {
        String fieldValue = element.getText();
        if (fieldValue != null) {
            return fieldValue;
        } else {
            return "";
        }
    }

    private static void processField(OMElement element, JCoRecord record) throws AxisFault {
        String fieldName = element.getAttributeValue(RFCConstants.NAME_Q);
        if (fieldName == null) {
            throw new AxisFault("A field should have a name. A response will not be sent back");
        }
        String fieldValue = getFieldValue(element, fieldName);
        record.setValue(fieldName, fieldValue);
    }

    private class BAPIWorker implements Runnable {

        private JCoFunction function;
        private String xmlContent;
        private SAPOutTransportInfo bapiOutTransportInfo;

        BAPIWorker(String xmlContent, JCoFunction function, SAPOutTransportInfo bapiOutTransportInfo) {
            this.xmlContent = xmlContent;
            this.function = function;
            this.bapiOutTransportInfo = bapiOutTransportInfo;
        }

        BAPIWorker(String xmlContent) {
            this.xmlContent = xmlContent;
        }

        public void run() {
            if (log.isDebugEnabled()) {
                log.debug("Starting a new BAPI worker thread to process the incoming request");
            }
            try (ByteArrayInputStream bais = new ByteArrayInputStream(this.xmlContent.getBytes())){
                MessageContext msgContext = endpoint.createMessageContext();
                msgContext.setIncomingTransportName(SAPConstants.SAP_BAPI_PROTOCOL_NAME);
                if (log.isDebugEnabled()) {
                    log.debug("Creating SOAP envelope from the BAPI function call");
                }
                SOAPEnvelope envelope = TransportUtils.createSOAPMessage(msgContext, bais,
                        SAPConstants.SAP_CONTENT_TYPE);
                msgContext.setEnvelope(envelope);
                msgContext.setProperty(Constants.OUT_TRANSPORT_INFO, bapiOutTransportInfo);
                //pass the constructed BAPI message through Axis engine
                AxisEngine.receive(msgContext);
            } catch (Exception e) {
                log.error("Error while processing the BAPI call through the Axis engine", e);
            }
        }
    }
}
