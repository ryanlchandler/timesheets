package com.ryanlchandler.timesheets.converter;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class ExcelToCSV {

    public static InputStream convert(InputStream excelIn){
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Workbook wb = new HSSFWorkbook(excelIn);
            Sheet sheet = wb.getSheetAt(0);

            for(int r = 0; r < sheet.getLastRowNum(); r++){
                Row row = sheet.getRow(r);
                List<String> columns = rowToCSV(wb, row);
                columns.remove(columns.size() - 1);

                String line = "\"" + StringUtils.remove(StringUtils.remove(StringUtils.join(columns, "\",\""), "\n"), "\r") + "\"\n";
                out.write(line.getBytes());
            }

            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return null;
    }


    private static List<String> rowToCSV(Workbook wb, Row row) {
        DataFormatter formatter = new DataFormatter(true);

        FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

        Cell cell = null;
        int lastCellNum = 0;
        ArrayList<String> csvLine = new ArrayList<String>();

        // Check to ensure that a row was recovered from the sheet as it is
        // possible that one or more rows between other populated rows could be
        // missing - blank. If the row does contain cells then...
        if(row != null) {

            // Get the index for the right most cell on the row and then
            // step along the row from left to right recovering the contents
            // of each cell, converting that into a formatted String and
            // then storing the String into the csvLine ArrayList.
            lastCellNum = row.getLastCellNum();
            for(int i = 0; i <= lastCellNum; i++) {
                cell = row.getCell(i);
                if(cell == null) {
                    csvLine.add("");
                }
                else {
                    if(cell.getCellType() != Cell.CELL_TYPE_FORMULA) {
                        csvLine.add(formatter.formatCellValue(cell));
                    }
                    else {
                        csvLine.add(formatter.formatCellValue(cell, evaluator));
                    }
                }
            }
        }
        return csvLine;
    }
}
