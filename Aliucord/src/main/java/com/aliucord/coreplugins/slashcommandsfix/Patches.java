/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Copyright (c) 2021 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.aliucord.coreplugins.slashcommandsfix;

import android.content.Context;
import com.aliucord.api.GatewayAPI;
import com.aliucord.Http;
import com.aliucord.Logger;
import com.aliucord.patcher.InsteadHook;
import com.aliucord.patcher.Patcher;
import com.aliucord.patcher.PreHook;
import com.aliucord.utils.GsonUtils;
import com.aliucord.utils.ReflectUtils;
import com.discord.models.commands.ApplicationCommand;
import com.discord.models.commands.ApplicationCommandKt;
import com.discord.stores.BuiltInCommandsProvider;
import com.discord.stores.StoreApplicationCommands;
import com.discord.stores.StoreApplicationCommands$handleDmUserApplication$1;
import com.discord.stores.StoreApplicationCommands$requestApplicationCommands$1;
import com.discord.stores.StoreApplicationCommands$requestApplicationCommandsQuery$1;
import com.discord.stores.StoreApplicationCommands$requestApplications$1;
import com.discord.stores.StoreStream;
import com.discord.utilities.permissions.PermissionUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.discord.utilities.messagesend.MessageResult;
import com.discord.models.commands.ApplicationCommandLocalSendData;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import com.discord.stores.StoreApplicationInteractions;

final class Patches {
    private static final int INTERACTION_APPLICATION_COMMAND_INVALID_VERSION_ERROR_CODE = 50035;

    private Map<Long, ApplicationIndex> guildApplicationIndexes;
    private Map<Long, ApplicationIndex> dmApplicationIndexes;
    private Logger logger;

    Patches(Logger logger) {
        this.guildApplicationIndexes = new HashMap<>();
        this.dmApplicationIndexes = new HashMap<>();
        this.logger = logger;
    }

