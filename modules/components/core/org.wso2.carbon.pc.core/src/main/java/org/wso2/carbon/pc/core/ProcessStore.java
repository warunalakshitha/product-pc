/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.pc.core;

import org.activiti.bpmn.converter.BpmnXMLConverter;
import org.activiti.bpmn.converter.util.InputStreamProvider;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.image.ProcessDiagramGenerator;
import org.activiti.image.impl.DefaultProcessDiagramGenerator;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jaggeryjs.hostobjects.stream.StreamHostObject;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.wso2.carbon.governance.api.util.GovernanceUtils;
import org.wso2.carbon.pc.core.internal.ProcessCenterServerHolder;
import org.wso2.carbon.pc.core.util.PCInputStreamProvider;
import org.wso2.carbon.pc.core.audit.util.RegPermissionUtil;
import org.wso2.carbon.registry.core.Association;
import org.wso2.carbon.registry.core.LogEntry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.Tag;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.exceptions.ResourceNotFoundException;
import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.registry.resource.services.utils.AddRolePermissionUtil;
import org.wso2.carbon.user.core.UserRealm;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import sun.misc.BASE64Decoder;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ProcessStore {

    private static final Log log = LogFactory.getLog(ProcessStore.class);
    private static final String mns = "http://www.wso2.org/governance/metadata";
    private List<String> exportedProcessList;

    private Element append(Document doc, Element parent, String childName, String childNS) {
        Element childElement = doc.createElementNS(childNS, childName);
        parent.appendChild(childElement);
        return childElement;
    }

    public Element appendText(Document doc, Element parent, String childName, String childNS, String text) {
        Element childElement = doc.createElementNS(childNS, childName);
        childElement.setTextContent(text);
        parent.appendChild(childElement);
        return childElement;
    }

    public String xmlToString(Document doc) throws TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.getBuffer().toString().replaceAll("\n|\r", "");
    }

    public Document stringToXML(String xmlString) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder;
        builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xmlString)));
    }

    public String createProcess(String processDetails, String userName, String processCreatedTime)
            throws ProcessCenterException {

        String processId = "FAILED TO ADD PROCESS";
        try {
            JSONObject processInfo = new JSONObject(processDetails);
            String processName = processInfo.getString("processName");
            String processVersion = processInfo.getString("processVersion");
            String processOwner = processInfo.getString("processOwner");
            String processDescription = processInfo.getString("processDescription");
            String processTags = processInfo.getString("processTags");
            JSONArray subprocess = processInfo.getJSONArray("subprocess");
            JSONArray successor = processInfo.getJSONArray("successor");
            JSONArray predecessor = processInfo.getJSONArray("predecessor");
            JSONObject imageObj = processInfo.getJSONObject("image");

            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(userName);
                RegPermissionUtil
                        .setPutPermission(registryService, userName, ProcessCenterConstants.AUDIT.PROCESS_PATH);

                DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

                // root elements
                String mns = "http://www.wso2.org/governance/metadata";
                Document doc = docBuilder.newDocument();

                Element rootElement = doc.createElementNS(mns, "metadata");
                doc.appendChild(rootElement);

                Element overviewElement = append(doc, rootElement, "overview", mns);
                appendText(doc, overviewElement, "name", mns, processName);
                appendText(doc, overviewElement, "version", mns, processVersion);
                appendText(doc, overviewElement, "owner", mns, processOwner);
                appendText(doc, overviewElement, "createdtime", mns, processCreatedTime);

                if ((processDescription != null) && (!processDescription.isEmpty())) {
                    appendText(doc, overviewElement, "description", mns, processDescription);
                } else {
                    appendText(doc, overviewElement, "description", mns, "NA");
                }

                Element propertiesElement = append(doc, rootElement, "properties", mns);
                appendText(doc, propertiesElement, "processtextpath", mns, "NA");

                // fill bpmn properties with NA values
                appendText(doc, propertiesElement, "bpmnpath", mns, "NA");
                appendText(doc, propertiesElement, "bpmnid", mns, "NA");

                if (subprocess.length() != 0) {
                    for (int i = 0; i < subprocess.length(); i++) {
                        Element successorElement = append(doc, rootElement, "subprocess", mns);
                        appendText(doc, successorElement, "name", mns, subprocess.getJSONObject(i).getString("name"));
                        appendText(doc, successorElement, "path", mns, subprocess.getJSONObject(i).getString("path"));
                        appendText(doc, successorElement, "id", mns, subprocess.getJSONObject(i).getString("id"));
                    }
                }

                if (successor.length() != 0) {
                    for (int i = 0; i < successor.length(); i++) {
                        Element successorElement = append(doc, rootElement, "successor", mns);
                        appendText(doc, successorElement, "name", mns, successor.getJSONObject(i).getString("name"));
                        appendText(doc, successorElement, "path", mns, successor.getJSONObject(i).getString("path"));
                        appendText(doc, successorElement, "id", mns, successor.getJSONObject(i).getString("id"));
                    }
                }

                if (predecessor.length() != 0) {
                    for (int i = 0; i < predecessor.length(); i++) {
                        Element predecessorElement = append(doc, rootElement, "predecessor", mns);
                        appendText(doc, predecessorElement, "name", mns,
                                predecessor.getJSONObject(i).getString("name"));
                        appendText(doc, predecessorElement, "path", mns,
                                predecessor.getJSONObject(i).getString("path"));
                        appendText(doc, predecessorElement, "id", mns, predecessor.getJSONObject(i).getString("id"));
                    }
                }

                Element flowchartElement = append(doc, rootElement, "flowchart", mns);
                appendText(doc, flowchartElement, "path", mns, "NA");

                if (imageObj.length() != 0) {
                    Element imageElement = append(doc, rootElement, "images", mns);
                    appendText(doc, imageElement, "thumbnail", mns, imageObj.getString("imgValue"));
                }

                String processAssetContent = xmlToString(doc);
                Resource processAsset = reg.newResource();
                processAsset.setContent(processAssetContent);
                processAsset.setMediaType("application/vnd.wso2-process+xml");
                String processAssetPath = "processes/" + processName + "/" + processVersion;
                reg.put(processAssetPath, processAsset);

                // associate lifecycle with the process asset, so that it can be promoted to published state
                GovernanceUtils.associateAspect(processAssetPath, "SampleLifeCycle2", reg);

                // apply tags to the resource
                String[] tags = processTags.split(",");
                for (String tag : tags) {
                    tag = tag.trim();
                    reg.applyTag(processAssetPath, tag);
                }
                Resource storedProcess = reg.get(processAssetPath);
                processId = storedProcess.getUUID();

                if (imageObj.length() != 0) {
                    String imageRegPath = ProcessCenterConstants.IMAGE_PATH + processId + "/" +
                            imageObj.getString("imgValue");
                    Resource imageContentResource = reg.newResource();
                    BASE64Decoder decoder = new BASE64Decoder();
                    byte[] imageContent = decoder.decodeBuffer(imageObj.getString("binaryImg"));
                    imageContentResource.setContent(imageContent);
                    reg.put(imageRegPath, imageContentResource);
                }

                setPermission(userName, processName, processVersion);

            }
        } catch (Exception e) {
            String errMsg = "Create process error:" + processDetails;
            log.error("Create process error:" + processDetails, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return processId;
    }

    public String updateProcess(String processDetails, String userName) throws ProcessCenterException {
        String processId = "FAILED TO UPDATE PROCESS";

        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            JSONObject processInfo = new JSONObject(processDetails);
            String processName = processInfo.getString("processName");
            String processVersion = processInfo.getString("processVersion");
            String processOwner = processInfo.getString("processOwner");
            String processDescription = processInfo.getString("processDescription");
            String processTags = processInfo.getString("processTags");
            JSONObject imageObj = processInfo.getJSONObject("image");

            String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                    processVersion;

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(userName);
                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);
                NodeList overviewElemetNode = ((Element) doc.getFirstChild()).getElementsByTagName("overview");

                if (doc.getElementsByTagName("description").getLength() != 0)
                    doc.getElementsByTagName("description").item(0).setTextContent(processDescription);

                doc.getElementsByTagName("owner").item(0).setTextContent(processOwner);
                doc.getElementsByTagName("owner").item(0).setTextContent(processOwner);

                List<String> tags = Arrays.asList(processTags.split(","));
                Tag[] curTags = reg.getTags(processAssetPath);

                for (Tag tag : curTags) {
                    reg.removeTag(processAssetPath, tag.getTagName());
                }

                for (String tag : tags) {
                    tag = tag.trim();
                    reg.applyTag(processAssetPath, tag);
                }

                String newProcessContent = xmlToString(doc);
                resource.setContent(newProcessContent);
                reg.put(processAssetPath, resource);

                //TODO check whether same uuid is generated for the updated resource
                Resource storedProcess = reg.get(processAssetPath);
                processId = storedProcess.getUUID();

                if (imageObj.length() != 0) {
                    String imageRegPath = ProcessCenterConstants.IMAGE_PATH + processId + "/" +
                            imageObj.getString("imgValue");
                    Resource imageContentResource = reg.newResource();
                    BASE64Decoder decoder = new BASE64Decoder();
                    byte[] imageContent = decoder.decodeBuffer(imageObj.getString("binaryImg"));
                    imageContentResource.setContent(imageContent);
                    reg.put(imageRegPath, imageContentResource);
                }

            }

        } catch (Exception e) {
            String errMsg = "Update process error:" + processDetails;
            log.error("Update process error:" + processDetails, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return processId;
    }

    public boolean saveProcessText(String processName, String processVersion, String processText, String user)
            throws ProcessCenterException {
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);
                RegPermissionUtil
                        .setPutPermission(registryService, user, ProcessCenterConstants.AUDIT.PROCESS_TEXT_PATH);

                // get process asset content
                String processPath = "processes/" + processName + "/" + processVersion;
                Resource processAsset = reg.get(processPath);
                byte[] processContentBytes = (byte[]) processAsset.getContent();
                String processContent = new String(processContentBytes);
                Document doc = stringToXML(processContent);

                // store process text as a separate resource
                String processTextResourcePath = "processText/" + processName + "/" + processVersion;
                reg.addAssociation(processTextResourcePath, processPath, ProcessCenterConstants.ASSOCIATION_TYPE);

                if (processText != null && processText.length() > 0) {
                    Resource processTextResource = reg.newResource();
                    processTextResource.setContent(processText);
                    processTextResource.setMediaType("text/html");
                    reg.put(processTextResourcePath, processTextResource);
                    doc.getElementsByTagName("processtextpath").item(0).setTextContent(processTextResourcePath);
                } else {
                    reg.delete(processTextResourcePath);
                    doc.getElementsByTagName("processtextpath").item(0).setTextContent("NA");
                }

                // update process asset
                String newProcessContent = xmlToString(doc);
                processAsset.setContent(newProcessContent);
                reg.put(processPath, processAsset);
            }
        } catch (Exception e) {
            String errMsg = "Save process text error for " + processName + " - " + processVersion + " : " + processText;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return true;
    }

    public void processBPMN(String version, String type, StreamHostObject s) {

        InputStream barStream = s.getStream();

        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();

                DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

                if (type.equals("bpmn_file")) {

                } else {

                    // add each bpmn file as a new asset
                    byte[] barContent = IOUtils.toByteArray(barStream);
                    ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(barContent));
                    ZipEntry entry = null;
                    while ((entry = zip.getNextEntry()) != null) {
                        String entryName = entry.getName();
                        if (entryName.endsWith(".bpmn") || entryName.endsWith(".bpmn20.xml")) {

                            // add bpmn xml as a separate resource
                            StringBuilder sb = new StringBuilder();
                            byte[] buffer = new byte[1024];
                            int read = 0;
                            while ((read = zip.read(buffer, 0, 1024)) >= 0) {
                                sb.append(new String(buffer, 0, read));
                            }
                            String bpmnContent = sb.toString();
                            Resource bpmnResource = reg.newResource();
                            bpmnResource.setContent(bpmnContent);
                            bpmnResource.setMediaType("text/xml");
                            String bpmnResourcePath = "bpmn_xml/" + entryName + "/" + version;
                            reg.put(bpmnResourcePath, bpmnResource);

                            // add bpmn asset
                            String displayName = entryName;
                            if (displayName.endsWith(".bpmn")) {
                                displayName = displayName.substring(0, (entryName.length() - 5));
                            }
                            String bpmnAssetPath = "bpmn/" + entryName + "/" + version;
                            Document bpmnDoc = docBuilder.newDocument();
                            Element bpmnRoot = bpmnDoc.createElementNS(mns, "metadata");
                            bpmnDoc.appendChild(bpmnRoot);
                            Element bpmnOverview = append(bpmnDoc, bpmnRoot, "overview", mns);
                            appendText(bpmnDoc, bpmnOverview, "name", mns, displayName);
                            appendText(bpmnDoc, bpmnOverview, "version", mns, version);
                            appendText(bpmnDoc, bpmnOverview, "processpath", mns, "");
                            appendText(bpmnDoc, bpmnOverview, "description", mns, "");
                            Element bpmnAssetContentElement = append(bpmnDoc, bpmnRoot, "content", mns);
                            appendText(bpmnDoc, bpmnAssetContentElement, "contentpath", mns, bpmnResourcePath);
                            String bpmnAssetContent = xmlToString(bpmnDoc);

                            Resource bpmnAsset = reg.newResource();
                            bpmnAsset.setContent(bpmnAssetContent);
                            bpmnAsset.setMediaType("application/vnd.wso2-bpmn+xml");
                            reg.put(bpmnAssetPath, bpmnAsset);
                            Resource storedBPMNAsset = reg.get(bpmnAssetPath);
                            String bpmnAssetID = storedBPMNAsset.getUUID();
                        }
                    }
                }

            } else {
                String msg = "Registry service is not available.";
                throw new ProcessCenterException(msg);
            }

        } catch (Exception e) {
            log.error("Failed to store bar archive: " + version, e);
        }
    }

    public String createBPMN(String bpmnName, String bpmnVersion, Object o, String user) throws ProcessCenterException {
        String processId = "FAILED TO CREATE BPMN";
        if (log.isDebugEnabled()) {
            log.debug("Creating BPMN resource:" + bpmnName + " - " + bpmnVersion);
        }
        try {
            StreamHostObject s = (StreamHostObject) o;
            InputStream bpmnStream = s.getStream();
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);
                RegPermissionUtil.setPutPermission(registryService, user, ProcessCenterConstants.AUDIT.PROCESS_BPMN);

                // store bpmn content as a registry resource
                Resource bpmnContentResource = reg.newResource();
                byte[] bpmnContent = IOUtils.toByteArray(bpmnStream);
                String bpmnText = new String(bpmnContent);
                bpmnContentResource.setContent(bpmnText);
                bpmnContentResource.setMediaType("application/xml");
                String bpmnContentPath = "bpmncontent/" + bpmnName + "/" + bpmnVersion;
                reg.put(bpmnContentPath, bpmnContentResource);

                DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

                // add bpmn asset pointing to above bpmn content
                String mns = "http://www.wso2.org/governance/metadata";
                Document doc = docBuilder.newDocument();

                Element rootElement = doc.createElementNS(mns, "metadata");
                doc.appendChild(rootElement);

                Element overviewElement = append(doc, rootElement, "overview", mns);
                appendText(doc, overviewElement, "name", mns, bpmnName);
                appendText(doc, overviewElement, "version", mns, bpmnVersion);
                appendText(doc, overviewElement, "description", mns, "");
                String processPath = "processes/" + bpmnName + "/" + bpmnVersion;
                appendText(doc, overviewElement, "processpath", mns, processPath);

                Element contentElement = append(doc, rootElement, "content", mns);
                appendText(doc, contentElement, "contentpath", mns, bpmnContentPath);

                String bpmnAssetContent = xmlToString(doc);
                Resource bpmnAsset = reg.newResource();
                bpmnAsset.setContent(bpmnAssetContent);
                bpmnAsset.setMediaType("application/vnd.wso2-bpmn+xml");

                String bpmnAssetPath = "bpmn/" + bpmnName + "/" + bpmnVersion;
                reg.put(bpmnAssetPath, bpmnAsset);
                Resource storedBPMNAsset = reg.get(bpmnAssetPath);
                String bpmnAssetID = storedBPMNAsset.getUUID();

                // update process by linking the bpmn asset

                Resource processAsset = reg.get(processPath);
                byte[] processContentBytes = (byte[]) processAsset.getContent();
                String processContent = new String(processContentBytes);
                Document pdoc = stringToXML(processContent);
                pdoc.getElementsByTagName("bpmnpath").item(0).setTextContent(bpmnAssetPath);
                pdoc.getElementsByTagName("bpmnid").item(0).setTextContent(bpmnAssetID);
                String newProcessContent = xmlToString(pdoc);
                processAsset.setContent(newProcessContent);
                reg.put(processPath, processAsset);

                Resource storedProcessAsset = reg.get(processPath);
                processId = storedProcessAsset.getUUID();
            }
        } catch (Exception e) {
            String errMsg = "Create BPMN error:" + bpmnName + " - " + bpmnVersion;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return processId;
    }

    public String removeBPMNDiagram(String processName, String processVersion, String user)
            throws ProcessCenterException {
        String processId = "";
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);
                String bpmnContentPath = ProcessCenterConstants.BPMN_CONTENT_PATH + processName + "/" + processVersion;

                if (reg.resourceExists(bpmnContentPath)) {
                    reg.delete(bpmnContentPath);
                }
                String bpmnAssetPath = ProcessCenterConstants.BPMN_PATH + processName + "/" + processVersion;
                if (reg.resourceExists(bpmnAssetPath)) {
                    reg.delete(bpmnAssetPath);
                }
                String processPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" + processVersion;
                if (reg.resourceExists(processPath)) {
                    Resource processResource = reg.get(processPath);

                    String processContent = new String((byte[]) processResource.getContent());
                    Document processXML = stringToXML(processContent);
                    processXML.getElementsByTagName("bpmnpath").item(0).setTextContent("NA");
                    processXML.getElementsByTagName("bpmnid").item(0).setTextContent("NA");

                    String newProcessContent = xmlToString(processXML);
                    processResource.setContent(newProcessContent);
                    reg.put(processPath, processResource);

                    Resource storedProcessAsset = reg.get(processPath);
                    processId = storedProcessAsset.getUUID();
                }
            }
        } catch (Exception e) {
            String errMsg = "Error has been occurred while removing BPMN diagram in the process:" + processName + "-"
                    + processVersion;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return processId;
    }

    public void processBAR(String barName, String barVersion, String description, StreamHostObject s) {

        log.debug("Processing BPMN archive " + barName);
        InputStream barStream = s.getStream();
        if (description == null) {
            description = "";
        }

        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();

                // add the bar asset
                //                String barAssetContent = "<metadata xmlns='http://www.wso2
                // .org/governance/metadata'>" +
                //                        "<overview><name>" + barName + "</name><version>" + barVersion + "</version>
                // <description>This is a test process2</description></overview></metadata>";

                DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

                // root elements
                String mns = "http://www.wso2.org/governance/metadata";
                Document doc = docBuilder.newDocument();

                Element rootElement = doc.createElementNS(mns, "metadata");
                doc.appendChild(rootElement);

                Element overviewElement = append(doc, rootElement, "overview", mns);
                appendText(doc, overviewElement, "name", mns, barName);
                appendText(doc, overviewElement, "version", mns, barVersion);
                appendText(doc, overviewElement, "description", mns, description);

                // add binary data of the bar asset as a separate resource
                Resource barResource = reg.newResource();
                byte[] barContent = IOUtils.toByteArray(barStream);
                barResource.setContent(barContent);
                barResource.setMediaType("application/bar");
                String barResourcePath = "bpmn_archives_binary/" + barName + "/" + barVersion;
                reg.put(barResourcePath, barResource);

                Element contentElement = append(doc, rootElement, "content", mns);
                appendText(doc, contentElement, "contentpath", mns, barResourcePath);

                //                reg.addAssociation(barAssetPath, barResourcePath, "has_content");

                // add each bpmn file as a new asset
                ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(barContent));
                ZipEntry entry = null;
                while ((entry = zip.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    if (entryName.endsWith(".bpmn") || entryName.endsWith(".bpmn20.xml")) {

                        // add bpmn xml as a separate resource
                        StringBuilder sb = new StringBuilder();
                        byte[] buffer = new byte[1024];
                        int read = 0;
                        while ((read = zip.read(buffer, 0, 1024)) >= 0) {
                            sb.append(new String(buffer, 0, read));
                        }
                        String bpmnContent = sb.toString();
                        Resource bpmnResource = reg.newResource();
                        bpmnResource.setContent(bpmnContent);
                        bpmnResource.setMediaType("text/xml");
                        String bpmnResourcePath = "bpmn_xml/" + barName + "." + entryName + "/" + barVersion;
                        reg.put(bpmnResourcePath, bpmnResource);

                        // add bpmn asset
                        String bpmnAssetName = barName + "." + entryName;
                        String displayName = entryName;
                        if (displayName.endsWith(".bpmn")) {
                            displayName = displayName.substring(0, (entryName.length() - 5));
                        }
                        String bpmnAssetPath = "bpmn/" + barName + "." + entryName + "/" + barVersion;

                        Document bpmnDoc = docBuilder.newDocument();
                        Element bpmnRoot = bpmnDoc.createElementNS(mns, "metadata");
                        bpmnDoc.appendChild(bpmnRoot);
                        Element bpmnOverview = append(bpmnDoc, bpmnRoot, "overview", mns);
                        appendText(bpmnDoc, bpmnOverview, "name", mns, displayName);
                        appendText(bpmnDoc, bpmnOverview, "version", mns, barVersion);
                        appendText(bpmnDoc, bpmnOverview, "package", mns, barName);
                        appendText(bpmnDoc, bpmnOverview, "description", mns, "");
                        Element bpmnAssetContentElement = append(bpmnDoc, bpmnRoot, "content", mns);
                        appendText(bpmnDoc, bpmnAssetContentElement, "contentpath", mns, bpmnResourcePath);
                        String bpmnAssetContent = xmlToString(bpmnDoc);

                        Resource bpmnAsset = reg.newResource();
                        bpmnAsset.setContent(bpmnAssetContent);
                        bpmnAsset.setMediaType("application/vnd.wso2-bpmn+xml");
                        reg.put(bpmnAssetPath, bpmnAsset);
                        Resource storedBPMNAsset = reg.get(bpmnAssetPath);
                        String bpmnAssetID = storedBPMNAsset.getUUID();

                        Element bpmnElement = append(doc, rootElement, "bpmn", mns);
                        appendText(doc, bpmnElement, "Name", mns, bpmnAssetName);
                        appendText(doc, bpmnElement, "Path", mns, bpmnAssetPath);
                        appendText(doc, bpmnElement, "Id", mns, bpmnAssetID);
                    }
                }

                String barAssetContent = xmlToString(doc);
                Resource barAsset = reg.newResource();
                barAsset.setContent(barAssetContent);
                barAsset.setMediaType("application/vnd.wso2-bar+xml");
                String barAssetPath = "bar/admin/" + barName + "/" + barVersion;
                reg.put(barAssetPath, barAsset);

            } else {
                String msg = "Registry service is not available.";
                throw new ProcessCenterException(msg);
            }

        } catch (Exception e) {
            log.error("Failed to store bar archive: " + barName + " - " + barVersion, e);
        }
    }

    public String getBAR(String barPath) {

        String barString = "";

        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                barPath = barPath.substring("/_system/governance/".length());
                Resource barAsset = reg.get(barPath);
                String barContent = new String((byte[]) barAsset.getContent());

                JSONObject bar = new JSONObject();
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder;
                builder = factory.newDocumentBuilder();
                Document document = builder.parse(new InputSource(new StringReader(barContent)));
                Element overviewElement = (Element) document.getElementsByTagName("overview").item(0);
                String barName = overviewElement.getElementsByTagName("name").item(0).getTextContent();
                String barVersion = overviewElement.getElementsByTagName("version").item(0).getTextContent();
                String barDescription = overviewElement.getElementsByTagName("description").item(0).getTextContent();

                bar.put("name", barName);
                bar.put("version", barVersion);
                bar.put("description", barDescription);

                Element contentElement = (Element) document.getElementsByTagName("content").item(0);
                String contentPath = contentElement.getElementsByTagName("contentpath").item(0).getTextContent();
                bar.put("contentpath", contentPath);

                JSONArray jbpmns = new JSONArray();
                bar.put("bpmnModels", jbpmns);

                NodeList bpmnElements = document.getElementsByTagName("bpmn");
                for (int i = 0; i < bpmnElements.getLength(); i++) {
                    Element bpmnElement = (Element) bpmnElements.item(i);
                    String bpmnId = bpmnElement.getElementsByTagName("Id").item(0).getTextContent();
                    String bpmnName = bpmnElement.getElementsByTagName("Name").item(0).getTextContent();
                    String bpmnPath = bpmnElement.getElementsByTagName("Path").item(0).getTextContent();

                    JSONObject jbpmn = new JSONObject();
                    jbpmn.put("bpmnId", bpmnId);
                    jbpmn.put("bpmnName", bpmnName);
                    jbpmn.put("bpmnPath", bpmnPath);
                    jbpmns.put(jbpmn);
                }

                barString = bar.toString();
            }
        } catch (Exception e) {
            log.error("Failed to fetch BPMN archive: " + barPath);
        }

        return barString;
    }

    public String getProcessText(String textPath) throws ProcessCenterException {
        String textContent = "FAILED TO GET TEXT CONTENT";
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                Resource textResource = reg.get(textPath);
                textContent = new String((byte[]) textResource.getContent());
            }
        } catch (Exception e) {
            String errMsg = "Process text retrieving error: " + textPath;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return textContent;
    }

    public String getBPMN(String bpmnPath) throws ProcessCenterException {

        String bpmnString = "";

        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                bpmnPath = bpmnPath.substring("/_system/governance/".length());
                Resource bpmnAsset = reg.get(bpmnPath);
                String bpmnContent = new String((byte[]) bpmnAsset.getContent());
                JSONObject bpmn = new JSONObject();
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(new InputSource(new StringReader(bpmnContent)));
                Element overviewElement = (Element) document.getElementsByTagName("overview").item(0);
                String bpmnName = overviewElement.getElementsByTagName("name").item(0).getTextContent();
                String bpmnVersion = overviewElement.getElementsByTagName("version").item(0).getTextContent();
                String bpmnDescription = overviewElement.getElementsByTagName("description").item(0).getTextContent();
                String processPath = overviewElement.getElementsByTagName("processpath").item(0).getTextContent();

                bpmn.put("name", bpmnName);
                bpmn.put("version", bpmnVersion);
                bpmn.put("description", bpmnDescription);
                bpmn.put("processPath", processPath);

                Element contentElement = (Element) document.getElementsByTagName("content").item(0);
                String contentPath = contentElement.getElementsByTagName("contentpath").item(0).getTextContent();
                bpmn.put("contentpath", contentPath);
                String encodedBPMNImage = getEncodedBPMNImage(contentPath);
                bpmn.put("bpmnImage", encodedBPMNImage);

                bpmnString = bpmn.toString();
            }
        } catch (Exception e) {
            String errMsg = "Failed to fetch BPMN model: " + bpmnPath;
            log.error("Failed to fetch BPMN model: " + bpmnPath);
            throw new ProcessCenterException(errMsg, e);
        }

        return bpmnString;
    }

    public String getEncodedBPMNImage(String path) throws ProcessCenterException {
        byte[] encoded = Base64.encodeBase64(getBPMNImage(path));
        return new String(encoded);
    }

    public String getProcesses() throws ProcessCenterException {

        String processDetails = "{}";

        try {
            JSONArray result = new JSONArray();

            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();

                String[] processPaths = GovernanceUtils
                        .findGovernanceArtifacts("application/vnd.wso2-process+xml", reg);
                for (String processPath : processPaths) {
                    Resource processResource = reg.get(processPath);
                    String processContent = new String((byte[]) processResource.getContent());
                    Document processXML = stringToXML(processContent);
                    String processName = processXML.getElementsByTagName("name").item(0).getTextContent();
                    String processVersion = processXML.getElementsByTagName("version").item(0).getTextContent();
                    String flowchartPath = processXML.getElementsByTagName("flowchart").item(0).getFirstChild()
                            .getTextContent();

                    JSONObject processJSON = new JSONObject();
                    processJSON.put("path", processPath);
                    processJSON.put("processid", processResource.getUUID());
                    processJSON.put("processname", processName);
                    processJSON.put("processversion", processVersion);
                    processJSON.put("flowchartpath", flowchartPath);
                    result.put(processJSON);
                }

                processDetails = result.toString();

            } else {
                String msg = "Registry service not available for retrieving processes.";
                throw new ProcessCenterException(msg);
            }
        } catch (Exception e) {
            String errMsg = "Failed to get all processes";
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return processDetails;
    }

    public byte[] getBPMNImage(String path) throws ProcessCenterException {

        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                Resource bpmnXMLResource = reg.get(path);
                byte[] bpmnContent = (byte[]) bpmnXMLResource.getContent();
                InputStreamProvider inputStreamProvider = new PCInputStreamProvider(bpmnContent);

                BpmnXMLConverter bpmnXMLConverter = new BpmnXMLConverter();
                BpmnModel bpmnModel = bpmnXMLConverter.convertToBpmnModel(inputStreamProvider, false, false);

                ProcessDiagramGenerator generator = new DefaultProcessDiagramGenerator();
                InputStream imageStream = generator.generatePngDiagram(bpmnModel);

                return IOUtils.toByteArray(imageStream);
            } else {
                String msg = "Registry service not available for fetching the BPMN image.";
                throw new ProcessCenterException(msg);
            }
        } catch (Exception e) {
            String msg = "Failed to fetch BPMN model: " + path;
            log.error(msg, e);
            throw new ProcessCenterException(msg, e);
        }
    }

    public byte[] getBPMNImage2(String path) throws ProcessCenterException {

        try {
            byte[] bpmnContent = FileUtils.readFileToByteArray(new File(path));
            InputStreamProvider inputStreamProvider = new PCInputStreamProvider(bpmnContent);

            BpmnXMLConverter bpmnXMLConverter = new BpmnXMLConverter();
            BpmnModel bpmnModel = bpmnXMLConverter.convertToBpmnModel(inputStreamProvider, false, false);

            ProcessDiagramGenerator generator = new DefaultProcessDiagramGenerator();
            InputStream imageStream = generator.generatePngDiagram(bpmnModel);

            return IOUtils.toByteArray(imageStream);
        } catch (Exception e) {
            String msg = "Failed to fetch BPMN model: " + path;
            log.error(msg, e);
            throw new ProcessCenterException(msg, e);
        }
    }

    public void populateAssociations(Association[] associations, JSONArray jsonArray, UserRegistry reg,
            String resourcePath) throws Exception {
        for (Association association : associations) {
            String associationPath = association.getDestinationPath();
            if (associationPath.equals("/" + resourcePath)) {
                continue;
            }
            Resource associatedResource = reg.get(associationPath);
            String processContent = new String((byte[]) associatedResource.getContent());
            Document doc = stringToXML(processContent);
            Element rootElement = doc.getDocumentElement();
            Element overviewElement = (Element) rootElement.getElementsByTagName("overview").item(0);
            JSONObject associatedProcessDetails = new JSONObject();
            associatedProcessDetails.put("name", overviewElement.getElementsByTagName("name").item(0).getTextContent());
            associatedProcessDetails.put("path", associatedResource.getPath());
            associatedProcessDetails.put("id", associatedResource.getId());
            associatedProcessDetails.put("processId", associatedResource.getUUID());
            associatedProcessDetails
                    .put("version", overviewElement.getElementsByTagName("version").item(0).getTextContent());
            associatedProcessDetails.put("LCState", associatedResource.getProperty("registry.lifecycle." +
                    associatedResource.getProperty("registry.LC.name") + ".state"));
            jsonArray.put(associatedProcessDetails);
        }
    }

    public String getSucessorPredecessorSubprocessList(String resourcePath) {
        String resourceString = "";
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                resourcePath = resourcePath.substring(ProcessCenterConstants.GREG_PATH.length());

                JSONObject conObj = new JSONObject();
                JSONArray subprocessArray = new JSONArray();
                Association[] aSubprocesses = reg
                        .getAssociations(resourcePath, ProcessCenterConstants.SUBPROCESS_ASSOCIATION);
                populateAssociations(aSubprocesses, subprocessArray, reg, resourcePath);
                conObj.put("subprocesses", subprocessArray);

                JSONArray successorArray = new JSONArray();
                Association[] aSuccessors = reg
                        .getAssociations(resourcePath, ProcessCenterConstants.SUCCESSOR_ASSOCIATION);
                populateAssociations(aSuccessors, successorArray, reg, resourcePath);
                conObj.put("successors", successorArray);

                JSONArray predecessorArray = new JSONArray();
                Association[] aPredecessors = reg
                        .getAssociations(resourcePath, ProcessCenterConstants.PREDECESSOR_ASSOCIATION);
                populateAssociations(aPredecessors, predecessorArray, reg, resourcePath);
                conObj.put("predecessors", predecessorArray);

                resourceString = conObj.toString();
            }
        } catch (Exception e) {
            log.error("Failed to fetch Successor, Predecessor and Subprocess information of " + resourcePath, e);
        }
        return resourceString;
    }

    public boolean updateOwner(String ownerDetails, String user) throws ProcessCenterException {
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);

                JSONObject processInfo = new JSONObject(ownerDetails);
                String processName = processInfo.getString("processName");
                String processVersion = processInfo.getString("processVersion");
                String processOwner = processInfo.getString("value");

                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                doc.getElementsByTagName("owner").item(0).setTextContent(processOwner);

                String newProcessContent = xmlToString(doc);
                resource.setContent(newProcessContent);
                reg.put(processAssetPath, resource);
                reg.getRegistryContext().getLogWriter()
                        .addLog(ProcessCenterConstants.GREG_PATH + processAssetPath, reg.getUserName(), LogEntry.UPDATE,
                                "OWNER");
            }

        } catch (Exception e) {
            String errMsg = "Failed to update the process owner:" + ownerDetails;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return true;
    }

    public boolean addSubprocess(String subprocessDetails, String user) throws ProcessCenterException {
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);

                JSONObject processInfo = new JSONObject(subprocessDetails);
                String processName = processInfo.getString("processName");
                String processVersion = processInfo.getString("processVersion");
                JSONObject subprocess = processInfo.getJSONObject("subprocess");

                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                if (subprocess != null) {
                    String subprocessPath = subprocess.getString("path");
                    Element rootElement = doc.getDocumentElement();
                    Element subprocessElement = append(doc, rootElement, "subprocess", mns);
                    appendText(doc, subprocessElement, "name", mns, subprocess.getString("name"));
                    appendText(doc, subprocessElement, "path", mns, subprocessPath);
                    appendText(doc, subprocessElement, "id", mns, subprocess.getString("id"));

                    String newProcessContent = xmlToString(doc);
                    resource.setContent(newProcessContent);
                    reg.put(processAssetPath, resource);
                    reg.addAssociation(processAssetPath, subprocessPath, ProcessCenterConstants.SUBPROCESS_ASSOCIATION);
                    reg.addAssociation(subprocessPath, processAssetPath,
                            ProcessCenterConstants.PARENTPROCESS_ASSOCIATION);
                }
            }

        } catch (Exception e) {
            String errMsg = "Failed to add subprocess: " + subprocessDetails;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return true;
    }

    public boolean addSuccessor(String successorDetails, String user) throws ProcessCenterException {
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);

                JSONObject processInfo = new JSONObject(successorDetails);
                String processName = processInfo.getString("processName");
                String processVersion = processInfo.getString("processVersion");
                JSONObject successor = processInfo.getJSONObject("successor");

                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                if (successor != null) {
                    Element rootElement = doc.getDocumentElement();
                    Element successorElement = append(doc, rootElement, "successor", mns);
                    appendText(doc, successorElement, "name", mns, successor.getString("name"));
                    appendText(doc, successorElement, "path", mns, successor.getString("path"));
                    appendText(doc, successorElement, "id", mns, successor.getString("id"));

                    String newProcessContent = xmlToString(doc);
                    resource.setContent(newProcessContent);
                    reg.put(processAssetPath, resource);
                    reg.addAssociation(processAssetPath, successor.getString("path"),
                            ProcessCenterConstants.SUCCESSOR_ASSOCIATION);
                    reg.addAssociation(successor.getString("path"), processAssetPath,
                            ProcessCenterConstants.PREDECESSOR_ASSOCIATION);

                    // add current process as a predecessor to the successor process
                    Resource successorProcess = reg.get(successor.getString("path"));
                }
            }

        } catch (Exception e) {
            String errMsg = "Failed to add successor: " + successorDetails;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return true;
    }

    public boolean addPredecessor(String predecessorDetails, String user) throws ProcessCenterException {
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);

                JSONObject processInfo = new JSONObject(predecessorDetails);
                String processName = processInfo.getString("processName");
                String processVersion = processInfo.getString("processVersion");
                JSONObject predecessor = processInfo.getJSONObject("predecessor");

                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                if (predecessor != null) {
                    Element rootElement = doc.getDocumentElement();
                    Element predecessorElement = append(doc, rootElement, "predecessor", mns);
                    appendText(doc, predecessorElement, "name", mns, predecessor.getString("name"));
                    appendText(doc, predecessorElement, "path", mns, predecessor.getString("path"));
                    appendText(doc, predecessorElement, "id", mns, predecessor.getString("id"));

                    String newProcessContent = xmlToString(doc);
                    resource.setContent(newProcessContent);
                    reg.put(processAssetPath, resource);
                    reg.addAssociation(processAssetPath, predecessor.getString("path"),
                            ProcessCenterConstants.PREDECESSOR_ASSOCIATION);
                    reg.addAssociation(predecessor.getString("path"), processAssetPath,
                            ProcessCenterConstants.SUCCESSOR_ASSOCIATION);
                }
            }

        } catch (Exception e) {
            String errMsg = "Failed to add predecessor: " + predecessorDetails;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return true;
    }

    public boolean deleteSubprocess(String deleteSubprocess, String user) throws ProcessCenterException {
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);

                JSONObject processInfo = new JSONObject(deleteSubprocess);
                String processName = processInfo.getString("processName");
                String processVersion = processInfo.getString("processVersion");
                JSONObject subprocess = processInfo.getJSONObject("deleteSubprocess");

                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                if (subprocess != null) {
                    NodeList subprocessElements = ((Element) doc.getFirstChild()).getElementsByTagName("subprocess");
                    for (int i = 0; i < subprocessElements.getLength(); i++) {
                        Element subprocessElement = (Element) subprocessElements.item(i);
                        String subprocessName = subprocessElement.getElementsByTagName("name").item(0).getTextContent();
                        String subprocessPath = subprocessElement.getElementsByTagName("path").item(0).getTextContent();
                        String subprocessId = subprocessElement.getElementsByTagName("id").item(0).getTextContent();

                        if (subprocessName.equals(subprocess.getString("name")) &&
                                subprocessPath.equals(subprocess.getString("path")) &&
                                subprocessId.equals(subprocess.getString("id"))) {
                            subprocessElement.getParentNode().removeChild(subprocessElement);
                            reg.removeAssociation(processAssetPath, subprocess.getString("path"),
                                    ProcessCenterConstants.SUBPROCESS_ASSOCIATION);
                            reg.removeAssociation(subprocess.getString("path"), processAssetPath,
                                    ProcessCenterConstants.PARENTPROCESS_ASSOCIATION);
                            break;
                        }
                    }
                    String newProcessContent = xmlToString(doc);
                    resource.setContent(newProcessContent);
                    reg.put(processAssetPath, resource);
                }
            }
        } catch (Exception e) {
            String errMsg = "Failed to delete subprocess: " + deleteSubprocess;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return true;
    }

    public boolean deleteSuccessor(String deleteSuccessor, String user) throws ProcessCenterException {
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);

                JSONObject processInfo = new JSONObject(deleteSuccessor);
                String processName = processInfo.getString("processName");
                String processVersion = processInfo.getString("processVersion");
                JSONObject successor = processInfo.getJSONObject("deleteSuccessor");

                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                if (successor != null) {
                    NodeList successorElements = ((Element) doc.getFirstChild()).getElementsByTagName("successor");
                    for (int i = 0; i < successorElements.getLength(); i++) {
                        Element successorElement = (Element) successorElements.item(i);
                        String successorName = successorElement.getElementsByTagName("name").item(0).getTextContent();
                        String successorPath = successorElement.getElementsByTagName("path").item(0).getTextContent();
                        String successorId = successorElement.getElementsByTagName("id").item(0).getTextContent();

                        if (successorName.equals(successor.getString("name")) &&
                                successorPath.equals(successor.getString("path")) &&
                                successorId.equals(successor.getString("id"))) {
                            successorElement.getParentNode().removeChild(successorElement);
                            reg.removeAssociation(processAssetPath, successor.getString("path"),
                                    ProcessCenterConstants.SUCCESSOR_ASSOCIATION);
                            reg.removeAssociation(successor.getString("path"), processAssetPath,
                                    ProcessCenterConstants.PREDECESSOR_ASSOCIATION);
                            break;
                        }
                    }
                    String newProcessContent = xmlToString(doc);
                    resource.setContent(newProcessContent);
                    reg.put(processAssetPath, resource);
                }
            }
        } catch (Exception e) {
            String errMsg = "Failed to delete successor: " + deleteSuccessor;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return true;
    }

    public boolean deletePredecessor(String deletePredecessor, String user) throws ProcessCenterException {
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);

                JSONObject processInfo = new JSONObject(deletePredecessor);
                String processName = processInfo.getString("processName");
                String processVersion = processInfo.getString("processVersion");
                JSONObject predecessor = processInfo.getJSONObject("deletePredecessor");

                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                if (predecessor != null) {
                    NodeList predecessorElements = ((Element) doc.getFirstChild()).getElementsByTagName("predecessor");
                    for (int i = 0; i < predecessorElements.getLength(); i++) {
                        Element predecessorElement = (Element) predecessorElements.item(i);
                        String predecessorName = predecessorElement.getElementsByTagName("name").item(0)
                                .getTextContent();
                        String predecessorPath = predecessorElement.getElementsByTagName("path").item(0)
                                .getTextContent();
                        String predecessorId = predecessorElement.getElementsByTagName("id").item(0).getTextContent();

                        if (predecessorName.equals(predecessor.getString("name")) &&
                                predecessorPath.equals(predecessor.getString("path")) &&
                                predecessorId.equals(predecessor.getString("id"))) {
                            predecessorElement.getParentNode().removeChild(predecessorElement);
                            reg.removeAssociation(processAssetPath, predecessor.getString("path"),
                                    ProcessCenterConstants.PREDECESSOR_ASSOCIATION);
                            reg.removeAssociation(predecessor.getString("path"), processAssetPath,
                                    ProcessCenterConstants.SUCCESSOR_ASSOCIATION);
                            break;
                        }
                    }
                    String newProcessContent = xmlToString(doc);
                    resource.setContent(newProcessContent);
                    reg.put(processAssetPath, resource);
                }
            }
        } catch (Exception e) {
            String errMsg = "Failed to delete predecessor: " + deletePredecessor;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return true;
    }

    /**
     * Check the documents availability for a given process
     *
     * @param resourcePath holds the process path
     * @return true if documents are available
     */
    public boolean isDocumentAvailable(String resourcePath) {
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                resourcePath = resourcePath.substring(ProcessCenterConstants.GREG_PATH.length());
                Resource resourceAsset = reg.get(resourcePath);
                String resourceContent = new String((byte[]) resourceAsset.getContent());

                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(new InputSource(new StringReader(resourceContent)));

                NodeList documentElements = ((Element) document.getFirstChild()).getElementsByTagName("document");
                if (documentElements.getLength() != 0) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("No documents available in the path: " + resourcePath, e);
        }
        return false;
    }

    /**
     * Upload a document
     *
     * @param processName    process name
     * @param processVersion process version
     * @param docName        document name
     * @param docSummary     summary of the document
     * @param docUrl         google document url
     * @param docObject      document stream object
     * @param docExtension   document extension
     * @return process id
     */
    public String uploadDocument(String processName, String processVersion, String docName, String docSummary,
            String docUrl, Object docObject, String docExtension, String user)
            throws ProcessCenterException, JSONException {

        String processId = "FAILED TO UPLOAD DOCUMENT";
        //Getting associated document list of the process and creating json format of it
        String processDocList = this.getUploadedDocumentDetails(
                ProcessCenterConstants.GREG_PATH_PROCESS + processName + "/" + processVersion);
        JSONArray processDocs = new JSONArray(processDocList);

        //checking for a uploaded document with same name in the list, in positive case throws an error and exit
        for (int iteratorValue = 0; iteratorValue < processDocs.length(); iteratorValue++) {
            String path = ((JSONObject) processDocs.get(iteratorValue)).get("path").toString();
            if ((getAssociatedDocFileName(path).equals(docName + "." + docExtension))) {
                throw new ProcessCenterException(
                        "Associated document " + getAssociatedDocFileName(path) + " exits in " + processName
                                + " version" + processVersion);
            }
        }
        try {
            StreamHostObject s = (StreamHostObject) docObject;
            InputStream docStream = s.getStream();
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                //                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);
                RegPermissionUtil
                        .setPutPermission(registryService, user, ProcessCenterConstants.AUDIT.PROCESS_DOC_PATH);

                // store doc content as a registry resource
                Resource docContentResource = reg.newResource();
                byte[] docContent = IOUtils.toByteArray(docStream);
                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                String docContentPath = null;
                if (docContent.length != 0) {
                    docContentResource.setContent(docContent);
                    if (docExtension.equalsIgnoreCase("pdf")) {
                        docContentResource.setMediaType("application/pdf");
                    } else {
                        docContentResource.setMediaType("application/msword");
                    }
                    docContentPath = "doccontent/" + processName + "/" + processVersion + "/" + docName +
                            "." + docExtension;
                    reg.put(docContentPath, docContentResource);
                    reg.addAssociation(docContentPath, processAssetPath, ProcessCenterConstants.ASSOCIATION_TYPE);
                }

                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                Element rootElement = doc.getDocumentElement();
                Element docElement = append(doc, rootElement, "document", mns);
                appendText(doc, docElement, "name", mns, docName);
                appendText(doc, docElement, "summary", mns, docSummary);
                if ((docUrl != null) && (!docUrl.isEmpty())) {
                    appendText(doc, docElement, "url", mns, docUrl);
                } else {
                    appendText(doc, docElement, "url", mns, "NA");
                }
                if (docContentPath != null) {
                    appendText(doc, docElement, "path", mns, docContentPath);
                } else {
                    appendText(doc, docElement, "path", mns, "NA");
                }

                String newProcessContent = xmlToString(doc);
                resource.setContent(newProcessContent);
                reg.put(processAssetPath, resource);

                Resource storedProcessAsset = reg.get(processAssetPath);
                processId = storedProcessAsset.getUUID();
            }
        } catch (Exception e) {
            String errMsg =
                    docName + "." + docExtension + " document upload error for " + processName + ":" + processVersion;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return processId;
    }

    /**
     * Get details of all the documents (including Google docs) added to a certain process
     *
     * @param resourcePath holds the process path
     * @return document information
     */
    public String getUploadedDocumentDetails(String resourcePath) throws ProcessCenterException {
        String documentString = "NA";
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                resourcePath = resourcePath.substring(ProcessCenterConstants.GREG_PATH.length());
                Resource resourceAsset = reg.get(resourcePath);
                String resourceContent = new String((byte[]) resourceAsset.getContent());
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(new InputSource(new StringReader(resourceContent)));

                JSONArray documentArray = new JSONArray();
                NodeList documentElements = ((Element) document.getFirstChild()).getElementsByTagName("document");

                if (documentElements.getLength() != 0) {
                    for (int i = 0; i < documentElements.getLength(); i++) {
                        Element documentElement = (Element) documentElements.item(i);
                        String docName = documentElement.getElementsByTagName("name").item(0).getTextContent();
                        String docSummary = documentElement.getElementsByTagName("summary").item(0).getTextContent();
                        String docUrl = documentElement.getElementsByTagName("url").item(0).getTextContent();
                        String docPath = documentElement.getElementsByTagName("path").item(0).getTextContent();

                        JSONObject processDoc = new JSONObject();
                        processDoc.put("name", docName);
                        processDoc.put("summary", docSummary);
                        processDoc.put("url", docUrl);
                        processDoc.put("path", docPath);
                        documentArray.put(processDoc);
                    }
                }
                documentString = documentArray.toString();
            }
        } catch (Exception e) {
            String errMsg = "Failed to fetch document: " + resourcePath;
            log.error(errMsg);
            throw new ProcessCenterException(errMsg, e);
        }
        return documentString;
    }

    /**
     * Download a document
     *
     * @param resourcePath holds the process path
     * @return document content as  a String
     */
    public String downloadDocument(String resourcePath) throws ProcessCenterException {
        String docString = "FAILED TO GET Document";
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                Resource docAsset = reg.get(resourcePath);
                byte[] docContent = (byte[]) docAsset.getContent();
                docString = new sun.misc.BASE64Encoder().encode(docContent);
                if (log.isDebugEnabled()) {
                    log.debug("Document Path:" + resourcePath);
                }
            }
        } catch (Exception e) {
            String errMsg = "Failed to download document: " + resourcePath;
            log.error(errMsg);
            throw new ProcessCenterException(errMsg, e);
        }
        return docString;
    }

    /**
     * delete a given document
     *
     * @param deleteDocument holds the information of the document that need to be deleted
     * @return true after successful deletion
     */
    public boolean deleteDocument(String deleteDocument, String user) throws ProcessCenterException {
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);

                JSONObject documentInfo = new JSONObject(deleteDocument);
                String processName = documentInfo.getString("processName");
                String processVersion = documentInfo.getString("processVersion");
                JSONObject removeDocument = documentInfo.getJSONObject("removeDocument");

                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                if (removeDocument != null) {
                    NodeList documentElements = ((Element) doc.getFirstChild()).getElementsByTagName("document");
                    for (int i = 0; i < documentElements.getLength(); i++) {
                        Element documentElement = (Element) documentElements.item(i);
                        String documentName = documentElement.getElementsByTagName("name").item(0).getTextContent();
                        String documentSummary = documentElement.getElementsByTagName("summary").item(0)
                                .getTextContent();
                        String documentUrl = documentElement.getElementsByTagName("url").item(0).getTextContent();
                        String documentPath = documentElement.getElementsByTagName("path").item(0).getTextContent();

                        if (documentName.equals(removeDocument.getString("name")) &&
                                documentSummary.equals(removeDocument.getString("summary")) &&
                                documentPath.equals(removeDocument.getString("path")) &&
                                documentUrl.equals(removeDocument.getString("url"))) {
                            documentElement.getParentNode().removeChild(documentElement);
                            break;
                        }
                    }
                    String newProcessContent = xmlToString(doc);
                    resource.setContent(newProcessContent);
                    reg.put(processAssetPath, resource);

                    String docContentResourcePath = removeDocument.getString("path");
                    if (!docContentResourcePath.equals("NA")) {
                        if (reg.resourceExists(docContentResourcePath)) {
                            reg.delete(docContentResourcePath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            String errMsg = "Failed to delete a document: " + deleteDocument;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return true;
    }

    /**
     * Updates document details including name and summary
     *
     * @param documentDetails
     * @param user
     * @throws ProcessCenterException
     */
    public String updateDocumentDetails(String documentDetails, String user) throws ProcessCenterException {

        String processId = "NA";

        RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
        try {
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);
                JSONObject processInfo = new JSONObject(documentDetails);
                String processName = processInfo.getString("processName");
                String processVersion = processInfo.getString("processVersion");
                String documentName = processInfo.getString("value");
                String docIndex = processInfo.getString("name").replace("docName", "");

                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());

                Document doc = stringToXML(processContent);

                NodeList documentElements = ((Element) doc.getFirstChild()).getElementsByTagName("document");
                if (documentElements.getLength() != 0) {
                    Element documentElement = (Element) documentElements.item(Integer.valueOf(docIndex));
                    documentElement.getElementsByTagName("name").item(0).setTextContent(documentName);
                }
                String newProcessContent = xmlToString(doc);
                resource.setContent(newProcessContent);
                reg.put(processAssetPath, resource);

                Resource storedProcess = reg.get(processAssetPath);
                processId = storedProcess.getUUID();
            }
        } catch (Exception e) {
            String msg = "Failed to update document details of " + documentDetails;
            log.error(msg, e);
            throw new ProcessCenterException(msg, e);
        }
        return processId;
    }

    public String getProcessTags() throws ProcessCenterException {

        String textContent = "FAILED TO GET PROCESS TAGS";

        try {
            JSONObject tagsObj = new JSONObject();

            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();

                String[] processPaths = GovernanceUtils
                        .findGovernanceArtifacts("application/vnd.wso2-process+xml", reg);
                for (String processPath : processPaths) {
                    Resource processResource = reg.get(processPath);

                    String processContent = new String((byte[]) processResource.getContent());
                    Document processXML = stringToXML(processContent);
                    String processName = processXML.getElementsByTagName("name").item(0).getTextContent();
                    String processVersion = processXML.getElementsByTagName("version").item(0).getTextContent();
                    NodeList processImages = processXML.getElementsByTagName("images");

                    String processImage = "";
                    if (processImages.getLength() != 0) {
                        Element imageElement = (Element) processImages.item(0);
                        processImage = imageElement.getElementsByTagName("thumbnail").item(0).getTextContent();
                    }

                    JSONObject processJSON = new JSONObject();
                    processJSON.put("path", processPath);
                    processJSON.put("processid", processResource.getUUID());
                    processJSON.put("processname", processName);
                    processJSON.put("processversion", processVersion);
                    processJSON.put("processImage", processImage);

                    Tag[] tags = reg.getTags(processPath);
                    for (Tag tag : tags) {

                        Iterator<String> keys = tagsObj.keys();

                        if (!keys.hasNext() || keys == null) {
                            JSONArray newTagArray = new JSONArray();
                            newTagArray.put(processJSON);
                            tagsObj.put(tag.getTagName(), newTagArray);
                            continue;
                        }

                        while (keys.hasNext()) {
                            String temp = (keys.next());

                            if (temp == tag.getTagName()) {
                                JSONArray processArray = ((JSONArray) tagsObj.get(temp));
                                processArray = processArray.put(processJSON);
                                tagsObj.put(temp, processArray);
                                break;

                            }
                            if (!keys.hasNext()) {
                                JSONArray newTagArray = new JSONArray();
                                newTagArray.put(processJSON);
                                tagsObj.put(tag.getTagName(), newTagArray);
                            }
                        }
                    }
                }

                textContent = tagsObj.toString();

            } else {
                String msg = "Registry service not available for retrieving processes.";
                throw new ProcessCenterException(msg);
            }
        } catch (Exception e) {
            String msg = "Process tags retrieving error";
            log.error(msg, e);
            throw new ProcessCenterException(msg, e);
        }
        return textContent;
    }

    /**
     * Get process related tags as a String in which the seperate tags are delimitted by 3 hashes(###)
     * i.e.  tag1###tag2###tag3
     *
     * @param processName
     * @param processVersion
     * @return
     * @throws RegistryException
     */
    public String getProcessTags(String processName, String processVersion) throws RegistryException {
        StringBuffer tagsSb = new StringBuffer();
        RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
        if (registryService != null) {
            UserRegistry reg = registryService.getGovernanceSystemRegistry();
            String processPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" + processVersion;
            Tag[] tags = reg.getTags(processPath);
            for (Tag tag : tags) {
                tagsSb.append("###" + tag.getTagName());
            }
        }
        return tagsSb.toString();
    }

    /**
     * @param processName
     * @param processVersion
     * @param flowchartJson
     * @return the processId once the flowchart is saved
     */
    public String uploadFlowchart(String processName, String processVersion, String flowchartJson, String user)
            throws ProcessCenterException {

        String processId = "NA";
        if (log.isDebugEnabled())
            log.debug("Creating Flowchart...");
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);
                RegPermissionUtil
                        .setPutPermission(registryService, user, ProcessCenterConstants.AUDIT.PROCESS_FLOW_CHART_PATH);

                Resource flowchartContentResource = reg.newResource();
                flowchartContentResource.setContent(flowchartJson);
                flowchartContentResource.setMediaType("application/json");
                String flowchartContentPath = "flowchart/" + processName + "/" + processVersion;
                reg.put(flowchartContentPath, flowchartContentResource);
                String processPath = "processes/" + processName + "/" + processVersion;

                // update process by linking the flowchart asset
                Resource processAsset = reg.get(processPath);
                byte[] processContentBytes = (byte[]) processAsset.getContent();
                String processContent = new String(processContentBytes);
                Document processXMLContent = stringToXML(processContent);

                //set the flowchart content
                processXMLContent.getElementsByTagName("flowchart").item(0).getFirstChild()
                        .setTextContent(flowchartContentPath);

                String newProcessContent = xmlToString(processXMLContent);
                processAsset.setContent(newProcessContent);
                reg.put(processPath, processAsset);

                Resource storedProcessAsset = reg.get(processPath);
                processId = storedProcessAsset.getUUID();
            }
        } catch (Exception e) {
            String errMsg =
                    "Flow-chart uploading error for " + processName + " - " + processVersion + ":" + flowchartJson;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        if (log.isDebugEnabled())
            log.debug("Successfully uploaded the flowchart for process " + processName + "-" + processVersion);
        return processId;
    }

    /**
     * @param flowchartPath
     * @return the flowchart string of a process
     */
    public String getFlowchart(String flowchartPath) throws ProcessCenterException {
        String flowchartString = "NA";

        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                flowchartPath = flowchartPath.substring("/_system/governance/".length());
                try {
                    Resource flowchartAsset = reg.get(flowchartPath);
                    flowchartString = new String((byte[]) flowchartAsset.getContent());
                } catch (ResourceNotFoundException e) {
                    flowchartString = "NA";
                }
            }
        } catch (Exception e) {
            String values[] = flowchartPath.split("/");
            String errorMessage = "Failed to retrieve the flowchart for process " + values[1] + "-" + values[2];
            log.error(errorMessage, e);
            throw new ProcessCenterException(errorMessage);
        }
        if (log.isDebugEnabled())
            log.debug("Successfully retrieved the flowchart at path " + flowchartPath);
        return flowchartString;
    }

    /**
     * Delete a flowchart from the registry
     *
     * @param name
     * @param version
     */
    public void deleteFlowchart(String name, String version, String user) throws ProcessCenterException {
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            String flowchartContentPath = "flowchart/" + name + "/" + version;
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);
                reg.delete(flowchartContentPath);

                String processPath = "processes/" + name + "/" + version;
                Resource processResource = reg.get(processPath);

                String processContent = new String((byte[]) processResource.getContent());
                Document processXML = stringToXML(processContent);
                processXML.getElementsByTagName("flowchart").item(0).getFirstChild().setTextContent("NA");

                String newProcessContent = xmlToString(processXML);
                processResource.setContent(newProcessContent);
                reg.put(processPath, processResource);
            }
        } catch (Exception e) {
            String errorMessage = "Failed to upload the flowchart for process " + name + "-" + version;
            log.error(errorMessage, e);
            throw new ProcessCenterException(errorMessage);
        }
    }

    /**
     * Delete the resource collection (directory) created in the governance registry for the process to keep its
     * documents
     *
     * @param processName
     * @param processVersion
     * @throws ProcessCenterException
     */
    public void deleteDocumentResourceCollection(String processName, String processVersion)
            throws ProcessCenterException, RegistryException {

        RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
        String documentsResourceCollectionPath =
                ProcessCenterConstants.DOC_CONTENT_PATH + processName + "/" + processVersion;
        if (registryService != null) {
            UserRegistry reg = registryService.getGovernanceSystemRegistry();
            reg.delete(documentsResourceCollectionPath);
        }
    }

    /**
     * Delete process related text
     *
     * @param processName
     * @param processVersion
     * @return
     * @throws ProcessCenterException
     */
    public String deleteProcessText(String processName, String processVersion) throws ProcessCenterException {
        String processId = "";
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                String processTextPath = ProcessCenterConstants.PROCESS_TEXT_PATH + processName + "/" + processVersion;
                if (reg.resourceExists(processTextPath)) {
                    //delete the processText resource
                    reg.delete(processTextPath);
                }

                //delete the processText resource's path defined in process.rxt
                String processPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" + processVersion;
                if (reg.resourceExists(processPath)) {
                    Resource processResource = reg.get(processPath);

                    String processContent = new String((byte[]) processResource.getContent());
                    Document processXML = stringToXML(processContent);
                    processXML.getElementsByTagName("processtextpath").item(0).setTextContent("NA");

                    String newProcessContent = xmlToString(processXML);
                    processResource.setContent(newProcessContent);
                    reg.put(processPath, processResource);

                    Resource storedProcessAsset = reg.get(processPath);
                    processId = storedProcessAsset.getUUID();
                }
            }
        } catch (Exception e) {
            String errMsg = "Error has been occurred while removing BPMN diagram in the process:" + processName + "-"
                    + processVersion;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return processId;
    }

    public String updateDescription(String descriptionDetails, String user) throws ProcessCenterException {
        String processId = "NA";
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);

                JSONObject processInfo = new JSONObject(descriptionDetails);
                String processName = processInfo.getString("processName");
                String processVersion = processInfo.getString("processVersion");
                String processDescription = processInfo.getString("value");

                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                String processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                if (doc.getElementsByTagName("description").getLength() != 0)
                    doc.getElementsByTagName("description").item(0).setTextContent(processDescription);

                String newProcessContent = xmlToString(doc);
                resource.setContent(newProcessContent);
                reg.put(processAssetPath, resource);
                reg.getRegistryContext().getLogWriter()
                        .addLog(ProcessCenterConstants.GREG_PATH + processAssetPath, reg.getUserName(), LogEntry.UPDATE,
                                "DESCRIPTION");
                Resource storedProcess = reg.get(processAssetPath);
                processId = storedProcess.getUUID();
            }

        } catch (Exception e) {
            String errMsg = "Failed to update the process description:" + descriptionDetails;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return processId;
    }

    /**
     * Delete the process related artifacts : Documents, BPMN model, Flow chart , Process text
     *
     * @param processName
     * @param processVersion
     */
    public void deleteProcessRelatedArtifacts(String processName, String processVersion, String user)
            throws ProcessCenterException {

        String processResourcePath =
                ProcessCenterConstants.GREG_PATH + ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/"
                        + processVersion;
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                //delete Process Documents
                String uploadedDocs = getUploadedDocumentDetails(processResourcePath);
                JSONArray uploadedDocJSNArray = new JSONArray(uploadedDocs);

                for (int i = 0; i < uploadedDocJSNArray.length(); i++) {
                    JSONObject deleteDocument = new JSONObject();
                    deleteDocument.put("processName", processName);
                    deleteDocument.put("processVersion", processVersion);
                    deleteDocument.put("removeDocument", uploadedDocJSNArray.get(i));
                    deleteDocument(deleteDocument.toString(), user);
                }
                deleteDocumentResourceCollection(processName, processVersion);

                //delete other associations
                removeBPMNDiagram(processName, processVersion, user);
                deleteFlowchart(processName, processVersion, user);
                deleteProcessText(processName, processVersion);
            }
        } catch (Exception e) {
            String errMsg =
                    "Error in deleting process related aftifacts of the process " + processName + "-" + processVersion;
            throw new ProcessCenterException(errMsg, e);
        }
    }

    /**
     * Adds a new tag for a process asset at the publisher
     *
     * @param processDetails
     * @param user
     * @return true if the process tag update is a success
     * @throws ProcessCenterException
     */
    public boolean addNewTag(String processDetails, String user) throws ProcessCenterException {

        RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
        if (registryService != null) {
            String processName = "FAILED TO APPLY TAG";
            try {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);
                JSONObject processInfo = new JSONObject(processDetails);

                processName = processInfo.getString("processName");
                String processVersion = processInfo.getString("processVersion");
                String tagName = processInfo.getString("tag");
                String assetPath = "processes/" + processName + "/" + processVersion;

                reg.applyTag(assetPath, tagName);
            } catch (Exception e) {
                String msg = "Process update error:" + processName;
                log.error(msg, e);
                throw new ProcessCenterException(msg, e);
            }
        }
        return true;
    }

    /**
     * Removes tag for a process asset at the publisher
     *
     * @param processDetails
     * @param user
     * @return true is the process tag removal is a success
     * @throws ProcessCenterException
     */
    public boolean removeTag(String processDetails, String user) throws ProcessCenterException {

        RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
        if (registryService != null) {
            String processName = "FAILED TO REMOVE TAG";
            try {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);
                JSONObject processInfo = new JSONObject(processDetails);

                processName = processInfo.getString("processName");
                String processVersion = processInfo.getString("processVersion");
                String tagName = processInfo.getString("tag");
                String assetPath = "processes/" + processName + "/" + processVersion;

                reg.removeTag(assetPath, tagName);
            } catch (Exception e) {
                String msg = "Process update error:" + processName;
                log.error(msg, e);
                throw new ProcessCenterException(msg, e);
            }
        }
        return true;
    }

    /*
    Utility method for extracting file name from the registry path
     */
    private static String getAssociatedDocFileName(String filepath) {
        return filepath.substring(filepath.lastIndexOf("/") + 1);
    }

    public String setPermission(String userName, String processName, String processVersion)
            throws ProcessCenterException {

        String status = "Failed to set permission";

        try {

            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry userRegistry = registryService.getGovernanceSystemRegistry();
                UserRealm userRealm = userRegistry.getUserRealm();
                String[] roles = userRealm.getUserStoreManager().getRoleListOfUser(userName);

                String path = "/_system/governance/processes/" + processName + "/" +
                        processVersion;

                for (String role : roles) {

                    if (role.equalsIgnoreCase("Internal/everyone") || role.equalsIgnoreCase("Internal/store") ||
                            role.equalsIgnoreCase("Internal/publisher")) {
                        continue;
                    } else {
                        //add read permission
                        AddRolePermissionUtil.addRolePermission(userRegistry, path, role, ProcessCenterConstants.READ,
                                ProcessCenterConstants.ALLOW);
                        //add write permission
                        AddRolePermissionUtil.addRolePermission(userRegistry, path, role, ProcessCenterConstants.WRITE,
                                ProcessCenterConstants.ALLOW);
                        //add authorize permission
                        AddRolePermissionUtil
                                .addRolePermission(userRegistry, path, role, ProcessCenterConstants.AUTHORIZE,
                                        ProcessCenterConstants.ALLOW);
                    }
                }
                status = "Permission set successfully";
            }
        } catch (Exception e) {
            String errMsg = "Failed to update Permission";
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
        return status;
    }

    public String initiateExportProcess(String processName, String processVersion, String exportWithAssociations,
            String user) throws Exception {
        exportedProcessList = new ArrayList<String>();
        new File(ProcessCenterConstants.PROCESS_EXPORT_DIR).mkdirs();
        Boolean exportWithAssociationsBool = Boolean.valueOf(exportWithAssociations);
        try {
            // save details about the exported zip and the core process
            String exportRootPath = ProcessCenterConstants.PROCESS_EXPORT_DIR + "/" + processName + "-" + processVersion
                    + "-PC-Package/";
            new File(exportRootPath).mkdirs();
            /*FileOutputStream out = new FileOutputStream(exportRootPath + "METADATA.json");
            JSONObject metaData = new JSONObject();
            metaData.put("core-process-name", processName);
            metaData.put("core-process-varsion", processVersion);
            String jsonString = metaData.toString(ProcessCenterConstants.JSON_FILE_INDENT_FACTOR);
            out.write(jsonString.getBytes());
            out.close();*/

            exportProcess(exportRootPath, processName, processVersion, exportWithAssociationsBool, user);

            //zip the folder
            FolderZiper folderZiper = new FolderZiper();
            String zipFileName = processName + "-" + processVersion + "-PC-Package.zip";
            folderZiper.zipFolder(exportRootPath, zipFileName);

            //encode zip file
            String encodedZip = encodeFileToBase64Binary(zipFileName);
            return encodedZip;

        } catch (Exception e) {
            String errMsg = "Failed to export process:" + processName + "-" + processVersion;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
    }

    public void exportProcess(String exportRootPath, String processName, String processVersion,
            boolean exportWithAssociations, String user) throws ProcessCenterException {

        if (exportedProcessList.contains(processName + "-" + processVersion)) {
            return;
        }
        exportedProcessList.add(processName + "-" + processVersion);

        try {

            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceUserRegistry(user);

                String exportProcessPath = exportRootPath + processName + "-" + processVersion + "/";
                new File(exportProcessPath + "documents/").mkdirs();

                //save the process rxt registry entry >> xml
                downloadResource(reg, ProcessCenterConstants.PROCESS_ASSET_ROOT,
                        ProcessCenterConstants.EXPORTED_PROCESS_RXT_FILE, processName, processVersion, "xml",
                        exportProcessPath);
                //save bpmn registry entry >> xml
                downloadResource(reg, ProcessCenterConstants.BPMN_PATH, ProcessCenterConstants.EXPORTED_BPMN_FILE,
                        processName, processVersion, "xml", exportProcessPath);
                //save bpmncontent registry entry >> xml
                downloadResource(reg, ProcessCenterConstants.BPMN_CONTENT_PATH,
                        ProcessCenterConstants.EXPORTED_BPMN_CONTENT_FILE, processName, processVersion, "xml",
                        exportProcessPath);
                //save flowchart registry entry >> json
                downloadResource(reg, ProcessCenterConstants.AUDIT.PROCESS_FLOW_CHART_PATH,
                        ProcessCenterConstants.EXPORTED_FLOW_CHART_FILE, processName, processVersion, "json",
                        exportProcessPath);
                //save processText registry entry
                downloadResource(reg, ProcessCenterConstants.PROCESS_TEXT_PATH,
                        ProcessCenterConstants.EXPORTED_PROCESS_TEXT_FILE, processName, processVersion, "txt",
                        exportProcessPath);

                //save doccontent registry entries >> doc, docx, pdf
                String processResourcePath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                JSONArray jsonArray = new JSONArray(
                        getUploadedDocumentDetails(ProcessCenterConstants.GREG_PATH + processResourcePath));
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObj = jsonArray.getJSONObject(i);
                    if (jsonObj.getString("url").equals("NA")) {
                        String docResourcePath = jsonObj.getString("path");
                        Resource docResource = reg.get(docResourcePath);
                        String[] tempStrArr = docResourcePath.split("/");
                        String docFileName = exportProcessPath + "documents/" + tempStrArr[tempStrArr.length - 1];
                        FileOutputStream docFileOutPutStream = new FileOutputStream(docFileName);
                        IOUtils.copy(docResource.getContentStream(), docFileOutPutStream);
                        docFileOutPutStream.close();
                    }
                }

                //download process image thumbnail
                downloadProcessThumbnailImage(reg, processName, processVersion, exportProcessPath);

                //write process tags in a file
                FileOutputStream tagsFileOutputStream = new FileOutputStream(
                        exportProcessPath + ProcessCenterConstants.PROCESS_TAGS_FILE);
                tagsFileOutputStream.write(getProcessTags(processName, processVersion).getBytes());
                tagsFileOutputStream.close();

                //export process associations
                if (exportWithAssociations) {
                    //write details on process associations (sub-processes/predecessors/successors) in a json file
                    FileOutputStream out = new FileOutputStream(
                            exportProcessPath + ProcessCenterConstants.PROCESS_ASSOCIATIONS_FILE);
                    JSONObject successorPredecessorSubprocessListJSON = new JSONObject(
                            getSucessorPredecessorSubprocessList(
                                    ProcessCenterConstants.GREG_PATH + processResourcePath));
                    out.write(successorPredecessorSubprocessListJSON
                            .toString(ProcessCenterConstants.JSON_FILE_INDENT_FACTOR).getBytes());
                    out.close();

                    exportAssociatedProcesses(successorPredecessorSubprocessListJSON, exportRootPath,
                            exportWithAssociations, "subprocesses", user);
                    exportAssociatedProcesses(successorPredecessorSubprocessListJSON, exportRootPath,
                            exportWithAssociations, "predecessors", user);
                    exportAssociatedProcesses(successorPredecessorSubprocessListJSON, exportRootPath,
                            exportWithAssociations, "successors", user);
                }
            }
        } catch (Exception e) {
            String errMsg = "Failed to export process:" + processName + "-" + processVersion;
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
    }

    /**
     * Export accociated process (sub process/predecessor/successor) - The related files are saved in the respective
     * directory
     *
     * @param successorPredecessorSubprocessListJSON
     * @param exportRootPath
     * @param exportWithAssociations
     * @param assocationType
     * @param user
     * @throws JSONException
     * @throws ProcessCenterException
     */
    public void exportAssociatedProcesses(JSONObject successorPredecessorSubprocessListJSON, String exportRootPath,
            boolean exportWithAssociations, String assocationType, String user)
            throws JSONException, ProcessCenterException {
        JSONArray associatedProcessesJArray = successorPredecessorSubprocessListJSON.getJSONArray(assocationType);

        for (int i = 0; i < associatedProcessesJArray.length(); i++) {
            String processName = associatedProcessesJArray.getJSONObject(i).getString("name");
            String processVersion = associatedProcessesJArray.getJSONObject(i).getString("version");
            exportProcess(exportRootPath, processName, processVersion, exportWithAssociations, user);
        }
    }

    /**
     * Download non-document process related resources (i.e: flow chart, bpmn, process text)
     *
     * @param reg
     * @param resourceRoot
     * @param savingFileName
     * @param processName
     * @param processVersion
     * @param exportedFileType
     * @param exportProcessPath
     * @throws RegistryException
     * @throws IOException
     * @throws JSONException
     * @throws ParserConfigurationException
     * @throws SAXException
     * @throws TransformerException
     */
    public void downloadResource(UserRegistry reg, String resourceRoot, String savingFileName, String processName,
            String processVersion, String exportedFileType, String exportProcessPath)
            throws RegistryException, IOException, JSONException, ParserConfigurationException, SAXException,
            TransformerException {
        String resourcePath = resourceRoot + processName + "/" + processVersion;

        if (reg.resourceExists(resourcePath)) {
            Resource resource = reg.get(resourcePath);
            FileOutputStream fileOutputStream = new FileOutputStream(exportProcessPath + savingFileName);

            if (exportedFileType.equals("json")) {
                String stringContent = IOUtils
                        .toString(resource.getContentStream(), String.valueOf(StandardCharsets.UTF_8));
                //create JSONObject to pretty-print content
                JSONObject contentInJSON = new JSONObject(stringContent);
                fileOutputStream
                        .write(contentInJSON.toString(ProcessCenterConstants.JSON_FILE_INDENT_FACTOR).getBytes());
                fileOutputStream.close();
            } else if (exportedFileType.equals("xml")) {
                IOUtils.copy(resource.getContentStream(), fileOutputStream);
            } else { //.pdf, doc, docx, txt
                IOUtils.copy(resource.getContentStream(), fileOutputStream);
            }
        }
    }

    public String encodeFileToBase64Binary(String fileName) throws IOException {

        File file = new File(fileName);
        byte[] bytes = loadFile(file);
        byte[] encoded = Base64.encodeBase64(bytes);
        String encodedString = new String(encoded);

        return encodedString;
    }

    public static byte[] loadFile(File file) throws IOException {
        InputStream is = new FileInputStream(file);

        long length = file.length();
        if (length > Integer.MAX_VALUE) {
            // File is too large
        }
        byte[] bytes = new byte[(int) length];

        int offset = 0;
        int numRead = 0;
        while (offset < bytes.length && (numRead = is.read(bytes, offset, bytes.length - offset)) >= 0) {
            offset += numRead;
        }

        if (offset < bytes.length) {
            throw new IOException("Could not completely read file " + file.getName());
        }

        is.close();
        return bytes;
    }

    public void downloadProcessThumbnailImage(UserRegistry reg, String processName, String processVersion,
            String exportProcessPath) throws RegistryException, IOException {
        String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                processVersion;
        Resource storedProcess = reg.get(processAssetPath);
        String processId = storedProcess.getUUID();
        String imageResourcePath =
                ProcessCenterConstants.PROCESS_ASSET_RESOURCE_REG_PATH + processId + "/images_thumbnail";

        if (reg.resourceExists(imageResourcePath)) {
            Resource resource = reg.get(imageResourcePath);
            FileOutputStream fileOutputStream = new FileOutputStream(exportProcessPath + "process_image_thumbnail");
            IOUtils.copy(resource.getContentStream(), fileOutputStream);
            fileOutputStream.close();
        }
    }

    /**
     * Get a list of configured process variables and their types, for analytics
     *
     * @param resourcePath
     * @return JSON Object in string representation, which includes the configured process variables for analytics
     * @throws ProcessCenterException
     */
    public String getProcessVariablesList(String resourcePath) throws ProcessCenterException {
        String resourceString = "";
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                resourcePath = resourcePath.substring(ProcessCenterConstants.GREG_PATH.length());
                Resource resourceAsset = reg.get(resourcePath);
                String resourceContent = new String((byte[]) resourceAsset.getContent());

                JSONObject procVariablesJob = new JSONObject();
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder;
                builder = factory.newDocumentBuilder();
                Document document = builder.parse(new InputSource(new StringReader(resourceContent)));

                JSONArray variableArray = new JSONArray();

                procVariablesJob.put("processVariables", variableArray);

                NodeList processVariableElements = ((Element) document.getFirstChild())
                        .getElementsByTagName("process_variable");

                if (processVariableElements.getLength() != 0) {
                    for (int i = 0; i < processVariableElements.getLength(); i++) {
                        Element processVariableElement = (Element) processVariableElements.item(i);
                        String processVariableName = processVariableElement.getElementsByTagName("name").item(0)
                                .getTextContent();
                        String processVariableType = processVariableElement.getElementsByTagName("type").item(0)
                                .getTextContent();
                        String isAnalyzeData = processVariableElement.getElementsByTagName("isAnalyzeData").item(0)
                                .getTextContent();
                        String isDrillDownVariable = processVariableElement.getElementsByTagName("isDrillDownVariable")
                                .item(0).getTextContent();

                        JSONObject processVariable = new JSONObject();
                        processVariable.put("name", processVariableName);
                        processVariable.put("type", processVariableType);
                        processVariable.put("isAnalyzeData", isAnalyzeData);
                        processVariable.put("isDrillDownVariable", isDrillDownVariable);
                        variableArray.put(processVariable);
                    }
                }
                resourceString = procVariablesJob.toString();
            }
        } catch (Exception e) {
            String errMsg = "Failed to get the process variables list";
            throw new ProcessCenterException(errMsg, e);
        }
        return resourceString;
    }

    /**
     * Save the process variables in process rxt which need to be configured for analytics
     *
     * @param processVariableDetails
     * @return
     */
    public void saveProcessVariables(String processVariableDetails) throws ProcessCenterException {
        String processContent = null;
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();

                JSONObject processInfo = new JSONObject(processVariableDetails);
                String processName = processInfo.getString(ProcessCenterConstants.PROCESS_NAME);
                String processVersion = processInfo.getString(ProcessCenterConstants.PROCESS_VERSION);
                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                JSONObject processVariablesJOb = processInfo.getJSONObject("processVariables");

                Iterator<?> keys = processVariablesJOb.keys();
                //saving pracess variable name,type as sub elements
                while (keys.hasNext()) {
                    String variableName = (String) keys.next();
                    String[] varMetaData = processVariablesJOb.get(variableName).toString().split("##");
                    String variableType = varMetaData[0];
                    String isAnalyzeData = varMetaData[1];
                    String isDrillDownVariable = varMetaData[2];
                    Element rootElement = doc.getDocumentElement();
                    Element variableElement = append(doc, rootElement, "process_variable", ProcessCenterConstants.MNS);
                    appendText(doc, variableElement, "name", ProcessCenterConstants.MNS, variableName);
                    appendText(doc, variableElement, "type", ProcessCenterConstants.MNS, variableType);
                    appendText(doc, variableElement, "isAnalyzeData", ProcessCenterConstants.MNS, isAnalyzeData);
                    appendText(doc, variableElement, "isDrillDownVariable", ProcessCenterConstants.MNS,
                            isDrillDownVariable);

                    String newProcessContent = xmlToString(doc);
                    resource.setContent(newProcessContent);
                    reg.put(processAssetPath, resource);
                }
                log.info("Saved process variables to configure analytics");
                if (log.isDebugEnabled()) {
                    log.debug("Saved process variables to configure analytics.Saved info:" + processVariableDetails);
                }
            }
        } catch (TransformerException | JSONException | RegistryException e) {
            String errMsg =
                    "Failed to save processVariables with info,\n" + processVariableDetails + "\n,to the process.rxt";
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        } catch (Exception e) {
            String errMsg = "Failed to convert " + processContent + " registry resource to XML";
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
    }

    /**
     * Get the configured event stream and receiver information (for analytics with DAS), of the process
     *
     * @param resourcePath path for the process resource, in governance registry
     * @return Information of Event Stream and Reciever, configured for the process
     * @throws ProcessCenterException
     */
    public String getStreamAndReceiverInfo(String resourcePath) throws ProcessCenterException {
        String resourceString = "";
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();
            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();
                resourcePath = resourcePath.substring(ProcessCenterConstants.GREG_PATH.length());
                Resource resourceAsset = reg.get(resourcePath);
                String resourceContent = new String((byte[]) resourceAsset.getContent());

                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(new InputSource(new StringReader(resourceContent)));
                JSONArray variableArray = new JSONArray();
                Element dasConfigInfoElement = (Element) ((Element) document.getFirstChild())
                        .getElementsByTagName("analytics_config_info").item(0);
                String processDefinitionId = dasConfigInfoElement.getElementsByTagName("processDefinitionId").item(0)
                        .getTextContent();
                String eventStreamName = dasConfigInfoElement.getElementsByTagName("eventStreamName").item(0)
                        .getTextContent();
                String eventStreamVersion = dasConfigInfoElement.getElementsByTagName("eventStreamVersion").item(0)
                        .getTextContent();
                String eventStreamDescription = dasConfigInfoElement.getElementsByTagName("eventStreamDescription")
                        .item(0).getTextContent();
                String eventStreamNickName = dasConfigInfoElement.getElementsByTagName("eventStreamNickName").item(0)
                        .getTextContent();
                String eventReceiverName = dasConfigInfoElement.getElementsByTagName("eventReceiverName").item(0)
                        .getTextContent();

                JSONObject dasConfigInfoJOb = new JSONObject();
                dasConfigInfoJOb.put("processDefinitionId", processDefinitionId);
                dasConfigInfoJOb.put("eventStreamName", eventStreamName);
                dasConfigInfoJOb.put("eventStreamVersion", eventStreamVersion);
                dasConfigInfoJOb.put("eventStreamDescription", eventStreamDescription);
                dasConfigInfoJOb.put("eventStreamNickName", eventStreamNickName);
                dasConfigInfoJOb.put("eventReceiverName", eventReceiverName);

                resourceString = dasConfigInfoJOb.toString();
            }
        } catch (Exception e) {
            String errMsg = "Failed to get the event stream and receeiver info";
            throw new ProcessCenterException(errMsg, e);
        }
        return resourceString;
    }

    /**
     * Save event stream and reciever information configured for analytics with DAS, for this process, in governance
     * registry in the process.rxt
     *
     * @param dasConfigData
     * @param processName
     * @param processVersion
     * @throws ProcessCenterException
     */
    public void saveStreamAndReceiverInfo(String dasConfigData, String processName, String processVersion)
            throws ProcessCenterException {
        String processContent = null;
        try {
            RegistryService registryService = ProcessCenterServerHolder.getInstance().getRegistryService();

            if (registryService != null) {
                UserRegistry reg = registryService.getGovernanceSystemRegistry();

                JSONObject dasConfigDataJOb = new JSONObject(dasConfigData);
               /* String processName = processInfo.getString(ProcessCenterConstants.PROCESS_NAME);
                String processVersion = processInfo.getString(ProcessCenterConstants.PROCESS_VERSION);*/

                String processDefinitionId = dasConfigDataJOb.getString(ProcessCenterConstants.PROCESS_DEFINITION_ID);
                String eventStreamName = dasConfigDataJOb.getString(ProcessCenterConstants.EVENT_STREAM_NAME);
                String eventStreamVersion = dasConfigDataJOb.getString(ProcessCenterConstants.EVENT_STREAM_VERSION);
                String eventStreamDescription = dasConfigDataJOb
                        .getString(ProcessCenterConstants.EVENT_STREAM_DESCRIPTION);
                String eventStreamNickName = dasConfigDataJOb.getString(ProcessCenterConstants.EVENT_STREAM_NICK_NAME);
                String eventReceiverName = dasConfigDataJOb.getString(ProcessCenterConstants.EVENT_RECEIVER_NAME);

                String processAssetPath = ProcessCenterConstants.PROCESS_ASSET_ROOT + processName + "/" +
                        processVersion;
                Resource resource = reg.get(processAssetPath);
                processContent = new String((byte[]) resource.getContent());
                Document doc = stringToXML(processContent);

                //  while (keys.hasNext()) {
                // String variableName = (String) keys.next();
                //if (Debugger.isEnabled())
                // log.debug(variableName);
                //String variableType = processVariablesJOb.get(variableName).toString();
                //JSONObject processVariableJOb= (JSONObject) processVariablesArray.get(i);
                Element rootElement = doc.getDocumentElement();
                Element dasConfigInfoElement = append(doc, rootElement, "analytics_config_info",
                        ProcessCenterConstants.MNS);
                appendText(doc, dasConfigInfoElement, "processDefinitionId", ProcessCenterConstants.MNS,
                        processDefinitionId);
                appendText(doc, dasConfigInfoElement, "eventStreamName", ProcessCenterConstants.MNS, eventStreamName);
                appendText(doc, dasConfigInfoElement, "eventStreamVersion", ProcessCenterConstants.MNS,
                        eventStreamVersion);
                appendText(doc, dasConfigInfoElement, "eventStreamDescription", ProcessCenterConstants.MNS,
                        eventStreamDescription);
                appendText(doc, dasConfigInfoElement, "eventStreamNickName", ProcessCenterConstants.MNS,
                        eventStreamNickName);
                appendText(doc, dasConfigInfoElement, "eventReceiverName", ProcessCenterConstants.MNS,
                        eventReceiverName);

                String newProcessContent = xmlToString(doc);
                resource.setContent(newProcessContent);
                reg.put(processAssetPath, resource);
                // }
                log.info("Saved das configuration details in the registry");
                if (log.isDebugEnabled()) {
                    log.debug("The Saved das configuration details:" + dasConfigData);
                }
            }
        } catch (TransformerException | JSONException | RegistryException e) {
            String errMsg =
                    "Failed to save das configuration details with info,\n" + dasConfigData + "\n,to the process.rxt";
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        } catch (Exception e) {
            String errMsg = "Failed to convert " + processContent + " registry resource to XML";
            log.error(errMsg, e);
            throw new ProcessCenterException(errMsg, e);
        }
    }

}