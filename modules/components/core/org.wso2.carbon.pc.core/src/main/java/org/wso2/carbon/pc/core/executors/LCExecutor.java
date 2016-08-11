/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.carbon.pc.core.executors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.governance.registry.extensions.interfaces.Execution;
import org.wso2.carbon.registry.core.jdbc.handlers.RequestContext;
import org.wso2.carbon.registry.core.session.CurrentSession;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.registry.resource.services.utils.AddRolePermissionUtil;

import java.util.Map;

public class LCExecutor implements Execution {

    private static final Log log = LogFactory.getLog(LCExecutor.class);
    private String user = null;
    private String path = null;
    private UserRegistry userRegistry = null;

    public void init(Map map) {
    }

    public boolean execute(RequestContext context, String currentState, String targetState) {

        boolean updated;

        try {
            user = context.getResource().getAuthorUserName();
            String[] roles = CurrentSession.getUserRealm().getUserStoreManager().getRoleNames();
            path = context.getResourcePath().toString();
            userRegistry = (UserRegistry) context.getSystemRegistry();

            setPublisherPermission(currentState, targetState);
            for (String role : roles) {
                if (role.equals("admin") || role.equals("Internal/publisher") || role
                        .equals("Internal/private_" + user)) {
                    continue;
                } else {
                    setOtherRolePermission(role, currentState, targetState);
                }
            }
            setPrivateRolePermission(user, currentState, targetState);

            updated = true;

        } catch (Exception e) {
            String errMsg = "Failed to update Permission";
            log.error(errMsg,e);
            throw new Error(errMsg , e);
        }

        return updated;
    }

    private void assignPermission(String role, String permissionString) throws Exception {

        String[] permissionList = permissionString.split(",");
        String permissionType;
        String actionToAuthorize = "";
        String action;

        for (String permission : permissionList) {
            if (permission.charAt(0) == '+') {
                permissionType = ExecutorConstants.ALLOW;
            } else {
                permissionType = ExecutorConstants.DENY;
            }
            action = permission.substring(1, permission.length());
            switch (action) {
            case "get":
                actionToAuthorize = ExecutorConstants.READ;
                break;
            case "add":
                actionToAuthorize = ExecutorConstants.WRITE;
                break;
            case "delete":
                actionToAuthorize = ExecutorConstants.DELETE;
                break;
            case "authorize":
                actionToAuthorize = ExecutorConstants.AUTHORIZE;
                break;
            }
            AddRolePermissionUtil.addRolePermission(userRegistry, path, role, actionToAuthorize, permissionType);
        }
    }

    private void setPublisherPermission(String currentState, String targetState) throws Exception {

        if (currentState.equals("Development")) {
            assignPermission("Internal/publisher", "+get,+add,+delete,+authorize");

        } else if (currentState.equals("In-Review") && targetState.equals("Development")) {
            assignPermission("Internal/publisher", "+get,+add,+delete,+authorize");

        } else if (currentState.equals("In-Review") && targetState.equals("Published")) {
            assignPermission("Internal/publisher", "+get,+add,-delete,+authorize");

        }   else if (currentState.equals("Published") && targetState.equals("Development")) {
            assignPermission("Internal/publisher", "+get,+add,+delete,+authorize");

        } else if (currentState.equals("Published") && targetState.equals("Retired")) {
            assignPermission("Internal/publisher", "+get,+add,+delete,-authorize");
        }
    }

    private void setPrivateRolePermission(String user, String currentState, String targetState) throws Exception {

        if(!user.equals("admin")) {

            if (currentState.equals("Development")) {
                assignPermission("Internal/private_" + user, "+get,-add,+delete,-authorize");

            } else if (currentState.equals("In-Review") && targetState.equals("Development")) {
                assignPermission("Internal/private_" + user, "+get,+add,+delete,+authorize");

            } else if (currentState.equals("In-Review") && targetState.equals("Published")) {
                assignPermission("Internal/private_" + user, "+get,-add,-delete,-authorize");

            } else if (currentState.equals("Published") && targetState.equals("Development")) {
                assignPermission("Internal/private_" + user, "+get,+add,+delete,+authorize");

            } else if (currentState.equals("Published") && targetState.equals("Retired")) {
                assignPermission("Internal/private_" + user, "+get,-add,+delete,-authorize");
            }
        }
    }

    private void setOtherRolePermission(String role, String currentState, String targetState) throws Exception {

        if (currentState.equals("Development")) {
            assignPermission(role, "+get,-add,-delete,-authorize");

        } else if (currentState.equals("In-Review")&& targetState.equals("Development")) {
            assignPermission(role, "+get,+add,-delete,-authorize");

        }  else if (currentState.equals("In-Review")&& targetState.equals("Published")) {
        assignPermission(role, "+get,-add,-delete,-authorize");

        } else if (currentState.equals("Published") && targetState.equals("Development")) {
            assignPermission(role, "+get,+add,-delete,-authorize");

        } else if (currentState.equals("Published") && targetState.equals("Retired")) {
            assignPermission(role, "-get,-add,-delete,-authorize");
        }
    }
}
