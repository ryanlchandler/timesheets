package com.ryanlchandler.timesheets.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Event {

    private Record[] records;


    public Record[] getRecords() {
        return records;
    }

    @JsonProperty("Records")
    public void setRecords(Record[] records) {
        this.records = records;
    }
}
