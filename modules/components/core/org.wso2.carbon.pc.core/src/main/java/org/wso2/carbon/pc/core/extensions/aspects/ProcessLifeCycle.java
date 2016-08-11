package org.wso2.carbon.pc.core.extensions.aspects;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.util.XMLUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.scxml.io.SCXMLParser;
import org.apache.commons.scxml.model.*;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.w3c.dom.Element;
import org.wso2.carbon.governance.api.exception.GovernanceException;
import org.wso2.carbon.governance.registry.extensions.aspects.utils.LifecycleCheckpointUtils;
import org.wso2.carbon.governance.registry.extensions.aspects.utils.LifecycleConstants;
import org.wso2.carbon.governance.registry.extensions.aspects.utils.StatCollection;
import org.wso2.carbon.governance.registry.extensions.aspects.utils.StatWriter;
import org.wso2.carbon.governance.registry.extensions.beans.*;
import org.wso2.carbon.governance.registry.extensions.executors.utils.ExecutorConstants;
import org.wso2.carbon.governance.registry.extensions.interfaces.CustomValidations;
import org.wso2.carbon.governance.registry.extensions.interfaces.Execution;
import org.wso2.carbon.mashup.javascript.hostobjects.registry.CollectionHostObject;
import org.wso2.carbon.mashup.javascript.hostobjects.registry.RegistryHostObject;
import org.wso2.carbon.mashup.javascript.hostobjects.registry.ResourceHostObject;
import org.wso2.carbon.mashup.utils.MashupConstants;
import org.wso2.carbon.registry.core.Aspect;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.config.RegistryContext;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.jdbc.handlers.RequestContext;
import org.wso2.carbon.registry.core.session.CurrentSession;
import org.wso2.carbon.registry.core.utils.RegistryUtils;
import org.wso2.carbon.user.core.UserStoreException;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import java.io.CharArrayReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static org.wso2.carbon.governance.registry.extensions.aspects.utils.Utils.*;

public class ProcessLifeCycle extends Aspect {
    private static final Log log = LogFactory.getLog(ProcessLifeCycle.class);

    private String lifecycleProperty = "registry.LC.name";
    private String stateProperty = "registry.lifecycle.SoftwareProjectLifecycle.state";
    private String ASSOCIATION = "association";

    private List<String> states;
    private Map<String, List<CheckItemBean>> checkListItems;
    private Map<String, List<CustomCodeBean>> transitionValidations;
    private Map<String, List<CustomCodeBean>> transitionExecution;
    private Map<String, List<PermissionsBean>> transitionPermission;
    private Map<String, List<String>> stateEvents;
    private Map<String, List<ScriptBean>> scriptElements;
    private Map<String, Map<String, String>> transitionUIs;
    private Map<String, List<InputBean>> transitionInputs;
    private Map<String, List<ApprovalBean>> transitionApproval;

    private boolean isConfigurationFromResource;
    private String configurationResourcePath;
    private OMElement configurationElement;
    private String aspectName;

    private boolean isAuditEnabled;
    private SCXML scxml;

    public ProcessLifeCycle(OMElement config) throws RegistryException {

        initialize();

        String currentAspectName = config.getAttributeValue(new QName(LifecycleConstants.NAME));
        aspectName = currentAspectName;
        currentAspectName = currentAspectName.replaceAll("\\s", "");
        stateProperty = LifecycleConstants.REGISTRY_LIFECYCLE + currentAspectName + ".state";
        lifecycleProperty = lifecycleProperty + "." + currentAspectName;

        Iterator configChildElements = config.getChildElements();
        while (configChildElements.hasNext()) {
            OMElement configChildEl = (OMElement) configChildElements.next();

            if (configChildEl.getAttribute(new QName(LifecycleConstants.TYPE)) != null) {
                String type = configChildEl.getAttributeValue(new QName(LifecycleConstants.TYPE));
                if (type.equalsIgnoreCase("resource")) {
                    isConfigurationFromResource = true;
                    configurationResourcePath = RegistryUtils
                            .getAbsolutePath(RegistryContext.getBaseInstance(), configChildEl.getText());
                    clearAll();
                    break;
                } else if (type.equalsIgnoreCase("literal")) {
                    isConfigurationFromResource = false;
                    configurationElement = configChildEl.getFirstElement();
                    clearAll();
                    break;
                }
            }
        }
    }

