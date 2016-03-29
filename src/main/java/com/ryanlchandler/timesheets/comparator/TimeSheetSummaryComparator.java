package com.ryanlchandler.timesheets.comparator;

import com.ryanlchandler.timesheets.bean.ProblemTime;
import com.ryanlchandler.timesheets.type.Problem;

import java.util.Comparator;
import java.util.List;

public class TimeSheetSummaryComparator implements Comparator<ProblemTime>{

    private final List<Problem> problems;

    public TimeSheetSummaryComparator(List<Problem> problems) {
        this.problems = problems;
    }

    public int compare(ProblemTime o1, ProblemTime o2) {
        if(problems != null){

            int cmpResult = 0;
            for(Problem problem : problems){
                switch (problem){
                    case LONG_INTERVAL:
                        cmpResult =  Integer.compare(o2.getLongInterval().size(), o1.getLongInterval().size());
                        break;
                    case MISSED_LUNCH:
                        cmpResult =  Integer.compare(o2.getMissedLunch().size(), o1.getMissedLunch().size());
                        break;
                    case MISSED_PUNCH:
                        cmpResult =  Integer.compare(o2.getMissedPunch().size(), o1.getMissedPunch().size());
                        break;
                    case OVERTIME_HOURS:
                        cmpResult =  Integer.compare(o2.getOvertimeHours().size(), o1.getOvertimeHours().size());
                        break;
                    case SHORT_LUNCH:
                        cmpResult =  Integer.compare(o2.getShortLunch().size(), o1.getShortLunch().size());
                        break;
                    case SHORT_SHIFT:
                        cmpResult =  Integer.compare(o2.getShortShift().size(), o1.getShortShift().size());
                        break;
                }

                if(cmpResult != 0){
                    return cmpResult;
                }
            }

            return cmpResult;
        }

        return Integer.compare(o2.getProblemEntries().size(), o1.getProblemEntries().size());
    }
}
