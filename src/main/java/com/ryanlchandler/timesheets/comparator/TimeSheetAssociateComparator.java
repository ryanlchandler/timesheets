package com.ryanlchandler.timesheets.comparator;


import com.ryanlchandler.timesheets.bean.ProblemTime;
import com.ryanlchandler.timesheets.bean.TimeSheetEntry;
import com.ryanlchandler.timesheets.type.Problem;

import java.util.Comparator;
import java.util.List;

public class TimeSheetAssociateComparator implements Comparator<TimeSheetEntry>{

    private ProblemTime pt;
    private final List<Problem> problems;

    public TimeSheetAssociateComparator(ProblemTime pt, List<Problem> problems){
        this.pt = pt;
        this.problems = problems;
    }

    public int compare(TimeSheetEntry o1, TimeSheetEntry o2) {
        if(problems != null){

            int cmpResult = 0;
            for(Problem problem : problems){
                switch (problem){
                    case LONG_INTERVAL:
                        cmpResult =  Integer.compare(defaultIfNull(pt.getLongIntervalCount().get(o2)), defaultIfNull(pt.getLongIntervalCount().get(o1)));
                        break;
                    case MISSED_LUNCH:
                        cmpResult =  Integer.compare(defaultIfNull(pt.getMissedLunchCount().get(o2)), defaultIfNull(pt.getMissedLunchCount().get(o1)));
                        break;
                    case MISSED_PUNCH:
                        cmpResult =  Integer.compare(defaultIfNull(pt.getMissedPunchCount().get(o2)), defaultIfNull(pt.getMissedPunchCount().get(o1)));
                        break;
                    case OVERTIME_HOURS:
                        cmpResult =  Integer.compare(defaultIfNull(pt.getOvertimeHoursCount().get(o2)), defaultIfNull(pt.getOvertimeHoursCount().get(o1)));
                        break;
                    case SHORT_LUNCH:
                        cmpResult =  Integer.compare(defaultIfNull(pt.getShortLunchCount().get(o2)), defaultIfNull(pt.getShortLunchCount().get(o1)));
                        break;
                    case SHORT_SHIFT:
                        cmpResult =  Integer.compare(defaultIfNull(pt.getShortShiftCount().get(o2)), defaultIfNull(pt.getShortShiftCount().get(o1)));
                        break;
                }

                if(cmpResult != 0){
                    return cmpResult;
                }
            }
        }

        return Integer.compare(pt.getEntryCount().get(o2), pt.getEntryCount().get(o1));
    }

    public static Integer defaultIfNull(Integer i){
        if(i == null){
            return 0;
        }

        return i;
    }
}
