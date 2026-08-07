package com.example.funeventbackend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailService {
    /**
     * 寄送 Email
     * @param to 收件者地址
     * @param subject 主旨
     * @param body 信件內容
     */
    public void sendEmail(String to, String subject, String body) {
        // 若傳送失敗，拋例外
        log.info("Sending email to: {} subject: {} body: {}", to, subject, body);
    }
}
