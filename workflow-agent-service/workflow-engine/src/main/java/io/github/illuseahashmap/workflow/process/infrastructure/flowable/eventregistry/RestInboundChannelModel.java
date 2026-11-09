package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import java.util.ArrayList;
import java.util.List;
import org.flowable.eventregistry.model.InboundChannelModel;

/**
 * Inbound channel reached over HTTP, like Flowable Enterprise's REST channel. It has no listener:
 * the platform's webhook endpoint hands each request to EventRegistry#eventReceived.
 */
public class RestInboundChannelModel extends InboundChannelModel {

    private List<String> allowedEventKeys = new ArrayList<>();
    private Security security = new Security();

    public RestInboundChannelModel() {
        setType("rest");
        setChannelType("inbound");
    }

    public List<String> getAllowedEventKeys() {
        return allowedEventKeys;
    }

    public void setAllowedEventKeys(List<String> allowedEventKeys) {
        this.allowedEventKeys = allowedEventKeys == null ? new ArrayList<>() : allowedEventKeys;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security == null ? new Security() : security;
    }

    /** Who may post to the channel: principal ids and role codes, as Enterprise's userIds / groupIds. */
    public static class Security {

        private List<String> userIds = new ArrayList<>();
        private List<String> groupIds = new ArrayList<>();

        public List<String> getUserIds() {
            return userIds;
        }

        public void setUserIds(List<String> userIds) {
            this.userIds = userIds == null ? new ArrayList<>() : userIds;
        }

        public List<String> getGroupIds() {
            return groupIds;
        }

        public void setGroupIds(List<String> groupIds) {
            this.groupIds = groupIds == null ? new ArrayList<>() : groupIds;
        }

        public boolean isEmpty() {
            return userIds.isEmpty() && groupIds.isEmpty();
        }
    }
}
