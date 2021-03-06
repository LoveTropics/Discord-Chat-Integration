package de.erdbeerbaerlp.dcintegration;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import de.erdbeerbaerlp.dcintegration.api.DiscordEventHandler;
import de.erdbeerbaerlp.dcintegration.commands.DiscordCommand;
import de.erdbeerbaerlp.dcintegration.integrations.EmojicordFormatter;
import de.erdbeerbaerlp.dcintegration.storage.Configuration;
import de.erdbeerbaerlp.dcintegration.storage.PlayerLinkController;
import de.erdbeerbaerlp.dcintegration.storage.PlayerSettings;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.managers.ChannelManager;
import net.dv8tion.jda.internal.managers.ChannelManagerImpl;
import net.dv8tion.jda.internal.utils.PermissionUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.util.text.event.HoverEvent.Action;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import org.apache.commons.collections4.KeyValue;
import org.apache.commons.collections4.keyvalue.DefaultKeyValue;

import javax.annotation.Nullable;
import javax.security.auth.login.LoginException;
import java.io.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static de.erdbeerbaerlp.dcintegration.storage.Configuration.INSTANCE;

@SuppressWarnings("ConstantConditions")
public class Discord implements EventListener {
    private static final File IGNORED_PLAYERS = new File("./players_ignoring_discord");
    public final ArrayList<String> ignoringPlayers = new ArrayList<>();
    public final JDA jda;
    private final HashMap<Integer, KeyValue<Instant, UUID>> pendingLinks = new HashMap<>();
    private final HashMap<String, Webhook> webhookHashMap = new HashMap<>();
    final Thread updatePresence = new Thread() {
        {
            setName("[DC INTEGRATION] Discord Presence Updater and link cleanup");
            setDaemon(true);
            setPriority(MAX_PRIORITY);
        }

        @Override
        public void run() {
            while (true) {
                if (ServerLifecycleHooks.getCurrentServer() != null) {
                    final String game = Configuration.INSTANCE.botPresenceName.get()
                            .replace("%online%", "" + ServerLifecycleHooks.getCurrentServer().getOnlinePlayerNames().length)
                            .replace("%max%", "" + ServerLifecycleHooks.getCurrentServer().getMaxPlayers());
                    switch (Configuration.INSTANCE.botPresenceType.get()) {
                        case DISABLED:
                            break;
                        case LISTENING:
                            jda.getPresence().setActivity(Activity.listening(game));
                            break;
                        case PLAYING:
                            jda.getPresence().setActivity(Activity.playing(game));
                            break;
                        case WATCHING:
                            jda.getPresence().setActivity(Activity.watching(game));
                            break;
                    }

                    // Removing of expired numbers
                    final ArrayList<Integer> remove = new ArrayList<>();
                    pendingLinks.forEach((k, v) -> {
                        final Instant now = Instant.now();
                        Duration d = Duration.between(v.getKey(), now);
                        if (d.toMinutes() > 10) remove.add(k);
                    });
                    for (int i : remove)
                        pendingLinks.remove(i);
                }
                try {
                    sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    };
    /*/*
     * This thread is used to detect auto shutdown status using ftb utilities
     *//*
    Thread ftbUtilitiesShutdownDetectThread = new Thread() {
        {
            setName("[DC INTEGRATION] FTB Utilities shutdown detector");
            setDaemon(true);
            setPriority(MAX_PRIORITY);
        }

        public void run() {
            while (!isKilled) {
                final long timeLeft = TimeUnit.MILLISECONDS.toSeconds(FTBUtilitiesUniverseData.shutdownTime - Instant.now().toEpochMilli());
                if (timeLeft == 120)
                    sendMessage(Configuration.FTB_UTILITIES.SHUTDOWN_MSG_2MINUTES, Configuration.FTB_UTILITIES.FTB_AVATAR_ICON, "FTB Utilities");
                else if (timeLeft == 10) {
                    sendMessage(ADVANCED.FTB_UTILITIES_CHANNEL_ID.isEmpty() ? getChannel() : getChannel(ADVANCED.FTB_UTILITIES_CHANNEL_ID), Configuration.FTB_UTILITIES.SHUTDOWN_MSG_10SECONDS, Configuration.FTB_UTILITIES.FTB_AVATAR_ICON, "FTB Utilities");
                    break;
                }

                try {
                    sleep(TimeUnit.SECONDS.toMillis(1));
                } catch (InterruptedException ignored) {
                }
            }
            interrupt();
        }
    };
    /**
     * This thread is used to detect AFK states using ftb utilities
     *//*
    Thread ftbUtilitiesAFKDetectThread = new Thread() {
        {
            setName("[DC INTEGRATION] FTB Utilities AFK detector");
            setDaemon(true);
            setPriority(MAX_PRIORITY);
        }

        public void run() {
            if (!Configuration.FTB_UTILITIES.DISCORD_AFK_MSG_ENABLED) return;
            final Map<EntityPlayerMP, Entry<Long, Boolean>> timers = new HashMap<>();
            final Universe universe = Universe.get();
            while (!isKilled) {
                for (EntityPlayerMP player : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
                    try {
                        final FTBUtilitiesPlayerData data = FTBUtilitiesPlayerData.get(Objects.requireNonNull(universe.getPlayer(player)));
                        if (timers.containsKey(player) && data.afkTime < timers.get(player).getKey() && timers.get(player).getValue())
                            sendMessage(ADVANCED.FTB_UTILITIES_CHANNEL_ID.isEmpty() ? getChannel() : getChannel(ADVANCED.FTB_UTILITIES_CHANNEL_ID), Configuration.FTB_UTILITIES.DISCORD_AFK_MSG_END.replace("%player%", DiscordIntegration.formatPlayerName(player)), Configuration.FTB_UTILITIES.FTB_AVATAR_ICON, "FTB Utilities");
                        timers.put(player, new SimpleEntry<>(data.afkTime, (timers.containsKey(player) ? timers.get(player).getValue() : false)));
                    } catch (NullPointerException ignored) {
                    }
                }
                final List<EntityPlayerMP> toRemove = new ArrayList<>();
                timers.keySet().forEach((p) -> {
                    if (!ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers().contains(p)) {
                        toRemove.add(p);
                    } else {
                        final boolean afk = timers.get(p).getKey() >= Ticks.get(FTBUtilitiesConfig.afk.notification_timer).millis();
                        if (afk && !timers.get(p).getValue())
                            sendMessage(ADVANCED.FTB_UTILITIES_CHANNEL_ID.isEmpty() ? getChannel() : getChannel(ADVANCED.FTB_UTILITIES_CHANNEL_ID), Configuration.FTB_UTILITIES.DISCORD_AFK_MSG.replace("%player%", DiscordIntegration.formatPlayerName(p)), Configuration.FTB_UTILITIES.FTB_AVATAR_ICON,
                                    "FTB " + "Utilities");
                        timers.put(p, new SimpleEntry<>(timers.get(p).getKey(), afk));

                    }
                });
                for (EntityPlayerMP p : toRemove) {
                    timers.remove(p);
                }
                try {
                    sleep(900);
                } catch (InterruptedException ignored) {
                }
            }
        }
    };
*/
    private final HashMap<String, ArrayList<String>> messages = new HashMap<>();
    /**
     * Thread to send messages from vanilla commands
     */
    final Thread messageSender = new Thread() {
        {
            setName("[DC INTEGRATION] Discord Command Message Sender");
            setDaemon(true);
            setPriority(MAX_PRIORITY);
        }

        @Override
        public void run() {
            try {
                while (true) {
                    if (!messages.isEmpty()) {
                        messages.forEach((channel, msgs) -> {
                            StringBuilder s = new StringBuilder();
                            for (final String msg : msgs)
                                s.append(msg).append("\n");
                            Discord.this.sendMessage(s.toString().trim(), getChannel(channel));
                        });
                        messages.clear();
                    }
                    Thread.sleep(500);
                }
            } catch (InterruptedException ignored) {
            }
        }
    };
    public boolean isKilled = false;
    private List<DiscordCommand> commands = new ArrayList<>();

    /**
     * Constructor for this class
     */
    Discord() throws LoginException, InterruptedException {
        final JDABuilder b = new JDABuilder(Configuration.INSTANCE.botToken.get());
        b.setAutoReconnect(true);
        b.setEnableShutdownHook(false);
        this.jda = b.build().awaitReady();
        System.out.println("Bot Ready");
        this.messageSender.start();
        jda.addEventListener(this);
        if (!PermissionUtil.checkPermission(getChannel(), getChannel().getGuild().getMember(jda.getSelfUser()), Permission.MESSAGE_READ, Permission.MESSAGE_WRITE, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_MANAGE)) {
            System.err.println("ERROR! Bot does not have all permissions to work!");
            throw new PermissionException("Bot requires message read, message write, embed links and manage messages");
        }
        if (Configuration.INSTANCE.botModifyDescription.get())
            if (!PermissionUtil.checkPermission(getChannel(), getChannel().getGuild().getMember(jda.getSelfUser()), Permission.MANAGE_CHANNEL)) {
                Configuration.INSTANCE.botModifyDescription.set(false);
                System.err.println("ERROR! Bot does not have permission to manage channel, disabling channel description");
            }
        if (Configuration.INSTANCE.enableWebhook.get())
            if (!PermissionUtil.checkPermission(getChannel(), getChannel().getGuild().getMember(jda.getSelfUser()), Permission.MANAGE_WEBHOOKS)) {
                Configuration.INSTANCE.enableWebhook.set(false);
                System.err.println("ERROR! Bot does not have permission to manage webhooks, disabling webhook");
            }
        try {
            loadIgnoreList();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @return an instance of the webhook or null
     */
    @Nullable
    public Webhook getWebhook(TextChannel c) {
        if (!Configuration.INSTANCE.enableWebhook.get()) return null;
        return webhookHashMap.computeIfAbsent(c.getId(), cid -> {
	        if (!PermissionUtil.checkPermission(c, c.getGuild().getMember(jda.getSelfUser()), Permission.MANAGE_WEBHOOKS)) {
	            Configuration.setValueAndSave(DiscordIntegration.cfg, Configuration.INSTANCE.enableWebhook.getPath(), false);
	            System.out.println("ERROR! Bot does not have permission to manage webhooks, disabling webhook");
	            return null;
	        }
	        for (Webhook web : c.retrieveWebhooks().complete()) {
	            if (web.getName().equals("MC_DISCORD_INTEGRATION")) {
	                return web;
	            }
	        }
	        return c.createWebhook("MC_DISCORD_INTEGRATION").complete();
        });
    }

    /**
     * Sends a message when *not* using a webhook and returns it as RequestFuture<Message> or null when using a webhook<br>
     * only used by starting message
     *
     * @param msg message
     * @return Sent message
     */
    public CompletableFuture<Message> sendMessageReturns(String msg) {
        if (Configuration.INSTANCE.enableWebhook.get() || msg.isEmpty()) return null;
        else return getChannel().sendMessage(msg).submit();
    }

    public ChannelManager getChannelManager(String id) {
        return new ChannelManagerImpl(getChannel(id));
    }

    /**
     * Sends a message as player
     *
     * @param player Player
     * @param msg    Message packed as {@link DCMessage}
     */
    public void sendMessage(ServerPlayerEntity player, DCMessage msg) {
        sendMessage(Utils.formatPlayerName(player), player.getUniqueID().toString(), msg, Configuration.INSTANCE.chatOutputChannel.get().isEmpty() ? getChannel() : getChannel(Configuration.INSTANCE.chatOutputChannel.get()));
    }

    /**
     * Sends a message embed as player
     *
     * @param playerName Player Name
     * @param embed      Discord embed
     * @param channel    Target channel
     * @param uuid       Player UUID
     */
    public void sendMessage(final String playerName, String uuid, MessageEmbed embed, TextChannel channel) {
        sendMessage(playerName, uuid, new DCMessage(embed), channel);
    }

    /**
     * Sends a message embed as player
     *
     * @param playerName Player Name
     * @param msg        Message
     * @param channel    Target channel
     * @param uuid       Player UUID
     */
    public void sendMessage(final String playerName, String uuid, String msg, TextChannel channel) {
        sendMessage(playerName, uuid, new DCMessage(msg), channel);
    }

    /**
     * Sends a message as server
     *
     * @param msg Message
     */
    public void sendMessage(String msg) {
        sendMessage(Configuration.INSTANCE.serverName.get(), "0000000", msg, INSTANCE.serverChannelID.get().isEmpty() ? getChannel() : getChannel(INSTANCE.serverChannelID.get()));
    }

    /**
     * Sends an generic message to discord with custom avatar url (when using a webhook)
     *
     * @param channel   target channel
     * @param message   message
     * @param avatarURL URL of the avatar image for the webhook
     * @param name      Webhook name
     */
    public void sendMessage(TextChannel channel, String message, String avatarURL, String name) {
        sendMessage(name, new DCMessage(message), avatarURL, channel, false);
    }

    /**
     * Sends a CHAT message to discord with custom avatar url (when using a webhook)
     *
     * @param msg       Message
     * @param avatarURL URL of the avatar image
     * @param name      Name of the fake player
     */
    public void sendMessage(String msg, String avatarURL, String name) {
        sendMessage(name, new DCMessage(msg), avatarURL, INSTANCE.serverChannelID.get().isEmpty() ? getChannel() : getChannel(INSTANCE.serverChannelID.get()), true);
    }

    /**
     * Sends a CHAT message to discord with custom avatar url (when using a webhook)
     *
     * @param name      Name of the fake player
     * @param msg       Message
     * @param channel   Channel to send message into
     * @param avatarURL URL of the avatar image
     */
    public void sendMessage(String name, String msg, TextChannel channel, String avatarURL) {
        sendMessage(name, new DCMessage(msg), avatarURL, channel, true);
    }

    /**
     * Sends an discord message
     *
     * @param name          Player name or Webhook user name
     * @param message       Message to send
     * @param avatarURL     Avatar URL for the webhook
     * @param channel       Target channel
     * @param isChatMessage true to send it as chat message (when not using webhook)
     */
    public void sendMessage(String name, DCMessage message, String avatarURL, TextChannel channel, boolean isChatMessage) {
        if (isKilled) return;
        try {
            if (INSTANCE.enableWebhook.get()) {
                final WebhookMessageBuilder b = message.buildWebhookMessage();
                b.setUsername(name);
                b.setAvatarUrl(avatarURL);
                final WebhookClient cli = WebhookClient.withUrl(getWebhook(channel).getUrl());
                cli.send(b.build());
                cli.close();
            } else if (isChatMessage) {
                message.setMessage(INSTANCE.msgChatMessage.get().replace("%player%", name).replace("%msg%", message.getMessage()));
                channel.sendMessage(message.buildMessage()).queue();
            } else {
                channel.sendMessage(message.buildMessage()).queue();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends a message to discord
     *
     * @param playerName the name of the player
     * @param uuid       the player uuid
     * @param msg        the message to send
     */
    public void sendMessage(String playerName, String uuid, DCMessage msg, TextChannel channel) {
        final boolean isServerMessage = playerName.equals(INSTANCE.serverName.get()) && uuid.equals("0000000");
        final UUID uUUID = uuid.equals("0000000") ? null : UUID.fromString(uuid);
        String avatarURL = "";
        if (!isServerMessage && uUUID != null) {
            if (PlayerLinkController.isPlayerLinked(uUUID)) {
                final PlayerSettings s = PlayerLinkController.getSettings(null, uUUID);
                final Member dc = getChannel().getGuild().getMemberById(PlayerLinkController.getDiscordFromPlayer(uUUID));
                if (s.useDiscordNameInChannel) {
                    playerName = dc.getEffectiveName();
                    avatarURL = dc.getUser().getAvatarUrl();
                }
            }
            if (avatarURL.isEmpty())
                avatarURL = "https://minotar.net/avatar/" + uuid;
        }
        if (isServerMessage) {
            avatarURL = INSTANCE.serverAvatar.get();
        }
        sendMessage(playerName, msg, avatarURL, channel, !isServerMessage);
    }

    /**
     * Adds messages to send in the next half second
     * Used by config commands
     *
     * @param msg       message
     * @param channelID the channel ID the message should get sent to
     */
    public void sendMessageFuture(String msg, String channelID) {
        if (msg.isEmpty() || channelID.isEmpty()) return;
        final ArrayList<String> msgs;
        if (messages.containsKey(channelID))
            msgs = messages.get(channelID);
        else
            msgs = new ArrayList<>();
        msgs.add(msg);
        messages.put(channelID, msgs);
    }

    public void sendMessage(String msg, TextChannel textChannel) {
        sendMessage(new DCMessage(msg), textChannel);
    }

    public void sendMessage(DCMessage msg, TextChannel channel) {
        this.sendMessage(Configuration.INSTANCE.serverName.get(), "0000000", msg, channel);
    }

    /**
     * Event handler to handle messages
     */
    @Override
    public void onEvent(GenericEvent event) {
        if (isKilled) return;
        if (event instanceof MessageReceivedEvent) {
            final MessageReceivedEvent ev = (MessageReceivedEvent) event;
            if (ev.getChannelType().equals(ChannelType.TEXT)) {
                if (!ev.isWebhookMessage() && !ev.getAuthor().getId().equals(jda.getSelfUser().getId())) {
                    for (DiscordEventHandler o : DiscordIntegration.eventHandlers) {
                        if (o.onDiscordMessagePre(ev)) return;
                    }
                    if (ev.getMessage().getContentRaw().startsWith(Configuration.INSTANCE.prefix.get() + (INSTANCE.prefixSpace.get() ? " " : ""))) {
                        final String[] command = ev.getMessage().getContentRaw().replaceFirst(Configuration.INSTANCE.prefix.get(), "").split(" ");
                        String argumentsRaw = "";
                        for (int i = 1; i < command.length; i++) {
                            argumentsRaw = argumentsRaw + command[i] + " ";
                        }
                        argumentsRaw = argumentsRaw.trim();
                        boolean hasPermission = true;
                        boolean executed = false;
                        for (final DiscordCommand cmd : commands) {
                            if (!cmd.worksInChannel(ev.getTextChannel())) {
                                continue;
                            }
                            if (cmd.getName().equals(command[0])) {
                                if (cmd.canUserExecuteCommand(ev.getAuthor())) {
                                    for (DiscordEventHandler o : DiscordIntegration.eventHandlers) {
                                        if (o.onDiscordCommand(ev, cmd)) return;
                                    }
                                    cmd.execute(argumentsRaw.split(" "), ev);
                                    executed = true;
                                } else {
                                    hasPermission = false;
                                }
                            }
                            for (final String alias : cmd.getAliases()) {
                                if (alias.equals(command[0])) {
                                    if (cmd.canUserExecuteCommand(ev.getAuthor())) {
                                        for (DiscordEventHandler o : DiscordIntegration.eventHandlers) {
                                            if (o.onDiscordCommand(ev, cmd)) return;
                                        }
                                        cmd.execute(argumentsRaw.split(" "), ev);
                                        executed = true;
                                    } else {
                                        hasPermission = false;
                                    }
                                }
                            }
                        }
                        if (!executed)
                            for (DiscordEventHandler o : DiscordIntegration.eventHandlers) {
                                if (o.onDiscordCommand(ev, null)) return;
                            }
                        if (!hasPermission) {
                            sendMessage(Configuration.INSTANCE.msgNoPermission.get(), ev.getTextChannel());
                            return;
                        }
                        if (!executed && (Configuration.INSTANCE.enableUnknownCommandEverywhere.get() || ev.getTextChannel().getId().equals(getChannel().getId())) && Configuration.INSTANCE.enableUnknownCommandMsg.get()) {
                            if (Configuration.INSTANCE.cmdHelpEnabled.get())
                                sendMessage(Configuration.INSTANCE.msgUnknownCommand.get().replace("%prefix%", Configuration.INSTANCE.prefix.get()), ev.getTextChannel());
                        }


                    } else if (ev.getChannel().getId().equals(INSTANCE.chatInputChannel.get().isEmpty() ? getChannel().getId() : INSTANCE.chatInputChannel.get())) {
                        final List<MessageEmbed> embeds = ev.getMessage().getEmbeds();
                        String msg = ev.getMessage().getContentRaw();

                        for (final Member u : ev.getMessage().getMentionedMembers()) {
                            msg = msg.replace(Pattern.quote("<@" + u.getId() + ">"), "@" + u.getEffectiveName());
                        }
                        for (final Role r : ev.getMessage().getMentionedRoles()) {
                            msg = msg.replace(Pattern.quote("<@" + r.getId() + ">"), "@" + r.getName());
                        }
                        for (final Emote e : ev.getMessage().getEmotes()) {
                            if (ModList.get().isLoaded("emojicord")) {
                                msg = EmojicordFormatter.formatDiscordToChat(msg, e);
                            } else
                                //Replace emote IDs (<:name:123456789> with easier to read ones (:name:)
                                msg = msg.replace(e.getAsMention(), ":" + e.getName() + ":");
                        }
                        StringBuilder message = new StringBuilder(msg);
                        for (Message.Attachment a : ev.getMessage().getAttachments()) {
                            message.append("\nAttachment: ").append(a.getProxyUrl());
                        }
                        for (MessageEmbed e : embeds) {
                            if (e.isEmpty()) continue;
                            message.append("\n\n-----[Embed]-----\n");
                            if (e.getAuthor() != null && !e.getAuthor().getName().trim().isEmpty()) {
                                message.append(TextFormatting.BOLD).append(TextFormatting.ITALIC).append(e.getAuthor().getName()).append("\n");
                            }
                            if (e.getTitle() != null && !e.getTitle().trim().isEmpty()) {
                                message.append(TextFormatting.BOLD).append(e.getTitle()).append("\n");
                            }
                            if (e.getDescription() != null && !e.getDescription().trim().isEmpty()) {
                                message.append("Message:\n").append(e.getDescription()).append("\n");
                            }
                            if (e.getImage() != null && !e.getImage().getProxyUrl().isEmpty()) {
                                message.append("Image: ").append(e.getImage().getProxyUrl()).append("\n");
                            }
                            message.append("\n-----------------");
                        }
                        final String outMsg = Utils.convertMarkdownToMCFormatting(message.toString());
                        sendMcMsg(ForgeHooks.newChatWithLinks(Configuration.INSTANCE.ingameDiscordMsg.get().replace("%user%", (ev.getMember() != null ? ev.getMember().getEffectiveName() : ev.getAuthor().getName()))
                                .replace("%id%", ev.getAuthor().getId())
                                .replace("%msg%", (Configuration.INSTANCE.preventDiscordFormattingCodesToMC.get() ? TextFormatting.getTextWithoutFormattingCodes(outMsg) : outMsg)))
                                .setStyle(new Style().setHoverEvent(new HoverEvent(Action.SHOW_TEXT, new StringTextComponent("Sent by discord user \"" + ev.getAuthor().getAsTag() + "\"")))));
                    }
                }
                for (DiscordEventHandler o : DiscordIntegration.eventHandlers) {
                    o.onDiscordMessagePost(ev);
                }
            } else if (ev.getChannelType().equals(ChannelType.PRIVATE)) {
                if (!ev.getAuthor().getId().equals(jda.getSelfUser().getId())) {
                    for (DiscordEventHandler o : DiscordIntegration.eventHandlers) {
                        if (o.onDiscordPrivateMessage(ev)) return;
                    }
                    if (INSTANCE.whitelist.get() && INSTANCE.allowLink.get() && ServerLifecycleHooks.getCurrentServer().isServerInOnlineMode()) {
                        final String[] command = ev.getMessage().getContentRaw().replaceFirst(INSTANCE.prefix.get(), "").split(" ");
                        if (command.length > 0 && command[0].equals("whitelist")) {
                            if (PlayerLinkController.isDiscordLinked(ev.getAuthor().getId())) {
                                ev.getChannel().sendMessage(INSTANCE.alreadyLinkedMessage.get().replace("%player%", PlayerLinkController.getNameFromUUID(PlayerLinkController.getPlayerFromDiscord(ev.getAuthor().getId())))).queue();
                                return;
                            }
                            System.out.println(command.length);
                            if (command.length > 2) {
                                ev.getChannel().sendMessage(INSTANCE.msgTooManyArgs.get()).queue();
                                return;
                            }
                            if (command.length < 2) {
                                ev.getChannel().sendMessage(INSTANCE.msgNotEnoughArgs.get()).queue();
                                return;
                            }
                            UUID u;
                            String s = command[1];
                            try {
                                if (!s.contains("-"))
                                    s = s.replaceFirst(
                                            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"
                                    );
                                u = UUID.fromString(s);
                                final boolean linked = PlayerLinkController.linkPlayer(ev.getAuthor().getId(), u);
                                if (linked)
                                    ev.getChannel().sendMessage(INSTANCE.linkSuccessfulMessage.get().replace("%name%", PlayerLinkController.getNameFromUUID(u))).queue();
                                else
                                    ev.getChannel().sendMessage(INSTANCE.linkFailedMessage.get()).queue();
                            } catch (IllegalArgumentException e) {
                                ev.getChannel().sendMessage(INSTANCE.linkArgumentNotUUIDMessage.get().replace("%prefix%", INSTANCE.prefix.get()).replace("%arg%", s)).queue();
                                return;
                            }
                        }
                    } else if (INSTANCE.allowLink.get() && ServerLifecycleHooks.getCurrentServer().isServerInOnlineMode() && !INSTANCE.whitelist.get()) {
                        if (!ev.getMessage().getContentRaw().startsWith(INSTANCE.prefix.get()))
                            try {
                                int num = Integer.parseInt(ev.getMessage().getContentRaw());
                                if (PlayerLinkController.isDiscordLinked(ev.getAuthor().getId())) {
                                    ev.getChannel().sendMessage(INSTANCE.alreadyLinkedMessage.get().replace("%player%", PlayerLinkController.getNameFromUUID(PlayerLinkController.getPlayerFromDiscord(ev.getAuthor().getId())))).queue();
                                    return;
                                }
                                if (pendingLinks.containsKey(num)) {
                                    final boolean linked = PlayerLinkController.linkPlayer(ev.getAuthor().getId(), pendingLinks.get(num).getValue());
                                    if (linked) {
                                        ev.getChannel().sendMessage(INSTANCE.linkSuccessfulMessage.get().replace("%name%", PlayerLinkController.getNameFromUUID(PlayerLinkController.getPlayerFromDiscord(ev.getAuthor().getId())))).queue();
                                        ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayerByUUID(pendingLinks.get(num).getValue()).sendMessage(new StringTextComponent("Your account is now linked with " + ev.getAuthor().getAsTag()));
                                    } else
                                        ev.getChannel().sendMessage(INSTANCE.linkFailedMessage.get()).queue();
                                } else {
                                    ev.getChannel().sendMessage(INSTANCE.invalidLinkNumberMessage.get()).queue();
                                    return;
                                }
                            } catch (NumberFormatException nfe) {
                                ev.getChannel().sendMessage(INSTANCE.NANLinkNumberMessage.get()).queue();
                                return;
                            }
                    }
                    if (INSTANCE.allowLink.get() && ServerLifecycleHooks.getCurrentServer().isServerInOnlineMode()) {
                        final String[] command = ev.getMessage().getContentRaw().replaceFirst(INSTANCE.prefix.get(), "").split(" ");
                        if (command.length > 0) {
                            final String cmdUsages = "Usages:\n\n" + INSTANCE.prefix.get() + "settings - lists all available keys\n" + INSTANCE.prefix.get() + "settings get <key> - Gets the current settings value\n" + INSTANCE.prefix.get() + "settings set <key> <value> - Sets an Settings value";
                            switch (command[0]) {
                                case "help":
                                    ev.getChannel().sendMessage("__Available commands here:__\n\n" + INSTANCE.prefix.get() + "help - Shows this\n" + INSTANCE.prefix.get() + "settings - Edit your personal settings").queue();
                                    break;
                                case "settings":
                                    if (!PlayerLinkController.isDiscordLinked(ev.getAuthor().getId()))
                                        ev.getChannel().sendMessage(INSTANCE.accountNotLinkedMessage.get().replace("%method%", INSTANCE.whitelist.get() ? (INSTANCE.linkMethodWhitelist.get().replace("%prefix%", INSTANCE.prefix.get())) : INSTANCE.linkMethodIngame.get())).queue();
                                    else if (command.length == 1) {
                                        final MessageBuilder mb = new MessageBuilder();
                                        mb.setContent(cmdUsages);
                                        final EmbedBuilder b = new EmbedBuilder();
                                        final PlayerSettings settings = PlayerLinkController.getSettings(ev.getAuthor().getId(), null);
                                        getSettings().forEach((name, desc) -> {
                                            if (!(!INSTANCE.enableWebhook.get() && name.equals("useDiscordNameInChannel"))) {
                                                try {
                                                    b.addField(name + " == " + (((boolean) settings.getClass().getDeclaredField(name).get(settings)) ? "true" : "false"), desc, false);
                                                } catch (IllegalAccessException | NoSuchFieldException e) {
                                                    b.addField(name + " == Unknown", desc, false);
                                                }
                                            }
                                        });
                                        b.setAuthor(INSTANCE.personalSettingsHeader.get());
                                        mb.setEmbed(b.build());
                                        ev.getChannel().sendMessage(mb.build()).queue();
                                    } else if (command.length == 3 && command[1].equals("get")) {
                                        if (getSettings().containsKey(command[2])) {
                                            final PlayerSettings settings = PlayerLinkController.getSettings(ev.getAuthor().getId(), null);
                                            try {
                                                ev.getChannel().sendMessage(INSTANCE.pSettingsGetMessage.get().replace("%bool%", settings.getClass().getField(command[2]).getBoolean(settings) ? "true" : "false")).queue();
                                            } catch (IllegalAccessException | NoSuchFieldException e) {
                                                e.printStackTrace();
                                            }
                                        } else
                                            ev.getChannel().sendMessage(INSTANCE.invalidPSettingsKeyMsg.get().replace("%key%", command[2])).queue();
                                    } else if (command.length == 4 && command[1].equals("set")) {
                                        if (getSettings().containsKey(command[2])) {
                                            final PlayerSettings settings = PlayerLinkController.getSettings(ev.getAuthor().getId(), null);
                                            int newval;
                                            try {
                                                newval = Integer.parseInt(command[3]);
                                            } catch (NumberFormatException e) {
                                                newval = -1;
                                            }
                                            final boolean newValue = newval == -1 ? Boolean.parseBoolean(command[3]) : newval >= 1;
                                            try {
                                                settings.getClass().getDeclaredField(command[2]).set(settings, newValue);
                                                PlayerLinkController.updatePlayerSettings(ev.getAuthor().getId(), null, settings);
                                            } catch (IllegalAccessException | NoSuchFieldException e) {
                                                e.printStackTrace();
                                                ev.getChannel().sendMessage(INSTANCE.settingUpdateFailedMessage.get()).queue();
                                            }
                                            ev.getChannel().sendMessage(INSTANCE.settingUpdatedSuccessfullyMessage.get()).queue();
                                        } else
                                            ev.getChannel().sendMessage(INSTANCE.invalidPSettingsKeyMsg.get().replace("%key%", command[2])).queue();
                                    } else {
                                        ev.getChannel().sendMessage(cmdUsages).queue();
                                    }
                                    break;
                                default:
                                    break;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Kills the discord bot
     */
    void kill() {
        stopThreads();
        this.isKilled = true;
        jda.shutdownNow();
    }

    public boolean togglePlayerIgnore(PlayerEntity sender) {
        if (PlayerLinkController.isPlayerLinked(sender.getUniqueID())) {
            final PlayerSettings settings = PlayerLinkController.getSettings(null, sender.getUniqueID());
            settings.ignoreDiscordChatIngame = !settings.ignoreDiscordChatIngame;
            PlayerLinkController.updatePlayerSettings(null, sender.getUniqueID(), settings);
            return !settings.ignoreDiscordChatIngame;
        } else {
            if (ignoringPlayers.contains(sender.getName().getUnformattedComponentText())) {
                ignoringPlayers.remove(sender.getName().getUnformattedComponentText());
                try {
                    saveIgnoreList();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return true;
            } else {
                ignoringPlayers.add(sender.getName().getUnformattedComponentText());
                return false;
            }
        }
    }

    private void saveIgnoreList() throws IOException {
        if (!IGNORED_PLAYERS.exists() && !ignoringPlayers.isEmpty()) IGNORED_PLAYERS.createNewFile();
        if (!IGNORED_PLAYERS.exists() && ignoringPlayers.isEmpty()) {
            IGNORED_PLAYERS.delete();
            return;
        }
        FileWriter w = new FileWriter(IGNORED_PLAYERS);
        w.write("");
        for (String a : ignoringPlayers) {
            if (!PlayerLinkController.isPlayerLinked(UUID.fromString(a)))
                w.append(a).append("\n");
        }
        w.close();
    }


    public void loadIgnoreList() throws IOException {
        if (IGNORED_PLAYERS.exists()) {
            BufferedReader r = new BufferedReader(new FileReader(IGNORED_PLAYERS));
            r.lines().iterator().forEachRemaining(ignoringPlayers::add);
            r.close();
        }
    }

    private void sendMcMsg(final ITextComponent msg) {
        final List<ServerPlayerEntity> l = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers();
        for (final ServerPlayerEntity p : l) {
            if (!ignoringPlayers.contains(p.getName().getUnformattedComponentText()) && !(PlayerLinkController.isPlayerLinked(p.getUniqueID()) && PlayerLinkController.getSettings(null, p.getUniqueID()).ignoreDiscordChatIngame))
                p.sendMessage(msg);
        }
    }

    public ChannelManager getChannelManager() {
        return getChannelManager(getChannel().getId());
    }

    /**
     * Registers an {@link DiscordCommand}
     *
     * @param cmd command
     * @return if the registration was successful
     */
    public boolean registerCommand(DiscordCommand cmd) {
        final ArrayList<DiscordCommand> toRemove = new ArrayList<>();
        for (DiscordCommand c : commands) {
            if (!cmd.isConfigCommand() && cmd.equals(c)) return false;
            else if (cmd.isConfigCommand() && cmd.equals(c)) toRemove.add(c);
        }
        for (DiscordCommand cm : toRemove)
            commands.remove(cm);
        return commands.add(cmd);
    }

    private void reRegisterAllCommands(final List<DiscordCommand> cmds) {
        System.out.println("Reloading " + cmds.size() + " commands");
        this.commands = cmds;

        for (DiscordCommand cmd : commands) {
            cmd.discord = DiscordIntegration.discord_instance;
        }

        System.out.println("Registered " + this.commands.size() + " commands");
    }

    /**
     * Restarts the discord bot (used by reload command)
     */
    public boolean restart() {
        try {
            kill();
            DiscordIntegration.discord_instance = new Discord();
            DiscordIntegration.discord_instance.reRegisterAllCommands(this.commands);
            DiscordIntegration.registerConfigCommands();
            DiscordIntegration.discord_instance.startThreads();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Starts all threads
     */
    public void startThreads() {
        if (!messageSender.isAlive()) messageSender.start();
        if (!updatePresence.isAlive()) updatePresence.start();
        /*if (Loader.isModLoaded("ftbutilities")) {
            if (FTBUtilitiesConfig.auto_shutdown.enabled) ftbUtilitiesShutdownDetectThread.start();
            if (FTBUtilitiesConfig.afk.enabled) ftbUtilitiesAFKDetectThread.start();
        }*/
    }


    /**
     * Used to stop all discord integration threads in background
     */
    void stopThreads() {
//		if(ftbUtilitiesAFKDetectThread.isAlive()) ftbUtilitiesAFKDetectThread.interrupt();
//		if(ftbUtilitiesShutdownDetectThread.isAlive()) ftbUtilitiesShutdownDetectThread.interrupt();
        if (messageSender.isAlive()) messageSender.interrupt();
        if (updatePresence.isAlive()) updatePresence.interrupt();
    }

    /**
     * @return A list of all registered commands
     */
    public List<DiscordCommand> getCommandList() {
        return this.commands;
    }

    /**
     * @return The admin role of the server
     */
    public Role getAdminRole() {
        return (Configuration.INSTANCE.adminRoleId.get().equals("0") || Configuration.INSTANCE.adminRoleId.get().trim().isEmpty()) ? null : jda.getRoleById(Configuration.INSTANCE.adminRoleId.get());
    }


    /**
     * @return the specified text channel
     */
    public TextChannel getChannel() {
        return getChannel(Configuration.INSTANCE.botChannel.get());
    }

    /**
     * @return the specified text channel
     */
    public TextChannel getChannel(String id) {
        return jda.getTextChannelById(id);
    }

    private HashMap<String, String> getSettings() {
        final HashMap<String, String> out = new HashMap<>();
        final Field[] fields = PlayerSettings.class.getFields();
        final Field[] descFields = PlayerSettings.Descriptions.class.getDeclaredFields();
        for (Field f : fields) {
            out.put(f.getName(), "No Description Provided");
        }
        for (Field f : descFields) {
            f.setAccessible(true);
            try {
                out.put(f.getName(), (String) f.get(new PlayerSettings.Descriptions()));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        return out;
    }

    public int genLinkNumber(UUID uniqueID) {
        final AtomicInteger r = new AtomicInteger(-1);
        pendingLinks.forEach((k, v) -> {
            if (v.getValue().equals(uniqueID))
                r.set(k);
        });
        if (r.get() != -1) return r.get();
        do {
            r.set(new Random().nextInt(Integer.MAX_VALUE));
        } while (pendingLinks.containsKey(r.get()));
        pendingLinks.put(r.get(), new DefaultKeyValue<>(Instant.now(), uniqueID));
        return r.get();
    }

    public enum GameTypes {
        WATCHING,
        PLAYING,
        LISTENING,
        //CUSTOM,
        DISABLED
    }

    public static final class DCMessage {
        private final boolean isNotRaw;
        private MessageEmbed embed;

        private String message = "";

        /**
         * @param isNotRaw set to true to enable markdown escaping and mc color conversion (default: false)
         */
        public DCMessage(final MessageEmbed embed, final String message, boolean isNotRaw) {
            this.embed = embed;
            this.message = message;
            this.isNotRaw = isNotRaw;
        }

        public DCMessage(final MessageEmbed embed, final String message) {
            this(embed, message, false);
        }

        public DCMessage(final String message) {
            this(null, message, false);
        }

        public DCMessage(final MessageEmbed embed) {
            this(embed, "", false);
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(final String message) {
            this.message = message;
        }

        public MessageEmbed getEmbed() {
            return embed;
        }

        public void setEmbed(final MessageEmbed embed) {
            this.embed = embed;
        }

        Message buildMessage() {
            final MessageBuilder out = new MessageBuilder();
            if (!message.isEmpty()) {
                if (isNotRaw) {
                    if (INSTANCE.formattingCodesToDiscord.get())
                        out.setContent(Utils.convertMCToMarkdown(message));
                    else
                        out.setContent(TextFormatting.getTextWithoutFormattingCodes(Utils.convertMCToMarkdown(message)));
                } else {
                    out.setContent(message);
                }
            }
            if (embed != null)
                out.setEmbed(embed);
            return out.build();
        }

        WebhookMessageBuilder buildWebhookMessage() {
            final WebhookMessageBuilder out = new WebhookMessageBuilder();
            if (!message.isEmpty()) {
                if (isNotRaw) {
                    if (INSTANCE.formattingCodesToDiscord.get())
                        out.setContent(Utils.convertMCToMarkdown(message));
                    else
                        out.setContent(TextFormatting.getTextWithoutFormattingCodes(Utils.convertMCToMarkdown(message)));
                } else {
                    out.setContent(message);
                }
            }
            if (embed != null) {
                final WebhookEmbedBuilder eb = new WebhookEmbedBuilder();
                if (embed.getAuthor() != null)
                    eb.setAuthor(new WebhookEmbed.EmbedAuthor(embed.getAuthor().getName(), embed.getAuthor().getIconUrl(), embed.getAuthor().getUrl()));
                eb.setColor(embed.getColorRaw());
                eb.setDescription(embed.getDescription());
                if (embed.getFooter() != null)
                    eb.setFooter(new WebhookEmbed.EmbedFooter(embed.getFooter().getText(), embed.getFooter().getIconUrl()));
                if (embed.getImage() != null)
                    eb.setImageUrl(embed.getImage().getUrl());
                if (embed.getThumbnail() != null)
                    eb.setThumbnailUrl(embed.getThumbnail().getUrl());
                for (MessageEmbed.Field f : embed.getFields()) {
                    eb.addField(new WebhookEmbed.EmbedField(f.isInline(), f.getName(), f.getValue()));
                }
                eb.setTimestamp(embed.getTimestamp());
                if (embed.getTitle() != null)
                    eb.setTitle(new WebhookEmbed.EmbedTitle(embed.getTitle(), embed.getUrl()));
                out.addEmbeds(eb.build());
            }
            return out;
        }
    }
}

