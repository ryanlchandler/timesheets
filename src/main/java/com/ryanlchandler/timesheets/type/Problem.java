package com.ryanlchandler.timesheets.type;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public enum Problem {


    MISSED_PUNCH,
    OVERTIME_HOURS,
    LONG_INTERVAL,
    SHORT_SHIFT,
    SHORT_LUNCH,
    MISSED_LUNCH;


    public static List<Problem> getProblems(String s){
        List<Problem> problemList = new ArrayList<>();
        String[] possibleValues = StringUtils.split(s, ",");

        for(String possibleValue : possibleValues){
            possibleValue = StringUtils.remove(possibleValue, " ");

            for(Problem p : Problem.values()){
                if(StringUtils.remove(p.name(), "_").equalsIgnoreCase(possibleValue)){
                    problemList.add(p);
                }
            }
        }

        if(problemList.size() == 0){
            problemList.add(MISSED_PUNCH);
        }

        return problemList;
    }
}
