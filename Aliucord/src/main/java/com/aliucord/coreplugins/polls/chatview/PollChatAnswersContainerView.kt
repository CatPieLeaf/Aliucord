package com.aliucord.coreplugins.polls.chatview

import android.content.Context
import com.aliucord.utils.ViewUtils.addTo
import com.aliucord.views.Divider
import com.aliucord.widgets.LinearLayout
import com.discord.api.message.poll.*

internal class PollChatAnswersContainerView(private val ctx: Context) : LinearLayout(ctx) {
    private val answerViews = HashMap<Int, PollChatAnswerView>()

    val hasChecked get() = getCheckedAnswers().count() > 0
    var onHasCheckedChange: ((Boolean) -> Unit)? = null

    fun configure(data: MessagePoll) {
        removeAllViews()
        answerViews.clear()

        Divider(ctx).addTo(this)
        for (answer in data.answers) {
            PollChatAnswerView.build(ctx, answer, data.allowMultiselect).addTo(this) {
                answerViews[answer.answerId!!] = this
                e { // setOnClickedListener
                    for (answerView in answerViews.values)
                        if (answerView !== this && !data.allowMultiselect)
                            answerView.isChecked = false
                        else if (answerView === this)
                            answerView.isChecked = !answerView.isChecked
                    onHasCheckedChange?.invoke(getCheckedAnswers().count() > 0)
                }
            }
            Divider(ctx).addTo(this)
        }
    }

    fun updateCounts(results: MessagePollResult, state: PollChatView.State) {
        val counts = results.answerCounts
        val total = counts.sumOf { it.count }.coerceAtLeast(1) // Prevent division by 0
        var winner = counts.maxOfOrNull { it.count } ?: -1
        if (winner == 0)
            winner = -1 // There is no winner if there are no votes
        for ((id, answerView) in answerViews) {
            val count = counts.find { it.id == id } ?: MessagePollAnswerCount(0, 0, false)
            answerView.updateCount(
                count,
                total,
                count.count == winner && state == PollChatView.State.FINALISED,
                state
            )
        }
    }

    fun updateState(state: PollChatView.State, shouldReanimate: Boolean) {
        for (answer in answerViews.values)
            answer.updateState(state, shouldReanimate)
    }

    fun getCheckedAnswers(): Iterable<Int> =
        answerViews.filter { (_, answerView) -> answerView.isChecked }.keys
}