    private void clearAll() {
        states.clear();
        checkListItems.clear();
        transitionPermission.clear();
        transitionValidations.clear();
        transitionExecution.clear();
        transitionUIs.clear();
        transitionApproval.clear();
        transitionInputs.clear();
    }

    private void initialize() {
        states = new ArrayList<String>();
        checkListItems = new HashMap<String, List<CheckItemBean>>();
        transitionValidations = new HashMap<String, List<CustomCodeBean>>();
        transitionExecution = new HashMap<String, List<CustomCodeBean>>();
        transitionPermission = new HashMap<String, List<PermissionsBean>>();
        stateEvents = new HashMap<String, List<String>>();
        scriptElements = new HashMap<String, List<ScriptBean>>();
        transitionUIs = new HashMap<String, Map<String, String>>();
        transitionInputs = new HashMap<String, List<InputBean>>();
        transitionApproval = new HashMap<String, List<ApprovalBean>>();
        isAuditEnabled = true;
    }

    private void setSCXMLConfiguration(Registry registry)
            throws RegistryException, XMLStreamException, IOException, SAXException, ModelException {
        String xmlContent;
        if (isConfigurationFromResource) {
            if (registry.resourceExists(configurationResourcePath)) {
                try {
                    Resource configurationResource = registry.get(configurationResourcePath);
                    xmlContent = RegistryUtils.decodeBytes((byte[]) configurationResource.getContent());
                    configurationElement = AXIOMUtil.stringToOM(xmlContent);
                    configurationElement.toString();
                } catch (Exception e) {
                    String msg = "Invalid lifecycle configuration found at " + configurationResourcePath;
                    log.error(msg, e);
                    throw new RegistryException(msg);
                }
            } else {
                String msg =
                        "Unable to find the lifecycle configuration from the given path: " + configurationResourcePath;
                log.error(msg);
                throw new RegistryException(msg);
            }
        }

        try {
            if (configurationElement.getAttributeValue(new QName(LifecycleConstants.AUDIT)) != null) {
                isAuditEnabled = Boolean
                        .parseBoolean(configurationElement.getAttributeValue(new QName(LifecycleConstants.AUDIT)));
            }
            OMElement scxmlElement = configurationElement.getFirstElement();
            scxml = SCXMLParser
                    .parse(new InputSource(new CharArrayReader((scxmlElement.toString()).toCharArray())), null);
        } catch (Exception e) {
            String msg = "Invalid SCXML configuration found";
            log.error(msg, e);
            throw new RegistryException(msg);
        }
    }

    @Override public void associate(Resource resource, Registry registry) throws RegistryException {

        clearAll();
        try {
            setSCXMLConfiguration(registry);

            if (configurationElement == null) {
                return;
            }

            resource.setProperty(ExecutorConstants.REGISTRY_LC_NAME, aspectName);
            List<String> propertyValues = resource.getPropertyValues(lifecycleProperty);
            addCheckPointProperties(resource, null);

            if (propertyValues != null && propertyValues.size() > 0) {
                return;
            }

            if (states.size() == 0) {
                populateItems();
            }

            String initialState = scxml.getInitial();
            addCheckItems(resource, checkListItems.get(initialState), initialState, aspectName);
            addTransitionApprovalItems(resource, transitionApproval.get(initialState), initialState, aspectName);
            addScripts(initialState, resource, scriptElements.get(initialState), aspectName);
            addTransitionUI(resource, transitionUIs.get(initialState), aspectName);
            addTransitionInputs(initialState, resource, transitionInputs.get(initialState), aspectName);

        } catch (Exception e) {
            String message = "Resource does not contain a valid XML configuration: " + e.toString();
            log.error(message,e);
            return;
        }

        resource.setProperty(stateProperty, scxml.getInitial().replace(".", " "));

        resource.setProperty(lifecycleProperty, aspectName);

        StatCollection statCollection = new StatCollection();
        statCollection.setActionType(ASSOCIATION);
        statCollection.setAction("");
        statCollection.setRegistry(registry.getRegistryContext().getEmbeddedRegistryService()
                .getSystemRegistry(CurrentSession.getTenantId()));
        statCollection.setTimeMillis(System.currentTimeMillis());
        statCollection.setState(scxml.getInitial());
        statCollection.setResourcePath(resource.getPath());
        statCollection.setUserName(CurrentSession.getUser());
        statCollection.setOriginalPath(resource.getPath());
        statCollection.setAspectName(aspectName);

        if (isAuditEnabled) {
            StatWriter.writeHistory(statCollection);
        }

    }

