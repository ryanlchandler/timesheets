package com.ryanlchandler.timesheets;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient;
import com.amazonaws.services.simpleemail.model.RawMessage;
import com.amazonaws.services.simpleemail.model.SendRawEmailRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.ThrowableDeserializer;
import com.ryanlchandler.timesheets.bean.ProblemTime;
import com.ryanlchandler.timesheets.bean.TimeSheetEntry;
import com.ryanlchandler.timesheets.comparator.TimeSheetAssociateComparator;
import com.ryanlchandler.timesheets.comparator.TimeSheetSummaryComparator;
import com.ryanlchandler.timesheets.configuration.Config;
import com.ryanlchandler.timesheets.converter.ExcelToCSV;
import com.ryanlchandler.timesheets.converter.SESEventToInputStream;
import com.ryanlchandler.timesheets.model.Event;
import com.ryanlchandler.timesheets.model.SESEvent;
import com.ryanlchandler.timesheets.service.MailService;
import com.ryanlchandler.timesheets.service.ReportService;
import com.ryanlchandler.timesheets.type.Problem;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.supercsv.cellprocessor.Optional;
import org.supercsv.cellprocessor.Trim;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.CsvBeanReader;
import org.supercsv.io.ICsvBeanReader;
import org.supercsv.prefs.CsvPreference;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.*;

public class TimesheetsCSV {

    private static final String FROM_ARN = Config.fromArn;
    private static final String EMAIL_FROM = Config.emailFrom;
    private static final String ACCESS_KEY = Config.accessKey;
    private static final String SECRET_KEY = Config.secretKey;

    public static void main(String[] args) {
        try{
            Session session = Session.getDefaultInstance(new Properties());
            Message msg = new MimeMessage(session, new ByteArrayInputStream(TEST_EMAIL.getBytes()));

            List<File> attachments = new ArrayList<File>();
            Multipart multipart = (MimeMultipart)msg.getContent();

            InputStream is = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                if (!Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition()) &&
                        !StringUtils.isNotBlank(bodyPart.getFileName())) {
                    continue; // dealing with attachments only
                }

                is = ExcelToCSV.convert(bodyPart.getInputStream());
            }

            // get problems from attachment
            Map<String, ProblemTime> problemMap = getProblemMap(is);

            // get report
            String report = ReportService.get(problemMap, Problem.getProblems("missed punch"));

