package school.sorokin.javabot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;

@Component
public class MyTelegramBot implements SpringLongPollingBot {

    private final UpdateConsumer updateConsumer;
    private final String botToken;

    public MyTelegramBot(UpdateConsumer updateConsumer, @Value("${telegram.bot.token}") String botToken) {
        this.updateConsumer = updateConsumer;
        this.botToken = botToken;
    }

    @Override
    public String getBotToken() {
        return botToken; // вынесено в конфиг
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return updateConsumer;
    }
}