    @Override public String[] getAvailableActions(RequestContext context) {

        Resource resource = context.getResource();
        String currentState;
        if (resource.getProperty(stateProperty) == null) {
            return new String[0];
        }
        currentState = resource.getProperty(stateProperty).replace(" ", ".");

        initializeAspect(context, currentState);

        ArrayList<String> actions = new ArrayList<String>();
        String user = CurrentSession.getUser();

        State currentExecutionState = (State) (scxml.getChildren()).get(currentState);
        List currentTransitions = currentExecutionState.getTransitionsList();

        try {
            List<PermissionsBean> permissionsBeans = transitionPermission.get(currentState);

            String[] roles = CurrentSession.getUserRealm().getUserStoreManager().getRoleListOfUser(user);

            for (Object currentTransition : currentTransitions) {
                Transition t = (Transition) currentTransition;
                String transitionName = t.getEvent();

                List<String> possibleActions = getPossibleActions(resource, currentState);
                if ((isTransitionAllowed(roles, permissionsBeans, transitionName) || permissionsBeans == null)
                        && possibleActions.contains(transitionName)) {
                    actions.add(transitionName);
                }
            }
        } catch (UserStoreException e) {
            log.error("Failed to get the current user role :", e);
            return new String[0];
        }
        return actions.toArray(new String[actions.size()]);
    }

    private void initializeAspect(RequestContext context, String currentState) {
        try {
            if (states.size() == 0 || !states.contains(currentState)) {
                clearAll();
                Registry registry = context.getRegistry();
                setSCXMLConfiguration(registry);
                populateItems();
            }

        } catch (Exception e) {
            throw new RuntimeException("Resource does not contain a valid XML configuration: " + e.toString());
        }
    }

    @Override public void invoke(RequestContext context, String action) throws RegistryException {
        invoke(context, action, Collections.<String, String>emptyMap());
    }

