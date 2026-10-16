package com.halloween.draft.discord;

import com.halloween.draft.competition.CompetitionService;
import com.halloween.draft.config.BotProperties;
import com.halloween.draft.core.DraftManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;

/** Connects to Discord once Spring (and the database) is ready. */
@Component
public class DiscordBot implements ApplicationRunner, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(DiscordBot.class);

    private final BotProperties props;
    private final DraftListener listener;
    private final DraftManager drafts;
    private final CompetitionService competitions;
    private final CountDownLatch running = new CountDownLatch(1);
    private JDA jda;

    public DiscordBot(BotProperties props, DraftListener listener, DraftManager drafts,
                      CompetitionService competitions) {
        this.props = props;
        this.listener = listener;
        this.drafts = drafts;
        this.competitions = competitions;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (props.getToken() == null || props.getToken().isBlank()) {
            throw new IllegalStateException("DISCORD_TOKEN is not set");
        }
        jda = JDABuilder.createDefault(props.getToken())
                .enableIntents(GatewayIntent.GUILD_MEMBERS)       // privileged: enable in the developer portal
                .setMemberCachePolicy(MemberCachePolicy.ALL)      // needed to find everyone with a role
                .setChunkingFilter(ChunkingFilter.ALL)
                .setActivity(Activity.watching("the draft board"))
                .addEventListeners(listener)
                .build();
        jda.awaitReady();
        drafts.attach(jda);
        competitions.start(jda);

        Thread keepAlive = new Thread(() -> {
            try {
                running.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "bot-keepalive");
        keepAlive.setDaemon(false);
        keepAlive.start();
        log.info("Draft bot running for {} factions", props.getFactions().size());
    }

    @Override
    public void destroy() {
        if (jda != null) jda.shutdown();
        running.countDown();
    }
}