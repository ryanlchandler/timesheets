package com.ryanlchandler.timesheets.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SESEvent {
    
    private String notificationType;
    private Email mail;
    private Receipt receipt;
    private String content;


    public Email getMail() {
        return mail;
    }

    @JsonProperty("mail")
    public void setMail(Email mail) {
        this.mail = mail;
    }

    public Receipt getReceipt() {
        return receipt;
    }

    @JsonProperty("receipt")
    public void setReceipt(Receipt receipt) {
        this.receipt = receipt;
    }

    public String getNotificationType() {
        return notificationType;
    }

    @JsonProperty("notificationType")
    public void setNotificationType(String notificationType) {
        this.notificationType = notificationType;
    }

    public String getContent() {
        return content;
    }

    @JsonProperty("content")
    public void setContent(String content) {
        this.content = content;
    }
}
