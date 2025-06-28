/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Licensed under the Open Software License version 3.0
 */

package com.aliucord.coreplugins

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.*

import com.aliucord.Logger
import com.aliucord.entities.CorePlugin
import com.aliucord.patcher.PreHook
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.*
import com.lytefast.flexinput.R

import java.lang.reflect.Field
import java.util.regex.Matcher
import java.util.regex.Pattern

internal class MarkdownFormatter : CorePlugin(Manifest("MarkdownFormatter")) {
    private val logger = Logger("MarkdownFormatter")
    private lateinit var rulesField: Field
    
    init {
        manifest.description = "Adds support for additional Markdown formatting like headers, bullet points, and subtext"
    }

    override fun start(context: Context) {
        try {
            rulesField = Parser::class.java.getDeclaredField("rules")
            rulesField.isAccessible = true
        } catch (e: NoSuchFieldException) {
            logger.error("Failed to get rules field", e)
        }

        patcher.patch(
            DiscordParser::class.java,
            "parseChannelMessage",
            arrayOf(
                Context::class.java, 
                String::class.java, 
                MessageRenderContext::class.java, 
                MessagePreprocessor::class.java, 
                DiscordParser.ParserOptions::class.java, 
                Boolean::class.java
            ),
            PreHook { callFrame ->
                try {
                    val ctx = callFrame.args[0] as Context
                    val parser = DiscordParser.createParser(true, true, true, false, false)
                    
                    @Suppress("UNCHECKED_CAST")
                    val rules = rulesField.get(parser) as ArrayList<Rule<MessageRenderContext, out Node<MessageRenderContext>, MessageParseState>>
                    rules.add(0, HeaderRule())
                    rules.add(0, SubtextRule(ctx))
                    rules.add(0, BulletPointRule(ctx))
                    
                    rulesField.set(parser, rules)
                } catch (e: Throwable) {
                    logger.error("Error patching parser", e)
                }
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
    private class HeaderRule : Rule.BlockRule<MessageRenderContext, HeaderNode, MessageParseState>(
        Pattern.compile("^\\s*(##?#?)\\s+(.+)(?=\\n|$)")
    ) {
        override fun parse(
            matcher: Matcher,
            parser: Parser<MessageRenderContext, in HeaderNode, MessageParseState>,
            state: MessageParseState
        ): ParseSpec<MessageRenderContext, MessageParseState> {
            val headerSize = matcher.group(1).length
            val node = HeaderNode(headerSize)
            return ParseSpec(node, state, matcher.start(2), matcher.end(2))
        }
    }
    private class SubtextRule(private val context: Context) : Rule.BlockRule<MessageRenderContext, SubtextNode, MessageParseState>(
        Pattern.compile("^\\s*(-#)\\s+(.+)(?=\\n|$)")
    ) {
        override fun parse(
            matcher: Matcher,
            parser: Parser<MessageRenderContext, in SubtextNode, MessageParseState>,
            state: MessageParseState
        ): ParseSpec<MessageRenderContext, MessageParseState> {
            val node = SubtextNode(context)
            return ParseSpec(node, state, matcher.start(2), matcher.end(2))
        }
    }
    private class BulletPointRule(private val context: Context) : Rule.BlockRule<MessageRenderContext, BulletPointNode, MessageParseState>(
        Pattern.compile("^\\s*([*-])\\s+(.+)(?=\\n|$)")
    ) {
        override fun parse(
            matcher: Matcher,
            parser: Parser<MessageRenderContext, in BulletPointNode, MessageParseState>,
            state: MessageParseState
        ): ParseSpec<MessageRenderContext, MessageParseState> {
            val bulletType = matcher.group(1)
            val node = BulletPointNode(context, bulletType)
            return ParseSpec(node, state, matcher.start(2), matcher.end(2))
        }
    }
    private class HeaderNode(private val headerSize: Int) : Node<MessageRenderContext>() {
        override fun render(builder: SpannableStringBuilder, renderContext: MessageRenderContext) {
            val length = builder.length
            for (node in children) {
                node.render(builder, renderContext)
            }
            val sizeMultiplier = when (headerSize) {
                1 -> 2.0f
                2 -> 1.5f
                else -> 1.25f
            }
            
            builder.setSpan(
                RelativeSizeSpan(sizeMultiplier),
                length,
                builder.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            
            builder.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                length,
                builder.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    
    private class SubtextNode(private val context: Context) : Node.a<MessageRenderContext>() {
        override fun render(builder: SpannableStringBuilder, renderContext: MessageRenderContext) {
            val length = builder.length
            super.render(builder, renderContext)
            builder.setSpan(
                RelativeSizeSpan(0.85f),
                length,
                builder.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            val greyColor = ColorCompat.getThemedColor(context, R.b.colorTextMuted)
            val alphaValue = 128
            val greyColorWithAlpha = (greyColor and 0x00FFFFFF) or (alphaValue shl 24)
            
            builder.setSpan(
                ForegroundColorSpan(greyColorWithAlpha),
                length,
                builder.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    
    private class BulletPointNode(private val context: Context, private val bulletType: String) : Node.a<MessageRenderContext>() {
        override fun render(builder: SpannableStringBuilder, renderContext: MessageRenderContext) {
            val length = builder.length
            super.render(builder, renderContext)
            
            val greyColor = ColorCompat.getThemedColor(context, R.b.colorTextMuted)
            val dpValue = 8
            val density = context.resources.displayMetrics.density
            val pixelValue = (dpValue * density).toInt()
            
            val bulletSpan = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                BulletSpan(pixelValue, greyColor, 6)
            } else {
                BulletSpan(pixelValue, greyColor)
            }
            
            builder.setSpan(
                bulletSpan,
                length,
                builder.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            
            builder.setSpan(
                StyleSpan(android.graphics.Typeface.NORMAL),
                length,
                builder.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}