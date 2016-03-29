package com.ryanlchandler.timesheets.bean;

public class TimeSheetEntry {


    private String commCode;
    private String associateName;
    private String associateID;
    private String dept;
    private String missedPunch;
    private String overtimeHours;
    private String longInterval;
    private String shortShift;
    private String shortLunch;
    private String missedLunch;


    public String getCommCode() {
        return commCode;
    }

    public void setCommCode(String commCode) {
        this.commCode = commCode;
    }

    public String getAssociateName() {
        return associateName;
    }

    public void setAssociateName(String associateName) {
        this.associateName = associateName;
    }

    public String getAssociateID() {
        return associateID;
    }

    public void setAssociateID(String associateID) {
        this.associateID = associateID;
    }

    public String getDept() {
        return dept;
    }

    public void setDept(String dept) {
        this.dept = dept;
    }

    public String getMissedPunch() {
        return missedPunch;
    }

    public void setMissedPunch(String missedPunch) {
        this.missedPunch = missedPunch;
    }

    public String getOvertimeHours() {
        return overtimeHours;
    }

    public void setOvertimeHours(String overtimeHours) {
        this.overtimeHours = overtimeHours;
    }

    public String getLongInterval() {
        return longInterval;
    }

    public void setLongInterval(String longInterval) {
        this.longInterval = longInterval;
    }

    public String getShortShift() {
        return shortShift;
    }

    public void setShortShift(String shortShift) {
        this.shortShift = shortShift;
    }

    public String getShortLunch() {
        return shortLunch;
    }

    public void setShortLunch(String shortLunch) {
        this.shortLunch = shortLunch;
    }

    public String getMissedLunch() {
        return missedLunch;
    }

    public void setMissedLunch(String missedLunch) {
        this.missedLunch = missedLunch;
    }
}