            System.out.println(report);
        }catch(Throwable t){
            t.printStackTrace();
        }
    }

    public void handle(SNSEvent event, Context context){
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            // parse event
            SESEvent ses = objectMapper.readValue(event.getRecords().get(0).getSNS().getMessage(), SESEvent.class);

            // get problems from attachment
            Map<String, ProblemTime> problemMap = getProblemMap(SESEventToInputStream.convert(ses));

            // get report
            String report = ReportService.get(problemMap, Problem.getProblems(ses.getMail().getCommonHeaders().getSubject()));

            // send email
            MailService.send(ses.getMail().getCommonHeaders().getFrom().get(0), EMAIL_FROM, FROM_ARN, "Re: " + ses.getMail().getCommonHeaders().getSubject(), report, ACCESS_KEY, SECRET_KEY);

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static Map<String, ProblemTime> getProblemMap(InputStream in){
        Map<String, ProblemTime> map = new HashMap();

        ICsvBeanReader beanReader = null;
        FileOutputStream fout = null;
        try {
            beanReader = new CsvBeanReader(new InputStreamReader(in), CsvPreference.STANDARD_PREFERENCE);

            // the header elements are used to map the values to the bean (names must match)
            final String[] header = {"CommCode", "AssociateName", "AssociateID", "Dept", "MissedPunch", "OvertimeHours", "LongInterval", "ShortShift", "ShortLunch", "MissedLunch"};
            final CellProcessor[] processors = getProcessors();

            TimeSheetEntry timeSheetEntry;
            int invalidRowCount = 0;

            while ((timeSheetEntry = beanReader.read(TimeSheetEntry.class, header, processors)) != null) {
                try {
                    ProblemTime pt = map.get(timeSheetEntry.getDept());

                    if (pt == null) {
                        pt = new ProblemTime();
                    }

                    if (hasProblem(timeSheetEntry.getMissedPunch())) {
                        pt.incMissedPunch(timeSheetEntry);
                    }

                    if (hasProblem(timeSheetEntry.getMissedLunch())) {
                        pt.incMissedLunch(timeSheetEntry);
                    }

                    if (hasProblem(timeSheetEntry.getOvertimeHours())) {
                        pt.incOvertimeHours(timeSheetEntry);
                    }

                    if (hasProblem(timeSheetEntry.getLongInterval())) {
                        pt.incLongInterval(timeSheetEntry);
                    }

                    if (hasProblem(timeSheetEntry.getShortShift())) {
                        pt.incShortShift(timeSheetEntry);
                    }

                    if (hasProblem(timeSheetEntry.getShortLunch())) {
                        pt.incShortLunch(timeSheetEntry);
                    }

                    if (hasProblem(timeSheetEntry.getMissedLunch())) {
                        pt.incMissedLunch(timeSheetEntry);
                    }

                    map.put(timeSheetEntry.getDept(), pt);
                } catch (IllegalArgumentException e) {
                    invalidRowCount++;
                }
            }
        }catch (Throwable e) {
            e.printStackTrace();
        }finally {
            try{
                if( beanReader != null ) {
                    beanReader.close();
                }

                if(fout != null){
                    fout.close();
                }
            }catch (Throwable e) {
                e.printStackTrace();
            }
        }

        return map;
    }

    private static boolean hasProblem(String value){
        if(StringUtils.isNotBlank(value)){
            return "x".equalsIgnoreCase(value);
        }

        return false;
    }

    private static BigDecimal checkScore(BigDecimal score){
        if(score.compareTo(new BigDecimal(0)) < 0 || score.compareTo(new BigDecimal(100)) > 0){
            throw new IllegalArgumentException("invalid score: " + score);
        }

        return score;
    }

    private static CellProcessor[] getProcessors() {

        final CellProcessor[] processors = new CellProcessor[] {
                new Optional(new Trim()),
                new Optional(new Trim()),
                new Optional(new Trim()),
                new Optional(new Trim()),
                new Optional(new Trim()),
                new Optional(new Trim()),
                new Optional(new Trim()),
                new Optional(new Trim()),
                new Optional(new Trim()),
                new Optional(new Trim())
        };

        return processors;
    }

    public static final String TEST_EMAIL = "Return-Path: <ryanlchandler@gmail.com>\n" +
            "Received: from mail-wm0-f45.google.com (mail-wm0-f45.google.com [74.125.82.45])\n" +
            " by inbound-smtp.us-east-1.amazonaws.com with SMTP id tf0246nvif4eu88c8hr0aso6ts762l2kfef0ocg1\n" +
            " for timesheets@ryanlchandler.com;\n" +
            " Mon, 28 Mar 2016 01:16:35 +0000 (UTC)\n" +
            "X-SES-Spam-Verdict: PASS\n" +
            "X-SES-Virus-Verdict: PASS\n" +
            "Received-SPF: pass (spfCheck: domain of _spf.google.com designates 74.125.82.45 as permitted sender) client-ip=74.125.82.45; envelope-from=ryanlchandler@gmail.com; helo=mail-wm0-f45.google.com;\n" +
            "Authentication-Results: amazonses.com;\n" +
            " spf=pass (spfCheck: domain of _spf.google.com designates 74.125.82.45 as permitted sender) client-ip=74.125.82.45; envelope-from=ryanlchandler@gmail.com; helo=mail-wm0-f45.google.com;\n" +
            " dkim=pass header.i=@gmail.com;\n" +
            "Received: by mail-wm0-f45.google.com with SMTP id r72so1872109wmg.0\n" +
            "        for <timesheets@ryanlchandler.com>; Sun, 27 Mar 2016 18:16:35 -0700 (PDT)\n" +
            "DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed;\n" +
            "        d=gmail.com; s=20120113;\n" +
            "        h=mime-version:date:message-id:subject:from:to;\n" +
            "        bh=2raitJiDSs2njKiOB7+kYsDvR6hkSBHriXERJxusZ2Y=;\n" +
            "        b=uex5Nwe5za6smY/NciV3jK8pnUnTxkzNipKcHAqsh+TiFViM0FRphUYAcE/bpWC5Ai\n" +
            "         LgHWfAkccbsmfmGK/De+UPOmjuiQmYR5SLFoaDg+tWr115B5cv+DMssglwMhFbVuTgfD\n" +
            "         phl/KinjSnzg3DHywsGIxqUrSYF3mgZ3xrcYfi7BNthLOCHTT1m0tLWmjlVj1WlRIPx6\n" +
            "         mhb76V3QR7Mei2SxvFVvJlkQVOQJE09/OGCVDLkxeKF1DTPGXqEW4QFAi9YUpRonfNs/\n" +
            "         yHOzs1vv8OVwLNsJ785XM+E9N0hfL6vHAcz8JdOMni4wWtjXlg6hb2gciiQ4nBhCFFqa\n" +
            "         MmHw==\n" +
            "X-Google-DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed;\n" +
            "        d=1e100.net; s=20130820;\n" +
            "        h=x-gm-message-state:mime-version:date:message-id:subject:from:to;\n" +
            "        bh=2raitJiDSs2njKiOB7+kYsDvR6hkSBHriXERJxusZ2Y=;\n" +
            "        b=AXyZrRFMF+d80FcwawUOYYna7EtcyfNIVLLya0c2MMdOuBe71LVkECgxjSXxdFcvCo\n" +
            "         oPTKYK8StrNtO8MirUcHcbezn/IfmuUEV+TW9nsmcyQQhGBuPWNg51Z/kzO5OYt42+XC\n" +
            "         e1nw56F2A3fJSjNjnJFmT7xDfTUbT0nO8PQKI/ncOhX5EvMadYe3hNCzigSujZ7/xPEp\n" +
            "         FOIrIiHAi+TqI0g1U6c9uTBHHs80LMsoL8FlXklpdB9eMt84dd6RC1mvNkwx6ZxMkW+C\n" +
            "         YBWY1ON3PhSuzV0+0MkPZlUzflJW/9fbzd98mR34rQX05Lssl7soyE+tKeFExTaepMJ2\n" +
            "         +M8A==\n" +
            "X-Gm-Message-State: AD7BkJKoP1AFC/yE6b2RPZu7rWqbh86CqoAwhETSQ4Hke6WzoB6SVur7f58S1DMbeVIfNeeHob2fyOIHFf407Q==\n" +
            "MIME-Version: 1.0\n" +
            "X-Received: by 10.28.172.194 with SMTP id v185mr7719837wme.21.1459127794506;\n" +
            " Sun, 27 Mar 2016 18:16:34 -0700 (PDT)\n" +
            "Received: by 10.28.59.213 with HTTP; Sun, 27 Mar 2016 18:16:34 -0700 (PDT)\n" +
            "Date: Sun, 27 Mar 2016 21:16:34 -0400\n" +
            "Message-ID: <CAJhb=Yt=kKr-XLMWkvocsJSWPHC_L9SW2AejedDfV-=uWfi9_A@mail.gmail.com>\n" +
            "Subject: test\n" +
            "From: Ryan Chandler <ryanlchandler@gmail.com>\n" +
            "To: timesheets@ryanlchandler.com\n" +
            "Content-Type: multipart/mixed; boundary=001a1141ec7edfc1f6052f11a9d4\n" +
            "\n" +
            "--001a1141ec7edfc1f6052f11a9d4\n" +
            "Content-Type: multipart/alternative; boundary=001a1141ec7edfc1ef052f11a9d2\n" +
            "\n" +
            "--001a1141ec7edfc1ef052f11a9d2\n" +
            "Content-Type: text/plain; charset=UTF-8\n" +
            "\n" +
            "test\n" +
            "\n" +
            "--001a1141ec7edfc1ef052f11a9d2\n" +
            "Content-Type: text/html; charset=UTF-8\n" +
            "\n" +
            "<div dir=\"ltr\">test</div>\n" +
            "\n" +
            "--001a1141ec7edfc1ef052f11a9d2--\n" +
            "--001a1141ec7edfc1f6052f11a9d4\n" +
            "Content-Type: application/vnd.ms-excel; name=\"Active-Timecards.xls\"\n" +
            "Content-Disposition: attachment; filename=\"Active-Timecards.xls\"\n" +
            "Content-Transfer-Encoding: base64\n" +
            "X-Attachment-Id: f_imbaziqb0\n" +
            "\n" +
            "0M8R4KGxGuEAAAAAAAAAAAAAAAAAAAAAPgADAP7/CQAGAAAAAAAAAAAAAAABAAAAAQAAAAAAAAAA\n" +
            "EAAANAAAAAEAAAD+////AAAAAAAAAAD/////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "///////////////////////////////////////////////////////////////////////////9\n" +
            "/////v///wMAAAAEAAAABQAAAAYAAAAHAAAACAAAAAkAAAAKAAAACwAAAAwAAAANAAAADgAAAA8A\n" +
            "AAAQAAAAEQAAABIAAAATAAAAFAAAABUAAAAWAAAAFwAAABgAAAAZAAAAGgAAABsAAAAcAAAAHQAA\n" +
            "AB4AAAAfAAAAIAAAACEAAAAiAAAAIwAAACQAAAAlAAAAJgAAACcAAAAoAAAAKQAAACoAAAArAAAA\n" +
            "LAAAAC0AAAAuAAAALwAAADAAAAAxAAAAMgAAADMAAAD+/////v////7/////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "/////////////////////////////////////////////////////////////////////////1IA\n" +
            "bwBvAHQAIABFAG4AdAByAHkAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
            "AAAAAAAWAAUA//////////8CAAAAIAgCAAAAAADAAAAAAAAARgAAAAAAAAAAAAAAAPACleKkhtEB\n" +
            "NQAAAMABAAAAAAAAVwBvAHIAawBiAG8AbwBrAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
            "AAAAAAAAAAAAAAAAAAAAAAAAABIAAgD///////////////8AAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
            "AAAAAAAAAAAAAAAAAAACAAAAA2MAAAAAAAAFAFMAdQBtAG0AYQByAHkASQBuAGYAbwByAG0AYQB0\n" +
            "AGkAbwBuAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAKAACAQEAAAADAAAA/////wAAAAAAAAAA\n" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACMAAAAAAAAAAUARABvAGMAdQBtAGUAbgB0\n" +
            "AFMAdQBtAG0AYQByAHkASQBuAGYAbwByAG0AYQB0AGkAbwBuAAAAAAAAAAAAAAA4AAIA////////\n" +
            "////////AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwAAAOAAAAAAAAAACQgQ\n" +
            "AAAGBQBnMs0HyYABAAYGAADhAAIAsATBAAIAAADiAAAAXABwAA0AAEFkbWluaXN0cmF0b3IgICAg\n" +
            "ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg\n" +
            "ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICBCAAIAsARhAQIAAADAAQAAPQECAAEA\n" +
            "nAACABEAGQACAAAAEgACAAAAEwACAAAArwECAAAAvAECAAAAPQASAGgBDgFcOr4jOAAAAAAAAQBY\n" +
            "AkAAAgAAAI0AAgAAACIAAgAAAA4AAgABALcBAgAAANoAAgAAADEAGgDIAAAA/3+QAQAAAAAA/wUB\n" +
            "QQByAGkAYQBsADEAGgDIAAAA/3+QAQAAAAAA/wUBQQByAGkAYQBsADEAGgDIAAAA/3+QAQAAAAAA\n" +
            "/wUBQQByAGkAYQBsADEAGgDIAAAA/3+QAQAAAAAA/wUBQQByAGkAYQBsADEAGgDIAAEA/3+8AgAA\n" +
            "AAAA/wUBQQByAGkAYQBsADEAHgBoAQEAOAC8AgAAAAIAAAcBQwBhAG0AYgByAGkAYQAxAB4ALAEB\n" +
            "ADgAvAIAAAACAAAHAUMAYQBsAGkAYgByAGkAMQAeAAQBAQA4ALwCAAAAAgAABwFDAGEAbABpAGIA\n" +
            "cgBpADEAHgDcAAEAOAC8AgAAAAIAAAcBQwBhAGwAaQBiAHIAaQAxAB4A3AAAABEAkAEAAAACAAAH\n" +
            "AUMAYQBsAGkAYgByAGkAMQAeANwAAAAUAJABAAAAAgAABwFDAGEAbABpAGIAcgBpADEAHgDcAAAA\n" +
            "PACQAQAAAAIAAAcBQwBhAGwAaQBiAHIAaQAxAB4A3AAAAD4AkAEAAAACAAAHAUMAYQBsAGkAYgBy\n" +
            "AGkAMQAeANwAAQA/ALwCAAAAAgAABwFDAGEAbABpAGIAcgBpADEAHgDcAAEANAC8AgAAAAIAAAcB\n" +
            "QwBhAGwAaQBiAHIAaQAxAB4A3AAAADQAkAEAAAACAAAHAUMAYQBsAGkAYgByAGkAMQAeANwAAQAJ\n" +
            "ALwCAAAAAgAABwFDAGEAbABpAGIAcgBpADEAHgDcAAAACgCQAQAAAAIAAAcBQwBhAGwAaQBiAHIA\n" +
            "aQAxABoAyAAAAP9/kAEAAAAAAAAFAUEAcgBpAGEAbAAxAB4A3AACABcAkAEAAAACAAAHAUMAYQBs\n" +
            "AGkAYgByAGkAMQAeANwAAQAIALwCAAAAAgAABwFDAGEAbABpAGIAcgBpADEAHgDcAAAACQCQAQAA\n" +
            "AAIAAAcBQwBhAGwAaQBiAHIAaQAxAB4A3AAAAAgAkAEAAAACAAAHAUMAYQBsAGkAYgByAGkAHgQc\n" +
            "AAUAFwAAIiQiIywjIzBfKTtcKCIkIiMsIyMwXCkeBCEABgAcAAAiJCIjLCMjMF8pO1tSZWRdXCgi\n" +
            "JCIjLCMjMFwpHgQiAAcAHQAAIiQiIywjIzAuMDBfKTtcKCIkIiMsIyMwLjAwXCkeBCcACAAiAAAi\n" +
            "JCIjLCMjMC4wMF8pO1tSZWRdXCgiJCIjLCMjMC4wMFwpHgQ3ACoAMgAAXygiJCIqICMsIyMwXyk7\n" +
            "XygiJCIqIFwoIywjIzBcKTtfKCIkIiogIi0iXyk7XyhAXykeBC4AKQApAABfKCogIywjIzBfKTtf\n" +
            "KCogXCgjLCMjMFwpO18oKiAiLSJfKTtfKEBfKR4EPwAsADoAAF8oIiQiKiAjLCMjMC4wMF8pO18o\n" +
            "IiQiKiBcKCMsIyMwLjAwXCk7XygiJCIqICItIj8/Xyk7XyhAXykeBDYAKwAxAABfKCogIywjIzAu\n" +
            "MDBfKTtfKCogXCgjLCMjMC4wMFwpO18oKiAiLSI/P18pO18oQF8p4AAUAAAAAAD1/yAAAAAAAAAA\n" +
            "AAAAAMAg4AAUAAEAAAD1/yAAAPQAAAAAAAAAAMAg4AAUAAEAAAD1/yAAAPQAAAAAAAAAAMAg4AAU\n" +
            "AAIAAAD1/yAAAPQAAAAAAAAAAMAg4AAUAAIAAAD1/yAAAPQAAAAAAAAAAMAg4AAUAAAAAAD1/yAA\n" +
            "APQAAAAAAAAAAMAg4AAUAAAAAAD1/yAAAPQAAAAAAAAAAMAg4AAUAAAAAAD1/yAAAPQAAAAAAAAA\n" +
            "AMAg4AAUAAAAAAD1/yAAAPQAAAAAAAAAAMAg4AAUAAAAAAD1/yAAAPQAAAAAAAAAAMAg4AAUAAAA\n" +
            "AAD1/yAAAPQAAAAAAAAAAMAg4AAUAAAAAAD1/yAAAPQAAAAAAAAAAMAg4AAUAAAAAAD1/yAAAPQA\n" +
            "AAAAAAAAAMAg4AAUAAAAAAD1/yAAAPQAAAAAAAAAAMAg4AAUAAAAAAD1/yAAAPQAAAAAAAAAAMAg\n" +
            "4AAUAAAAAAABACAAAAAAAAAAAAAAAMAg4AAUABcAAAD1/yAAALQAAAAAAAAABJ8g4AAUABcAAAD1\n" +
            "/yAAALQAAAAAAAAABK0g4AAUABcAAAD1/yAAALQAAAAAAAAABKog4AAUABcAAAD1/yAAALQAAAAA\n" +
            "AAAABK4g4AAUABcAAAD1/yAAALQAAAAAAAAABJsg4AAUABcAAAD1/yAAALQAAAAAAAAABK8g4AAU\n" +
            "ABcAAAD1/yAAALQAAAAAAAAABKwg4AAUABcAAAD1/yAAALQAAAAAAAAABJ0g4AAUABcAAAD1/yAA\n" +
            "ALQAAAAAAAAABIsg4AAUABcAAAD1/yAAALQAAAAAAAAABK4g4AAUABcAAAD1/yAAALQAAAAAAAAA\n" +
            "BKwg4AAUABcAAAD1/yAAALQAAAAAAAAABLMg4AAUABYAAAD1/yAAALQAAAAAAAAABJ4g4AAUABYA\n" +
            "AAD1/yAAALQAAAAAAAAABJ0g4AAUABYAAAD1/yAAALQAAAAAAAAABIsg4AAUABYAAAD1/yAAALQA\n" +
            "AAAAAAAABKQg4AAUABYAAAD1/yAAALQAAAAAAAAABLEg4AAUABYAAAD1/yAAALQAAAAAAAAABLQg\n" +
            "4AAUABYAAAD1/yAAALQAAAAAAAAABL4g4AAUABYAAAD1/yAAALQAAAAAAAAABIog4AAUABYAAAD1\n" +
            "/yAAALQAAAAAAAAABLkg4AAUABYAAAD1/yAAALQAAAAAAAAABKQg4AAUABYAAAD1/yAAALQAAAAA\n" +
            "AAAABLEg4AAUABYAAAD1/yAAALQAAAAAAAAABLUg4AAUAAsAAAD1/yAAALQAAAAAAAAABK0g4AAU\n" +
            "AA8AAAD1/yAAAJQREZcLlwsABJYg4AAUABEAAAD1/yAAAJRmZr8fvx8ABLcg4AAUAAEAKwD1/yAA\n" +
            "APgAAAAAAAAAAMAg4AAUAAEAKgD1/yAAAPgAAAAAAAAAAMAg4AAUAAEALAD1/yAAAPgAAAAAAAAA\n" +
            "AMAg4AAUAAEAKQD1/yAAAPgAAAAAAAAAAMAg4AAUABQAAAD1/yAAAPQAAAAAAAAAAMAg4AAUAAoA\n" +
            "AAD1/yAAALQAAAAAAAAABKog4AAUAAcAAAD1/yAAANQAUAAAAB8AAMAg4AAUAAgAAAD1/yAAANQA\n" +
            "UAAAAAsAAMAg4AAUAAkAAAD1/yAAANQAIAAAAA8AAMAg4AAUAAkAAAD1/yAAAPQAAAAAAAAAAMAg\n" +
            "4AAUAA0AAAD1/yAAAJQREZcLlwsABK8g4AAUABAAAAD1/yAAANQAYAAAABoAAMAg4AAUAAwAAAD1\n" +
            "/yAAALQAAAAAAAAABKsg4AAUABMAAAD1/yAAAJwRERYLFgsABJog4AAUAA4AAAD1/yAAAJQREb8f\n" +
            "vx8ABJYg4AAUAAEACQD1/yAAAPgAAAAAAAAAAMAg4AAUAAYAAAD1/yAAAPQAAAAAAAAAAMAg4AAU\n" +
            "ABUAAAD1/yAAANQAYQAAPh8AAMAg4AAUABIAAAD1/yAAAPQAAAAAAAAAAMAg4AAUAAUAAAABABoA\n" +
            "ABgAAAAAAAAAAMAgfAgUAHwIAAAAAAAAAAAAAAAAPwCoICS/fQgtAH0IAAAAAAAAAAAAAAAAOwAA\n" +
            "AAIADQAUAAMAAAADAAAAMDBcKTtfKCoOAAUAAX0IQQB9CAAAAAAAAAAAAAAAADEAAAADAA0AFAAD\n" +
            "AAAAAwAAADAwXCk7XygqDgAFAAIIABQAAwAAAAQAAAA7XyhAXykgIH0IQQB9CAAAAAAAAAAAAAAA\n" +
            "ADIAAAADAA0AFAADAAAAAwAAADAwXCk7XygqDgAFAAIIABQAAwD/PwQAAAA7XyhAXykgIH0IQQB9\n" +
            "CAAAAAAAAAAAAAAAADMAAAADAA0AFAADAAAAAwAAADAwXCk7XygqDgAFAAIIABQAAwAyMwQAAAA7\n" +
            "XyhAXykgIH0ILQB9CAAAAAAAAAAAAAAAADQAAAACAA0AFAADAAAAAwAAADAwXCk7XygqDgAFAAJ9\n" +
            "CEEAfQgAAAAAAAAAAAAAAAAwAAAAAwANABQAAgAAAABhAP8wMFwpO18oKg4ABQACBAAUAAIAAADG\n" +
            "787/O18oQF8pICB9CEEAfQgAAAAAAAAAAAAAAAAoAAAAAwANABQAAgAAAJwABv8wMFwpO18oKg4A\n" +
            "BQACBAAUAAIAAAD/x87/O18oQF8pICB9CEEAfQgAAAAAAAAAAAAAAAA3AAAAAwANABQAAgAAAJxl\n" +
            "AP8wMFwpO18oKg4ABQACBAAUAAIAAAD/65z/O18oQF8pICB9CJEAfQgAAAAAAAAAAAAAAAA1AAAA\n" +
            "BwANABQAAgAAAD8/dv8wMFwpO18oKg4ABQACBAAUAAIAAAD/zJn/O18oQF8pICAHABQAAgAAAH9/\n" +
            "f/8gICAgICAgIAgAFAACAAAAf39//yAgICAgICAgCQAUAAIAAAB/f3//AAAAAAAAAAAKABQAAgAA\n" +
            "AH9/f/8AAAAAAAAAAH0IkQB9CAAAAAAAAAAAAAAAADkAAAAHAA0AFAACAAAAPz8//zAwXCk7Xygq\n" +
            "DgAFAAIEABQAAgAAAPLy8v87XyhAXykgIAcAFAACAAAAPz8//yAgICAgICAgCAAUAAIAAAA/Pz//\n" +
            "ICAgICAgICAJABQAAgAAAD8/P/8AAAAAAAAAAAoAFAACAAAAPz8//wAAAAAAAAAAfQiRAH0IAAAA\n" +
            "AAAAAAAAAAAAKQAAAAcADQAUAAIAAAD6fQD/MDBcKTtfKCoOAAUAAgQAFAACAAAA8vLy/ztfKEBf\n" +
            "KSAgBwAUAAIAAAB/f3//ICAgICAgICAIABQAAgAAAH9/f/8gICAgICAgIAkAFAACAAAAf39//wAA\n" +
            "AAAAAAAACgAUAAIAAAB/f3//AAAAAAAAAAB9CEEAfQgAAAAAAAAAAAAAAAA2AAAAAwANABQAAgAA\n" +
            "APp9AP8wMFwpO18oKg4ABQACCAAUAAIAAAD/gAH/O18oQF8pICB9CJEAfQgAAAAAAAAAAAAAAAAq\n" +
            "AAAABwANABQAAwAAAAAAAAAwMFwpO18oKg4ABQACBAAUAAIAAAClpaX/O18oQF8pICAHABQAAgAA\n" +
            "AD8/P/8gICAgICAgIAgAFAACAAAAPz8//yAgICAgICAgCQAUAAIAAAA/Pz//AAAAAAAAAAAKABQA\n" +
            "AgAAAD8/P/8AAAAAAAAAAH0ILQB9CAAAAAAAAAAAAAAAAD0AAAACAA0AFAACAAAA/wAA/zAwXCk7\n" +
            "XygqDgAFAAJ9CHgAfQgAAAAAAAAAAAAAAAA4AAAABQAEABQAAgAAAP//zP8wMFwpO18oKgcAFAAC\n" +
            "AAAAsrKy/wClpaX/O18oCAAUAAIAAACysrL/AD8/P/8gICAJABQAAgAAALKysv8APz8//yAgIAoA\n" +
            "FAACAAAAsrKy/wA/Pz//AAAAfQgtAH0IAAAAAAAAAAAAAAAALwAAAAIADQAUAAIAAAB/f3//MDBc\n" +
            "KTtfKCoOAAUAAn0IVQB9CAAAAAAAAAAAAAAAADwAAAAEAA0AFAADAAAAAQAAADAwXCk7XygqDgAF\n" +
            "AAIHABQAAwAAAAQAAAA7XygIABQAAggAFAADAAAABAAAACAgIAkAFAACfQhBAH0IAAAAAAAAAAAA\n" +
            "AAAAIgAAAAMADQAUAAMAAAAAAAAAMDBcKTtfKCoOAAUAAgQAFAADAAAABAAAADtfKAgAFAACfQhB\n" +
            "AH0IAAAAAAAAAAAAAAAAEAAAAAMADQAUAAMAAAABAAAAMDBcKTtfKCoOAAUAAgQAFAADAGVmBAAA\n" +
            "ADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAFgAAAAMADQAUAAMAAAABAAAAMDBcKTtfKCoOAAUA\n" +
            "AgQAFAADAMxMBAAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAHAAAAAMADQAUAAMAAAAAAAAA\n" +
            "MDBcKTtfKCoOAAUAAgQAFAADADIzBAAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAIwAAAAMA\n" +
            "DQAUAAMAAAAAAAAAMDBcKTtfKCoOAAUAAgQAFAADAAAABQAAADtfKAgAFAACfQhBAH0IAAAAAAAA\n" +
            "AAAAAAAAEQAAAAMADQAUAAMAAAABAAAAMDBcKTtfKCoOAAUAAgQAFAADAGVmBQAAADtfKAgAFAAC\n" +
            "fQhBAH0IAAAAAAAAAAAAAAAAFwAAAAMADQAUAAMAAAABAAAAMDBcKTtfKCoOAAUAAgQAFAADAMxM\n" +
            "BQAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAHQAAAAMADQAUAAMAAAAAAAAAMDBcKTtfKCoO\n" +
            "AAUAAgQAFAADADIzBQAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAJAAAAAMADQAUAAMAAAAA\n" +
            "AAAAMDBcKTtfKCoOAAUAAgQAFAADAAAABgAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAEgAA\n" +
            "AAMADQAUAAMAAAABAAAAMDBcKTtfKCoOAAUAAgQAFAADAGVmBgAAADtfKAgAFAACfQhBAH0IAAAA\n" +
            "AAAAAAAAAAAAGAAAAAMADQAUAAMAAAABAAAAMDBcKTtfKCoOAAUAAgQAFAADAMxMBgAAADtfKAgA\n" +
            "FAACfQhBAH0IAAAAAAAAAAAAAAAAHgAAAAMADQAUAAMAAAAAAAAAMDBcKTtfKCoOAAUAAgQAFAAD\n" +
            "ADIzBgAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAJQAAAAMADQAUAAMAAAAAAAAAMDBcKTtf\n" +
            "KCoOAAUAAgQAFAADAAAABwAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAEwAAAAMADQAUAAMA\n" +
            "AAABAAAAMDBcKTtfKCoOAAUAAgQAFAADAGVmBwAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAA\n" +
            "GQAAAAMADQAUAAMAAAABAAAAMDBcKTtfKCoOAAUAAgQAFAADAMxMBwAAADtfKAgAFAACfQhBAH0I\n" +
            "AAAAAAAAAAAAAAAAHwAAAAMADQAUAAMAAAAAAAAAMDBcKTtfKCoOAAUAAgQAFAADADIzBwAAADtf\n" +
            "KAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAJgAAAAMADQAUAAMAAAAAAAAAMDBcKTtfKCoOAAUAAgQA\n" +
            "FAADAAAACAAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAFAAAAAMADQAUAAMAAAABAAAAMDBc\n" +
            "KTtfKCoOAAUAAgQAFAADAGVmCAAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAGgAAAAMADQAU\n" +
            "AAMAAAABAAAAMDBcKTtfKCoOAAUAAgQAFAADAMxMCAAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAA\n" +
            "AAAAIAAAAAMADQAUAAMAAAAAAAAAMDBcKTtfKCoOAAUAAgQAFAADADIzCAAAADtfKAgAFAACfQhB\n" +
            "AH0IAAAAAAAAAAAAAAAAJwAAAAMADQAUAAMAAAAAAAAAMDBcKTtfKCoOAAUAAgQAFAADAAAACQAA\n" +
            "ADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAFQAAAAMADQAUAAMAAAABAAAAMDBcKTtfKCoOAAUA\n" +
            "AgQAFAADAGVmCQAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAGwAAAAMADQAUAAMAAAABAAAA\n" +
            "MDBcKTtfKCoOAAUAAgQAFAADAMxMCQAAADtfKAgAFAACfQhBAH0IAAAAAAAAAAAAAAAAIQAAAAMA\n" +
            "DQAUAAMAAAAAAAAAMDBcKTtfKCoOAAUAAgQAFAADADIzCQAAADtfKAgAFAACkwISABAADQAAMjAl\n" +
            "IC0gQWNjZW50MZIITQCSCAAAAAAAAAAAAAABBB7/DQAyADAAJQAgAC0AIABBAGMAYwBlAG4AdAAx\n" +
            "AAAAAwABAAwABwRlZtzm8f8FAAwABwEAAAAAAP8lAAUAApMCEgARAA0AADIwJSAtIEFjY2VudDKS\n" +
            "CE0AkggAAAAAAAAAAAAAAQQi/w0AMgAwACUAIAAtACAAQQBjAGMAZQBuAHQAMgAAAAMAAQAMAAcF\n" +
            "ZWby3Nv/BQAMAAcBAAAAAAD/JQAFAAKTAhIAEgANAAAyMCUgLSBBY2NlbnQzkghNAJIIAAAAAAAA\n" +
            "AAAAAAEEJv8NADIAMAAlACAALQAgAEEAYwBjAGUAbgB0ADMAAAADAAEADAAHBmVm6/He/wUADAAH\n" +
            "AQAAAAAA/yUABQACkwISABMADQAAMjAlIC0gQWNjZW50NJIITQCSCAAAAAAAAAAAAAABBCr/DQAy\n" +
            "ADAAJQAgAC0AIABBAGMAYwBlAG4AdAA0AAAAAwABAAwABwdlZuTf7P8FAAwABwEAAAAAAP8lAAUA\n" +
            "ApMCEgAUAA0AADIwJSAtIEFjY2VudDWSCE0AkggAAAAAAAAAAAAAAQQu/w0AMgAwACUAIAAtACAA\n" +
            "QQBjAGMAZQBuAHQANQAAAAMAAQAMAAcIZWba7vP/BQAMAAcBAAAAAAD/JQAFAAKTAhIAFQANAAAy\n" +
            "MCUgLSBBY2NlbnQ2kghNAJIIAAAAAAAAAAAAAAEEMv8NADIAMAAlACAALQAgAEEAYwBjAGUAbgB0\n" +
            "ADYAAAADAAEADAAHCWVm/enZ/wUADAAHAQAAAAAA/yUABQACkwISABYADQAANDAlIC0gQWNjZW50\n" +
            "MZIITQCSCAAAAAAAAAAAAAABBB//DQA0ADAAJQAgAC0AIABBAGMAYwBlAG4AdAAxAAAAAwABAAwA\n" +
            "BwTMTLjM5P8FAAwABwEAAAAAAP8lAAUAApMCEgAXAA0AADQwJSAtIEFjY2VudDKSCE0AkggAAAAA\n" +
            "AAAAAAAAAQQj/w0ANAAwACUAIAAtACAAQQBjAGMAZQBuAHQAMgAAAAMAAQAMAAcFzEzmuLf/BQAM\n" +
            "AAcBAAAAAAD/JQAFAAKTAhIAGAANAAA0MCUgLSBBY2NlbnQzkghNAJIIAAAAAAAAAAAAAAEEJ/8N\n" +
            "ADQAMAAlACAALQAgAEEAYwBjAGUAbgB0ADMAAAADAAEADAAHBsxM2OS8/wUADAAHAQAAAAAA/yUA\n" +
            "BQACkwISABkADQAANDAlIC0gQWNjZW50NJIITQCSCAAAAAAAAAAAAAABBCv/DQA0ADAAJQAgAC0A\n" +
            "IABBAGMAYwBlAG4AdAA0AAAAAwABAAwABwfMTMzA2v8FAAwABwEAAAAAAP8lAAUAApMCEgAaAA0A\n" +
            "ADQwJSAtIEFjY2VudDWSCE0AkggAAAAAAAAAAAAAAQQv/w0ANAAwACUAIAAtACAAQQBjAGMAZQBu\n" +
            "AHQANQAAAAMAAQAMAAcIzEy33uj/BQAMAAcBAAAAAAD/JQAFAAKTAhIAGwANAAA0MCUgLSBBY2Nl\n" +
            "bnQ2kghNAJIIAAAAAAAAAAAAAAEEM/8NADQAMAAlACAALQAgAEEAYwBjAGUAbgB0ADYAAAADAAEA\n" +
            "DAAHCcxM/NW0/wUADAAHAQAAAAAA/yUABQACkwISABwADQAANjAlIC0gQWNjZW50MZIITQCSCAAA\n" +
            "AAAAAAAAAAABBCD/DQA2ADAAJQAgAC0AIABBAGMAYwBlAG4AdAAxAAAAAwABAAwABwQyM5Wz1/8F\n" +
            "AAwABwAAAP////8lAAUAApMCEgAdAA0AADYwJSAtIEFjY2VudDKSCE0AkggAAAAAAAAAAAAAAQQk\n" +
            "/w0ANgAwACUAIAAtACAAQQBjAGMAZQBuAHQAMgAAAAMAAQAMAAcFMjPalpT/BQAMAAcAAAD/////\n" +
            "JQAFAAKTAhIAHgANAAA2MCUgLSBBY2NlbnQzkghNAJIIAAAAAAAAAAAAAAEEKP8NADYAMAAlACAA\n" +
            "LQAgAEEAYwBjAGUAbgB0ADMAAAADAAEADAAHBjIzxNeb/wUADAAHAAAA/////yUABQACkwISAB8A\n" +
            "DQAANjAlIC0gQWNjZW50NJIITQCSCAAAAAAAAAAAAAABBCz/DQA2ADAAJQAgAC0AIABBAGMAYwBl\n" +
            "AG4AdAA0AAAAAwABAAwABwcyM7Ggx/8FAAwABwAAAP////8lAAUAApMCEgAgAA0AADYwJSAtIEFj\n" +
            "Y2VudDWSCE0AkggAAAAAAAAAAAAAAQQw/w0ANgAwACUAIAAtACAAQQBjAGMAZQBuAHQANQAAAAMA\n" +
            "AQAMAAcIMjOSzdz/BQAMAAcAAAD/////JQAFAAKTAhIAIQANAAA2MCUgLSBBY2NlbnQ2kghNAJII\n" +
            "AAAAAAAAAAAAAAEENP8NADYAMAAlACAALQAgAEEAYwBjAGUAbgB0ADYAAAADAAEADAAHCTIz+r+P\n" +
            "/wUADAAHAAAA/////yUABQACkwIMACIABwAAQWNjZW50MZIIQQCSCAAAAAAAAAAAAAABBB3/BwBB\n" +
            "AGMAYwBlAG4AdAAxAAAAAwABAAwABwQAAE+Bvf8FAAwABwAAAP////8lAAUAApMCDAAjAAcAAEFj\n" +
            "Y2VudDKSCEEAkggAAAAAAAAAAAAAAQQh/wcAQQBjAGMAZQBuAHQAMgAAAAMAAQAMAAcFAADAUE3/\n" +
            "BQAMAAcAAAD/////JQAFAAKTAgwAJAAHAABBY2NlbnQzkghBAJIIAAAAAAAAAAAAAAEEJf8HAEEA\n" +
            "YwBjAGUAbgB0ADMAAAADAAEADAAHBgAAm7tZ/wUADAAHAAAA/////yUABQACkwIMACUABwAAQWNj\n" +
            "ZW50NJIIQQCSCAAAAAAAAAAAAAABBCn/BwBBAGMAYwBlAG4AdAA0AAAAAwABAAwABwcAAIBkov8F\n" +
            "AAwABwAAAP////8lAAUAApMCDAAmAAcAAEFjY2VudDWSCEEAkggAAAAAAAAAAAAAAQQt/wcAQQBj\n" +
            "AGMAZQBuAHQANQAAAAMAAQAMAAcIAABLrMb/BQAMAAcAAAD/////JQAFAAKTAgwAJwAHAABBY2Nl\n" +
            "bnQ2kghBAJIIAAAAAAAAAAAAAAEEMf8HAEEAYwBjAGUAbgB0ADYAAAADAAEADAAHCQAA95ZG/wUA\n" +
            "DAAHAAAA/////yUABQACkwIIACgAAwAAQmFkkgg5AJIIAAAAAAAAAAAAAAEBG/8DAEIAYQBkAAAA\n" +
            "AwABAAwABf8AAP/Hzv8FAAwABf8AAJwABv8lAAUAApMCEAApAAsAAENhbGN1bGF0aW9ukgiBAJII\n" +
            "AAAAAAAAAAAAAAECFv8LAEMAYQBsAGMAdQBsAGEAdABpAG8AbgAAAAcAAQAMAAX/AADy8vL/BQAM\n" +
            "AAX/AAD6fQD/JQAFAAIGAA4ABf8AAH9/f/8BAAcADgAF/wAAf39//wEACAAOAAX/AAB/f3//AQAJ\n" +
            "AA4ABf8AAH9/f/8BAJMCDwAqAAoAAENoZWNrIENlbGySCH8AkggAAAAAAAAAAAAAAQIX/woAQwBo\n" +
            "AGUAYwBrACAAQwBlAGwAbAAAAAcAAQAMAAX/AAClpaX/BQAMAAcAAAD/////JQAFAAIGAA4ABf8A\n" +
            "AD8/P/8GAAcADgAF/wAAPz8//wYACAAOAAX/AAA/Pz//BgAJAA4ABf8AAD8/P/8GAJMCBAArgAP/\n" +
            "kgggAJIIAAAAAAAAAAAAAAEFA/8FAEMAbwBtAG0AYQAAAAAAkwIEACyABv+SCCgAkggAAAAAAAAA\n" +
            "AAAAAQUG/wkAQwBvAG0AbQBhACAAWwAwAF0AAAAAAJMCBAAtgAT/kggmAJIIAAAAAAAAAAAAAAEF\n" +
            "BP8IAEMAdQByAHIAZQBuAGMAeQAAAAAAkwIEAC6AB/+SCC4AkggAAAAAAAAAAAAAAQUH/wwAQwB1\n" +
            "AHIAcgBlAG4AYwB5ACAAWwAwAF0AAAAAAJMCFQAvABAAAEV4cGxhbmF0b3J5IFRleHSSCEcAkggA\n" +
            "AAAAAAAAAAAAAQI1/xAARQB4AHAAbABhAG4AYQB0AG8AcgB5ACAAVABlAHgAdAAAAAIABQAMAAX/\n" +
            "AAB/f3//JQAFAAKTAgkAMAAEAABHb29kkgg7AJIIAAAAAAAAAAAAAAEBGv8EAEcAbwBvAGQAAAAD\n" +
            "AAEADAAF/wAAxu/O/wUADAAF/wAAAGEA/yUABQACkwIOADEACQAASGVhZGluZyAxkghHAJIIAAAA\n" +
            "AAAAAAAAAAEDEP8JAEgAZQBhAGQAaQBuAGcAIAAxAAAAAwAFAAwABwMAAB9Jff8lAAUAAgcADgAH\n" +
            "BAAAT4G9/wUAkwIOADIACQAASGVhZGluZyAykghHAJIIAAAAAAAAAAAAAAEDEf8JAEgAZQBhAGQA\n" +
            "aQBuAGcAIAAyAAAAAwAFAAwABwMAAB9Jff8lAAUAAgcADgAHBP8/p7/e/wUAkwIOADMACQAASGVh\n" +
            "ZGluZyAzkghHAJIIAAAAAAAAAAAAAAEDEv8JAEgAZQBhAGQAaQBuAGcAIAAzAAAAAwAFAAwABwMA\n" +
            "AB9Jff8lAAUAAgcADgAHBDIzlbPX/wIAkwIOADQACQAASGVhZGluZyA0kgg5AJIIAAAAAAAAAAAA\n" +
            "AAEDE/8JAEgAZQBhAGQAaQBuAGcAIAA0AAAAAgAFAAwABwMAAB9Jff8lAAUAApMCCgA1AAUAAElu\n" +
            "cHV0kgh1AJIIAAAAAAAAAAAAAAECFP8FAEkAbgBwAHUAdAAAAAcAAQAMAAX/AAD/zJn/BQAMAAX/\n" +
            "AAA/P3b/JQAFAAIGAA4ABf8AAH9/f/8BAAcADgAF/wAAf39//wEACAAOAAX/AAB/f3//AQAJAA4A\n" +
            "Bf8AAH9/f/8BAJMCEAA2AAsAAExpbmtlZCBDZWxskghLAJIIAAAAAAAAAAAAAAECGP8LAEwAaQBu\n" +
            "AGsAZQBkACAAQwBlAGwAbAAAAAMABQAMAAX/AAD6fQD/JQAFAAIHAA4ABf8AAP+AAf8GAJMCDAA3\n" +
            "AAcAAE5ldXRyYWySCEEAkggAAAAAAAAAAAAAAQEc/wcATgBlAHUAdAByAGEAbAAAAAMAAQAMAAX/\n" +
            "AAD/65z/BQAMAAX/AACcZQD/JQAFAAKTAgQAAIAA/5IIIgCSCAAAAAAAAAAAAAABAQD/BgBOAG8A\n" +
            "cgBtAGEAbAAAAAAAkwIJADgABAAATm90ZZIIYgCSCAAAAAAAAAAAAAABAgr/BABOAG8AdABlAAAA\n" +
            "BQABAAwABf8AAP//zP8GAA4ABf8AALKysv8BAAcADgAF/wAAsrKy/wEACAAOAAX/AACysrL/AQAJ\n" +
            "AA4ABf8AALKysv8BAJMCCwA5AAYAAE91dHB1dJIIdwCSCAAAAAAAAAAAAAABAhX/BgBPAHUAdABw\n" +
            "AHUAdAAAAAcAAQAMAAX/AADy8vL/BQAMAAX/AAA/Pz//JQAFAAIGAA4ABf8AAD8/P/8BAAcADgAF\n" +
            "/wAAPz8//wEACAAOAAX/AAA/Pz//AQAJAA4ABf8AAD8/P/8BAJMCBAA6gAX/kggkAJIIAAAAAAAA\n" +
            "AAAAAAEFBf8HAFAAZQByAGMAZQBuAHQAAAAAAJMCCgA7AAUAAFRpdGxlkggxAJIIAAAAAAAAAAAA\n" +
            "AAEDD/8FAFQAaQB0AGwAZQAAAAIABQAMAAcDAAAfSX3/JQAFAAGTAgoAPAAFAABUb3RhbJIITQCS\n" +
            "CAAAAAAAAAAAAAABAxn/BQBUAG8AdABhAGwAAAAEAAUADAAHAQAAAAAA/yUABQACBgAOAAcEAABP\n" +
            "gb3/AQAHAA4ABwQAAE+Bvf8GAJMCEQA9AAwAAFdhcm5pbmcgVGV4dJIIPwCSCAAAAAAAAAAAAAAB\n" +
            "Agv/DABXAGEAcgBuAGkAbgBnACAAVABlAHgAdAAAAAIABQAMAAX/AAD/AAD/JQAFAAKOCFgAjggA\n" +
            "AAAAAAAAAAAAkAAAABEAEQBUAGEAYgBsAGUAUwB0AHkAbABlAE0AZQBkAGkAdQBtADIAUABpAHYA\n" +
            "bwB0AFMAdAB5AGwAZQBMAGkAZwBoAHQAMQA2AGABAgAAAIUADgBbNAAAAAAGAHNoZWV0MZoIGACa\n" +
            "CAAAAAAAAAAAAAABAAAAAAAAAAQAAACjCBAAowgAAAAAAAAAAAAAAAAAAIwABAABAAEArgEEAAEA\n" +
            "AQQXAAgAAQAAAAAAAADBAQgAwQEAANU4AgD8AH8JxgIAALIAAAAJAABDb21tCkNvZGUOAABBc3Nv\n" +
            "Y2lhdGUKTmFtZQwAAEFzc29jaWF0ZQpJRAQAAERlcHQMAABNaXNzZWQKUHVuY2gOAABPdmVydGlt\n" +
            "ZQpIb3Vycw0AAExvbmcKSW50ZXJ2YWwLAABTaG9ydApTaGlmdBQAAFNob3J0IEx1bmNoCjwgMjMg\n" +
            "TWluDAAATWlzc2VkCkx1bmNoBQAAMjQzNzUNAABCcmljZSwgSXNhYmVsCQAAMDAwMzMyMTA1AwAA\n" +
            "MDE2AAAADgAAQnV0bGVyLCBNYWRlbmEJAAAwMDA0Njc1NzgPAABDaGFuZXksIEJhcmJhcmEJAAAw\n" +
            "MDA1NDA2MDQBAABYDQAAQ29ubmVyLCBKYW5pcwkAADAwMDQ4ODAxNA8AAEN1bW1pbmdzLCBLYXJl\n" +
            "bgkAADAwMDQ5NjMzNwMAADYuMAsAAEVhc29uLCBWZXJhCQAAMDAwNDQxNDUxEAAARm9udGh1cywg\n" +
            "Qnlva2luZQkAADAwMDA2Nzk1MRUAAEdhbGljaHNrYXlhLCBWZXJvbmlrYQkAADAwMDQ0ODMyNwMA\n" +
            "ADguMBEAAEdhbGxvd2F5LCBKYXNtaW5lCQAAMDAwNTMzMDM3EAAAR2F5bm9yLCBTaGF0YXZpYQkA\n" +
            "ADAwMDU0MDU5MQ4AAEdyYXksIEphcXVlbGxhCQAAMDAwNDk3MzUwCwAAR3JheSwgTmlraWEJAAAw\n" +
            "MDA0OTc1MDMEAAA3LjI1DgAASG9vZmtpbiwgQmVubnkJAAAwMDA0NDU2OTQFAAA1Mi4yNRAAAEpl\n" +
            "bm5pbmdzLCBUYW1la2EJAAAwMDA0NTQzNTARAABKb2huc29uLCBLaW1iZXJseQkAADAwMDQ3ODA5\n" +
            "Mw0AAEtlbGx5LCBDYXRpbmEJAAAwMDA1NDA2NDIPAABLaXJrbGFuZCwgS2FyZW4JAAAwMDAyMDk1\n" +
            "NjIOAABLcmFtZXIsIFNhbmRyYQkAADAwMDQ5MzUxNg4AAExha2UsIFZlcm9uaWNhCQAAMDAwNTMz\n" +
            "MzIyEAAATG9wZXosIEd1YWRhbHVwZQkAADAwMDI1ODI2OBAAAE1jSW50eXJlLCBCZXJ0aGEJAAAw\n" +
            "MDA0Mzk1MjkMAABNY0theSwgV2VuZHkJAAAwMDA0MzAyNzUUAABNaWxsaWdhbiwgQmVybmFyZGl0\n" +
            "YQkAADAwMDQ0OTk4OQ0AAE1pcmNhbiwgRWxlbmEJAAAwMDA0NTIzMzURAABNb3VsdHJpZSwgVHJh\n" +
            "dm95YQkAADAwMDQ4OTM2OBAAAE9yZWx1cywgQmxleWV0dGUJAAAwMDA0NTQ2MjUPAABQYXJhbW9y\n" +
            "ZSwgTGluZGEJAAAwMDA0NDA2MTERAABSZW5lc2NhLCBNaXJsYW5kZQkAADAwMDQ1MzgxMhEAAFJv\n" +
            "Ymluc29uLCBCZXZlcmx5CQAAMDAwMjQ1MTM2AwAANS41CgAAU2FwcCwgRXJpYwkAADAwMDU0MDc0\n" +
            "Mg8AAFNjaXBwaW8sIFRhbWlrYQkAADAwMDQ5MDUyMgsAAFNoYXcsIFNhcmFoCQAAMDAwMzk4MjEz\n" +
            "EQAAVGhhcnJpbmd0b24sIE1hcnkJAAAwMDA0NDU4MjIPAABUdXJuZXIsIExpbmRzZXkJAAAwMDA1\n" +
            "NDEwNjgPAABVcHNoYXcsIFl1a2llbHkJAAAwMDA0ODk5NDILAABXYWxrZXIsIFRpYQkAADAwMDUy\n" +
            "NjI3MA8AAFRob21wc29uLCBEZWx0YQkAADAwMDQ0NjI3NQUAADAxNjA1FgAAQXhzb24tTWF0dGhl\n" +
            "d3MsIEtlbmRyYQkAADAwMDM3MTYxMwMAADAzMAQAADIuMjUMAABCZWNrLCBFZHdhcmQJAAAwMDA0\n" +
            "MzI5NjAQAABDaGlzb2xtLCBTaGFraXJhCQAAMDAwNDQ1NTA5AwAAMi41DwAARGFpbGV5LCBLYXRl\n" +
            "cmlhCQAAMDAwNDk0MTM4EAAARGVsZ2FkbywgTGl6YmV0aAkAADAwMDUyOTQyNw4AAEZ5ZSwgQ2hh\n" +
            "cmxvdHRlCQAAMDAwNDYzOTUwBQAAMzguNzUPAABIYXJyaXMsIE5hcnNoYWUJAAAwMDA1MTEwMjIS\n" +
            "AABIZW5kZXJzb24sIFRpbmNoZWEJAAAwMDA0NjQxMjcOAABIb3dhcmQsIFNoZWlsYQkAADAwMDQ0\n" +
            "NjUzMgQAADAuNzUOAABNZWRpbmEsIFNhbXVlbAkAADAwMDUyMzMxNw0AAE15ZXJzLCBBbWFuZGEJ\n" +
            "AAAwMDA1MTYwMjMEAAAxMi41DAAAU21pdGgsIE1hcmxhCQAAMDAwNDQzNTQyEAAAVGhvbXBzb24s\n" +
            "IFJpZXNoYQkAADAwMDQ5MjcxNQQAADguMjURAABXaWxsaWFtcywgTWljaGFlbAkAADAwMDQzOTE0\n" +
            "NRMAAEdvbnphbGV6LCBKYWNrZWxpbmUJAAAwMDA0NDI3NTMDAAAwNDAPAABNY011bGxpbiwgU3Vz\n" +
            "YW4JAAAwMDA0NDYyMzkNAABGbHl0aGUsIFJvZ2VyCQAAMDAwNDkzNTEzAwAAMDQyDAAAQmxha2Us\n" +
            "IFJhdmVuCQAAMDAwNDkzNTIzAwAAMDQ1BQAAMTAuMjUOAABNY011bGxpbiwgU2VhbgkAADAwMDQ0\n" +
            "ODU3Mg0AAENoYXZleiwgUm9jaW8JAAAwMDA0NTMzMzEDAAAwNTAQAABKb2huc29uLCBNaWNoYWVs\n" +
            "CQAAMDAwNDUyNjY4DwAATWFydGluZXosIE1hcmlhCQAAMDAwNDMxOTg1DgAATmFyYW5qbywgTWFy\n" +
            "Y28JAAAwMDA0NDAyNTARAABQZWFyc2FsbCBKUiwgUGF1bAkAADAwMDQ0ODY2MQ4AAFJpdmFzLCBM\n" +
            "b3VyZGVzCQAAMDAwNTAzNTY4DgAAQWxpY2VhLCBKb2Jpbm8JAAAwMDA0ODM1NDEDAAAwNjAQAABM\n" +
            "b3BleiBJSUksIFBlZHJvCQAAMDAwNTI4Njk5AwAAMS4wDQAAUnlhbiwgV2lsbGlhbQkAADAwMDAw\n" +
            "ODg4MBIAAEd1cmd1cmljaCwgV2lsbGlhbQkAADAwMDQ0ODk2OQMAADA2NxEAAENyYXdmb3JkLCBD\n" +
            "b2xsZWVuCQAAMDAwNTM1NTM0AwAAMDcwDQAAQ2FydGVyLCBKYW1lcwkAADAwMDQ0MjAzNgMAADA4\n" +
            "MAwAAERvd25zLCBBbmRyYQkAADAwMDQ0NDg2MREAAFNob3dhbHRlciwgU2hlcnlsCQAAMDAwNDQ1\n" +
            "MzcwBAAAMC4yNf8AugAIAMwpAAAMAAAAPSoAAH0AAAChKgAA4QAAAAkrAABJAQAAcisAALIBAADo\n" +
            "KwAAKAIAAFMsAACTAgAAxywAAAcDAABDLQAAgwMAALwtAAD8AwAALS4AAG0EAACjLgAA4wQAABQv\n" +
            "AABUBQAAcy8AALMFAADhLwAAIQYAAFMwAACTBgAAuzAAAPsGAAApMQAAaQcAAIUxAADFBwAA8TEA\n" +
            "ADEIAABkMgAApAgAAM0yAAANCQAAMDMAAHAJAABjCBYAYwgAAAAAAAAAAAAAFgAAAAAAAAACAJYI\n" +
            "EACWCAAAAAAAAAAAAABC5QEAmwgQAJsIAAAAAAAAAAAAAAEAAACMCBAAjAgAAAAAAAAAAAAAAAAA\n" +
            "AAoAAAAJCBAAAAYQAGcyzQfJgAEABgYAAAsCHAAAAAAAAAAAAEcAAAB5NQAAoUkAAOldAACRYgAA\n" +
            "DQACAAEADAACAGQADwACAAEAEQACAAAAEAAIAPyp8dJNYlA/XwACAAEAKgACAAAAKwACAAAAggAC\n" +
            "AAEAgAAIAAAAAAAAAAAAJQIEAAAA/wCBAAIAwQQUAAAAFQAAAIMAAgAAAIQAAgAAACYACAAAAAAA\n" +
            "AADoPycACAAAAAAAAADoPygACAAAAAAAAADwPykACAAAAAAAAADwP6EAIgABAGQAAQABAAEAAgAs\n" +
            "ASwBAAAAAAAA4D8AAAAAAADgPwEAnAgmAJwIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAA\n" +
            "AAAAAAAAVQACAAgAfQAMAAAAAAEAEA8AAgAAAAACDgAAAAAARwAAAAAACgAAAAgCEAAAAAAACgD+\n" +
            "AQAAAAAAAQ8ACAIQAAEAAAAKAP8AAAAAAAABDwAIAhAAAgAAAAoA/wAAAAAAAAEPAAgCEAADAAAA\n" +
            "CgD/AAAAAAAAAQ8ACAIQAAQAAAAKAP8AAAAAAAABDwAIAhAABQAAAAoA/wAAAAAAAAEPAAgCEAAG\n" +
            "AAAACgD/AAAAAAAAAQ8ACAIQAAcAAAAKAP8AAAAAAAABDwAIAhAACAAAAAoA/wAAAAAAAAEPAAgC\n" +
            "EAAJAAAACgD/AAAAAAAAAQ8ACAIQAAoAAAAKAP8AAAAAAAABDwAIAhAACwAAAAoA/wAAAAAAAAEP\n" +
            "AAgCEAAMAAAACgD/AAAAAAAAAQ8ACAIQAA0AAAAKAP8AAAAAAAABDwAIAhAADgAAAAoA/wAAAAAA\n" +
            "AAEPAAgCEAAPAAAACgD/AAAAAAAAAQ8ACAIQABAAAAAKAP8AAAAAAAABDwAIAhAAEQAAAAoA/wAA\n" +
            "AAAAAAEPAAgCEAASAAAACgD/AAAAAAAAAQ8ACAIQABMAAAAKAP8AAAAAAAABDwAIAhAAFAAAAAoA\n" +
            "/wAAAAAAAAEPAAgCEAAVAAAACgD/AAAAAAAAAQ8ACAIQABYAAAAKAP8AAAAAAAABDwAIAhAAFwAA\n" +
            "AAoA/wAAAAAAAAEPAAgCEAAYAAAACgD/AAAAAAAAAQ8ACAIQABkAAAAKAP8AAAAAAAABDwAIAhAA\n" +
            "GgAAAAoA/wAAAAAAAAEPAAgCEAAbAAAACgD/AAAAAAAAAQ8ACAIQABwAAAAKAP8AAAAAAAABDwAI\n" +
            "AhAAHQAAAAoA/wAAAAAAAAEPAAgCEAAeAAAACgD/AAAAAAAAAQ8ACAIQAB8AAAAKAP8AAAAAAAAB\n" +
            "DwD9AAoAAAAAAD4AAAAAAP0ACgAAAAEAPgABAAAA/QAKAAAAAgA+AAIAAAD9AAoAAAADAD4AAwAA\n" +
            "AP0ACgAAAAQAPgAEAAAA/QAKAAAABQA+AAUAAAD9AAoAAAAGAD4ABgAAAP0ACgAAAAcAPgAHAAAA\n" +
            "/QAKAAAACAA+AAgAAAD9AAoAAAAJAD4ACQAAAP0ACgABAAAADwAKAAAA/QAKAAEAAQAPAAsAAAD9\n" +
            "AAoAAQACAA8ADAAAAP0ACgABAAMADwANAAAA/QAKAAEABAAPAA4AAAD9AAoAAQAFAA8ADgAAAP0A\n" +
            "CgABAAYADwAOAAAA/QAKAAEABwAPAA4AAAD9AAoAAQAIAA8ADgAAAP0ACgABAAkADwAOAAAA/QAK\n" +
            "AAIAAAAPAAoAAAD9AAoAAgABAA8ADwAAAP0ACgACAAIADwAQAAAA/QAKAAIAAwAPAA0AAAD9AAoA\n" +
            "AgAEAA8ADgAAAP0ACgACAAUADwAOAAAA/QAKAAIABgAPAA4AAAD9AAoAAgAHAA8ADgAAAP0ACgAC\n" +
            "AAgADwAOAAAA/QAKAAIACQAPAA4AAAD9AAoAAwAAAA8ACgAAAP0ACgADAAEADwARAAAA/QAKAAMA\n" +
            "AgAPABIAAAD9AAoAAwADAA8ADQAAAP0ACgADAAQADwAOAAAA/QAKAAMABQAPAA4AAAD9AAoAAwAG\n" +
            "AA8ADgAAAP0ACgADAAcADwAOAAAA/QAKAAMACAAPAA4AAAD9AAoAAwAJAA8AEwAAAP0ACgAEAAAA\n" +
            "DwAKAAAA/QAKAAQAAQAPABQAAAD9AAoABAACAA8AFQAAAP0ACgAEAAMADwANAAAA/QAKAAQABAAP\n" +
            "AA4AAAD9AAoABAAFAA8ADgAAAP0ACgAEAAYADwAOAAAA/QAKAAQABwAPAA4AAAD9AAoABAAIAA8A\n" +
            "DgAAAP0ACgAEAAkADwAOAAAA/QAKAAUAAAAPAAoAAAD9AAoABQABAA8AFgAAAP0ACgAFAAIADwAX\n" +
            "AAAA/QAKAAUAAwAPAA0AAAD9AAoABQAEAA8ADgAAAP0ACgAFAAUADwAYAAAA/QAKAAUABgAPAA4A\n" +
            "AAD9AAoABQAHAA8ADgAAAP0ACgAFAAgADwAOAAAA/QAKAAUACQAPABMAAAD9AAoABgAAAA8ACgAA\n" +
            "AP0ACgAGAAEADwAZAAAA/QAKAAYAAgAPABoAAAD9AAoABgADAA8ADQAAAP0ACgAGAAQADwATAAAA\n" +
            "/QAKAAYABQAPAA4AAAD9AAoABgAGAA8ADgAAAP0ACgAGAAcADwAOAAAA/QAKAAYACAAPABMAAAD9\n" +
            "AAoABgAJAA8ADgAAAP0ACgAHAAAADwAKAAAA/QAKAAcAAQAPABsAAAD9AAoABwACAA8AHAAAAP0A\n" +
            "CgAHAAMADwANAAAA/QAKAAcABAAPAA4AAAD9AAoABwAFAA8ADgAAAP0ACgAHAAYADwAOAAAA/QAK\n" +
            "AAcABwAPAA4AAAD9AAoABwAIAA8ADgAAAP0ACgAHAAkADwAOAAAA/QAKAAgAAAAPAAoAAAD9AAoA\n" +
            "CAABAA8AHQAAAP0ACgAIAAIADwAeAAAA/QAKAAgAAwAPAA0AAAD9AAoACAAEAA8ADgAAAP0ACgAI\n" +
            "AAUADwAfAAAA/QAKAAgABgAPAA4AAAD9AAoACAAHAA8ADgAAAP0ACgAIAAgADwAOAAAA/QAKAAgA\n" +
            "CQAPABMAAAD9AAoACQAAAA8ACgAAAP0ACgAJAAEADwAgAAAA/QAKAAkAAgAPACEAAAD9AAoACQAD\n" +
            "AA8ADQAAAP0ACgAJAAQADwATAAAA/QAKAAkABQAPAA4AAAD9AAoACQAGAA8ADgAAAP0ACgAJAAcA\n" +
            "DwAOAAAA/QAKAAkACAAPABMAAAD9AAoACQAJAA8ADgAAAP0ACgAKAAAADwAKAAAA/QAKAAoAAQAP\n" +
            "ACIAAAD9AAoACgACAA8AIwAAAP0ACgAKAAMADwANAAAA/QAKAAoABAAPAA4AAAD9AAoACgAFAA8A\n" +
            "DgAAAP0ACgAKAAYADwAOAAAA/QAKAAoABwAPAA4AAAD9AAoACgAIAA8ADgAAAP0ACgAKAAkADwAT\n" +
            "AAAA/QAKAAsAAAAPAAoAAAD9AAoACwABAA8AJAAAAP0ACgALAAIADwAlAAAA/QAKAAsAAwAPAA0A\n" +
            "AAD9AAoACwAEAA8AEwAAAP0ACgALAAUADwAOAAAA/QAKAAsABgAPAA4AAAD9AAoACwAHAA8ADgAA\n" +
            "AP0ACgALAAgADwAOAAAA/QAKAAsACQAPABMAAAD9AAoADAAAAA8ACgAAAP0ACgAMAAEADwAmAAAA\n" +
            "/QAKAAwAAgAPACcAAAD9AAoADAADAA8ADQAAAP0ACgAMAAQADwAOAAAA/QAKAAwABQAPACgAAAD9\n" +
            "AAoADAAGAA8ADgAAAP0ACgAMAAcADwAOAAAA/QAKAAwACAAPAA4AAAD9AAoADAAJAA8ADgAAAP0A\n" +
            "CgANAAAADwAKAAAA/QAKAA0AAQAPACkAAAD9AAoADQACAA8AKgAAAP0ACgANAAMADwANAAAA/QAK\n" +
            "AA0ABAAPABMAAAD9AAoADQAFAA8AKwAAAP0ACgANAAYADwATAAAA/QAKAA0ABwAPAA4AAAD9AAoA\n" +
            "DQAIAA8AEwAAAP0ACgANAAkADwATAAAA/QAKAA4AAAAPAAoAAAD9AAoADgABAA8ALAAAAP0ACgAO\n" +
            "AAIADwAtAAAA/QAKAA4AAwAPAA0AAAD9AAoADgAEAA8ADgAAAP0ACgAOAAUADwAOAAAA/QAKAA4A\n" +
            "BgAPAA4AAAD9AAoADgAHAA8ADgAAAP0ACgAOAAgADwAOAAAA/QAKAA4ACQAPAA4AAAD9AAoADwAA\n" +
            "AA8ACgAAAP0ACgAPAAEADwAuAAAA/QAKAA8AAgAPAC8AAAD9AAoADwADAA8ADQAAAP0ACgAPAAQA\n" +
            "DwATAAAA/QAKAA8ABQAPAA4AAAD9AAoADwAGAA8ADgAAAP0ACgAPAAcADwAOAAAA/QAKAA8ACAAP\n" +
            "AA4AAAD9AAoADwAJAA8ADgAAAP0ACgAQAAAADwAKAAAA/QAKABAAAQAPADAAAAD9AAoAEAACAA8A\n" +
            "MQAAAP0ACgAQAAMADwANAAAA/QAKABAABAAPAA4AAAD9AAoAEAAFAA8ADgAAAP0ACgAQAAYADwAO\n" +
            "AAAA/QAKABAABwAPAA4AAAD9AAoAEAAIAA8ADgAAAP0ACgAQAAkADwAOAAAA/QAKABEAAAAPAAoA\n" +
            "AAD9AAoAEQABAA8AMgAAAP0ACgARAAIADwAzAAAA/QAKABEAAwAPAA0AAAD9AAoAEQAEAA8ADgAA\n" +
            "AP0ACgARAAUADwAOAAAA/QAKABEABgAPAA4AAAD9AAoAEQAHAA8ADgAAAP0ACgARAAgADwAOAAAA\n" +
            "/QAKABEACQAPAA4AAAD9AAoAEgAAAA8ACgAAAP0ACgASAAEADwA0AAAA/QAKABIAAgAPADUAAAD9\n" +
            "AAoAEgADAA8ADQAAAP0ACgASAAQADwAOAAAA/QAKABIABQAPAA4AAAD9AAoAEgAGAA8ADgAAAP0A\n" +
            "CgASAAcADwAOAAAA/QAKABIACAAPAA4AAAD9AAoAEgAJAA8ADgAAAP0ACgATAAAADwAKAAAA/QAK\n" +
            "ABMAAQAPADYAAAD9AAoAEwACAA8ANwAAAP0ACgATAAMADwANAAAA/QAKABMABAAPAA4AAAD9AAoA\n" +
            "EwAFAA8ADgAAAP0ACgATAAYADwAOAAAA/QAKABMABwAPAA4AAAD9AAoAEwAIAA8ADgAAAP0ACgAT\n" +
            "AAkADwATAAAA/QAKABQAAAAPAAoAAAD9AAoAFAABAA8AOAAAAP0ACgAUAAIADwA5AAAA/QAKABQA\n" +
            "AwAPAA0AAAD9AAoAFAAEAA8ADgAAAP0ACgAUAAUADwAOAAAA/QAKABQABgAPAA4AAAD9AAoAFAAH\n" +
            "AA8ADgAAAP0ACgAUAAgADwAOAAAA/QAKABQACQAPAA4AAAD9AAoAFQAAAA8ACgAAAP0ACgAVAAEA\n" +
            "DwA6AAAA/QAKABUAAgAPADsAAAD9AAoAFQADAA8ADQAAAP0ACgAVAAQADwAOAAAA/QAKABUABQAP\n" +
            "AA4AAAD9AAoAFQAGAA8ADgAAAP0ACgAVAAcADwAOAAAA/QAKABUACAAPAA4AAAD9AAoAFQAJAA8A\n" +
            "DgAAAP0ACgAWAAAADwAKAAAA/QAKABYAAQAPADwAAAD9AAoAFgACAA8APQAAAP0ACgAWAAMADwAN\n" +
            "AAAA/QAKABYABAAPAA4AAAD9AAoAFgAFAA8AKAAAAP0ACgAWAAYADwAOAAAA/QAKABYABwAPAA4A\n" +
            "AAD9AAoAFgAIAA8ADgAAAP0ACgAWAAkADwAOAAAA/QAKABcAAAAPAAoAAAD9AAoAFwABAA8APgAA\n" +
            "AP0ACgAXAAIADwA/AAAA/QAKABcAAwAPAA0AAAD9AAoAFwAEAA8ADgAAAP0ACgAXAAUADwAOAAAA\n" +
            "/QAKABcABgAPAA4AAAD9AAoAFwAHAA8ADgAAAP0ACgAXAAgADwAOAAAA/QAKABcACQAPAA4AAAD9\n" +
            "AAoAGAAAAA8ACgAAAP0ACgAYAAEADwBAAAAA/QAKABgAAgAPAEEAAAD9AAoAGAADAA8ADQAAAP0A\n" +
            "CgAYAAQADwAOAAAA/QAKABgABQAPAA4AAAD9AAoAGAAGAA8ADgAAAP0ACgAYAAcADwAOAAAA/QAK\n" +
            "ABgACAAPAA4AAAD9AAoAGAAJAA8ADgAAAP0ACgAZAAAADwAKAAAA/QAKABkAAQAPAEIAAAD9AAoA\n" +
            "GQACAA8AQwAAAP0ACgAZAAMADwANAAAA/QAKABkABAAPAA4AAAD9AAoAGQAFAA8ADgAAAP0ACgAZ\n" +
            "AAYADwAOAAAA/QAKABkABwAPAA4AAAD9AAoAGQAIAA8ADgAAAP0ACgAZAAkADwAOAAAA/QAKABoA\n" +
            "AAAPAAoAAAD9AAoAGgABAA8ARAAAAP0ACgAaAAIADwBFAAAA/QAKABoAAwAPAA0AAAD9AAoAGgAE\n" +
            "AA8AEwAAAP0ACgAaAAUADwAOAAAA/QAKABoABgAPAA4AAAD9AAoAGgAHAA8ADgAAAP0ACgAaAAgA\n" +
            "DwAOAAAA/QAKABoACQAPAA4AAAD9AAoAGwAAAA8ACgAAAP0ACgAbAAEADwBGAAAA/QAKABsAAgAP\n" +
            "AEcAAAD9AAoAGwADAA8ADQAAAP0ACgAbAAQADwATAAAA/QAKABsABQAPAA4AAAD9AAoAGwAGAA8A\n" +
            "DgAAAP0ACgAbAAcADwAOAAAA/QAKABsACAAPAA4AAAD9AAoAGwAJAA8ADgAAAP0ACgAcAAAADwAK\n" +
            "AAAA/QAKABwAAQAPAEgAAAD9AAoAHAACAA8ASQAAAP0ACgAcAAMADwANAAAA/QAKABwABAAPAA4A\n" +
            "AAD9AAoAHAAFAA8ADgAAAP0ACgAcAAYADwAOAAAA/QAKABwABwAPAA4AAAD9AAoAHAAIAA8ADgAA\n" +
            "AP0ACgAcAAkADwAOAAAA/QAKAB0AAAAPAAoAAAD9AAoAHQABAA8ASgAAAP0ACgAdAAIADwBLAAAA\n" +
            "/QAKAB0AAwAPAA0AAAD9AAoAHQAEAA8ADgAAAP0ACgAdAAUADwBMAAAA/QAKAB0ABgAPAA4AAAD9\n" +
            "AAoAHQAHAA8ADgAAAP0ACgAdAAgADwAOAAAA/QAKAB0ACQAPAA4AAAD9AAoAHgAAAA8ACgAAAP0A\n" +
            "CgAeAAEADwBNAAAA/QAKAB4AAgAPAE4AAAD9AAoAHgADAA8ADQAAAP0ACgAeAAQADwAOAAAA/QAK\n" +
            "AB4ABQAPAA4AAAD9AAoAHgAGAA8ADgAAAP0ACgAeAAcADwAOAAAA/QAKAB4ACAAPAA4AAAD9AAoA\n" +
            "HgAJAA8ADgAAAP0ACgAfAAAADwAKAAAA/QAKAB8AAQAPAE8AAAD9AAoAHwACAA8AUAAAAP0ACgAf\n" +
            "AAMADwANAAAA/QAKAB8ABAAPAA4AAAD9AAoAHwAFAA8ADgAAAP0ACgAfAAYADwAOAAAA/QAKAB8A\n" +
            "BwAPAA4AAAD9AAoAHwAIAA8ADgAAAP0ACgAfAAkADwATAAAA1wBEAAAUAABsAowAjACMAIwAjACM\n" +
            "AIwAjACMAIwAjACMAIwAjACMAIwAjACMAIwAjACMAIwAjACMAIwAjACMAIwAjACMAIwACAIQACAA\n" +
            "AAAKAP8AAAAAAAABDwAIAhAAIQAAAAoA/wAAAAAAAAEPAAgCEAAiAAAACgD/AAAAAAAAAQ8ACAIQ\n" +
            "ACMAAAAKAP8AAAAAAAABDwAIAhAAJAAAAAoA/wAAAAAAAAEPAAgCEAAlAAAACgD/AAAAAAAAAQ8A\n" +
            "CAIQACYAAAAKAP8AAAAAAAABDwAIAhAAJwAAAAoA/wAAAAAAAAEPAAgCEAAoAAAACgD/AAAAAAAA\n" +
            "AQ8ACAIQACkAAAAKAP8AAAAAAAABDwAIAhAAKgAAAAoA/wAAAAAAAAEPAAgCEAArAAAACgD/AAAA\n" +
            "AAAAAQ8ACAIQACwAAAAKAP8AAAAAAAABDwAIAhAALQAAAAoA/wAAAAAAAAEPAAgCEAAuAAAACgD/\n" +
            "AAAAAAAAAQ8ACAIQAC8AAAAKAP8AAAAAAAABDwAIAhAAMAAAAAoA/wAAAAAAAAEPAAgCEAAxAAAA\n" +
            "CgD/AAAAAAAAAQ8ACAIQADIAAAAKAP8AAAAAAAABDwAIAhAAMwAAAAoA/wAAAAAAAAEPAAgCEAA0\n" +
            "AAAACgD/AAAAAAAAAQ8ACAIQADUAAAAKAP8AAAAAAAABDwAIAhAANgAAAAoA/wAAAAAAAAEPAAgC\n" +
            "EAA3AAAACgD/AAAAAAAAAQ8ACAIQADgAAAAKAP8AAAAAAAABDwAIAhAAOQAAAAoA/wAAAAAAAAEP\n" +
            "AAgCEAA6AAAACgD/AAAAAAAAAQ8ACAIQADsAAAAKAP8AAAAAAAABDwAIAhAAPAAAAAoA/wAAAAAA\n" +
            "AAEPAAgCEAA9AAAACgD/AAAAAAAAAQ8ACAIQAD4AAAAKAP8AAAAAAAABDwAIAhAAPwAAAAoA/wAA\n" +
            "AAAAAAEPAP0ACgAgAAAADwAKAAAA/QAKACAAAQAPAFEAAAD9AAoAIAACAA8AUgAAAP0ACgAgAAMA\n" +
            "DwANAAAA/QAKACAABAAPAA4AAAD9AAoAIAAFAA8ADgAAAP0ACgAgAAYADwAOAAAA/QAKACAABwAP\n" +
            "AA4AAAD9AAoAIAAIAA8ADgAAAP0ACgAgAAkADwAOAAAA/QAKACEAAAAPAAoAAAD9AAoAIQABAA8A\n" +
            "UwAAAP0ACgAhAAIADwBUAAAA/QAKACEAAwAPAA0AAAD9AAoAIQAEAA8AEwAAAP0ACgAhAAUADwAO\n" +
            "AAAA/QAKACEABgAPAA4AAAD9AAoAIQAHAA8ADgAAAP0ACgAhAAgADwAOAAAA/QAKACEACQAPAA4A\n" +
            "AAD9AAoAIgAAAA8ACgAAAP0ACgAiAAEADwBVAAAA/QAKACIAAgAPAFYAAAD9AAoAIgADAA8ADQAA\n" +
            "AP0ACgAiAAQADwAOAAAA/QAKACIABQAPAA4AAAD9AAoAIgAGAA8ADgAAAP0ACgAiAAcADwAOAAAA\n" +
            "/QAKACIACAAPAA4AAAD9AAoAIgAJAA8ADgAAAP0ACgAjAAAADwAKAAAA/QAKACMAAQAPAFcAAAD9\n" +
            "AAoAIwACAA8AWAAAAP0ACgAjAAMADwANAAAA/QAKACMABAAPABMAAAD9AAoAIwAFAA8ADgAAAP0A\n" +
            "CgAjAAYADwAOAAAA/QAKACMABwAPAA4AAAD9AAoAIwAIAA8ADgAAAP0ACgAjAAkADwAOAAAA/QAK\n" +
            "ACQAAAAPAAoAAAD9AAoAJAABAA8AWQAAAP0ACgAkAAIADwBaAAAA/QAKACQAAwAPAA0AAAD9AAoA\n" +
            "JAAEAA8ADgAAAP0ACgAkAAUADwAOAAAA/QAKACQABgAPAA4AAAD9AAoAJAAHAA8ADgAAAP0ACgAk\n" +
            "AAgADwAOAAAA/QAKACQACQAPAA4AAAD9AAoAJQAAAA8ACgAAAP0ACgAlAAEADwBbAAAA/QAKACUA\n" +
            "AgAPAFwAAAD9AAoAJQADAA8AXQAAAP0ACgAlAAQADwAOAAAA/QAKACUABQAPAA4AAAD9AAoAJQAG\n" +
            "AA8ADgAAAP0ACgAlAAcADwAOAAAA/QAKACUACAAPABMAAAD9AAoAJQAJAA8AEwAAAP0ACgAmAAAA\n" +
            "DwAKAAAA/QAKACYAAQAPAF4AAAD9AAoAJgACAA8AXwAAAP0ACgAmAAMADwBgAAAA/QAKACYABAAP\n" +
            "AA4AAAD9AAoAJgAFAA8AYQAAAP0ACgAmAAYADwAOAAAA/QAKACYABwAPAA4AAAD9AAoAJgAIAA8A\n" +
            "DgAAAP0ACgAmAAkADwATAAAA/QAKACcAAAAPAAoAAAD9AAoAJwABAA8AYgAAAP0ACgAnAAIADwBj\n" +
            "AAAA/QAKACcAAwAPAGAAAAD9AAoAJwAEAA8ADgAAAP0ACgAnAAUADwAOAAAA/QAKACcABgAPAA4A\n" +
            "AAD9AAoAJwAHAA8ADgAAAP0ACgAnAAgADwAOAAAA/QAKACcACQAPABMAAAD9AAoAKAAAAA8ACgAA\n" +
            "AP0ACgAoAAEADwBkAAAA/QAKACgAAgAPAGUAAAD9AAoAKAADAA8AYAAAAP0ACgAoAAQADwAOAAAA\n" +
            "/QAKACgABQAPAGYAAAD9AAoAKAAGAA8ADgAAAP0ACgAoAAcADwAOAAAA/QAKACgACAAPAA4AAAD9\n" +
            "AAoAKAAJAA8AEwAAAP0ACgApAAAADwAKAAAA/QAKACkAAQAPAGcAAAD9AAoAKQACAA8AaAAAAP0A\n" +
            "CgApAAMADwBgAAAA/QAKACkABAAPAA4AAAD9AAoAKQAFAA8ADgAAAP0ACgApAAYADwAOAAAA/QAK\n" +
            "ACkABwAPAA4AAAD9AAoAKQAIAA8ADgAAAP0ACgApAAkADwAOAAAA/QAKACoAAAAPAAoAAAD9AAoA\n" +
            "KgABAA8AaQAAAP0ACgAqAAIADwBqAAAA/QAKACoAAwAPAGAAAAD9AAoAKgAEAA8ADgAAAP0ACgAq\n" +
            "AAUADwAOAAAA/QAKACoABgAPAA4AAAD9AAoAKgAHAA8ADgAAAP0ACgAqAAgADwAOAAAA/QAKACoA\n" +
            "CQAPAA4AAAD9AAoAKwAAAA8ACgAAAP0ACgArAAEADwBrAAAA/QAKACsAAgAPAGwAAAD9AAoAKwAD\n" +
            "AA8AYAAAAP0ACgArAAQADwATAAAA/QAKACsABQAPAG0AAAD9AAoAKwAGAA8AEwAAAP0ACgArAAcA\n" +
            "DwAOAAAA/QAKACsACAAPAA4AAAD9AAoAKwAJAA8AEwAAAP0ACgAsAAAADwAKAAAA/QAKACwAAQAP\n" +
            "AG4AAAD9AAoALAACAA8AbwAAAP0ACgAsAAMADwBgAAAA/QAKACwABAAPAA4AAAD9AAoALAAFAA8A\n" +
            "DgAAAP0ACgAsAAYADwAOAAAA/QAKACwABwAPAA4AAAD9AAoALAAIAA8ADgAAAP0ACgAsAAkADwAT\n" +
            "AAAA/QAKAC0AAAAPAAoAAAD9AAoALQABAA8AcAAAAP0ACgAtAAIADwBxAAAA/QAKAC0AAwAPAGAA\n" +
            "AAD9AAoALQAEAA8ADgAAAP0ACgAtAAUADwAOAAAA/QAKAC0ABgAPAA4AAAD9AAoALQAHAA8ADgAA\n" +
            "AP0ACgAtAAgADwAOAAAA/QAKAC0ACQAPAA4AAAD9AAoALgAAAA8ACgAAAP0ACgAuAAEADwByAAAA\n" +
            "/QAKAC4AAgAPAHMAAAD9AAoALgADAA8AYAAAAP0ACgAuAAQADwAOAAAA/QAKAC4ABQAPAHQAAAD9\n" +
            "AAoALgAGAA8ADgAAAP0ACgAuAAcADwAOAAAA/QAKAC4ACAAPAA4AAAD9AAoALgAJAA8AEwAAAP0A\n" +
            "CgAvAAAADwAKAAAA/QAKAC8AAQAPAHUAAAD9AAoALwACAA8AdgAAAP0ACgAvAAMADwBgAAAA/QAK\n" +
            "AC8ABAAPABMAAAD9AAoALwAFAA8ADgAAAP0ACgAvAAYADwAOAAAA/QAKAC8ABwAPAA4AAAD9AAoA\n" +
            "LwAIAA8ADgAAAP0ACgAvAAkADwATAAAA/QAKADAAAAAPAAoAAAD9AAoAMAABAA8AdwAAAP0ACgAw\n" +
            "AAIADwB4AAAA/QAKADAAAwAPAGAAAAD9AAoAMAAEAA8ADgAAAP0ACgAwAAUADwB5AAAA/QAKADAA\n" +
            "BgAPAA4AAAD9AAoAMAAHAA8ADgAAAP0ACgAwAAgADwAOAAAA/QAKADAACQAPAA4AAAD9AAoAMQAA\n" +
            "AA8ACgAAAP0ACgAxAAEADwB6AAAA/QAKADEAAgAPAHsAAAD9AAoAMQADAA8AYAAAAP0ACgAxAAQA\n" +
            "DwATAAAA/QAKADEABQAPAA4AAAD9AAoAMQAGAA8ADgAAAP0ACgAxAAcADwAOAAAA/QAKADEACAAP\n" +
            "AA4AAAD9AAoAMQAJAA8AEwAAAP0ACgAyAAAADwAKAAAA/QAKADIAAQAPAHwAAAD9AAoAMgACAA8A\n" +
            "fQAAAP0ACgAyAAMADwBgAAAA/QAKADIABAAPAA4AAAD9AAoAMgAFAA8AfgAAAP0ACgAyAAYADwAO\n" +
            "AAAA/QAKADIABwAPAA4AAAD9AAoAMgAIAA8ADgAAAP0ACgAyAAkADwATAAAA/QAKADMAAAAPAAoA\n" +
            "AAD9AAoAMwABAA8AfwAAAP0ACgAzAAIADwCAAAAA/QAKADMAAwAPAGAAAAD9AAoAMwAEAA8AEwAA\n" +
            "AP0ACgAzAAUADwAOAAAA/QAKADMABgAPAA4AAAD9AAoAMwAHAA8ADgAAAP0ACgAzAAgADwAOAAAA\n" +
            "/QAKADMACQAPAA4AAAD9AAoANAAAAA8ACgAAAP0ACgA0AAEADwCBAAAA/QAKADQAAgAPAIIAAAD9\n" +
            "AAoANAADAA8AgwAAAP0ACgA0AAQADwAOAAAA/QAKADQABQAPAA4AAAD9AAoANAAGAA8ADgAAAP0A\n" +
            "CgA0AAcADwAOAAAA/QAKADQACAAPABMAAAD9AAoANAAJAA8AEwAAAP0ACgA1AAAADwAKAAAA/QAK\n" +
            "ADUAAQAPAIQAAAD9AAoANQACAA8AhQAAAP0ACgA1AAMADwCDAAAA/QAKADUABAAPAA4AAAD9AAoA\n" +
            "NQAFAA8ADgAAAP0ACgA1AAYADwAOAAAA/QAKADUABwAPAA4AAAD9AAoANQAIAA8ADgAAAP0ACgA1\n" +
            "AAkADwATAAAA/QAKADYAAAAPAAoAAAD9AAoANgABAA8AhgAAAP0ACgA2AAIADwCHAAAA/QAKADYA\n" +
            "AwAPAIgAAAD9AAoANgAEAA8ADgAAAP0ACgA2AAUADwAOAAAA/QAKADYABgAPAA4AAAD9AAoANgAH\n" +
            "AA8ADgAAAP0ACgA2AAgADwAOAAAA/QAKADYACQAPAA4AAAD9AAoANwAAAA8ACgAAAP0ACgA3AAEA\n" +
            "DwCJAAAA/QAKADcAAgAPAIoAAAD9AAoANwADAA8AiwAAAP0ACgA3AAQADwAOAAAA/QAKADcABQAP\n" +
            "AIwAAAD9AAoANwAGAA8AEwAAAP0ACgA3AAcADwAOAAAA/QAKADcACAAPABMAAAD9AAoANwAJAA8A\n" +
            "EwAAAP0ACgA4AAAADwAKAAAA/QAKADgAAQAPAI0AAAD9AAoAOAACAA8AjgAAAP0ACgA4AAMADwCL\n" +
            "AAAA/QAKADgABAAPAA4AAAD9AAoAOAAFAA8ADgAAAP0ACgA4AAYADwAOAAAA/QAKADgABwAPAA4A\n" +
            "AAD9AAoAOAAIAA8ADgAAAP0ACgA4AAkADwAOAAAA/QAKADkAAAAPAAoAAAD9AAoAOQABAA8AjwAA\n" +
            "AP0ACgA5AAIADwCQAAAA/QAKADkAAwAPAJEAAAD9AAoAOQAEAA8ADgAAAP0ACgA5AAUADwAOAAAA\n" +
            "/QAKADkABgAPAA4AAAD9AAoAOQAHAA8ADgAAAP0ACgA5AAgADwAOAAAA/QAKADkACQAPAA4AAAD9\n" +
            "AAoAOgAAAA8ACgAAAP0ACgA6AAEADwCSAAAA/QAKADoAAgAPAJMAAAD9AAoAOgADAA8AkQAAAP0A\n" +
            "CgA6AAQADwATAAAA/QAKADoABQAPAA4AAAD9AAoAOgAGAA8ADgAAAP0ACgA6AAcADwAOAAAA/QAK\n" +
            "ADoACAAPAA4AAAD9AAoAOgAJAA8ADgAAAP0ACgA7AAAADwAKAAAA/QAKADsAAQAPAJQAAAD9AAoA\n" +
            "OwACAA8AlQAAAP0ACgA7AAMADwCRAAAA/QAKADsABAAPAA4AAAD9AAoAOwAFAA8ADgAAAP0ACgA7\n" +
            "AAYADwAOAAAA/QAKADsABwAPAA4AAAD9AAoAOwAIAA8ADgAAAP0ACgA7AAkADwAOAAAA/QAKADwA\n" +
            "AAAPAAoAAAD9AAoAPAABAA8AlgAAAP0ACgA8AAIADwCXAAAA/QAKADwAAwAPAJEAAAD9AAoAPAAE\n" +
            "AA8ADgAAAP0ACgA8AAUADwAOAAAA/QAKADwABgAPAA4AAAD9AAoAPAAHAA8ADgAAAP0ACgA8AAgA\n" +
            "DwATAAAA/QAKADwACQAPAA4AAAD9AAoAPQAAAA8ACgAAAP0ACgA9AAEADwCYAAAA/QAKAD0AAgAP\n" +
            "AJkAAAD9AAoAPQADAA8AkQAAAP0ACgA9AAQADwATAAAA/QAKAD0ABQAPAHQAAAD9AAoAPQAGAA8A\n" +
            "DgAAAP0ACgA9AAcADwAOAAAA/QAKAD0ACAAPABMAAAD9AAoAPQAJAA8AEwAAAP0ACgA+AAAADwAK\n" +
            "AAAA/QAKAD4AAQAPAJoAAAD9AAoAPgACAA8AmwAAAP0ACgA+AAMADwCRAAAA/QAKAD4ABAAPABMA\n" +
            "AAD9AAoAPgAFAA8ADgAAAP0ACgA+AAYADwAOAAAA/QAKAD4ABwAPAA4AAAD9AAoAPgAIAA8ADgAA\n" +
            "AP0ACgA+AAkADwAOAAAA/QAKAD8AAAAPAAoAAAD9AAoAPwABAA8AnAAAAP0ACgA/AAIADwCdAAAA\n" +
            "/QAKAD8AAwAPAJ4AAAD9AAoAPwAEAA8ADgAAAP0ACgA/AAUADwAOAAAA/QAKAD8ABgAPAA4AAAD9\n" +
            "AAoAPwAHAA8ADgAAAP0ACgA/AAgADwATAAAA/QAKAD8ACQAPABMAAADXAEQAABQAAGwCjACMAIwA\n" +
            "jACMAIwAjACMAIwAjACMAIwAjACMAIwAjACMAIwAjACMAIwAjACMAIwAjACMAIwAjACMAIwAjAAI\n" +
            "AhAAQAAAAAoA/wAAAAAAAAEPAAgCEABBAAAACgD/AAAAAAAAAQ8ACAIQAEIAAAAKAP8AAAAAAAAB\n" +
            "DwAIAhAAQwAAAAoA/wAAAAAAAAEPAAgCEABEAAAACgD/AAAAAAAAAQ8ACAIQAEUAAAAKAP8AAAAA\n" +
            "AAABDwAIAhAARgAAAAoA/wAAAAAAAAEPAP0ACgBAAAAADwAKAAAA/QAKAEAAAQAPAJ8AAAD9AAoA\n" +
            "QAACAA8AoAAAAP0ACgBAAAMADwCeAAAA/QAKAEAABAAPAA4AAAD9AAoAQAAFAA8AoQAAAP0ACgBA\n" +
            "AAYADwAOAAAA/QAKAEAABwAPAA4AAAD9AAoAQAAIAA8ADgAAAP0ACgBAAAkADwAOAAAA/QAKAEEA\n" +
            "AAAPAAoAAAD9AAoAQQABAA8AogAAAP0ACgBBAAIADwCjAAAA/QAKAEEAAwAPAJ4AAAD9AAoAQQAE\n" +
            "AA8ADgAAAP0ACgBBAAUADwB0AAAA/QAKAEEABgAPAA4AAAD9AAoAQQAHAA8ADgAAAP0ACgBBAAgA\n" +
            "DwAOAAAA/QAKAEEACQAPABMAAAD9AAoAQgAAAA8ACgAAAP0ACgBCAAEADwCkAAAA/QAKAEIAAgAP\n" +
            "AKUAAAD9AAoAQgADAA8ApgAAAP0ACgBCAAQADwAOAAAA/QAKAEIABQAPAA4AAAD9AAoAQgAGAA8A\n" +
            "DgAAAP0ACgBCAAcADwAOAAAA/QAKAEIACAAPAA4AAAD9AAoAQgAJAA8ADgAAAP0ACgBDAAAADwAK\n" +
            "AAAA/QAKAEMAAQAPAKcAAAD9AAoAQwACAA8AqAAAAP0ACgBDAAMADwCpAAAA/QAKAEMABAAPAA4A\n" +
            "AAD9AAoAQwAFAA8ADgAAAP0ACgBDAAYADwAOAAAA/QAKAEMABwAPAA4AAAD9AAoAQwAIAA8ADgAA\n" +
            "AP0ACgBDAAkADwAOAAAA/QAKAEQAAAAPAAoAAAD9AAoARAABAA8AqgAAAP0ACgBEAAIADwCrAAAA\n" +
            "/QAKAEQAAwAPAKwAAAD9AAoARAAEAA8AEwAAAP0ACgBEAAUADwAOAAAA/QAKAEQABgAPAA4AAAD9\n" +
            "AAoARAAHAA8ADgAAAP0ACgBEAAgADwAOAAAA/QAKAEQACQAPAA4AAAD9AAoARQAAAA8ACgAAAP0A\n" +
            "CgBFAAEADwCtAAAA/QAKAEUAAgAPAK4AAAD9AAoARQADAA8ArAAAAP0ACgBFAAQADwAOAAAA/QAK\n" +
            "AEUABQAPAA4AAAD9AAoARQAGAA8ADgAAAP0ACgBFAAcADwAOAAAA/QAKAEUACAAPAA4AAAD9AAoA\n" +
            "RQAJAA8AEwAAAP0ACgBGAAAADwAKAAAA/QAKAEYAAQAPAK8AAAD9AAoARgACAA8AsAAAAP0ACgBG\n" +
            "AAMADwCsAAAA/QAKAEYABAAPAA4AAAD9AAoARgAFAA8AsQAAAP0ACgBGAAYADwAOAAAA/QAKAEYA\n" +
            "BwAPAA4AAAD9AAoARgAIAA8ADgAAAP0ACgBGAAkADwAOAAAA1wASAGAEAAB4AIwAjACMAIwAjACM\n" +
            "AD4CEgC2BgAAAABAAAAAAAAAAAAAAACLCBAAiwgAAAAAAAAAAAAAAAACAB0ADwADAAAAAAAAAQAA\n" +
            "AAAAAABnCBcAZwgAAAAAAAAAAAAAAgAB/////wNEAAAKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
            "AAAAAAEAAAACAAAA/v///wQAAAAFAAAABgAAAP7/////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "////////////////////////////////////////////////////////////////////////////\n" +
            "/////v8AAAYBAgAAAAAAAAAAAAAAAAAAAAAAAQAAAOCFn/L5T2gQq5EIACsns9kwAAAAXAAAAAQA\n" +
            "AAABAAAAKAAAAAgAAAAwAAAADQAAAEgAAAATAAAAVAAAAAIAAADkBAAAHgAAABAAAABBZG1pbmlz\n" +
            "dHJhdG9yAAAAQAAAAABdTuKkhtEBAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/v8AAAYBAgAAAAAAAAAAAAAAAAAAAAAAAQAAAALVzdWc\n" +
            "LhsQk5cIACss+a4wAAAAsAAAAAgAAAABAAAASAAAABcAAABQAAAACwAAAFgAAAAQAAAAYAAAABMA\n" +
            "AABoAAAAFgAAAHAAAAANAAAAeAAAAAwAAACLAAAAAgAAAOQEAAADAAAAAAAOAAsAAAAAAAAACwAA\n" +
            "AAAAAAALAAAAAAAAAAsAAAAAAAAAHhAAAAEAAAAHAAAAc2hlZXQxAAwQAAACAAAAHgAAAAsAAABX\n" +
            "b3Jrc2hlZXRzAAMAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" +
            "AAA=\n" +
            "--001a1141ec7edfc1f6052f11a9d4--\n";
}
