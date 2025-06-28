package com.aliucord.coreplugins

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.entities.CorePlugin
import com.aliucord.patcher.after
import com.aliucord.patcher.component1
import com.aliucord.patcher.component2
import com.aliucord.utils.DimenUtils
import com.discord.widgets.chat.list.actions.`WidgetChatListActions$binding$2`
import com.lytefast.flexinput.R

internal class AlignThreads : CorePlugin(Manifest("AlignThreads")) {
    override val isHidden = true
    override val isRequired = true

    init {
        manifest.description = "Fixes the alignment of \"Create Thread\" button in message actions"
    }

    override fun start(context: Context) {
        patcher.after<`WidgetChatListActions$binding$2`>("invoke", View::class.java) { (_, view: View) ->
            val id = Utils.getResId("dialog_chat_actions_start_thread", "id")
            val threadTextView = view.findViewById<TextView>(id)
            val size = DimenUtils.dpToPx(24)
            val icon = ContextCompat.getDrawable(threadTextView.context, R.e.ic_thread)!!
            icon.setBounds(0, 0, size, size)
            threadTextView.setCompoundDrawables(icon, null, null, null)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}