    @Override public void invoke(RequestContext requestContext, String action, Map<String, String> parameterMap)
            throws RegistryException {
        boolean preserveOldResource = !Boolean.toString(false).equals(parameterMap.remove("preserveOriginal"));
        Resource resource = requestContext.getResource();
        String currentState = resource.getProperty(stateProperty).replace(" ", ".");
        String resourcePath = requestContext.getResourcePath().getPath();

        initializeAspect(requestContext, currentState);

        String newResourcePath;
        String nextState = currentState;

        if (nextState == null || "".equals(nextState)) {
            throw new GovernanceException("Next state is not defined ");
        }

        String user;
        String[] roles;
        try {
            user = CurrentSession.getUser();
            roles = CurrentSession.getUserRealm().getUserStoreManager().getRoleListOfUser(user);
        } catch (UserStoreException e) {
            String message = "Unable to get user information";
            log.error(message,e);
            throw new RegistryException(message, e);
        }

        lifeCycleActionValidation(resource, requestContext, currentState, action);

        State currentExecutionState = (State) scxml.getChildren().get(currentState);

        StatCollection statCollection = new StatCollection();
        requestContext.setProperty(LifecycleConstants.STAT_COLLECTION, statCollection);

        statCollection.setAction(action);
        statCollection.setRegistry(requestContext.getSystemRegistry());
        statCollection.setTimeMillis(System.currentTimeMillis());
        statCollection.setState(currentState);
        statCollection.setResourcePath(resourcePath);
        statCollection.setUserName(user);
        statCollection.setOriginalPath(resourcePath);
        statCollection.setAspectName(aspectName);

        if ("voteClick".equals(action)) {
            handleApprovalClick(resource, currentState, extractVotesValues(parameterMap), user, roles, requestContext);
        } else {
            handleItemClick(resource, currentState, extractCheckItemValues(parameterMap), roles, requestContext);
        }

        List transitions = currentExecutionState.getTransitionsList();
        try {
            List<String> possibleEvents = getPossibleActions(resource, currentState);
            if (possibleEvents.size() > 0) {
                for (Object o : transitions) {
                    String eventName = ((Transition) o).getEvent();
                    if (possibleEvents.contains(eventName) && eventName.equals(action)) {
                        if (isTransitionAllowed(roles, transitionPermission.get(currentState), eventName)) {
                            if (doAllCustomValidations(requestContext, currentState, eventName)) {
                                statCollection.setActionType(LifecycleConstants.TRANSITION);
                                if (resource.getProperty(
                                        LifecycleConstants.REGISTRY_LIFECYCLE_HISTORY_ORIGINAL_PATH + aspectName)
                                        != null) {
                                    statCollection.setOriginalPath(resource.getProperty(
                                            LifecycleConstants.REGISTRY_LIFECYCLE_HISTORY_ORIGINAL_PATH + aspectName));
                                }
                                nextState = ((Transition) o).getNext();
                                updateCheckpointProperties(resource, nextState);

                                List<ScriptBean> scriptElement = scriptElements.get(currentState);
                                try {
                                    if (scriptElement != null) {
                                        for (ScriptBean scriptBean : scriptElement) {
                                            if (scriptBean.getEventName().equals(eventName) && !scriptBean
                                                    .isConsole()) {
                                                executeJS(AXIOMUtil.stringToOM(scriptBean.getScript()).getText() + "\n"
                                                        + scriptBean.getFunctionName() + "()");
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    String msg = "JavaScript execution failed.";
                                    log.error(msg,e);
                                    throw new RegistryException(msg);
                                }
                                break;
                            } else {
                                String msg = "Transition validations failed.";
                                log.info(msg);
                                throw new RegistryException(msg);
                            }
                        }
                    }
                }
            }
        } catch (RegistryException e) {
            log.error(e);
            throw new RegistryException(e.getMessage());
        }

        if (requestContext.getResource() == null) {
            requestContext.setResource(resource);
            requestContext.setProcessingComplete(true);
            return;
        }
        if (!requestContext.getResourcePath().getPath().equals(resourcePath) && !requestContext.getResource()
                .equals(resource))
            requestContext.getRegistry().put(resourcePath, resource);

        resource = requestContext.getResource();
        newResourcePath = requestContext.getResourcePath().getPath();

        if (!currentState.equals(nextState)) {
            State state = (State) scxml.getChildren().get(nextState);
            resource.setProperty(stateProperty, state.getId().replace(".", " "));

            resource.setProperty(ExecutorConstants.REGISTRY_LC_NAME, aspectName);
            clearCheckItems(resource, aspectName);
            clearTransitionApprovals(resource, aspectName);
            addCheckItems(resource, checkListItems.get(state.getId()), state.getId(), aspectName);
            addTransitionApprovalItems(resource, transitionApproval.get(state.getId()), state.getId(), aspectName);
            addScripts(state.getId(), resource, scriptElements.get(state.getId()), aspectName);
            addTransitionUI(resource, transitionUIs.get(state.getId()), aspectName);
            addTransitionInputs(state.getId(), resource, transitionInputs.get(state.getId()), aspectName);

            statCollection.setTargetState(nextState);
        }
        if (!preserveOldResource) {
            requestContext.getRegistry().delete(resourcePath);
        }

        requestContext.getRegistry().put(newResourcePath, resource);
        runCustomExecutorsCode(action, requestContext, transitionExecution.get(currentState), currentState, nextState);

        if (isAuditEnabled) {
            StatWriter.writeHistory(statCollection);
        }
    }

    @Override public void dissociate(RequestContext requestContext) {

        Resource resource = requestContext.getResource();

        if (resource != null) {
            resource.removeProperty(stateProperty);
            resource.removeProperty(lifecycleProperty);
        }
    }

    private void populateItems() throws Exception {
        Map stateList = scxml.getChildren();

        for (Object stateObject : stateList.entrySet()) {

            Map.Entry state = (Map.Entry) stateObject;

            String currentStateName = (String) state.getKey();
            State currentState = (State) state.getValue();
            Datamodel model = currentState.getDatamodel();

            states.add(currentStateName);
            if (model != null) {
                List dataList = model.getData();
                for (Object dataObject : dataList) {
                    Data data = (Data) dataObject;
                    OMElement node = XMLUtils.toOM((Element) data.getNode());

                    populateCheckItems(currentStateName, node, checkListItems);
                    populateTransitionValidations(currentStateName, node, transitionValidations);
                    populateTransitionPermissions(currentStateName, node, transitionPermission);
                    populateTransitionScripts(currentStateName, node, scriptElements);
                    populateTransitionUIs(currentStateName, node, transitionUIs);
                    populateTransitionExecutors(currentStateName, node, transitionExecution);
                    populateTransitionApprovals(currentStateName, node, transitionApproval);
                    populateTransitionInputs(currentStateName, node, transitionInputs);
                }
            }

            List<String> events = new ArrayList<String>();
            for (Object t : currentState.getTransitionsList()) {
                Transition transition = (Transition) t;
                events.add(transition.getEvent());
            }
            stateEvents.put(currentStateName, events);
        }
    }

    private List<String> getPossibleActions(Resource resource, String currentState) {

        Properties propertyNameValues = resource.getProperties();
        Iterator propIterator = propertyNameValues.entrySet().iterator();
        List<CheckItemBean> checkItems = checkListItems.get(currentState);
        List<String> events = new ArrayList<String>(stateEvents.get(currentState));

        if (checkItems != null && checkItems.size() > 0) {
            while (propIterator.hasNext()) {
                Map.Entry entry = (Map.Entry) propIterator.next();
                String propertyName = (String) entry.getKey();

                if (propertyName
                        .startsWith(LifecycleConstants.REGISTRY_CUSTOM_LIFECYCLE_CHECKLIST_OPTION + aspectName)) {
                    List<String> propValues = (List<String>) entry.getValue();
                    for (String propValue : propValues)
                        if (propValue.startsWith("name:"))
                            for (CheckItemBean checkItem : checkItems)
                                if ((checkItem.getName().equals(propValue.substring(propValue.indexOf(":") + 1))) &&
                                        (checkItem.getEvents() != null) && propValues.contains("value:false")) {
                                    events.removeAll(checkItem.getEvents());
                                }
                }

            }
        }
        return events;
    }

    private void executeJS(String script) throws Exception {
        Context cx = Context.enter();
        try {
            ConfigurationContext configurationContext = MessageContext.getCurrentMessageContext()
                    .getConfigurationContext();
            cx.putThreadLocal(MashupConstants.AXIS2_CONFIGURATION_CONTEXT, configurationContext);
            AxisService service = new AxisService();
            service.addParameter(MashupConstants.MASHUP_AUTHOR, CurrentSession.getUser());
            cx.putThreadLocal(MashupConstants.AXIS2_SERVICE, service);
            Scriptable scope = cx.initStandardObjects();
            ScriptableObject.defineClass(scope, ResourceHostObject.class);
            ScriptableObject.defineClass(scope, CollectionHostObject.class);
            ScriptableObject.defineClass(scope, RegistryHostObject.class);
            Object result = cx.evaluateString(scope, script, "<cmd>", 1, null);
            if (result != null && log.isInfoEnabled()) {
                log.info("JavaScript Result: " + Context.toString(result));
            }
        } catch (IllegalAccessException e) {
            String msg = "Unable to defining registry host objects.";
            throw new Exception(msg, e);
        } catch (InstantiationException e) {
            String msg = "Unable to instantiate the given registry host object.";
            throw new Exception(msg, e);
        } catch (InvocationTargetException e) {
            String msg = "An exception occurred while creating registry host objects.";
            throw new Exception(msg, e);
        } catch (AxisFault e) {
            String msg = "Failed to set user name parameter.";
            throw new Exception(msg, e);
        } catch (SecurityException ignored) {
            // If there is a security issue, simply live with that. This portion of the sample is
            // not intended to work on a system with security restrictions.
        } finally {
            Context.exit();
        }
    }

    private boolean doAllCustomValidations(RequestContext context, String currentState, String action)
            throws RegistryException {
        List<CheckItemBean> currentStateCheckItems = checkListItems.get(currentState);
        if (currentStateCheckItems != null) {
            for (CheckItemBean currentStateCheckItem : currentStateCheckItems) {
                try {
                    runCustomValidationsCode(context, currentStateCheckItem.getValidationBeans(), action);
                } catch (RegistryException registryException) {
                    throw new RegistryException(
                            "Validation failed for check item : " + currentStateCheckItem.getName());
                }
            }
        }
        try {
            return runCustomValidationsCode(context, transitionValidations.get(currentState), action);
        } catch (RegistryException e) {
            throw new RegistryException("Validation failed for check item : " + action);
        }
    }

    private boolean runCustomValidationsCode(RequestContext context, List<CustomCodeBean> customCodeBeans,
            String action) throws RegistryException {
        if (customCodeBeans != null) {
            for (CustomCodeBean customCodeBean : customCodeBeans) {
                if (customCodeBean.getEventName().equals(action)) {
                    CustomValidations customValidations = (CustomValidations) customCodeBean.getClassObeject();

                    StatCollection statCollection = (StatCollection) context
                            .getProperty(LifecycleConstants.STAT_COLLECTION);
                    statCollection.addValidations(customCodeBean.getClass().getName(), null);

                    if (!customValidations.validate(context)) {
                        statCollection.addValidations(customCodeBean.getClass().getName(),
                                getHistoryInfoElement("validation failed"));

                        String userMsg = (String) context.getProperty(LifecycleConstants.VALIDATIONS_MESSAGE_KEY);
                        String message = "Validation : " + customCodeBean.getClassObeject().getClass().getName()
                                + " failed for action : " + customCodeBean.getEventName();

                        if (userMsg != null) {
                            message = message + " Embedded error : " + userMsg;
                        }

                        throw new RegistryException(message);
                    }
                }
            }
        }
        return true;
    }

    private boolean runCustomExecutorsCode(String action, RequestContext context, List<CustomCodeBean> customCodeBeans,
            String currentState, String nextState) throws RegistryException {
        if (customCodeBeans != null) {
            for (CustomCodeBean customCodeBean : customCodeBeans) {
                if (customCodeBean.getEventName().equals(action)) {
                    Execution customExecutor = (Execution) customCodeBean.getClassObeject();

                    StatCollection statCollection = (StatCollection) context
                            .getProperty(LifecycleConstants.STAT_COLLECTION);
                    statCollection.addExecutors(customExecutor.getClass().getName(), null);

                    if (!customExecutor.execute(context, currentState, nextState)) {
                        statCollection.addExecutors(customExecutor.getClass().getName(),
                                getHistoryInfoElement("executor failed"));

                        String userMsg = (String) context.getProperty(LifecycleConstants.EXECUTOR_MESSAGE_KEY);
                        String message = "Execution failed for action : " + customCodeBean.getEventName();

                        if (userMsg != null) {
                            message = message + " Embedded error : " + userMsg;
                        }
                        throw new RegistryException(message);
                    }
                }
            }
        }
        return true;
    }

    private void handleItemClick(Resource resource, String state, Map<String, String> itemParameterMap, String[] roles,
            RequestContext context) throws RegistryException {
        for (Map.Entry<String, String> entry : itemParameterMap.entrySet()) {
            List<String> propertyValues = resource.getPropertyValues(
                    LifecycleConstants.REGISTRY_CUSTOM_LIFECYCLE_CHECKLIST_OPTION + aspectName + "." + entry.getKey());
            if (propertyValues != null) {
                String name = null;

                for (String propertyValue : propertyValues) {
                    if (propertyValue.startsWith("name:")) {
                        name = propertyValue.replace("name:", "");
                        break;
                    }
                }

                for (String propertyValue : propertyValues) {
                    if (propertyValue.startsWith("value:") && !propertyValue.contains(entry.getValue())) {
                        List<String> newProps = new ArrayList<String>(propertyValues);
                        newProps.remove(propertyValue);

                        if (Boolean.parseBoolean(entry.getValue())) {
                            List<CheckItemBean> checkItemBeans = checkListItems.get(state);

                            for (CheckItemBean checkItemBean : checkItemBeans) {
                                if (checkItemBean.getName().equals(name)) {

                                    if (!isCheckItemClickAllowed(roles, checkItemBean.getPermissionsBeans())) {
                                        String message = "User is not authorized to check item :" + name;
                                        log.error(message);
                                        throw new RegistryException(message);
                                    }
                                }
                            }
                        }

                        String replace = propertyValue
                                .replace(Boolean.toString(!Boolean.valueOf(entry.getValue())), entry.getValue());
                        newProps.add(replace);
                        resource.removeProperty(
                                LifecycleConstants.REGISTRY_CUSTOM_LIFECYCLE_CHECKLIST_OPTION + aspectName + "." + entry
                                        .getKey());
                        resource.setProperty(
                                LifecycleConstants.REGISTRY_CUSTOM_LIFECYCLE_CHECKLIST_OPTION + aspectName + "." + entry
                                        .getKey(), newProps);

                        StatCollection statCollection = (StatCollection) context
                                .getProperty(LifecycleConstants.STAT_COLLECTION);
                        statCollection.setAction(getCheckItemName(propertyValues));
                        statCollection.setActionType(LifecycleConstants.ITEM_CLICK);
                        statCollection.setActionValue(replace);

                        if (resource
                                .getProperty(LifecycleConstants.REGISTRY_LIFECYCLE_HISTORY_ORIGINAL_PATH + aspectName)
                                != null) {
                            statCollection.setOriginalPath(resource.getProperty(
                                    LifecycleConstants.REGISTRY_LIFECYCLE_HISTORY_ORIGINAL_PATH + aspectName));
                        }
                    }
                }
            }
        }
    }

    private void handleApprovalClick(Resource resource, String currentState, Map<String, String> itemParameterMap,
            String user, String[] roles, RequestContext requestContext) {
        for (Map.Entry<String, String> entry : itemParameterMap.entrySet()) {
            List<String> propertyValues = resource.getPropertyValues(
                    LifecycleConstants.REGISTRY_CUSTOM_LIFECYCLE_VOTES_OPTION + aspectName + "." + entry.getKey());

            String userPropertyValue = "";
            boolean userVoted = false;
            List<String> userList = new ArrayList<String>();
            for (String propertyValue : propertyValues) {
                if (propertyValue.startsWith("users:")) {
                    userPropertyValue = propertyValue;
                    String users = propertyValue.replace("users:", "");
                    String[] votedUsers = users.split(",");
                    userList = Arrays.asList(votedUsers);
                    if (userList != null && !userList.isEmpty()) {
                        userVoted = Arrays.asList(votedUsers).contains(user);
                    } else if (userList == null) {
                        userList = new ArrayList<String>();
                    }
                    break;
                }
            }

            if ((userVoted == true) || (userVoted == false && Boolean.valueOf(entry.getValue()))) {
                for (String propertyValue : propertyValues) {
                    if (propertyValue.startsWith("current:") && !propertyValue.contains(entry.getValue())) {
                        List<String> newProps = new ArrayList<String>(propertyValues);
                        String approvals = newProps.get(newProps.indexOf(propertyValue));
                        approvals = approvals.replace("current:", "");
                        int approvalCount = Integer.parseInt(approvals);

                        List<String> list = new ArrayList<String>(userList);
                        if (Boolean.valueOf(entry.getValue()) && userVoted == false) {
                            approvalCount++;
                            list.add(user);
                        } else if (!Boolean.valueOf(entry.getValue()) && userVoted == true) {
                            approvalCount--;
                            list.remove(user);
                        }
                        newProps.remove(propertyValue);
                        newProps.add("current:" + approvalCount);

                        StringBuilder sb = new StringBuilder();
                        for (String n : list) {
                            if (sb.length() > 0)
                                sb.append(',');
                            sb.append(n);
                        }
                        newProps.remove(userPropertyValue);
                        newProps.add("users:" + sb.toString());

                        resource.removeProperty(
                                LifecycleConstants.REGISTRY_CUSTOM_LIFECYCLE_VOTES_OPTION + aspectName + "." + entry
                                        .getKey());
                        resource.setProperty(
                                LifecycleConstants.REGISTRY_CUSTOM_LIFECYCLE_VOTES_OPTION + aspectName + "." + entry
                                        .getKey(), newProps);
                        //resource.setProperty(userSpecificVote,Boolean.toString(Boolean.valueOf(entry.getValue())));

                        StatCollection statCollection = (StatCollection) requestContext
                                .getProperty(LifecycleConstants.STAT_COLLECTION);
                        statCollection.setAction(getCheckItemName(propertyValues));
                        statCollection.setActionType(LifecycleConstants.VOTE);
                        statCollection.setActionValue(entry.getValue());

                        if (resource
                                .getProperty(LifecycleConstants.REGISTRY_LIFECYCLE_HISTORY_ORIGINAL_PATH + aspectName)
                                != null) {
                            statCollection.setOriginalPath(resource.getProperty(
                                    LifecycleConstants.REGISTRY_LIFECYCLE_HISTORY_ORIGINAL_PATH + aspectName));
                        }
                        break;
                    }
                }
            }
        }
    }

    private void lifeCycleActionValidation(Resource resource, RequestContext requestContext, String currentState,
            String action) throws RegistryException {
        if ((!action.equals("voteClick") && !action.equals("itemClick"))
                && transitionApproval.get(currentState) != null) {
            int order = 0;
            for (ApprovalBean approvalBean : transitionApproval.get(currentState)) {
                if (action.equals(approvalBean.getForEvent())) {
                    String resourcePropertyNameForItem =
                            LifecycleConstants.REGISTRY_CUSTOM_LIFECYCLE_VOTES_OPTION + aspectName + "." + order
                                    + LifecycleConstants.VOTE;
                    List<String> list = resource.getPropertyValues(resourcePropertyNameForItem);
                    for (String value : list) {
                        if (value.startsWith("current:")) {
                            value = value.replace("current:", "");
                            int currentVotes = Integer.parseInt(value);
                            if (currentVotes < approvalBean.getVotes()) {
                                String message = "Unable to " + action
                                        + " the lifecycle with available Approvals. Required Votes: " + approvalBean
                                        .getVotes() + ", Current Votes: " + currentVotes;
                                log.error(message);
                                throw new RegistryException(message);
                            }
                        }
                    }
                }
                order++;
            }
        }

        String[] vailableActions = getAvailableActions(requestContext);
        List actionsList = Arrays.asList(vailableActions);
        if (!action.equals("voteClick") && !action.equals("itemClick") && !actionsList.contains(action)) {
            String message = "Preprequest action must be completed before " + action + "";
            log.error(message);
            throw new RegistryException(message);
        }
    }

    /**
     * This method is used to add current lifecycle's checkpoint properties.
     *
     * @param resource  registry resource.
     * @param nextState next lifecycle state.
     * @throws RegistryException
     */
    private void addCheckPointProperties(Resource resource, String nextState) throws RegistryException {

        if (nextState == null) {
            nextState = LifecycleCheckpointUtils.getLCInitialStateId(configurationElement);
        }

        if (nextState != null) {
            String xpathString =
                    LifecycleConstants.XPATH_STATE_WITH_ID + nextState + "']" + LifecycleConstants.XPATH_CHECKPOINT;
            List checkpoints = LifecycleCheckpointUtils.evaluateXpath(configurationElement, xpathString, null);

            if (!checkpoints.isEmpty()) {
                for (Object checkpoint : checkpoints) {
                    OMElement checkpointOMElement = (OMElement) checkpoint;
                    String checkpointId = checkpointOMElement
                            .getAttributeValue(new QName(LifecycleConstants.LIFECYCLE_CHECKPOINT_NAME));
                    String checkpointDurationColour = checkpointOMElement
                            .getAttributeValue(new QName(LifecycleConstants.LIFECYCLE_DURATION_COLOUR));
                    String checkpointDurationMinBoundary = checkpointOMElement.getFirstElement()
                            .getAttributeValue(new QName(LifecycleConstants.LIFECYCLE_LOWER_BOUNDARY));
                    String checkpointDurationMaxBoundary = checkpointOMElement.getFirstElement()
                            .getAttributeValue(new QName(LifecycleConstants.LIFECYCLE_UPPER_BOUNDARY));

                    resource.addProperty(
                            LifecycleConstants.REGISTRY_LIFECYCLE + aspectName + LifecycleConstants.CHECKPOINT,
                            checkpointId);
                    List<String> lcCheckpointProperties1 = new ArrayList<>();
                    lcCheckpointProperties1.add(0, checkpointId);
                    lcCheckpointProperties1.add(1, checkpointDurationMinBoundary);
                    lcCheckpointProperties1.add(2, checkpointDurationMaxBoundary);
                    lcCheckpointProperties1.add(3, LifecycleCheckpointUtils.getCurrentTime());
                    lcCheckpointProperties1.add(4, checkpointDurationColour);

                    String checkpointPropertyKey = LifecycleConstants.REGISTRY_LIFECYCLE + aspectName +
                            LifecycleConstants.CHECKPOINT + LifecycleConstants.DOT + checkpointId;
                    resource.removeProperty(checkpointPropertyKey);
                    resource.setProperty(checkpointPropertyKey, lcCheckpointProperties1);
                }
            }
        }
        resource.setProperty(LifecycleConstants.REGISTRY_LIFECYCLE + aspectName + LifecycleConstants.LAST_UPDATED_TIME,
                LifecycleCheckpointUtils.getCurrentTime());
    }

    /**
     * This method is used to update checkpoint current state duration information.
     *
     * @param resource  registry resource.
     * @param nextState next lifecycle state.
     * @throws RegistryException
     */
    private void updateCheckpointProperties(Resource resource, String nextState) throws RegistryException {

        String checkpointProperty = LifecycleConstants.REGISTRY_LIFECYCLE + aspectName + LifecycleConstants.CHECKPOINT;
        List<String> checkpoints = resource.getPropertyValues(checkpointProperty);
        if (checkpoints != null && !checkpoints.isEmpty()) {
            for (String checkpoint : checkpoints) {
                resource.removeProperty(checkpointProperty + LifecycleConstants.DOT + checkpoint);
            }
        }
        resource.removeProperty(checkpointProperty);
        addCheckPointProperties(resource, nextState);
    }
}