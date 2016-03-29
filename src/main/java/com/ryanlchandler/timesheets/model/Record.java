package com.ryanlchandler.timesheets.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Record {

    private String eventSource;
    private String eventVersion;
    private SESEvent ses;

    public String getEventSource() {
        return eventSource;
    }

    public void setEventSource(String eventSource) {
        this.eventSource = eventSource;
    }

    public String getEventVersion() {
        return eventVersion;
    }

    public void setEventVersion(String eventVersion) {
        this.eventVersion = eventVersion;
    }

    public SESEvent getSes() {
        return ses;
    }

    public void setSes(SESEvent ses) {
        this.ses = ses;
    }
}
