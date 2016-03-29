package com.ryanlchandler.timesheets.converter;

import com.ryanlchandler.timesheets.model.SESEvent;
import org.apache.commons.lang3.StringUtils;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Properties;

public class SESEventToInputStream {

    public static InputStream convert(SESEvent ses){
        try{
            Session session = Session.getDefaultInstance(new Properties());
            Message msg = new MimeMessage(session, new ByteArrayInputStream(ses.getContent().getBytes()));
            Multipart multipart = (MimeMultipart)msg.getContent();

            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                if(!Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition()) && !StringUtils.isNotBlank(bodyPart.getFileName())) {
                    continue; // dealing with attachments only
                }

                return ExcelToCSV.convert(bodyPart.getInputStream());
            }
        }catch(Throwable t){
            t.printStackTrace();
        }

        return null;
    }
}
