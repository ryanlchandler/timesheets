package com.ryanlchandler.timesheets.service;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient;
import com.amazonaws.services.simpleemail.model.RawMessage;
import com.amazonaws.services.simpleemail.model.SendRawEmailRequest;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Properties;

public class MailService {

    public static void send(String to, String from, String fromArn, String subject, String reportBody, String accessKey, String secretKey){
        try{
            Session session = Session.getDefaultInstance(new Properties());
            MimeMessage message = new MimeMessage(session);
            message.setSubject(subject, "UTF-8");

            message.setFrom(new InternetAddress(from));
            message.setReplyTo(new Address[]{new InternetAddress(from)});
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));

            MimeBodyPart body = new MimeBodyPart();
            body.setContent("<pre style=\"font-family: 'courier'\">" + reportBody + "</pre>", "text/html");

            MimeMultipart multipart = new MimeMultipart();
            multipart.addBodyPart(body);
            message.setContent(multipart);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.writeTo(out);


            SendRawEmailRequest rawEmailRequest = new SendRawEmailRequest();

            rawEmailRequest.setFromArn(fromArn);
            rawEmailRequest.setDestinations(Arrays.asList(to));
            rawEmailRequest.setReturnPathArn(fromArn);

            RawMessage rawMessage = new RawMessage();
            rawMessage.setData(ByteBuffer.wrap(out.toByteArray()));
            rawEmailRequest.setRawMessage(rawMessage);

            AmazonSimpleEmailServiceClient simpleEmailServiceClient = new AmazonSimpleEmailServiceClient(new BasicAWSCredentials(accessKey, secretKey));
            simpleEmailServiceClient.sendRawEmail(rawEmailRequest);
        }catch(Throwable t){
            t.printStackTrace();
        }
    }
}
