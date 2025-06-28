/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Copyright (c) 2021 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */
package com.aliucord.coreplugins

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.aliucord.CollectionUtils
import com.aliucord.entities.CorePlugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.InsteadHook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.RxUtils.await
import com.discord.models.user.CoreUser
import com.discord.stores.Dispatcher
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.`StoreStream$users$1`
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.UserMentionNode
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.adapter.*
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.lytefast.flexinput.R
import de.robv.android.xposed.XC_MethodHook
import retrofit2.HttpException

val StoreUser.notifyUserUpdated: `StoreStream$users$1`
    @Suppress("UNCHECKED_CAST")
    get() = StoreUser.`access$getNotifyUserUpdated$p`(this) as `StoreStream$users$1`

val StoreUser.dispatcher: Dispatcher
    @Suppress("UNCHECKED_CAST")
    get() = StoreUser.`access$getDispatcher$p`(this)

internal class ValidUser : CorePlugin(Manifest("ValidUser")) {
    private val lockedUsers = HashMap<Long, ArrayList<Long>>()
    private val invalidUsers = HashSet<Long>()

    init {
        manifest.description = "Shows proper information for missing users in user mentions"
    }

    override fun start(context: Context) {
        var chatList: WidgetChatList? = null

        patcher.patch(
            WidgetChatList::class.java.getDeclaredConstructor(),
            Hook { param: XC_MethodHook.MethodHookParam ->
                chatList = param.thisObject as WidgetChatList
            })

        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java.getDeclaredMethod(
                "getMessageRenderContext",
                Context::class.java,
                MessageEntry::class.java,
                Function1::class.java
            ),
            InsteadHook patch@{ param: XC_MethodHook.MethodHookParam ->
                val context = param.args[0] as Context
                val messageEntry = param.args[1] as MessageEntry
                val function1 = param.args[2]

                (param.thisObject as WidgetChatListAdapterItemMessage).run {
                    return@patch MessageRenderContext(
                        context,
                        messageEntry.message.id, // PATCH: set message as context id instead of author
                        messageEntry.animateEmojis,
                        messageEntry.nickOrUsernames,
                        (this.adapter as WidgetChatListAdapter).data.channelNames,
                        messageEntry.roles,
                        R.b.colorTextLink,
                        `WidgetChatListAdapterItemMessage$getMessageRenderContext$1`.INSTANCE,
                        `WidgetChatListAdapterItemMessage$getMessageRenderContext$2`(this),
                        ColorCompat.getThemedColor(context, R.b.theme_chat_spoiler_bg),
                        ColorCompat.getThemedColor(context, R.b.theme_chat_spoiler_bg_visible),
                        function1 as `WidgetChatListAdapterItemMessage$getSpoilerClickHandler$1`?,
                        `WidgetChatListAdapterItemMessage$getMessageRenderContext$3`(this),
                        `WidgetChatListAdapterItemMessage$getMessageRenderContext$4`(context)
                    )
                }
            }
        )

        patcher.patch(
            UserMentionNode::class.java.getDeclaredMethod(
                "renderUserMention",
                SpannableStringBuilder::class.java,
                UserMentionNode.RenderContext::class.java
            ),
            PreHook patch@{ param: XC_MethodHook.MethodHookParam ->
                @Suppress("UNCHECKED_CAST")
                val thisObject =
                    param.thisObject as UserMentionNode<UserMentionNode.RenderContext>
                val spannableStringBuilder = param.args[0] as SpannableStringBuilder
                val renderContext = param.args[1] as UserMentionNode.RenderContext?
                    ?: return@patch

                val userId = thisObject.userId

                if (invalidUsers.contains(userId)) {
                    logger.verbose("Set [$userId] = <invalid>")
                    setInvalidUser(renderContext.context, spannableStringBuilder, userId)
                    param.result = null
                    return@patch
                }

                if (renderContext.userNames == null || !renderContext.userNames.containsKey(userId)) {
                    val storeUser: StoreUser = StoreStream.getUsers()
                    val users = storeUser.users

                    if (!users.containsKey(userId)) {
                        param.result = null

                        if (lockedUsers.contains(userId)) {
                            logger.verbose("Waiting for $userId in ${renderContext.myId}")
                            lockedUsers[userId]!!.add(renderContext.myId)
                            setText(renderContext.context, spannableStringBuilder, "loading?")
                            return@patch
                        }

                        logger.verbose("Fetching $userId")
                        setText(renderContext.context, spannableStringBuilder, "loading")

                        fun refreshMessages() {
                            if (renderContext is MessageRenderContext && chatList != null) {
                                val adapter = WidgetChatList.`access$getAdapter$p`(chatList)
                                val data = adapter.internalData

                                for (messageId in lockedUsers[userId]!!) {
                                    val index =
                                        CollectionUtils.findIndex(data) { e: ChatListEntry? ->
                                            e is MessageEntry && e.message.id == messageId
                                        }

                                    if (index != -1) {
                                        try {
                                            adapter.notifyItemChanged(index)
                                            logger.verbose("Refreshed message $messageId")
                                        } catch (e: Throwable) {
                                            logger.warn("Failed to refresh message $messageId: $e")
                                        }
                                    } else {
                                        logger.warn("Failed to refresh message $messageId")
                                    }
                                }
                            }
                        }

                        lockedUsers[userId] = arrayListOf(renderContext.myId)
                        storeUser.dispatcher.schedule {
                            val (user, error) = RestAPI.getApi().userGet(userId).await()

                            if (error != null) {
                                if (error is HttpException && error.a() == 404) {
                                    invalidUsers.add(userId)
                                    logger.verbose("Fetched [$userId] = <invalid>")

                                    refreshMessages()
                                } else {
                                    logger.error("Failed to fetch the user", error)
                                }

                                lockedUsers.remove(userId)
                                return@schedule
                            }

                            logger.verbose("Fetched [$userId] = ${if (user == null) "null" else CoreUser(user).username}")

                            if (user != null) {
                                storeUser.notifyUserUpdated.invoke(user)
                                refreshMessages()
                            }

                            lockedUsers.remove(userId)
                        }

                        return@patch
                    }

                    logger.verbose("Set [$userId] = ${users[userId]!!.username}")

                    if (renderContext.userNames != null) {
                        renderContext.userNames[userId] = users[userId]!!.username
                    }
                }
            }
        )
    }

    private fun setInvalidUser(
        context: Context,
        spannableStringBuilder: SpannableStringBuilder,
        userId: Long
    ) {
        setText(context, spannableStringBuilder, "<@!$userId>")
    }

    private fun setText(
        context: Context,
        spannableStringBuilder: SpannableStringBuilder,
        text: String
    ) {
        val arrayList = ArrayList<CharacterStyle>()
        val length = spannableStringBuilder.length
        arrayList.add(StyleSpan(1))
        arrayList.add(
            BackgroundColorSpan(
                ColorCompat.getThemedColor(
                    context,
                    R.b.theme_chat_mention_background
                )
            )
        )
        arrayList.add(
            ForegroundColorSpan(
                ColorCompat.getThemedColor(
                    context,
                    R.b.theme_chat_mention_foreground
                )
            )
        )
        spannableStringBuilder.append(text)
        for (characterStyle in arrayList) {
            spannableStringBuilder.setSpan(
                characterStyle,
                length,
                spannableStringBuilder.length,
                33
            )
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}