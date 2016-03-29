package com.ryanlchandler.timesheets.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Receipt {

    private String timestamp;
    private long processingTimeMillis;
    private String[] recipients;
    private Verdict spamVerdict;
    private Verdict virusVerdict;
    private Verdict spfVerdict;
    private Verdict dkimVerdict;
    private Action action;


    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public long getProcessingTimeMillis() {
        return processingTimeMillis;
    }

    public void setProcessingTimeMillis(long processingTimeMillis) {
        this.processingTimeMillis = processingTimeMillis;
    }

    public String[] getRecipients() {
        return recipients;
    }

    public void setRecipients(String[] recipients) {
        this.recipients = recipients;
    }

    public Verdict getSpamVerdict() {
        return spamVerdict;
    }

    public void setSpamVerdict(Verdict spamVerdict) {
        this.spamVerdict = spamVerdict;
    }

    public Verdict getVirusVerdict() {
        return virusVerdict;
    }

    public void setVirusVerdict(Verdict virusVerdict) {
        this.virusVerdict = virusVerdict;
    }

    public Verdict getSpfVerdict() {
        return spfVerdict;
    }

    public void setSpfVerdict(Verdict spfVerdict) {
        this.spfVerdict = spfVerdict;
    }

    public Verdict getDkimVerdict() {
        return dkimVerdict;
    }

    public void setDkimVerdict(Verdict dkimVerdict) {
        this.dkimVerdict = dkimVerdict;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }
}
