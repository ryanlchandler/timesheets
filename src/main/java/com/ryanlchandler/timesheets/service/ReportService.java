package com.ryanlchandler.timesheets.service;

import com.ryanlchandler.timesheets.bean.ProblemTime;
import com.ryanlchandler.timesheets.bean.TimeSheetEntry;
import com.ryanlchandler.timesheets.comparator.TimeSheetAssociateComparator;
import com.ryanlchandler.timesheets.comparator.TimeSheetSummaryComparator;
import com.ryanlchandler.timesheets.type.Problem;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

public class ReportService {

    private static final Map<String, String> DEPT_MAP = new HashMap<String, String>();

    static {
        DEPT_MAP.put("016", "RCA");
        DEPT_MAP.put("030", "Dietary");
        DEPT_MAP.put("040", "Activites");
        DEPT_MAP.put("050", "Housekeeping");
        DEPT_MAP.put("060", "Maintenance");
        DEPT_MAP.put("080", "Admin");
    }

    public static String get(Map<String, ProblemTime> map, List<Problem> displayProblems){
        StringBuilder sbud = new StringBuilder(getSummary(map, displayProblems));
        sbud.append("\n\n");

        Map<String, Boolean> deptProblemMap = deptProblemMap(map, displayProblems);

        List<String> depts = new ArrayList<>(map.keySet());
        Collections.sort(depts);

        for(String dept : depts){
            if(deptProblemMap.get(dept)){
                ProblemTime pt = map.get(dept);

                if(pt.getEntryCount().keySet().size() > 0){
                    StringBuilder header = new StringBuilder();
                    header.append(StringUtils.rightPad("Department", 18) + " | " + StringUtils.rightPad("Name", 30) + " | ");
                    StringBuilder underline = new StringBuilder();
                    underline.append("-------------------+--------------------------------+-");

                    for(Problem p : displayProblems){
                        switch (p){
                            case LONG_INTERVAL:
                                header.append(   "Long Interval   | ");
                                underline.append("----------------+-");
                                break;
                            case MISSED_LUNCH:
                                header.append(   "Missed Lunch    | ");
                                underline.append("----------------+-");
                                break;
                            case MISSED_PUNCH:
                                header.append(   "Missed Punch    | ");
                                underline.append("----------------+-");
                                break;
                            case OVERTIME_HOURS:
                                header.append(   "Overtime Hours  | ");
                                underline.append("----------------+-");
                                break;
                            case SHORT_LUNCH:
                                header.append(   "Short Lunch     | ");
                                underline.append("----------------+-");
                                break;
                            case SHORT_SHIFT:
                                header.append(   "Short Shift     | ");
                                underline.append("----------------+-");
                                break;
                        }
                    }

                    List<TimeSheetEntry> entries = new ArrayList<TimeSheetEntry>(pt.getEntryCount().keySet());
                    Collections.sort(entries, new TimeSheetAssociateComparator(pt, displayProblems));

                    StringBuilder body = new StringBuilder();
                    for(TimeSheetEntry entry : entries){


                        if(hasProblem(entry, pt, displayProblems)){
                            body.append(StringUtils.rightPad(getDepartment(dept), 18) + " | " + StringUtils.rightPad(entry.getAssociateName(), 30) + " | ");

                            for(Problem p : displayProblems){
                                switch (p){
                                    case LONG_INTERVAL:
                                        body.append(StringUtils.leftPad(Integer.toString(defaultIfNull(pt.getLongIntervalCount().get(entry))), 15) + " | ");
                                        break;
                                    case MISSED_LUNCH:
                                        body.append(StringUtils.leftPad(Integer.toString(defaultIfNull(pt.getMissedLunchCount().get(entry))), 15) + " | ");
                                        break;
                                    case MISSED_PUNCH:
                                        body.append(StringUtils.leftPad(Integer.toString(defaultIfNull(pt.getMissedPunchCount().get(entry))), 15) + " | ");
                                        break;
                                    case OVERTIME_HOURS:
                                        body.append(StringUtils.leftPad(Integer.toString(defaultIfNull(pt.getOvertimeHoursCount().get(entry))), 15) + " | ");
                                        break;
                                    case SHORT_LUNCH:
                                        body.append(StringUtils.leftPad(Integer.toString(defaultIfNull(pt.getShortLunchCount().get(entry))), 15) + " | ");
                                        break;
                                    case SHORT_SHIFT:
                                        body.append(StringUtils.leftPad(Integer.toString(defaultIfNull(pt.getShortShiftCount().get(entry))), 15) + " | ");
                                        break;
                                }
                            }
                            body.append("\n");
                        }
                    }

                    sbud.append(header.toString()).append("\n").append(underline.toString()).append("\n").append(body.toString());
                    sbud.append("\n\n\n");
                }
            }
        }

        return sbud.toString();
    }

