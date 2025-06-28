/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Copyright (c) 2021 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.aliucord.coreplugins

import android.content.Context
import android.widget.TextView
import com.aliucord.Logger
import com.aliucord.entities.CorePlugin
import com.aliucord.patcher.*
import com.discord.api.auth.OAuthScope
import com.discord.views.OAuthPermissionViews

internal class AuthorizedAppsCrashFix : CorePlugin(Manifest("AuthorizedAppsCrashFix")) {
    private val log: Logger = Logger("AuthorizedAppsCrashFix")

    init {
        manifest.description = "Fixes the crash when visiting 'Authorized Apps' page"
    }

    override val isRequired = true

    override fun load(context: Context) {
        // No implementation needed
    }

    override fun start(context: Context) {
        patcher.patch(
            OAuthPermissionViews::class.java.getMethod(
                "a",
                TextView::class.java, 
                OAuthScope::class.java
            ),
            Hook {
                if (it.hasThrowable()) {
                    val exc = it.throwable
                    if (exc is OAuthPermissionViews.InvalidScopeException) {
                        val scope = exc.a()
                        
                        log.verbose("Preventing crash (encountered $scope)")
                        val textView = it.args[0] as TextView
                        textView.text = "? '$scope'"
                        it.throwable = null
                    }
                }
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}