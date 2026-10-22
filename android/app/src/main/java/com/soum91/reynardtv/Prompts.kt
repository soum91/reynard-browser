package com.soum91.reynardtv

import android.app.Activity
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt

/** Native dialogs for page alerts, confirms, prompts and <select> menus (remote-friendly). */
class Prompts(private val activity: Activity) : PromptDelegate {

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        var answered = false
        AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                answered = true
                result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                answered = true
                result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE))
            }
            .setOnDismissListener { if (!answered) result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val input = EditText(activity).apply { setText(prompt.defaultValue ?: "") }
        var answered = false
        AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                answered = true
                result.complete(prompt.confirm(input.text.toString()))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!answered) result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        // Flatten <optgroup>s; group headers themselves aren't selectable.
        val choices = mutableListOf<ChoicePrompt.Choice>()
        fun collect(items: Array<out ChoicePrompt.Choice>?) {
            items?.forEach { choice ->
                if (choice.items != null) collect(choice.items) else if (!choice.separator) choices.add(choice)
            }
        }
        collect(prompt.choices)
        val labels = choices.map { it.label }.toTypedArray()
        var answered = false
        val builder = AlertDialog.Builder(activity).setTitle(prompt.title)

        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            val checked = choices.map { it.selected }.toBooleanArray()
            builder.setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    answered = true
                    val ids = choices.filterIndexed { i, _ -> checked[i] }.map { it.id }.toTypedArray()
                    result.complete(prompt.confirm(ids))
                }
        } else {
            val selected = choices.indexOfFirst { it.selected }
            builder.setSingleChoiceItems(labels, selected) { dialog, which ->
                answered = true
                result.complete(prompt.confirm(choices[which]))
                dialog.dismiss()
            }
        }
        builder.setOnDismissListener { if (!answered) result.complete(prompt.dismiss()) }.show()
        return result
    }
}