    public static Map<String, Boolean> deptProblemMap(Map<String, ProblemTime> map, List<Problem> displayProblems){
        Map<String, Boolean> deptProblemMap = new HashMap<>();
        for(String dept : map.keySet()){
            deptProblemMap.put(dept, false);
        }

        for(String dept : map.keySet()){
            ProblemTime pt = map.get(dept);

            Set<TimeSheetEntry> entries = pt.getProblemEntries();

            for(TimeSheetEntry entry : entries){
                if(hasProblem(entry, pt, displayProblems)){
                    deptProblemMap.put(dept, true);
                }
            }
        }

        return deptProblemMap;
    }

    public static boolean hasProblem(TimeSheetEntry entry, ProblemTime pt, List<Problem> displayProblems){
        for(Problem p : displayProblems){
            switch (p){
                case LONG_INTERVAL:
                    if(pt.getLongIntervalCount().get(entry) != null){
                        return true;
                    }
                    break;
                case MISSED_LUNCH:
                    if(pt.getMissedLunchCount().get(entry) != null) {
                        return true;
                    }
                    break;
                case MISSED_PUNCH:
                    if(pt.getMissedPunchCount().get(entry) != null) {
                        return true;
                    }
                    break;
                case OVERTIME_HOURS:
                    if(pt.getOvertimeHoursCount().get(entry) != null) {
                        return true;
                    }
                    break;
                case SHORT_LUNCH:
                    if(pt.getShortLunchCount().get(entry) != null) {
                        return true;
                    }
                    break;
                case SHORT_SHIFT:
                    if(pt.getShortShiftCount().get(entry) != null) {
                        return true;
                    }
                    break;
            }
        }

        return false;
    }

    public static Integer defaultIfNull(Integer i){
        if(i == null){
            return 0;
        }

        return i;
    }

    private static String getDepartment(String code){
        if(DEPT_MAP.containsKey(code)){
            return code + " " + DEPT_MAP.get(code);
        }

        return code;
    }

    public static String getSummary(Map<String, ProblemTime> map, List<Problem> displayProblems){
        StringBuilder sbud = new StringBuilder();
        StringBuilder underline = new StringBuilder();

        sbud.append(StringUtils.rightPad("Department", 18));
        underline.append("------------------");

        for(Problem p : displayProblems){
            switch (p){
                case LONG_INTERVAL:
                    sbud.append(     " | Long Interval  ");
                    underline.append("-+----------------");
                    break;
                case MISSED_LUNCH:
                    sbud.append(     " | Missed Lunch   ");
                    underline.append("-+----------------");
                    break;
                case MISSED_PUNCH:
                    sbud.append(     " | Missed Punch   ");
                    underline.append("-+----------------");
                    break;
                case OVERTIME_HOURS:
                    sbud.append(     " | Overtime Hours ");
                    underline.append("-+----------------");
                    break;
                case SHORT_LUNCH:
                    sbud.append(     " | Short Lunch    ");
                    underline.append("-+----------------");
                    break;
                case SHORT_SHIFT:
                    sbud.append(     " | Short Shift    ");
                    underline.append("-+----------------");
                    break;
            }
        }

        sbud.append("\n").append(underline).append("\n");

        List<ProblemTime> pts = new ArrayList<ProblemTime>(map.values());
        Collections.sort(pts, new TimeSheetSummaryComparator(displayProblems));

        for(ProblemTime pt : pts){
            if(pt.getTotal() > 0){
                String dept = pt.getProblemEntries().iterator().next().getDept();

                sbud.append(StringUtils.rightPad(getDepartment(dept), 18));

                for(Problem p : displayProblems) {
                    switch (p) {
                        case LONG_INTERVAL:
                            sbud.append(" | " + StringUtils.leftPad(Integer.toString(pt.getLongInterval().size()), 15));
                            break;
                        case MISSED_LUNCH:
                            sbud.append(" | " + StringUtils.leftPad(Integer.toString(pt.getMissedLunch().size()), 15));
                            break;
                        case MISSED_PUNCH:
                            sbud.append(" | " + StringUtils.leftPad(Integer.toString(pt.getMissedPunch().size()), 15));
                            break;
                        case OVERTIME_HOURS:
                            sbud.append(" | " + StringUtils.leftPad(Integer.toString(pt.getOvertimeHours().size()), 15));
                            break;
                        case SHORT_LUNCH:
                            sbud.append(" | " + StringUtils.leftPad(Integer.toString(pt.getShortLunch().size()), 15));
                            break;
                        case SHORT_SHIFT:
                            sbud.append(" | " + StringUtils.leftPad(Integer.toString(pt.getShortShift().size()), 15));
                            break;
                    }
                }

                sbud.append("\n");
            }
        }

        return sbud.toString();
    }
}
