package com.overlord08x.sebbypass

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PopupPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RepostConfirmPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles web dialogs triggered inside GeckoView (alerts, confirms, selects, file uploads)
 * because GeckoView does not provide built-in Android dialog UI.
 */
class SebPromptDelegate(
    private val activity: Activity,
    private val filePickerLauncher: (mimeTypes: Array<String>, multiple: Boolean, callback: (List<Uri>) -> Unit) -> Boolean
) : PromptDelegate {

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt): GeckoResult<PromptResponse> {
        val res = GeckoResult<PromptResponse>()
        val builder = AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok, null)
        showPromptDialog(builder, prompt, res)
        return res
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt): GeckoResult<PromptResponse> {
        val res = GeckoResult<PromptResponse>()
        var handle: PromptDialogHandle? = null
        val builder = AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                handle?.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                handle?.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE))
            }
        handle = showPromptDialog(builder, prompt, res)
        return res
    }

    override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt): GeckoResult<PromptResponse> {
        val res = GeckoResult<PromptResponse>()
        val input = EditText(activity).apply {
            setText(prompt.defaultValue ?: "")
        }
        var handle: PromptDialogHandle? = null
        val builder = AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setView(createDialogPaddingLayout(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                handle?.complete(prompt.confirm(input.text.toString()))
            }
            .setNegativeButton(android.R.string.cancel, null)
        handle = showPromptDialog(builder, prompt, res)
        return res
    }

    override fun onAuthPrompt(session: GeckoSession, prompt: AuthPrompt): GeckoResult<PromptResponse> {
        val res = GeckoResult<PromptResponse>()
        val flags = prompt.authOptions.flags
        val onlyPassword = (flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0

        val usernameInput = if (!onlyPassword) {
            EditText(activity).apply {
                hint = "Username"
                setText(prompt.authOptions.username ?: "")
            }
        } else {
            null
        }
        val passwordInput = EditText(activity).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prompt.authOptions.password ?: "")
        }

        val container = if (usernameInput != null) {
            createDialogPaddingLayout(usernameInput, passwordInput)
        } else {
            createDialogPaddingLayout(passwordInput)
        }

        var handle: PromptDialogHandle? = null
        val builder = AlertDialog.Builder(activity)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pw = passwordInput.text.toString()
                val response = if (usernameInput != null) {
                    prompt.confirm(usernameInput.text.toString(), pw)
                } else {
                    prompt.confirm(pw)
                }
                handle?.complete(response)
            }
            .setNegativeButton(android.R.string.cancel, null)
        handle = showPromptDialog(builder, prompt, res)
        return res
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse> {
        val res = GeckoResult<PromptResponse>()
        showChoiceDialog(prompt.title, prompt.message, prompt.type, prompt.choices, prompt, res)
        return res
    }

    private fun showChoiceDialog(
        title: String?,
        message: String?,
        type: Int,
        choices: Array<ChoicePrompt.Choice>,
        prompt: ChoicePrompt,
        res: GeckoResult<PromptResponse>
    ) {
        val flat = flattenChoices(choices, indent = "")
        if (flat.isEmpty()) {
            res.complete(prompt.dismiss())
            return
        }

        val isMultiple = type == ChoicePrompt.Type.MULTIPLE
        val selectedFlags = BooleanArray(flat.size) { flat[it].choice.selected }
        val itemLayout = if (isMultiple) {
            android.R.layout.simple_list_item_multiple_choice
        } else {
            android.R.layout.simple_list_item_single_choice
        }

        val listView = ListView(activity).apply {
            choiceMode = if (isMultiple) ListView.CHOICE_MODE_MULTIPLE else ListView.CHOICE_MODE_SINGLE
            adapter = object : ArrayAdapter<FlatChoice>(activity, itemLayout, flat) {
                override fun isEnabled(position: Int): Boolean = flat[position].selectable
            }
            for (i in flat.indices) {
                if (selectedFlags[i]) {
                    setItemChecked(i, true)
                }
            }
        }

        var handle: PromptDialogHandle? = null
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setView(listView)

        if (isMultiple) {
            builder
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val chosenIds = flat.indices
                        .filter { selectedFlags[it] && flat[it].selectable }
                        .map { flat[it].choice.id }
                    handle?.complete(prompt.confirm(chosenIds.toTypedArray()))
                }
                .setNegativeButton(android.R.string.cancel, null)
        }

        handle = showPromptDialog(builder, prompt, res)

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = flat[position]
            if (!entry.selectable) return@setOnItemClickListener

            if (isMultiple) {
                selectedFlags[position] = listView.isItemChecked(position)
                return@setOnItemClickListener
            }

            val subItems = entry.choice.items
            if (type == ChoicePrompt.Type.MENU && subItems != null && subItems.isNotEmpty()) {
                handle.dismissWithoutCompleting()
                showChoiceDialog(entry.label, null, type, subItems, prompt, res)
                return@setOnItemClickListener
            }

            handle.complete(prompt.confirm(entry.choice))
            handle.dialog.dismiss()
        }
    }

    override fun onRepostConfirmPrompt(
        session: GeckoSession,
        prompt: RepostConfirmPrompt
    ): GeckoResult<PromptResponse> {
        val res = GeckoResult<PromptResponse>()
        var handle: PromptDialogHandle? = null
        val builder = AlertDialog.Builder(activity)
            .setTitle("Confirm Form Resubmission")
            .setMessage("Resend data submitted earlier?")
            .setPositiveButton("Resend") { _, _ ->
                handle?.complete(prompt.confirm(AllowOrDeny.ALLOW))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                handle?.complete(prompt.confirm(AllowOrDeny.DENY))
            }
        handle = showPromptDialog(builder, prompt, res)
        return res
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: BeforeUnloadPrompt
    ): GeckoResult<PromptResponse> {
        val res = GeckoResult<PromptResponse>()
        var handle: PromptDialogHandle? = null
        val builder = AlertDialog.Builder(activity)
            .setTitle("Leave Page?")
            .setMessage("Changes you made may not be saved.")
            .setPositiveButton("Leave") { _, _ ->
                handle?.complete(prompt.confirm(AllowOrDeny.ALLOW))
            }
            .setNegativeButton("Stay") { _, _ ->
                handle?.complete(prompt.confirm(AllowOrDeny.DENY))
            }
        handle = showPromptDialog(builder, prompt, res)
        return res
    }

    override fun onPopupPrompt(session: GeckoSession, prompt: PopupPrompt): GeckoResult<PromptResponse> {
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
    }

    override fun onFilePrompt(session: GeckoSession, prompt: FilePrompt): GeckoResult<PromptResponse> {
        if (prompt.type == FilePrompt.Type.FOLDER) {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        val res = GeckoResult<PromptResponse>()
        val multiple = prompt.type == FilePrompt.Type.MULTIPLE
        val mimeFilters = normalizeMimeFilters(prompt.mimeTypes)

        val launched = filePickerLauncher(mimeFilters, multiple) { pickedUris ->
            if (pickedUris.isEmpty()) {
                res.complete(prompt.dismiss())
                return@filePickerLauncher
            }

            val fileUris = pickedUris.mapNotNull { uri ->
                if ("file".equals(uri.scheme, ignoreCase = true)) uri else copyToCacheFileUri(activity, uri)
            }

            if (fileUris.isEmpty()) {
                res.complete(prompt.dismiss())
            } else if (multiple) {
                res.complete(prompt.confirm(activity, fileUris.toTypedArray()))
            } else {
                res.complete(prompt.confirm(activity, fileUris.first()))
            }
        }

        if (!launched) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        return res
    }

    private fun showPromptDialog(
        builder: AlertDialog.Builder,
        prompt: BasePrompt,
        res: GeckoResult<PromptResponse>
    ): PromptDialogHandle {
        val dialog = builder.create()
        val handle = PromptDialogHandle(dialog, prompt, res)
        if (activity.isFinishing || activity.isDestroyed) {
            handle.dismissPromptIfNeeded()
            return handle
        }
        dialog.setOnDismissListener {
            handle.dismissPromptIfNeeded()
        }
        dialog.show()
        return handle
    }

    private class PromptDialogHandle(
        val dialog: AlertDialog,
        private val prompt: BasePrompt,
        private val res: GeckoResult<PromptResponse>
    ) {
        private val completed = AtomicBoolean(false)

        fun complete(response: PromptResponse) {
            if (completed.compareAndSet(false, true)) {
                res.complete(response)
            }
        }

        fun dismissPromptIfNeeded() {
            if (completed.compareAndSet(false, true)) {
                res.complete(prompt.dismiss())
            }
        }

        fun dismissWithoutCompleting() {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
    }

    private fun createDialogPaddingLayout(vararg views: EditText): LinearLayout {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            views.forEach { addView(it) }
        }
    }

    private fun flattenChoices(choices: Array<ChoicePrompt.Choice>, indent: String): List<FlatChoice> {
        val result = mutableListOf<FlatChoice>()
        for (choice in choices) {
            if (choice.separator) continue
            val children = choice.items
            val hasChildren = children != null && children.isNotEmpty()
            val label = "$indent${choice.label}".trimEnd()
            result.add(FlatChoice(choice, label, !choice.disabled && !hasChildren))
            if (hasChildren && children != null) {
                result.addAll(flattenChoices(children, "$indent  "))
            }
        }
        return result
    }

    private fun normalizeMimeFilters(rawTypes: Array<String>?): Array<String> {
        if (rawTypes.isNullOrEmpty()) return arrayOf("*/*")
        val mimeMap = MimeTypeMap.getSingleton()
        val resolved = rawTypes.mapNotNull { raw ->
            val trimmed = raw.trim().lowercase()
            when {
                trimmed.isEmpty() -> null
                trimmed.startsWith(".") -> mimeMap.getMimeTypeFromExtension(trimmed.removePrefix("."))
                trimmed.contains("/") -> trimmed
                else -> null
            }
        }.distinct()
        return if (resolved.isEmpty()) arrayOf("*/*") else resolved.toTypedArray()
    }

    private fun copyToCacheFileUri(context: Context, uri: Uri): Uri? {
        return try {
            val cacheDir = File(context.cacheDir, "upload_tmp").apply { mkdirs() }
            val name = queryDisplayName(context, uri) ?: UUID.randomUUID().toString()
            val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(cacheDir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            Uri.fromFile(dest)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }

    private data class FlatChoice(
        val choice: ChoicePrompt.Choice,
        val label: String,
        val selectable: Boolean
    ) {
        override fun toString(): String = label
    }
}
