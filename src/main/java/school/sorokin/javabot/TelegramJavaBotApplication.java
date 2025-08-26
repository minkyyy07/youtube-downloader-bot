package school.sorokin.javabot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TelegramJavaBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelegramJavaBotApplication.class, args);
    }

}
