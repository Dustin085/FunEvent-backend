package com.example.funeventbackend.service;

import com.example.funeventbackend.exception.EmailSendException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;

    /**
     * 寄送 Email
     * @param to 收件者地址
     * @param subject 主旨
     * @param body 信件內容
     */
    public void sendEmail(String to, String subject, String body) {
        try{
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        }catch (MailException e){
            log.error("寄信失敗 to={}", to, e);
            throw new EmailSendException("寄信失敗", e);
        }
    }
}
