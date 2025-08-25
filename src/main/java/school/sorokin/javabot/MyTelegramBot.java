package school.sorokin.javabot;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;

@Component
public class MyTelegramBot implements SpringLongPollingBot {

    private final UpdateConsumer updateConsumer;

    public MyTelegramBot(UpdateConsumer updateConsumer) {
        this.updateConsumer = updateConsumer;
    }

    @Override
    public String getBotToken() {
        return "7572851697:AAH1SOhaG_UpvFLsqN-mMct1-PVG-Oj9DE4"; // TODO: вынести в конфиг
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return updateConsumer;
    }
}
