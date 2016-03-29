package com.ryanlchandler.timesheets.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Date;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Email {


    private String timestamp;
    private String source;
    private String messageId;
    private String message;
    private String[] destination;
    private boolean headersTruncated;
    private Header[] headers;
    private CommonHeaders commonHeaders;

    public String getTimestamp() {
        return timestamp;
    }

    @JsonProperty("timestamp")
    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getSource() {
        return source;
    }

    @JsonProperty("source")
    public void setSource(String source) {
        this.source = source;
    }

    public String getMessageId() {
        return messageId;
    }

    @JsonProperty("messageId")
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String[] getDestination() {
        return destination;
    }

    @JsonProperty("destination")
    public void setDestination(String[] destination) {
        this.destination = destination;
    }

    public boolean isHeadersTruncated() {
        return headersTruncated;
    }

    @JsonProperty("headersTruncated")
    public void setHeadersTruncated(boolean headersTruncated) {
        this.headersTruncated = headersTruncated;
    }

    public Header[] getHeaders() {
        return headers;
    }

    @JsonProperty("headers")
    public void setHeaders(Header[] headers) {
        this.headers = headers;
    }

    public String getMessage() {
        return message;
    }

    @JsonProperty("message")
    public void setMessage(String message) {
        this.message = message;
    }

    public CommonHeaders getCommonHeaders() {
        return commonHeaders;
    }

    @JsonProperty("commonHeaders")
    public void setCommonHeaders(CommonHeaders commonHeaders) {
        this.commonHeaders = commonHeaders;
    }
}
