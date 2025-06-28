/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Copyright (c) 2021 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.aliucord.coreplugins

import android.content.Context
import android.content.Intent
import android.view.MenuItem
import androidx.core.content.ContextCompat
import com.aliucord.entities.CorePlugin
import com.aliucord.patcher.after
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.settings.WidgetSettings
import com.lytefast.flexinput.R

internal class RestartButton : CorePlugin(Manifest("RestartButton")) {
    init {
        manifest.description = "Adds a button to restart Aliucord to the settings page"
    }

    override fun start(context: Context) {
        val icon = ContextCompat.getDrawable(context, com.yalantis.ucrop.R.c.ucrop_rotate)?.mutate()

        patcher.after<WidgetSettings>("configureToolbar") {
            icon?.setTint(ColorCompat.getThemedColor(requireContext(), R.b.colorInteractiveNormal))

            requireAppActivity()
                .u
                .menu
                .add("Restart")
                .setIcon(icon)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                .setOnMenuItemClickListener {
                    val intent = context.packageManager.getLaunchIntentForPackage(
                        context.packageName
                    )
                    context.startActivity(Intent.makeRestartActivityTask(intent?.component))
                    Runtime.getRuntime().exit(0)
                    false
                }
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}