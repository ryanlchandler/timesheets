package com.ryanlchandler.timesheets.bean;

import java.util.*;

public class ProblemTime {

    private List<TimeSheetEntry> missedPunch = new ArrayList<TimeSheetEntry>();
    private List<TimeSheetEntry> overtimeHours  = new ArrayList<TimeSheetEntry>();
    private List<TimeSheetEntry> longInterval = new ArrayList<TimeSheetEntry>();
    private List<TimeSheetEntry> shortShift = new ArrayList<TimeSheetEntry>();
    private List<TimeSheetEntry> shortLunch = new ArrayList<TimeSheetEntry>();
    private List<TimeSheetEntry> missedLunch = new ArrayList<TimeSheetEntry>();
    private int total = 0;
    private Set<TimeSheetEntry> problemEntries = new HashSet<TimeSheetEntry>();
    private Map<TimeSheetEntry, Integer> entryCount = new HashMap<TimeSheetEntry, Integer>();
    private Map<TimeSheetEntry, Integer> missedPunchCount = new HashMap<TimeSheetEntry, Integer>();
    private Map<TimeSheetEntry, Integer> overtimeHoursCount = new HashMap<TimeSheetEntry, Integer>();
    private Map<TimeSheetEntry, Integer> longIntervalCount = new HashMap<TimeSheetEntry, Integer>();
    private Map<TimeSheetEntry, Integer> shortShiftCount = new HashMap<TimeSheetEntry, Integer>();
    private Map<TimeSheetEntry, Integer> shortLunchCount = new HashMap<TimeSheetEntry, Integer>();
    private Map<TimeSheetEntry, Integer> missedLunchCount = new HashMap<TimeSheetEntry, Integer>();

    public void incMissedPunch(TimeSheetEntry entry) {
        incEntry(entry, missedPunchCount);
        problemEntries.add(entry);
        this.missedPunch.add(entry);
        this.total++;
    }

    public void incOvertimeHours(TimeSheetEntry entry) {
        incEntry(entry, overtimeHoursCount);
        problemEntries.add(entry);
        this.overtimeHours.add(entry);
        this.total++;
    }

    public void incLongInterval(TimeSheetEntry entry) {
        incEntry(entry, longIntervalCount);
        problemEntries.add(entry);
        this.longInterval.add(entry);
        this.total++;
    }

    public void incShortShift(TimeSheetEntry entry) {
        incEntry(entry, shortShiftCount);
        problemEntries.add(entry);
        this.shortShift.add(entry);
        this.total++;
    }

    public void incShortLunch(TimeSheetEntry entry) {
        incEntry(entry, shortLunchCount);
        problemEntries.add(entry);
        this.shortLunch.add(entry);
        this.total++;
    }

    public void incMissedLunch(TimeSheetEntry entry) {
        incEntry(entry, missedLunchCount);
        problemEntries.add(entry);
        this.missedLunch.add(entry);
        this.total++;
    }

    public List<TimeSheetEntry> getMissedPunch() {
        return missedPunch;
    }

    public List<TimeSheetEntry> getOvertimeHours() {
        return overtimeHours;
    }

    public List<TimeSheetEntry> getLongInterval() {
        return longInterval;
    }

    public List<TimeSheetEntry> getShortShift() {
        return shortShift;
    }

    public List<TimeSheetEntry> getShortLunch() {
        return shortLunch;
    }

    public List<TimeSheetEntry> getMissedLunch() {
        return missedLunch;
    }

    public int getTotal() {
        return total;
    }

    public Set<TimeSheetEntry> getProblemEntries() {
        return problemEntries;
    }

    public Map<TimeSheetEntry, Integer> getEntryCount() {
        return entryCount;
    }

    public Map<TimeSheetEntry, Integer> getMissedPunchCount() {
        return missedPunchCount;
    }

    public Map<TimeSheetEntry, Integer> getOvertimeHoursCount() {
        return overtimeHoursCount;
    }

    public Map<TimeSheetEntry, Integer> getLongIntervalCount() {
        return longIntervalCount;
    }

    public Map<TimeSheetEntry, Integer> getShortShiftCount() {
        return shortShiftCount;
    }

    public Map<TimeSheetEntry, Integer> getShortLunchCount() {
        return shortLunchCount;
    }

    public Map<TimeSheetEntry, Integer> getMissedLunchCount() {
        return missedLunchCount;
    }

    private void incEntry(TimeSheetEntry entry, Map<TimeSheetEntry, Integer> specificEntryCount){
        // total
        Integer count = entryCount.get(entry);

        if(count == null){
            count = 0;
        }
        count++;

        entryCount.put(entry, count);


        // specific
        count = specificEntryCount.get(entry);

        if(count == null){
            count = 0;
        }
        count++;

        specificEntryCount.put(entry, count);
    }
}