    @SuppressWarnings("unchecked")
    public void loadPatches(Context context) throws Throwable {
        var storeApplicationCommands = StoreStream.getApplicationCommands();
        var storeChannelsSelected = StoreStream.getChannelsSelected();
        var storeUsers = StoreStream.getUsers();
        var storePermissions = StoreStream.getPermissions();
        var storeGuilds = StoreStream.getGuilds();

        // Requesting applications present in the guild
        Patcher.addPatch(
            StoreApplicationCommands$requestApplications$1.class.getDeclaredMethod("invoke"),
            new PreHook(param -> {
                var this_ = (StoreApplicationCommands$requestApplications$1) param.thisObject;

                if (this_.$guildId == null) {
                    return;
                }

                try {
                    this.passCommandData(this_.this$0, new ApplicationIndexSourceGuild(this_.$guildId), RequestSource.INITIAL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                param.setResult(null);
            })
        );

        // Requesting applications present in the DM
        Patcher.addPatch(
            StoreApplicationCommands$handleDmUserApplication$1.class.getDeclaredMethod("invoke"),
            new InsteadHook(param -> {
                var this_ = (StoreApplicationCommands$handleDmUserApplication$1) param.thisObject;

                var channelId = storeChannelsSelected
                    .getSelectedChannel()
                    .k();

                try {
                    this.passCommandData(this_.this$0, new ApplicationIndexSourceDm(channelId), RequestSource.INITIAL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                return null;
            })
        );

        // Browsing commands (when just a '/' is typed)
        Patcher.addPatch(
            StoreApplicationCommands$requestApplicationCommands$1.class.getDeclaredMethod("invoke"),
            new PreHook(param -> {
                var this_ = (StoreApplicationCommands$requestApplicationCommands$1) param.thisObject;

                if (this_.$guildId == null) {
                    return;
                }

                ApplicationIndexSource applicationIndexSource = null;
                if (this_.$guildId != 0) {
                    applicationIndexSource = new ApplicationIndexSourceGuild(this_.$guildId);
                } else {
                    var channelId = storeChannelsSelected
                        .getSelectedChannel()
                        .k();
                    applicationIndexSource = new ApplicationIndexSourceDm(channelId);
                }

                try {
                    this.passCommandData(this_.this$0, applicationIndexSource, RequestSource.BROWSE);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                param.setResult(null);
            })
        );

        // Autocompleting commands
        Patcher.addPatch(
            StoreApplicationCommands$requestApplicationCommandsQuery$1.class.getDeclaredMethod("invoke"),
            new PreHook(param -> {
                var this_ = (StoreApplicationCommands$requestApplicationCommandsQuery$1) param.thisObject;

                if (this_.$guildId == null) {
                    return;
                }

                try {
                    ReflectUtils.setField(this_.this$0, "query", this_.$query);
                    this.passCommandData(this_.this$0, new ApplicationIndexSourceGuild(this_.$guildId), RequestSource.QUERY);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                param.setResult(null);
            })
        );

        // Command permission check
        Patcher.addPatch(
            ApplicationCommandKt.class.getDeclaredMethod("hasPermission", ApplicationCommand.class, long.class, List.class),
            new InsteadHook(param -> {
                var applicationCommand = (ApplicationCommand) param.args[0];
                var roleIds = (List<Long>) param.args[2];

                var channel = storeChannelsSelected.getSelectedChannel();
                var guildId = channel.i();

                // Allow all commands in DMs
                if (guildId == 0) {
                    return true;
                }

                var channelId = channel.k();
                var applicationId = applicationCommand.getApplicationId();
                var application = this.requestApplicationIndex(new ApplicationIndexSourceGuild(guildId))
                    .applications
                    .stream()
                    .filter(a -> {
                        var id = a.component1();
                        return id == applicationId;
                    })
                    .findFirst();
                var user = storeUsers.getMe();
                var memberPermissions = storePermissions.getGuildPermissions()
                    .get(guildId);
                var guild = storeGuilds.getGuild(guildId);

                return !(applicationCommand instanceof RemoteApplicationCommand)
                    || (application.get().permissions_.checkFor(roleIds, channelId, guild, memberPermissions, user)
                        && ((RemoteApplicationCommand) applicationCommand).permissions_.checkFor(roleIds, channelId, guild, memberPermissions, user));
            })
        );

        Patcher.addPatch(
            StoreApplicationInteractions.class.getDeclaredMethod("handleApplicationCommandResult", MessageResult.class, ApplicationCommandLocalSendData.class, Function0.class, Function1.class),
            new PreHook(param -> {
                var result = (MessageResult) param.args[0];
                var localSendData = (ApplicationCommandLocalSendData) param.args[1];

                if (result instanceof MessageResult.UnknownFailure) {
                    var errorCode = ((MessageResult.UnknownFailure) result).getError().getResponse().getCode();
                    if (errorCode == Patches.INTERACTION_APPLICATION_COMMAND_INVALID_VERSION_ERROR_CODE) {
                        ApplicationIndexSource applicationIndexSource = null;
                        var guildId = localSendData.component3();
                        if (guildId != null) {
                            applicationIndexSource = new ApplicationIndexSourceGuild(guildId);
                        } else {
                            var channelId = localSendData.component2();
                            applicationIndexSource = new ApplicationIndexSourceDm(channelId);
                        }
                        this.cleanApplicationIndexCache(applicationIndexSource);
                    }
                }
            })
        );

        GatewayAPI.onEvent("GUILD_APPLICATION_COMMAND_INDEX_UPDATE", ApiGuildApplicationCommandIndexUpdate.class, guildApplicationCommandIndexUpdate -> {
            this.cleanApplicationIndexCache(new ApplicationIndexSourceGuild(guildApplicationCommandIndexUpdate.guildId));
            return null;
        });
    }

    private void passCommandData(StoreApplicationCommands storeApplicationCommands, ApplicationIndexSource applicationIndexSource, RequestSource requestSource) throws Exception {
        // TODO: Cache the fields as they are requested every time this runs

        var applicationIndex = this.requestApplicationIndex(applicationIndexSource);

        switch (requestSource) {
            case INITIAL:
                var applications = new ArrayList<com.discord.models.commands.Application>(applicationIndex.applications);
                Collections.sort(applications, new Comparator<com.discord.models.commands.Application>() {
                    @Override
                    public int compare(com.discord.models.commands.Application left, com.discord.models.commands.Application right) {
                        return left.getName().compareTo(right.getName());
                    }
                });
                applications.add(((BuiltInCommandsProvider) ReflectUtils.getField(storeApplicationCommands, "builtInCommandsProvider")).getBuiltInApplication());
                var handleGuildApplicationsUpdateMethod = StoreApplicationCommands.class.getDeclaredMethod("handleGuildApplicationsUpdate", List.class);
                handleGuildApplicationsUpdateMethod.setAccessible(true);
                handleGuildApplicationsUpdateMethod.invoke(storeApplicationCommands, applications);
                break;

            case BROWSE:
                var handleDiscoverCommandsUpdateMethod = StoreApplicationCommands.class.getDeclaredMethod("handleDiscoverCommandsUpdate", List.class);
                handleDiscoverCommandsUpdateMethod.setAccessible(true);
                handleDiscoverCommandsUpdateMethod.invoke(storeApplicationCommands, applicationIndex.applicationCommands);
                break;

            case QUERY:
                var handleQueryCommandsUpdateMethod = StoreApplicationCommands.class.getDeclaredMethod("handleQueryCommandsUpdate", List.class);
                handleQueryCommandsUpdateMethod.setAccessible(true);
                handleQueryCommandsUpdateMethod.invoke(storeApplicationCommands, applicationIndex.applicationCommands);
                break;
        }
    }

    private ApplicationIndex requestApplicationIndex(ApplicationIndexSource source) {
        // Reuse application index from cache
        var applicationIndex = source.getIndex(this.guildApplicationIndexes, this.dmApplicationIndexes);
        if (applicationIndex == null) {
            try {
                // Request application index from API
                applicationIndex = Http.Request.newDiscordRNRequest(source.getEndpoint())
                    .execute()
                    .json(GsonUtils.getGsonRestApi(), ApiApplicationIndex.class)
                    .toModel();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            source.putIndex(this.guildApplicationIndexes, this.dmApplicationIndexes, applicationIndex);
        }
        return applicationIndex;
    }

    private void cleanApplicationIndexCache(ApplicationIndexSource source) {
        source.cleanCache(this.guildApplicationIndexes, this.dmApplicationIndexes);
    }
}
